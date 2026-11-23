package app.kerkida.data.thesportsdb

import android.content.Context
import app.kerkida.data.DataException
import app.kerkida.data.ProviderInfo
import app.kerkida.data.Sourced
import app.kerkida.data.SportsDataProvider
import app.kerkida.data.model.LeagueDetails
import app.kerkida.data.model.LeagueFixtures
import app.kerkida.data.model.LeagueRef
import app.kerkida.data.model.Match
import app.kerkida.data.model.MatchDay
import app.kerkida.data.model.MatchDetails
import app.kerkida.data.model.MatchStatus
import app.kerkida.data.model.SearchResults
import app.kerkida.data.model.Standings
import app.kerkida.data.model.Team
import app.kerkida.data.model.TeamMatches
import app.kerkida.data.net.CachedHttpClient
import app.kerkida.data.net.Fetched
import app.kerkida.data.thesportsdb.TheSportsDbParsing.rows
import app.kerkida.data.thesportsdb.TheSportsDbParsing.text
import app.kerkida.data.thesportsdb.TheSportsDbParsing.toMatch
import app.kerkida.data.thesportsdb.TheSportsDbParsing.toMatchEvent
import app.kerkida.data.thesportsdb.TheSportsDbParsing.toStandingRow
import app.kerkida.data.thesportsdb.TheSportsDbParsing.toTeam
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.net.URLEncoder
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * TheSportsDB public API with the free key.
 * Observed free-key limits: day lists stop at 3 matches, tables at 5 rows, team history at 1 match.
 * Full league rounds are available, so the day view combines them with the live feed.
 */
