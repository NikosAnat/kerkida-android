package app.kerkida.data.espn

import android.content.Context
import app.kerkida.data.DataException
import app.kerkida.data.LeagueCatalog
import app.kerkida.data.ProviderInfo
import app.kerkida.data.Sourced
import app.kerkida.data.SportsDataProvider
import app.kerkida.data.espn.EspnParsing.list
import app.kerkida.data.espn.EspnParsing.logo
import app.kerkida.data.espn.EspnParsing.obj
import app.kerkida.data.espn.EspnParsing.str
import app.kerkida.data.model.LeagueDetails
import app.kerkida.data.model.LeagueFixtures
import app.kerkida.data.model.LeagueRef
import app.kerkida.data.model.Match
import app.kerkida.data.model.MatchDay
import app.kerkida.data.model.MatchDetails
import app.kerkida.data.model.MatchStatus
import app.kerkida.data.model.SearchResults
import app.kerkida.data.model.Sport
import app.kerkida.data.model.Standings
import app.kerkida.data.model.Team
import app.kerkida.data.model.TeamMatches
import app.kerkida.data.net.CachedHttpClient
import app.kerkida.data.net.Fetched
import app.kerkida.tv.TeamDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * ESPN's public JSON feeds.
 *
 * WARNING: these endpoints are unofficial, undocumented and can change without notice.
 * Review ESPN's terms of use before any public release of this app.
 */