class TheSportsDbProvider(
    context: Context,
    private val http: CachedHttpClient,
    private val clock: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : SportsDataProvider {

    override val info = ProviderInfo(
        id = ID,
        displayName = "TheSportsDB",
        isDemo = false,
        attributionUrl = "https://www.thesportsdb.com",
    )

    private val badgePrefs = context.getSharedPreferences("kerkida_league_badges", Context.MODE_PRIVATE)
    private val leagueBadges = ConcurrentHashMap<String, String>().apply {
        badgePrefs.all.forEach { (key, value) -> if (value is String) put(key, value) }
    }

    private fun url(path: String) = "$BASE_URL/$path"

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun rememberBadges(matches: List<Match>) {
        matches.forEach { match ->
            val badge = match.league.badgeUrl ?: return@forEach
            if (match.league.id.isNotEmpty() && leagueBadges.put(match.league.id, badge) != badge) {
                badgePrefs.edit().putString(match.league.id, badge).apply()
            }
        }
    }

    private fun withKnownBadge(league: LeagueRef) =
        if (league.badgeUrl != null) league else league.copy(badgeUrl = leagueBadges[league.id])

    private fun parseMatches(fetched: Fetched, key: String): List<Match> {
        val now = clock()
        return rows(fetched.body, key).mapNotNull { it.toMatch(now) }
            .map { it.copy(league = withKnownBadge(it.league)) }
            .also(::rememberBadges)
    }

    private fun dayTtl(date: LocalDate): Long {
        val today = LocalDate.now(zone())
        return when {
            date == today -> TTL_TODAY
            date.isBefore(today) -> TTL_PAST_DAY
            else -> TTL_FUTURE_DAY
        }
    }

    private fun Fetched.instant(): Instant = Instant.ofEpochMilli(fetchedAtMs)

    override fun matchDay(date: LocalDate, priorityLeagueIds: List<String>, refresh: Boolean): Flow<MatchDay> = flow {
        val isToday = date == LocalDate.now(zone())
        val ttl = dayTtl(date)
        val leagueIds = (priorityLeagueIds + FEATURED.map { it.id }).distinct().take(MAX_DAY_LEAGUES)
        val total = leagueIds.size + 1 + if (isToday) 1 else 0
        val found = LinkedHashMap<String, Match>()
        var loaded = 0
        var failed = 0
        var stale = false
        var firstError: DataException? = null
        var oldest: Instant? = null

        fun note(fetched: Fetched) {
            stale = stale || fetched.isStale
            val at = fetched.instant()
            oldest = oldest?.let { minOf(it, at) } ?: at
        }

        fun snapshot() = MatchDay(
            matches = found.values.sortedWith(compareBy({ it.kickoff ?: Instant.MAX }, { it.home.name })),
            fetchedAt = oldest ?: clock(),
            loadedSources = loaded,
            totalSources = total,
            failedSources = failed,
            isStale = stale,
            isLimitedCoverage = true,
            firstError = firstError,
        )

        try {
            val general = http.get(url("eventsday.php?d=$date&s=Soccer"), ttl, refresh)
            note(general)
            parseMatches(general, "events").forEach { found[it.id] = it }
            loaded++
        } catch (error: DataException) {
            failed++
            firstError = firstError ?: error
        }
        emit(snapshot())

        for (leagueId in leagueIds) {
            try {
                val fetched = http.get(url("eventsday.php?d=$date&l=$leagueId"), ttl, refresh)
                note(fetched)
                val events = parseMatches(fetched, "events").filter { it.league.id == leagueId }
                events.forEach { found[it.id] = it }
                val sample = events.firstOrNull()
                val round = sample?.round
                val season = sample?.season
                if (events.size >= FREE_DAY_LIMIT && round != null && season != null) {
                    val fullRound = http.get(url("eventsround.php?id=$leagueId&r=$round&s=${encode(season)}"), ttl, refresh)
                    note(fullRound)
                    parseMatches(fullRound, "events")
                        .filter { it.date == date && it.league.id == leagueId }
                        .forEach { match -> found[match.id] = found[match.id]?.takeIf { it.statusDetail != null } ?: match }
                }
                loaded++
            } catch (error: DataException) {
                failed++
                firstError = firstError ?: error
            }
            emit(snapshot())
        }

        if (isToday) {
            try {
                val live = http.get(url("livescore.php?s=Soccer"), TTL_LIVE, refresh)
                note(live)
                parseMatches(live, "livescore").forEach { update ->
                    val existing = found[update.id]
                    when {
                        existing != null -> found[update.id] = existing.withLiveState(update)
                        // The feed's dates are UTC. Just after midnight a live match belongs to the local day.
                        (update.kickoff?.atZone(zone())?.toLocalDate() ?: update.date) == date ->
                            found[update.id] = update
                    }
                }
                loaded++
            } catch (error: DataException) {
                failed++
                firstError = firstError ?: error
            }
            emit(snapshot())
        }
    }

    private fun Match.withLiveState(update: Match) = copy(
        status = update.status,
        statusDetail = update.statusDetail,
        minute = update.minute,
        homeScore = update.homeScore ?: homeScore,
        awayScore = update.awayScore ?: awayScore,
    )

    /** The live feed is fresher than the single-match lookup while a game is running. */
    private suspend fun withLiveFeed(match: Match): Match {
        val now = clock()
        val kickoff = match.kickoff ?: return match
        val nearKickoff = now.isAfter(kickoff.minusSeconds(600)) && now.isBefore(kickoff.plusSeconds(4 * 3600))
        if (!nearKickoff || match.status == MatchStatus.FINISHED) return match
        return try {
            val live = http.get(url("livescore.php?s=Soccer"), TTL_LIVE)
            parseMatches(live, "livescore").firstOrNull { it.id == match.id }?.let { match.withLiveState(it) } ?: match
        } catch (_: DataException) {
            match
        }
    }

    override suspend fun matchDetails(matchId: String, refresh: Boolean): Sourced<MatchDetails> {
        val fetched = http.get(url("lookupevent.php?id=${encode(matchId)}"), TTL_MATCH, refresh)
        val looked = parseMatches(fetched, "events").firstOrNull() ?: throw DataException.NotFound()
        val match = withLiveFeed(looked)
        val settled = match.status == MatchStatus.FINISHED
        val detailTtl = if (settled) TTL_FINISHED_MATCH else TTL_MATCH

        val events = if (match.status == MatchStatus.SCHEDULED) {
            emptyList()
        } else {
            try {
                val timeline = http.get(url("lookuptimeline.php?id=${encode(matchId)}"), detailTtl, refresh)
                rows(timeline.body, "timeline").mapNotNull { it.toMatchEvent() }.sortedBy { it.minute ?: Int.MAX_VALUE }
            } catch (_: DataException) {
                emptyList()
            }
        }

        val standings = if (match.league.id.isNotEmpty()) {
            try {
                standings(match.league.id, match.season, refresh = false).data
            } catch (_: DataException) {
                null
            }
        } else {
            null
        }

        return Sourced(
            MatchDetails(
                match = match,
                events = events,
                standings = standings,
                description = rows(fetched.body, "events").firstOrNull()?.text("strDescriptionEN"),
                eventsMayBeIncomplete = events.size >= FREE_TIMELINE_LIMIT,
            ),
            fetched.instant(),
            fetched.isStale,
        )
    }

    override suspend fun featuredLeagues(): Sourced<List<LeagueRef>> =
        Sourced(FEATURED.map { it.ref().let(::withKnownBadge) }, clock())

    override suspend fun league(leagueId: String, refresh: Boolean): Sourced<LeagueDetails> {
        val fetched = http.get(url("lookupleague.php?id=${encode(leagueId)}"), TTL_REFERENCE, refresh)
        val row = rows(fetched.body, "leagues").firstOrNull() ?: throw DataException.NotFound()
        val badge = row.text("strBadge")
        if (badge != null && leagueBadges.put(leagueId, badge) != badge) {
            badgePrefs.edit().putString(leagueId, badge).apply()
        }
        return Sourced(
            LeagueDetails(
                league = LeagueRef(
                    id = leagueId,
                    name = row.text("strLeague") ?: throw DataException.BadData(),
                    badgeUrl = badge ?: leagueBadges[leagueId],
                    country = row.text("strCountry"),
                ),
                currentSeason = row.text("strCurrentSeason"),
                description = row.text("strDescriptionEN"),
            ),
            fetched.instant(),
            fetched.isStale,
        )
    }

    private suspend fun resolveSeason(leagueId: String, season: String?): String =
        season ?: league(leagueId).data.currentSeason ?: throw DataException.NotFound()

    override suspend fun standings(leagueId: String, season: String?, refresh: Boolean): Sourced<Standings> {
        val resolved = resolveSeason(leagueId, season)
        val fetched = http.get(
            url("lookuptable.php?l=${encode(leagueId)}&s=${encode(resolved)}"),
            TTL_TABLE,
            refresh,
        )
        val tableRows = rows(fetched.body, "table")
        val parsed = tableRows.mapNotNull { it.toStandingRow() }.sortedBy { it.rank }
        val first = tableRows.firstOrNull()
        val league = withKnownBadge(
            LeagueRef(leagueId, first?.text("strLeague") ?: FEATURED.firstOrNull { it.id == leagueId }?.name.orEmpty()),
        )
        return Sourced(
            Standings(league, resolved, parsed, isPartial = parsed.size in 1..FREE_TABLE_LIMIT),
            fetched.instant(),
            fetched.isStale,
        )
    }

    private suspend fun currentRound(leagueId: String): Int? {
        val next = try {
            parseMatches(http.get(url("eventsnextleague.php?id=${encode(leagueId)}"), TTL_ROUND_POINTER), "events")
        } catch (_: DataException) {
            emptyList()
        }
        next.firstNotNullOfOrNull { it.round }?.let { return it }
        return parseMatches(http.get(url("eventspastleague.php?id=${encode(leagueId)}"), TTL_ROUND_POINTER), "events")
            .firstNotNullOfOrNull { it.round }
    }

    override suspend fun fixtures(
        leagueId: String,
        season: String?,
        round: Int?,
        refresh: Boolean,
    ): Sourced<LeagueFixtures> {
        val resolved = resolveSeason(leagueId, season)
        val leagueRef = withKnownBadge(
            FEATURED.firstOrNull { it.id == leagueId }?.ref() ?: LeagueRef(leagueId, ""),
        )
        val chosen = round ?: currentRound(leagueId)
            ?: return Sourced(LeagueFixtures(leagueRef, resolved, null, emptyList(), false, false), clock())
        val fetched = http.get(
            url("eventsround.php?id=${encode(leagueId)}&r=$chosen&s=${encode(resolved)}"),
            TTL_ROUND,
            refresh,
        )
        val matches = parseMatches(fetched, "events").sortedBy { it.kickoff ?: Instant.MAX }
        return Sourced(
            LeagueFixtures(
                league = matches.firstOrNull()?.league ?: leagueRef,
                season = resolved,
                round = chosen,
                matches = matches,
                hasPreviousRound = chosen > 1,
                hasNextRound = matches.isNotEmpty(),
            ),
            fetched.instant(),
            fetched.isStale,
        )
    }

    override suspend fun team(teamId: String, refresh: Boolean): Sourced<Team> {
        val fetched = http.get(url("lookupteam.php?id=${encode(teamId)}"), TTL_REFERENCE, refresh)
        val team = rows(fetched.body, "teams").firstNotNullOfOrNull { it.toTeam() } ?: throw DataException.NotFound()
        return Sourced(team, fetched.instant(), fetched.isStale)
    }

    override suspend fun teamMatches(teamId: String, refresh: Boolean): Sourced<TeamMatches> {
        val id = encode(teamId)
        val lastFetched = http.get(url("eventslast.php?id=$id"), TTL_TEAM_MATCHES, refresh)
        val nextFetched = http.get(url("eventsnext.php?id=$id"), TTL_TEAM_MATCHES, refresh)
        val last = parseMatches(lastFetched, "results")
        val next = parseMatches(nextFetched, "events")

        // The free key returns one match each way, so nearby league rounds fill in the rest.
        val nearby = LinkedHashMap<String, Match>()
        (last + next).forEach { nearby[it.id] = it }
        val anchor = last.firstOrNull { it.round != null && it.season != null }
            ?: next.firstOrNull { it.round != null && it.season != null }
        if (anchor != null && anchor.league.id.isNotEmpty()) {
            val base = anchor.round ?: 0
            for (round in listOf(base - 1, base - 2, base + 1, base + 2).filter { it >= 1 }) {
                try {
                    val fetched = http.get(
                        url("eventsround.php?id=${anchor.league.id}&r=$round&s=${encode(anchor.season.orEmpty())}"),
                        TTL_ROUND,
                    )
                    parseMatches(fetched, "events")
                        .filter { it.home.id == teamId || it.away.id == teamId }
                        .forEach { nearby.putIfAbsent(it.id, it) }
                } catch (_: DataException) {
                    break
                }
            }
        }

        val now = clock()
        val recent = nearby.values
            .filter { it.status == MatchStatus.FINISHED || (it.kickoff?.isBefore(now) == true && it.homeScore != null) }
            .sortedByDescending { it.kickoff }
            .take(5)
        val recentIds = recent.map { it.id }.toSet()
        val upcoming = nearby.values
            .filter { it.id !in recentIds && it.kickoff?.isAfter(now) == true }
            .sortedBy { it.kickoff }
            .take(5)
        return Sourced(
            TeamMatches(recent, upcoming),
            Instant.ofEpochMilli(minOf(lastFetched.fetchedAtMs, nextFetched.fetchedAtMs)),
            lastFetched.isStale || nextFetched.isStale,
        )
    }

    override suspend fun search(query: String): Sourced<SearchResults> {
        val normalized = normalize(query)
        val apiQuery = GREEK_ALIASES[normalized]
            ?: GREEK_ALIASES.entries.firstOrNull { it.key.startsWith(normalized) }?.value
            ?: query.trim()
        val fetched = http.get(url("searchteams.php?t=${encode(apiQuery)}"), TTL_REFERENCE)
        val teams = rows(fetched.body, "teams").mapNotNull { it.toTeam() }
        val leagues = FEATURED
            .filter { league -> league.searchTerms.any { normalize(it).contains(normalized) } }
            .map { withKnownBadge(it.ref()) }
        return Sourced(SearchResults(teams, leagues), fetched.instant(), fetched.isStale)
    }

    private fun normalize(text: String): String =
        Normalizer.normalize(text.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(DIACRITICS, "")
            .replace('ς', 'σ')

    private data class FeaturedLeague(
        val id: String,
        val name: String,
        val country: String,
        val searchTerms: List<String>,
    ) {
        fun ref() = LeagueRef(id, name, country = country)
    }

    companion object {
        const val ID = "thesportsdb"
        private const val BASE_URL = "https://www.thesportsdb.com/api/v1/json/123"

        private const val FREE_DAY_LIMIT = 3
        private const val FREE_TABLE_LIMIT = 5
        private const val FREE_TIMELINE_LIMIT = 5
        private const val MAX_DAY_LEAGUES = 10

        private const val MINUTE = 60_000L
        private const val HOUR = 60 * MINUTE
        private const val TTL_LIVE = MINUTE
        private const val TTL_TODAY = 3 * MINUTE
        private const val TTL_FUTURE_DAY = HOUR
        private const val TTL_PAST_DAY = 6 * HOUR
        private const val TTL_MATCH = MINUTE
        private const val TTL_FINISHED_MATCH = 12 * HOUR
        private const val TTL_TABLE = 30 * MINUTE
        private const val TTL_ROUND = 10 * MINUTE
        private const val TTL_ROUND_POINTER = HOUR
        private const val TTL_TEAM_MATCHES = 30 * MINUTE
        private const val TTL_REFERENCE = 24 * HOUR

        private val DIACRITICS = Regex("\\p{Mn}+")

        private val FEATURED = listOf(
            FeaturedLeague("4336", "Greek Super League 1", "Greece", listOf("Greek Super League 1", "Σούπερ Λίγκα", "Ελλάδα", "Greece")),
            FeaturedLeague("4480", "UEFA Champions League", "Europe", listOf("UEFA Champions League", "Τσάμπιονς Λιγκ", "Ευρώπη")),
            FeaturedLeague("4481", "UEFA Europa League", "Europe", listOf("UEFA Europa League", "Γιουρόπα Λιγκ", "Ευρώπη")),
            FeaturedLeague("4328", "English Premier League", "England", listOf("English Premier League", "Πρέμιερ Λιγκ", "Αγγλία", "England")),
            FeaturedLeague("4335", "Spanish La Liga", "Spain", listOf("Spanish La Liga", "Λα Λίγκα", "Ισπανία", "Spain")),
            FeaturedLeague("4332", "Italian Serie A", "Italy", listOf("Italian Serie A", "Σέριε Α", "Ιταλία", "Italy")),
            FeaturedLeague("4331", "German Bundesliga", "Germany", listOf("German Bundesliga", "Μπουντεσλίγκα", "Γερμανία", "Germany")),
            FeaturedLeague("4334", "French Ligue 1", "France", listOf("French Ligue 1", "Λιγκ 1", "Γαλλία", "France")),
        )

        /** The API only understands Latin names, so common Greek club names are mapped. */
        private val GREEK_ALIASES = mapOf(
            "ολυμπιακοσ" to "Olympiacos",
            "παναθηναικοσ" to "Panathinaikos",
            "αεκ" to "AEK Athens",
            "παοκ" to "PAOK",
            "αρησ" to "Aris",
            "αστερασ τριπολησ" to "Asteras Tripolis",
            "ατρομητοσ" to "Atromitos",
            "οφη" to "OFI",
            "βολοσ" to "Volos",
            "λαρισα" to "Larissa",
            "πανσερραικοσ" to "Panserraikos",
            "λεβαδειακοσ" to "Levadiakos",
            "παναιτωλικοσ" to "Panetolikos",
            "κηφισια" to "Kifisia",
        )
    }
}