class EspnProvider(
    context: Context,
    private val http: CachedHttpClient,
    private val clock: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val catalog: LeagueCatalog? = null,
    private val directory: TeamDirectory? = null,
) : SportsDataProvider {

    override val info = ProviderInfo(ID, "ESPN", isDemo = false, attributionUrl = "https://www.espn.com/soccer/")

    private val badgePrefs = context.getSharedPreferences("kerkida_espn_badges", Context.MODE_PRIVATE)
    private val badges = ConcurrentHashMap<String, String>().apply {
        badgePrefs.all.forEach { (key, value) -> if (value is String) put(key, value) }
    }

    private val others = MultiSportSource(http, clock, zone, catalog)

    /** Football ids are plain ("gre.1"). Other sports carry their path ("basketball/nba"). */
    private fun isOther(id: String) = '/' in id

    override val sports: List<Sport> get() = others.sports

    override fun sportDay(sport: Sport, date: LocalDate, refresh: Boolean): Flow<MatchDay> = others.day(sport, date, refresh)

    private fun site(path: String) = "$SITE/$path"

    private fun Fetched.instant() = Instant.ofEpochMilli(fetchedAtMs)

    private fun leagueRef(id: String, league: JsonObject?): LeagueRef {
        val known = Featured.firstOrNull { it.id == id } ?: catalog?.find(id)?.toRef()
        val badge = league?.get("logos").list().firstOrNull().obj()?.str("href")
        if (badge != null && badges.put(id, badge) != badge) badgePrefs.edit().putString(id, badge).apply()
        return LeagueRef(
            id = id,
            name = known?.name ?: league?.str("name") ?: id,
            badgeUrl = badge ?: badges[id],
            country = known?.country,
        )
    }

    private fun seasonOf(league: JsonObject?): String? =
        league?.get("season").obj()?.str("displayName")?.substringBefore(' ')

    private fun parseBoard(fetched: Fetched, leagueId: String): List<Match> {
        val root = EspnParsing.root(fetched.body)
        val league = root["leagues"].list().firstOrNull().obj()
        val ref = leagueRef(leagueId, league)
        val season = seasonOf(league)
        return root["events"].list().mapNotNull { it.obj()?.let { event -> EspnParsing.toMatch(event, ref, season) } }
    }

    private fun dayTtl(date: LocalDate): Long {
        val today = LocalDate.now(zone())
        return when {
            date == today -> TTL_TODAY
            date.isBefore(today) -> TTL_PAST
            else -> TTL_FUTURE
        }
    }

    override fun matchDay(date: LocalDate, priorityLeagueIds: List<String>, refresh: Boolean): Flow<MatchDay> = flow {
        val chosen = footballLeagues().map { it.id }
        val leagueIds = (priorityLeagueIds.filter { it in chosen } + chosen).distinct()
        val ttl = dayTtl(date)
        val found = LinkedHashMap<String, Match>()
        var loaded = 0
        var failed = 0
        var stale = false
        var firstError: DataException? = null
        var oldest: Instant? = null

        fun snapshot() = MatchDay(
            matches = found.values.sortedWith(compareBy({ it.kickoff ?: Instant.MAX }, { it.home.name })),
            fetchedAt = oldest ?: clock(),
            loadedSources = loaded,
            totalSources = leagueIds.size,
            failedSources = failed,
            isStale = stale,
            firstError = firstError,
        )

        if (leagueIds.isEmpty()) emit(snapshot())
        // Leagues load side by side. The client's rate limiter still spaces out the actual requests.
        val gate = Semaphore(PARALLEL_LEAGUES)
        channelFlow {
            leagueIds.forEach { leagueId ->
                launch {
                    send(
                        gate.withPermit {
                            try {
                                // ESPN's days do not start at local midnight, so a local day spans two of them.
                                val boards = listOf(date.minusDays(1), date).map { day ->
                                    http.get(site("$leagueId/scoreboard?dates=${day.format(DAY)}"), ttl, refresh)
                                }
                                LeagueBoard(
                                    matches = boards.flatMap { parseBoard(it, leagueId) }
                                        .filter { it.kickoff?.atZone(zone())?.toLocalDate() == date },
                                    isStale = boards.any { it.isStale },
                                    oldest = boards.minOf { it.instant() },
                                )
                            } catch (error: DataException) {
                                LeagueBoard(error = error)
                            }
                        },
                    )
                }
            }
        }.collect { board ->
            if (board.error != null) {
                failed++
                firstError = firstError ?: board.error
            } else {
                board.matches.forEach { found[it.id] = it }
                stale = stale || board.isStale
                board.oldest?.let { time -> oldest = oldest?.let { minOf(it, time) } ?: time }
                loaded++
            }
            emit(snapshot())
        }
    }

    private class LeagueBoard(
        val matches: List<Match> = emptyList(),
        val isStale: Boolean = false,
        val oldest: Instant? = null,
        val error: DataException? = null,
    )

    override suspend fun matchDetails(matchId: String, refresh: Boolean): Sourced<MatchDetails> {
        val (leagueId, eventId) = EspnParsing.splitMatchId(matchId)
        if (isOther(leagueId)) return others.details(matchId, refresh) { others.standings(it, false).data }
        val fetched = http.get(site("$leagueId/summary?event=$eventId"), TTL_MATCH, refresh)
        val root = EspnParsing.root(fetched.body)
        val header = root["header"].obj() ?: throw DataException.BadData()
        val league = header["league"].obj()
        val season = header["season"].obj()?.str("name")?.substringBefore(' ')
        val parsed = EspnParsing.toMatch(header, leagueRef(leagueId, league), season) ?: throw DataException.BadData()
        val venue = root["gameInfo"].obj()?.get("venue").obj()?.str("fullName")
        val match = parsed.copy(venue = parsed.venue ?: venue)
        val standings = try {
            standings(leagueId, season, refresh = false).data
        } catch (_: DataException) {
            null
        }
        return Sourced(
            MatchDetails(
                match = match,
                events = EspnParsing.toEvents(root, match),
                standings = standings,
                extras = EspnParsing.toExtras(root, match),
            ),
            fetched.instant(),
            fetched.isStale,
        )
    }

    override suspend fun featuredLeagues(): Sourced<List<LeagueRef>> =
        Sourced(footballLeagues().map { it.copy(badgeUrl = it.badgeUrl ?: badges[it.id]) } + others.featured, clock())

    private suspend fun board(leagueId: String, refresh: Boolean = false) =
        http.get(site("$leagueId/scoreboard"), TTL_LEAGUE, refresh)

    override suspend fun league(leagueId: String, refresh: Boolean): Sourced<LeagueDetails> {
        if (isOther(leagueId)) return others.league(leagueId)
        val fetched = board(leagueId, refresh)
        val league = EspnParsing.root(fetched.body)["leagues"].list().firstOrNull().obj()
        return Sourced(
            LeagueDetails(leagueRef(leagueId, league), seasonOf(league)),
            fetched.instant(),
            fetched.isStale,
        )
    }

    override suspend fun standings(leagueId: String, season: String?, refresh: Boolean): Sourced<Standings> {
        if (isOther(leagueId)) return others.standings(leagueId, refresh)
        val fetched = http.get("$STANDINGS/$leagueId/standings", TTL_STANDINGS, refresh)
        val rows = EspnParsing.toStandingRows(EspnParsing.root(fetched.body))
        return Sourced(
            Standings(leagueRef(leagueId, null), season, rows, isPartial = false),
            fetched.instant(),
            fetched.isStale,
        )
    }

    override suspend fun fixtures(
        leagueId: String,
        season: String?,
        round: Int?,
        refresh: Boolean,
    ): Sourced<LeagueFixtures> {
        if (isOther(leagueId)) return others.fixtures(leagueId, refresh)
        val boardFetched = board(leagueId, refresh)
        val root = EspnParsing.root(boardFetched.body)
        val league = root["leagues"].list().firstOrNull().obj()
        val ref = leagueRef(leagueId, league)
        val rounds = league?.let { EspnParsing.roundsOf(EspnParsing.calendarDays(it)) }.orEmpty()
        if (rounds.isEmpty()) {
            val matches = parseBoard(boardFetched, leagueId)
            return Sourced(
                LeagueFixtures(ref, seasonOf(league), null, matches, hasPreviousRound = false, hasNextRound = false),
                boardFetched.instant(),
                boardFetched.isStale,
            )
        }
        val today = LocalDate.now(zone())
        val current = rounds.indexOfFirst { !it.last().isBefore(today) }.takeIf { it >= 0 } ?: rounds.lastIndex
        val index = ((round ?: (current + 1)) - 1).coerceIn(rounds.indices)
        val days = rounds[index]
        var fetchedAt = boardFetched.instant()
        var stale = boardFetched.isStale
        val months = days.map(YearMonth::from).distinct()
        val matches = months.flatMap { month ->
            val fetched = http.get(site("$leagueId/scoreboard?dates=${month.format(MONTH)}&limit=$MONTH_LIMIT"), TTL_LEAGUE, refresh)
            fetchedAt = minOf(fetchedAt, fetched.instant())
            stale = stale || fetched.isStale
            parseBoard(fetched, leagueId)
        }.distinctBy { it.id }
            .filter { match ->
                val day = match.kickoff?.atZone(ZoneOffset.UTC)?.minusHours(ESPN_DAY_START_HOUR)?.toLocalDate()
                day != null && !day.isBefore(days.first()) && !day.isAfter(days.last())
            }
            .sortedBy { it.kickoff }
        return Sourced(
            LeagueFixtures(
                league = ref,
                season = seasonOf(league),
                round = index + 1,
                matches = matches,
                hasPreviousRound = index > 0,
                hasNextRound = index < rounds.lastIndex,
            ),
            fetchedAt,
            stale,
        )
    }

    private suspend fun teamRoot(teamId: String, refresh: Boolean): Pair<JsonObject, Fetched> {
        val fetched = http.get(site("all/teams/$teamId"), TTL_TEAM, refresh)
        val team = EspnParsing.root(fetched.body)["team"].obj() ?: throw DataException.NotFound()
        return team to fetched
    }

    override suspend fun team(teamId: String, refresh: Boolean): Sourced<Team> {
        if (isOther(teamId)) return others.team(teamId, refresh)
        val (team, fetched) = teamRoot(teamId, refresh)
        val defaultLeague = team["defaultLeague"].obj()
        val slug = defaultLeague?.str("slug")
        return Sourced(
            Team(
                id = team.str("id") ?: teamId,
                name = team.str("displayName") ?: throw DataException.BadData(),
                shortName = team.str("shortDisplayName"),
                badgeUrl = logo(team),
                location = team.str("location"),
                league = slug?.let { leagueRef(it, defaultLeague) },
            ),
            fetched.instant(),
            fetched.isStale,
        )
    }

    override suspend fun teamHistory(teamId: String, seasons: Int): List<Match> {
        if (isOther(teamId)) return emptyList()
        val (team, _) = teamRoot(teamId, false)
        val defaultLeague = team["defaultLeague"].obj()
        val slug = defaultLeague?.str("slug") ?: return emptyList()
        val ref = leagueRef(slug, defaultLeague)
        val today = LocalDate.now(zone())
        val current = if (today.monthValue >= 7) today.year else today.year - 1
        return (0 until seasons).flatMap { back ->
            try {
                val fetched = http.get(site("$slug/teams/$teamId/schedule?season=${current - back}"), TTL_HISTORY)
                EspnParsing.root(fetched.body)["events"].list().mapNotNull { it.obj()?.let { event -> EspnParsing.toMatch(event, ref, null) } }
            } catch (_: DataException) {
                emptyList()
            }
        }
    }

    override suspend fun teamMatches(teamId: String, refresh: Boolean): Sourced<TeamMatches> {
        if (isOther(teamId)) return others.teamMatches(teamId, refresh)
        val (team, _) = teamRoot(teamId, false)
        val defaultLeague = team["defaultLeague"].obj()
        val slug = defaultLeague?.str("slug") ?: throw DataException.NotFound()
        val ref = leagueRef(slug, defaultLeague)
        val pastFetched = http.get(site("$slug/teams/$teamId/schedule"), TTL_SCHEDULE, refresh)
        val nextFetched = http.get(site("$slug/teams/$teamId/schedule?fixture=true"), TTL_SCHEDULE, refresh)
        fun parse(fetched: Fetched) = EspnParsing.root(fetched.body)["events"].list()
            .mapNotNull { it.obj()?.let { event -> EspnParsing.toMatch(event, ref, null) } }
        val all = (parse(pastFetched) + parse(nextFetched)).distinctBy { it.id }
        val (upcoming, recent) = all.partition { it.status == MatchStatus.SCHEDULED }
        return Sourced(
            TeamMatches(
                recent = recent.sortedByDescending { it.kickoff },
                upcoming = upcoming.sortedBy { it.kickoff },
            ),
            minOf(pastFetched.instant(), nextFetched.instant()),
            pastFetched.isStale || nextFetched.isStale,
        )
    }

    /** The chosen football leagues, with the familiar ones first. */
    private fun footballLeagues(): List<LeagueRef> {
        val chosen = catalog?.selected?.value ?: return Featured
        val known = Featured.filter { it.id in chosen }
        val extra = chosen.filter { id -> '/' !in id && !id.startsWith("grl.") && Featured.none { it.id == id } }.mapNotNull { catalog.find(it)?.toRef() }
        return known + extra.sortedBy { it.name }
    }

    private fun teamFromHit(hit: JsonObject): Team? {
        val name = hit.str("displayName") ?: return null
        val raw = hit.str("uid")?.substringAfter("~t:", "")?.takeIf { it.isNotEmpty() } ?: return null
        val slug = hit.str("defaultLeagueSlug") ?: return null
        val sport = hit.str("sport") ?: return null
        val leagueId = if (sport == "soccer") slug else "$sport/$slug"
        if (sport != "soccer" && catalog?.find(leagueId) == null) return null
        val listed = Featured.firstOrNull { it.id == leagueId } ?: catalog?.find(leagueId)?.toRef()
        val league = listed ?: LeagueRef(leagueId, hit.str("subtitle") ?: slug)
        return Team(
            id = if (sport == "soccer") raw else "$leagueId:$raw",
            name = name,
            badgeUrl = hit["image"].obj()?.str("default"),
            league = league,
        )
    }

    private suspend fun searchEverywhere(text: String): List<Team> {
        val url = "$SEARCH?limit=$SEARCH_LIMIT&query=" + URLEncoder.encode(text, "UTF-8")
        val root = EspnParsing.root(http.get(url, TTL_SEARCH).body)
        return root["results"].list().mapNotNull { it.obj() }
            .filter { it.str("type") == "team" }
            .flatMap { it["contents"].list().mapNotNull { hit -> hit.obj()?.let(::teamFromHit) } }
    }

    override suspend fun search(query: String): Sourced<SearchResults> {
        val needle = searchKey(query)
        if (needle.isEmpty()) return Sourced(SearchResults(emptyList(), emptyList()), clock())
        val leagues = if (catalog != null) {
            catalog.all.filter { searchKey(it.name).contains(needle) }.map { it.toRef() }.take(MAX_LEAGUE_HITS)
        } else {
            Featured.filter { searchKey(it.name).contains(needle) }
        } + Featured.filter { searchKey(it.country.orEmpty()).contains(needle) }
        // The search service speaks Latin, so Greek text is also tried through known team names.
        val spellings = buildList {
            add(query.trim())
            if (query.any { it in '\u0370'..'\u03FF' || it in '\u1F00'..'\u1FFF' }) {
                directory?.let { addAll(withContext(Dispatchers.Default) { it.closestNames(query.trim()) }) }
                add(needle)
            }
        }.distinct()
        val teams = LinkedHashMap<String, Team>()
        var failure: DataException? = null
        for (text in spellings) {
            try {
                searchEverywhere(text).forEach { teams.putIfAbsent(it.id, it) }
            } catch (error: DataException) {
                failure = error
            }
        }
        others.searchTeams(needle).forEach { teams.putIfAbsent(it.id, it) }
        if (teams.isEmpty() && leagues.isEmpty()) failure?.let { throw it }
        return Sourced(SearchResults(teams.values.toList(), leagues.distinctBy { it.id }), clock())
    }

    companion object {
        const val ID = "espn"
        private const val SITE = "https://site.api.espn.com/apis/site/v2/sports/soccer"
        private const val STANDINGS = "https://site.api.espn.com/apis/v2/sports/soccer"
        private const val PARALLEL_LEAGUES = 6
        private const val TTL_TODAY = 25_000L
        private const val TTL_MATCH = 25_000L
        private const val TTL_PAST = 3_600_000L
        private const val TTL_FUTURE = 900_000L
        private const val TTL_LEAGUE = 600_000L
        private const val TTL_STANDINGS = 300_000L
        private const val TTL_SCHEDULE = 300_000L
        private const val TTL_TEAM = 86_400_000L
        private const val MONTH_LIMIT = 200
        private const val SEARCH = "https://site.web.api.espn.com/apis/search/v2"
        private const val SEARCH_LIMIT = 25
        private const val TTL_SEARCH = 3_600_000L
        private const val TTL_HISTORY = 43_200_000L
        private const val MAX_LEAGUE_HITS = 15
        private const val ESPN_DAY_START_HOUR = 7L
        private val DAY = DateTimeFormatter.ofPattern("yyyyMMdd")
        private val MONTH = DateTimeFormatter.ofPattern("yyyyMM")

        val Featured = listOf(
            LeagueRef("gre.1", "Super League Ελλάδας", country = "Greece"),
            LeagueRef("eng.1", "Premier League", country = "England"),
            LeagueRef("uefa.champions", "UEFA Champions League", country = "Europe"),
            LeagueRef("uefa.europa", "UEFA Europa League", country = "Europe"),
            LeagueRef("uefa.europa.conf", "UEFA Conference League", country = "Europe"),
            LeagueRef("esp.1", "LaLiga", country = "Spain"),
            LeagueRef("ita.1", "Serie A", country = "Italy"),
            LeagueRef("ger.1", "Bundesliga", country = "Germany"),
            LeagueRef("fra.1", "Ligue 1", country = "France"),
            LeagueRef("ned.1", "Eredivisie", country = "Netherlands"),
            LeagueRef("por.1", "Primeira Liga", country = "Portugal"),
            LeagueRef("tur.1", "Süper Lig", country = "Turkey"),
            LeagueRef("eng.2", "Championship", country = "England"),
        )

        private val GreekLetters = mapOf(
            'α' to "a", 'β' to "v", 'γ' to "g", 'δ' to "d", 'ε' to "e", 'ζ' to "z", 'η' to "i", 'θ' to "th",
            'ι' to "i", 'κ' to "k", 'λ' to "l", 'μ' to "m", 'ν' to "n", 'ξ' to "x", 'ο' to "o", 'π' to "p",
            'ρ' to "r", 'σ' to "s", 'ς' to "s", 'τ' to "t", 'υ' to "y", 'φ' to "f", 'χ' to "ch", 'ψ' to "ps", 'ω' to "o",
        )

        /** Lets "ολυμπιακος", "Olympiakos" and "Olympiacos" all find the same team. */
        internal fun searchKey(text: String): String {
            val plain = Normalizer.normalize(text.trim().lowercase(), Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
            return plain.map { GreekLetters[it] ?: it.toString() }.joinToString("").replace('c', 'k')
        }
    }
}
