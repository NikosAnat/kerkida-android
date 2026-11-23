package app.kerkida.data.greek

import android.content.Context
import app.kerkida.data.DataException
import app.kerkida.data.LeagueCatalog
import app.kerkida.data.Sourced
import app.kerkida.data.SportsDataProvider
import app.kerkida.data.espn.EspnProvider
import app.kerkida.data.model.LeagueDetails
import app.kerkida.data.model.LeagueFixtures
import app.kerkida.data.model.LeagueRef
import app.kerkida.data.model.Match
import app.kerkida.data.model.MatchDay
import app.kerkida.data.model.MatchDetails
import app.kerkida.data.model.MatchStatus
import app.kerkida.data.model.SearchResults
import app.kerkida.data.model.Sport
import app.kerkida.data.model.StandingRow
import app.kerkida.data.model.Standings
import app.kerkida.data.model.Team
import app.kerkida.data.model.TeamMatches
import app.kerkida.data.model.TeamRef
import app.kerkida.data.net.CachedHttpClient
import app.kerkida.data.thesportsdb.TheSportsDbParsing
import app.kerkida.data.thesportsdb.TheSportsDbParsing.toMatch
import app.kerkida.data.thesportsdb.TheSportsDbParsing.toStandingRow
import app.kerkida.stadium.Stadium
import app.kerkida.stadium.StadiumPack
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class GreekLeague(val id: String, val source: Int, val name: String, val rounds: Int = 0, val sport: String = "FOOTBALL")

@Serializable
data class GreekTeam(
    val id: String,
    val name: String,
    val league: Int,
    val badge: String? = null,
    val location: String? = null,
    val venue: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
)

@Serializable
data class GreekEvent(
    @SerialName("e") val id: String,
    @SerialName("l") val league: Int,
    @SerialName("r") val round: Int,
    @SerialName("h") val home: String? = null,
    @SerialName("a") val away: String? = null,
    @SerialName("t") val timestamp: String? = null,
    @SerialName("d") val date: String? = null,
    @SerialName("v") val venue: String? = null,
    @SerialName("s") val status: String? = null,
    @SerialName("hs") val homeScore: String? = null,
    @SerialName("as") val awayScore: String? = null,
)

@Serializable
data class GreekPack(
    val season: String = "",
    val leagues: List<GreekLeague> = emptyList(),
    val teams: List<GreekTeam> = emptyList(),
    val events: List<GreekEvent> = emptyList(),
)

/** Rules with no network or Android in them, so they can be tested directly. */
object GreekLowerLogic {
    const val LEAGUE_PREFIX = "grl."
    const val TEAM_PREFIX = "grl:"

    fun isLeague(id: String) = id.startsWith(LEAGUE_PREFIX) && '~' !in id
    fun isMatch(id: String) = id.startsWith(LEAGUE_PREFIX) && '~' in id
    fun isTeam(id: String) = id.startsWith(TEAM_PREFIX)
    fun leagueId(source: Int) = "$LEAGUE_PREFIX$source"
    fun teamId(source: String) = "$TEAM_PREFIX$source"
    fun matchId(league: Int, event: String) = "$LEAGUE_PREFIX$league~$event"

    /**
     * Super League clubs appear in the Greek Cup under the cup source's ids.
     * They are the same clubs as in the league, so each one keeps a single profile.
     */
    private val LeagueIdOfCupClub = mapOf(
        "133753" to "887", "133742" to "11553", "133752" to "8354", "133744" to "6790",
        "134275" to "132429", "144147" to "21969", "133755" to "5276", "133743" to "1010",
        "133754" to "435", "133749" to "605", "133746" to "443", "136853" to "20043",
    )
    private val CupIdOfLeagueClub = LeagueIdOfCupClub.entries.associate { (cup, league) -> league to cup }

    /** Old links and favourites of a cup profile, and where they now point. */
    val MergedTeams: Map<String, String> = LeagueIdOfCupClub.entries.associate { (cup, league) -> teamId(cup) to league }

    fun canonicalTeam(id: String) = MergedTeams[id] ?: id

    /** Greek basketball clubs in Europe. Their profile is the one from the Greek league. */
    val EuropeanClubs: Map<String, String> = mapOf(
        "basketball/euroleague:PAN" to "${TEAM_PREFIX}gbl-00000001",
        "basketball/euroleague:OLY" to "${TEAM_PREFIX}gbl-00000002",
        "basketball/eurocup:ARI" to "${TEAM_PREFIX}gbl-00000005",
        "basketball/eurocup:PAO" to "${TEAM_PREFIX}gbl-0000000C",
    )
    private val EuropeanIdOfClub = EuropeanClubs.entries.associate { (europe, league) -> league to europe }

    fun europeanId(id: String): String? = EuropeanIdOfClub[id]

    /** The id a league club has in the bundled season, if it plays in the cup there. */
    fun packId(id: String): String? = CupIdOfLeagueClub[id]

    /** Greek and Latin spellings meet here: "παναιγιαλειος" and "Panegialios" give the same key. */
    fun nameKey(text: String): String = EspnProvider.searchKey(text)
        .replace("ai", "e").replace("ei", "i").replace("oi", "i").replace("ou", "u")
        .replace('y', 'i')
        .replace(Regex("[^a-z0-9 ]+"), "")
        .split(' ').filter(String::isNotEmpty).joinToString(" ") { it.removeSuffix("s") }

    /**
     * The free feed lists only the top of a table, so the rest is counted from results.
     * Rows the source does list stay first, because they include any points penalties.
     */
    fun table(
        teams: List<TeamRef>,
        matches: List<Match>,
        listed: List<StandingRow> = emptyList(),
        win: Int = 3,
        draw: Int = 1,
        loss: Int = 0,
        /** Volleyball: a 3-2 result gives 2 points to the winner and 1 to the loser. */
        fiveSets: Boolean = false,
    ): List<StandingRow> {
        data class Tally(var w: Int = 0, var d: Int = 0, var l: Int = 0, var gf: Int = 0, var ga: Int = 0, var bonus: Int = 0)
        val tally = teams.associate { it.id to Tally() }
        matches.asSequence().filter { it.status == MatchStatus.FINISHED }.forEach { game ->
            val home = game.homeScore ?: return@forEach
            val away = game.awayScore ?: return@forEach
            val a = tally[game.home.id] ?: return@forEach
            val b = tally[game.away.id] ?: return@forEach
            a.gf += home; a.ga += away; b.gf += away; b.ga += home
            when {
                home > away -> { a.w++; b.l++ }
                home < away -> { b.w++; a.l++ }
                else -> { a.d++; b.d++ }
            }
            if (fiveSets && minOf(home, away) == 2) {
                // Counted as a normal win above, so the winner gives one point to the loser.
                if (home > away) { a.bonus--; b.bonus++ } else { b.bonus--; a.bonus++ }
            }
        }
        val counted = teams.map { team ->
            val t = tally.getValue(team.id)
            StandingRow(0, team, t.w + t.d + t.l, t.w, t.d, t.l, t.gf, t.ga, t.gf - t.ga, t.w * win + t.d * draw + t.l * loss + t.bonus)
        }.sortedWith(compareBy({ -it.points }, { -it.goalDifference }, { -it.goalsFor }, { it.team.name }))
        val top = listed.sortedBy { it.rank }
        val shown = top.map { it.team.id }.toSet()
        return (top + counted.filter { it.team.id !in shown }).mapIndexed { index, row -> row.copy(rank = index + 1) }
    }

    /** The team's own ground only counts when the match is really played there. */
    fun groundOf(venue: String?, home: GreekTeam?, all: List<GreekTeam>): GreekTeam? {
        if (venue.isNullOrBlank()) return home?.takeIf { it.lat != null }
        val key = StadiumPack.key(venue)
        if (home?.venue != null && StadiumPack.key(home.venue) == key && home.lat != null) return home
        return all.firstOrNull { it.lat != null && it.venue != null && StadiumPack.key(it.venue) == key }
    }
}

/** Super League 2 and Gamma Ethniki: a bundled season, updated from TheSportsDB only where it can have changed. */
class GreekLowerSource(
    private val http: CachedHttpClient,
    private val loadPack: () -> GreekPack,
    private val clock: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val pack by lazy(loadPack)
    private val teamsById by lazy { pack.teams.associateBy { it.id } }
    private val leaguesById by lazy { pack.leagues.associateBy { it.id } }
    private val updates = ConcurrentHashMap<String, Match>()

    val leagues: List<GreekLeague> get() = pack.leagues

    fun ref(leagueId: String): LeagueRef = LeagueRef(
        leagueId,
        leaguesById[leagueId]?.name ?: leagueId,
        country = "Greece",
        sport = if (isVolleyball(leagueId)) Sport.VOLLEYBALL else Sport.FOOTBALL,
    )

    fun isVolleyball(leagueId: String) = leaguesById[leagueId]?.sport == Sport.VOLLEYBALL.name

    private fun teamRef(id: String?, fallback: String? = null): TeamRef {
        val team = id?.let(teamsById::get)
        return TeamRef(GreekLowerLogic.canonicalTeam(GreekLowerLogic.teamId(id.orEmpty())), displayName(team?.name ?: fallback.orEmpty()), team?.badge)
    }

    private fun kickoff(event: GreekEvent): Instant? = event.timestamp?.let {
        runCatching { LocalDateTime.parse(it.removeSuffix("Z").take(19)).toInstant(ZoneOffset.UTC) }.getOrNull()
    }

    private fun toMatch(event: GreekEvent, now: Instant): Match {
        val start = kickoff(event)
        val home = event.homeScore?.toIntOrNull()
        val away = event.awayScore?.toIntOrNull()
        val status = TheSportsDbParsing.classifyStatus(event.status, home != null && away != null, start, now)
        val showScore = status != MatchStatus.SCHEDULED && status != MatchStatus.POSTPONED
        return Match(
            id = GreekLowerLogic.matchId(event.league, event.id),
            league = ref(GreekLowerLogic.leagueId(event.league)),
            home = teamRef(event.home),
            away = teamRef(event.away),
            kickoff = start,
            date = event.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            status = status,
            statusDetail = event.status,
            homeScore = home.takeIf { showScore },
            awayScore = away.takeIf { showScore },
            round = event.round,
            season = pack.season,
            venue = event.venue,
        )
    }

    private fun leagueMatches(leagueId: String): List<Match> {
        val source = leaguesById[leagueId]?.source ?: return emptyList()
        val now = clock()
        val merged = LinkedHashMap<String, Match>()
        pack.events.filter { it.league == source }.forEach { event -> toMatch(event, now).let { merged[it.id] = it } }
        updates.values.filter { it.league.id == leagueId }.forEach { merged[it.id] = it }
        return merged.values.sortedBy { it.kickoff ?: Instant.MAX }
    }

    private fun onDay(game: Match, date: LocalDate) = (game.kickoff?.atZone(zone())?.toLocalDate() ?: game.date) == date

    fun bundledDay(leagueIds: List<String>, date: LocalDate): List<Match> =
        leagueIds.flatMap(::leagueMatches).filter { onDay(it, date) }

    private suspend fun refreshRound(leagueId: String, round: Int, force: Boolean): Boolean {
        val source = leaguesById[leagueId]?.source ?: return false
        val url = "$BASE/eventsround.php?id=$source&r=$round&s=${pack.season}"
        val fetched = http.get(url, TTL_ROUND, force)
        val now = clock()
        val wanted = if (isVolleyball(leagueId)) "Volleyball" else "Soccer"
        TheSportsDbParsing.rows(fetched.body, "events").mapNotNull { it.toMatch(now, wanted) }.forEach { game ->
            val rawHome = game.home.id
            val rawAway = game.away.id
            val id = GreekLowerLogic.matchId(source, game.id)
            updates[id] = game.copy(
                id = id,
                league = ref(leagueId),
                home = teamRef(rawHome, game.home.name),
                away = teamRef(rawAway, game.away.name),
                round = game.round ?: round,
            )
        }
        return fetched.isStale
    }

    /** Rounds whose matches should have been played, or are due soon, but have no final score here. */
    private fun openRounds(leagueId: String, until: Instant): List<Int> = leagueMatches(leagueId)
        .filter { it.status != MatchStatus.FINISHED && it.status != MatchStatus.CANCELLED && it.kickoff?.isBefore(until) == true }
        .mapNotNull { it.round }.distinct()

    private suspend fun catchUp(leagueId: String, refresh: Boolean): Boolean {
        var stale = false
        for (round in openRounds(leagueId, clock().plusSeconds(WEEK_SECONDS))) {
            try {
                stale = refreshRound(leagueId, round, refresh) || stale
            } catch (error: DataException) {
                if (error is DataException.RateLimited || error is DataException.Offline) return true
                stale = true
            }
        }
        return stale
    }

    /** Returns true when part of the answer could not be updated. */
    suspend fun updateDay(leagueIds: List<String>, refresh: Boolean): Boolean {
        var stale = false
        leagueIds.forEach { stale = catchUp(it, refresh) || stale }
        return stale
    }

    suspend fun standings(leagueId: String, refresh: Boolean): Sourced<Standings> {
        val source = leaguesById[leagueId]?.source ?: throw DataException.NotFound()
        var stale = catchUp(leagueId, refresh)
        val listed = try {
            val fetched = http.get("$BASE/lookuptable.php?l=$source&s=${pack.season}", TTL_TABLE, refresh)
            stale = stale || fetched.isStale
            TheSportsDbParsing.rows(fetched.body, "table").mapNotNull { it.toStandingRow() }
                .map { it.copy(team = teamRef(it.team.id, it.team.name), form = null, note = null) }
        } catch (_: DataException) {
            stale = true
            emptyList()
        }
        return Sourced(Standings(ref(leagueId), pack.season, localTable(leagueId, listed), isPartial = false), clock(), stale)
    }

    private fun localTable(leagueId: String, listed: List<StandingRow> = emptyList()): List<StandingRow> {
        val source = leaguesById[leagueId]?.source
        // A cup includes clubs whose main league is another one, so teams come from the games.
        val teams = pack.events.filter { it.league == source }.flatMap { listOfNotNull(it.home, it.away) }.distinct().map { teamRef(it) }
        return GreekLowerLogic.table(teams, leagueMatches(leagueId), listed, fiveSets = isVolleyball(leagueId))
    }

    suspend fun fixtures(leagueId: String, round: Int?, refresh: Boolean): Sourced<LeagueFixtures> {
        val all = leagueMatches(leagueId)
        val last = maxOf(leaguesById[leagueId]?.rounds ?: 0, all.maxOfOrNull { it.round ?: 0 } ?: 0)
        val chosen = round ?: all.firstOrNull { it.status != MatchStatus.FINISHED }?.round ?: last
        var stale = false
        if (all.none { it.round == chosen } || all.any { it.round == chosen && it.status != MatchStatus.FINISHED }) {
            stale = runCatching { refreshRound(leagueId, chosen, refresh) }.getOrDefault(true)
        }
        return Sourced(
            LeagueFixtures(
                league = ref(leagueId),
                season = pack.season,
                round = chosen,
                matches = leagueMatches(leagueId).filter { it.round == chosen },
                hasPreviousRound = chosen > 1,
                hasNextRound = chosen < last,
            ),
            clock(),
            stale,
        )
    }

    fun league(leagueId: String) = Sourced(LeagueDetails(ref(leagueId), pack.season), clock())

    private fun greekTeam(teamId: String) = teamsById[GreekLowerLogic.packId(teamId) ?: teamId.removePrefix(GreekLowerLogic.TEAM_PREFIX)]

    fun team(teamId: String): Sourced<Team> {
        val team = greekTeam(teamId) ?: throw DataException.NotFound()
        return Sourced(
            Team(
                id = teamId,
                name = displayName(team.name),
                badgeUrl = team.badge,
                country = "Greece",
                stadium = team.venue,
                location = team.location,
                league = ref(GreekLowerLogic.leagueId(team.league)),
            ),
            clock(),
        )
    }

    private fun teamGames(teamId: String): List<Match> {
        return leaguesOf(teamId).flatMap(::leagueMatches).filter { it.home.id == teamId || it.away.id == teamId }
    }

    /** A club can play in its league and in the cup. */
    private fun leaguesOf(teamId: String): List<String> {
        val raw = GreekLowerLogic.packId(teamId) ?: teamId.removePrefix(GreekLowerLogic.TEAM_PREFIX)
        return pack.events.filter { it.home == raw || it.away == raw }.map { GreekLowerLogic.leagueId(it.league) }.distinct()
    }

    suspend fun teamMatches(teamId: String, refresh: Boolean): Sourced<TeamMatches> {
        greekTeam(teamId) ?: throw DataException.NotFound()
        var stale = false
        leaguesOf(teamId).forEach { stale = catchUp(it, refresh) || stale }
        val (played, coming) = teamGames(teamId).partition { it.status == MatchStatus.FINISHED }
        return Sourced(TeamMatches(played.sortedByDescending { it.kickoff }, coming.sortedBy { it.kickoff }), clock(), stale)
    }

    fun history(teamId: String): List<Match> = teamGames(teamId).filter { it.status == MatchStatus.FINISHED }

    suspend fun details(matchId: String, refresh: Boolean): Sourced<MatchDetails> {
        val leagueId = matchId.substringBefore('~')
        var game = leagueMatches(leagueId).firstOrNull { it.id == matchId } ?: throw DataException.NotFound()
        var stale = false
        if (game.status != MatchStatus.FINISHED) {
            stale = runCatching { refreshRound(leagueId, game.round ?: 1, refresh) }.getOrDefault(true)
            game = leagueMatches(leagueId).firstOrNull { it.id == matchId } ?: game
        }
        val table = Standings(ref(leagueId), pack.season, localTable(leagueId), isPartial = false)
        return Sourced(MatchDetails(game, emptyList(), table), clock(), stale)
    }

    fun searchTeams(query: String): List<Team> {
        val needle = GreekLowerLogic.nameKey(query)
        if (needle.length < 2) return emptyList()
        return pack.teams.filter { team ->
            GreekLowerLogic.nameKey(team.name).contains(needle) || GreekLowerLogic.nameKey(displayName(team.name)).contains(needle)
        }.take(MAX_HITS).map { found ->
            val entry = team(GreekLowerLogic.teamId(found.id)).data
            val merged = GreekLowerLogic.canonicalTeam(entry.id)
            if (merged == entry.id) entry else entry.copy(id = merged, league = LeagueRef("gre.1", "Super League Ελλάδας", country = "Greece"))
        }
    }

    /** A real position for small grounds, which the stadium pack does not know. */
    fun stadiumOf(game: Match): Stadium? {
        if (!GreekLowerLogic.isMatch(game.id)) return null
        val home = greekTeam(game.home.id)
        val ground = GreekLowerLogic.groundOf(game.venue, home, pack.teams) ?: return null
        return Stadium(name = game.venue ?: ground.venue ?: ground.name, city = ground.location, country = "Greece", lat = ground.lat, lng = ground.lng)
    }

    companion object {
        private const val BASE = "https://www.thesportsdb.com/api/v1/json/123"
        private const val TTL_ROUND = 600_000L
        private const val TTL_TABLE = 1_800_000L
        private const val WEEK_SECONDS = 7 * 86_400L
        private const val MAX_HITS = 15

        private val GreekNames = mapOf(
            "Panachaiki" to "Παναχαϊκή",
            "Panegialios" to "Παναιγιάλειος",
            "Atromitos Patron" to "Ατρόμητος Πατρών",
            "OFI" to "OFI Crete",
            "Volos" to "Volos NFC",
            "Asteras Tripolis" to "Asteras Tripoli",
        )

        fun displayName(name: String) = GreekNames[name] ?: name

        private val json = Json { ignoreUnknownKeys = true }

        fun readPack(context: Context): GreekPack = runCatching {
            context.assets.open("catalog/greek_lower.json").bufferedReader().use { json.decodeFromString<GreekPack>(it.readText()) }
        }.getOrDefault(GreekPack())
    }
}

/** Adds the Greek lower divisions to a source, leaving everything else to it. */
class GreekLowerProvider(
    private val base: SportsDataProvider,
    private val source: GreekLowerSource,
    private val catalog: LeagueCatalog,
    private val gbl: GblSource,
    private val local: LocalLeagues,
    private val open: OpenResultsSource,
    private val isOpenEnabled: () -> Boolean,
    private val isEnabled: () -> Boolean,
) : SportsDataProvider by base {

    private fun openChosen() = if (isEnabled() && isOpenEnabled()) catalog.selected.value.filter { it in open.ids } else emptyList()

    private fun openChosen(sport: Sport) = openChosen().filter { open.sportOf(it) == sport }

    override val sports: List<Sport>
        get() = (base.sports + openChosen().mapNotNull(open::sportOf)).distinct().sortedBy { it.ordinal }

    private fun isGbl(id: String) = id == GblSource.LEAGUE_ID || id.startsWith("${GblSource.LEAGUE_ID}~") || id.startsWith("${GreekLowerLogic.TEAM_PREFIX}gbl-")

    private fun localChosen() = if (isEnabled()) catalog.selected.value.filter { it in local.ids } else emptyList()

    private fun gblOn() = isEnabled() && GblSource.LEAGUE_ID in catalog.selected.value

    private fun volleyChosen() =
        if (isEnabled()) catalog.selected.value.filter { GreekLowerLogic.isLeague(it) && source.isVolleyball(it) } else emptyList()

    private fun volleyDay(date: LocalDate, refresh: Boolean): Flow<MatchDay> = flow {
        val ids = volleyChosen()
        fun merged(day: MatchDay, done: Boolean, stale: Boolean = false) = day.copy(
            matches = (day.matches + source.bundledDay(ids, date)).distinctBy { it.id }
                .sortedWith(compareBy({ it.kickoff ?: Instant.MAX }, { it.home.name })),
            loadedSources = day.loadedSources + if (done) 1 else 0,
            totalSources = day.totalSources + 1,
            isStale = day.isStale || stale,
        )
        var last: MatchDay? = null
        base.sportDay(Sport.VOLLEYBALL, date, refresh).collect {
            last = it
            emit(merged(it, done = false))
        }
        val stale = source.updateDay(ids, refresh)
        emit(merged(last ?: MatchDay(emptyList(), Instant.now(), 0, 0), done = true, stale = stale))
    }

    /** One id per club everywhere, so favourites, highlights and links all agree. */
    private fun sameClub(id: String): String {
        val merged = GreekLowerLogic.canonicalTeam(id)
        return if (isEnabled()) GreekLowerLogic.EuropeanClubs[merged] ?: merged else merged
    }

    private fun TeamRef.oneId() = copy(id = sameClub(id))

    private fun Match.oneId() = copy(home = home.oneId(), away = away.oneId())

    private fun Standings.oneId() = copy(rows = rows.map { it.copy(team = it.team.oneId()) })

    override fun sportDay(sport: Sport, date: LocalDate, refresh: Boolean): Flow<MatchDay> =
        allSportDay(sport, date, refresh).map { day -> day.copy(matches = day.matches.map { it.oneId() }) }

    private fun allSportDay(sport: Sport, date: LocalDate, refresh: Boolean): Flow<MatchDay> = flow {
        val extra = openChosen(sport)
        if (extra.isEmpty()) {
            coreSportDay(sport, date, refresh).collect { emit(it) }
            return@flow
        }
        fun merged(day: MatchDay, done: Boolean, stale: Boolean = false) = day.copy(
            matches = (day.matches + open.day(extra, date)).distinctBy { it.id }
                .sortedWith(compareBy({ it.kickoff ?: Instant.MAX }, { it.home.name })),
            loadedSources = day.loadedSources + if (done) 1 else 0,
            totalSources = day.totalSources + 1,
            isStale = day.isStale || stale,
        )
        var last: MatchDay? = null
        try {
            coreSportDay(sport, date, refresh).collect {
                last = it
                emit(merged(it, done = false))
            }
        } catch (error: DataException) {
            // The other sources failing must not hide the games this source can still show.
            if (open.day(extra, date).isEmpty() && !open.hasGames(extra)) {
                val stale = open.load(extra, date, refresh)
                if (open.day(extra, date).isEmpty() && stale) throw error
            }
        }
        val stale = open.load(extra, date, refresh)
        emit(merged(last ?: MatchDay(emptyList(), Instant.now(), 0, 0), done = true, stale = stale))
    }

    private fun coreSportDay(sport: Sport, date: LocalDate, refresh: Boolean): Flow<MatchDay> = flow {
        if (sport == Sport.VOLLEYBALL && volleyChosen().isNotEmpty()) {
            volleyDay(date, refresh).collect { emit(it) }
            return@flow
        }
        if (sport != Sport.BASKETBALL || !gblOn()) {
            base.sportDay(sport, date, refresh).collect { emit(it) }
            return@flow
        }
        fun merged(day: MatchDay, done: Boolean, stale: Boolean = false) = day.copy(
            matches = (day.matches + gbl.day(date)).distinctBy { it.id }.sortedWith(compareBy({ it.kickoff ?: Instant.MAX }, { it.home.name })),
            loadedSources = day.loadedSources + if (done) 1 else 0,
            totalSources = day.totalSources + 1,
            isStale = day.isStale || stale,
        )
        var last: MatchDay? = null
        base.sportDay(sport, date, refresh).collect {
            last = it
            emit(merged(it, done = false))
        }
        val stale = gbl.load(refresh)
        val day = last ?: MatchDay(emptyList(), Instant.now(), 0, 0)
        emit(merged(day, done = true, stale = stale))
    }

    private fun chosen(priority: List<String>): List<String> {
        if (!isEnabled()) return emptyList()
        val selected = catalog.selected.value.filter { GreekLowerLogic.isLeague(it) && it != GblSource.LEAGUE_ID && it !in local.ids && it !in open.ids && !source.isVolleyball(it) }
        return priority.filter { it in selected } + selected.filter { it !in priority }
    }

    override fun matchDay(date: LocalDate, priorityLeagueIds: List<String>, refresh: Boolean): Flow<MatchDay> = flow {
        val ids = chosen(priorityLeagueIds)
        val nearby = localChosen()
        if (ids.isEmpty() && nearby.isEmpty()) {
            base.matchDay(date, priorityLeagueIds, refresh).collect { emit(it) }
            return@flow
        }
        fun merged(day: MatchDay, done: Boolean, stale: Boolean = false) = day.copy(
            matches = (day.matches + source.bundledDay(ids, date) + local.day(nearby, date)).distinctBy { it.id }
                .sortedWith(compareBy({ it.kickoff ?: Instant.MAX }, { it.home.name })),
            loadedSources = day.loadedSources + if (done) 1 else 0,
            totalSources = day.totalSources + 1,
            isStale = day.isStale || stale,
        )
        var last: MatchDay? = null
        base.matchDay(date, priorityLeagueIds, refresh).collect {
            last = it
            emit(merged(it, done = false))
        }
        val stale = source.updateDay(ids, refresh) or local.load(nearby, refresh)
        last?.let { emit(merged(it, done = true, stale = stale)) }
    }

    override suspend fun matchDetails(matchId: String, refresh: Boolean): Sourced<MatchDetails> {
        val found = rawDetails(matchId, refresh)
        return found.copy(data = found.data.copy(match = found.data.match.oneId(), standings = found.data.standings?.oneId()))
    }

    private suspend fun rawDetails(matchId: String, refresh: Boolean): Sourced<MatchDetails> =
        if (open.owns(matchId)) open.details(matchId, refresh) else if (isGbl(matchId)) gbl.details(matchId, refresh) else if (local.owns(matchId)) local.details(matchId, refresh) else if (GreekLowerLogic.isMatch(matchId)) source.details(matchId, refresh) else base.matchDetails(matchId, refresh)

    override suspend fun featuredLeagues(): Sourced<List<LeagueRef>> {
        val listed = base.featuredLeagues()
        return listed.copy(data = listed.data + chosen(emptyList()).map(source::ref) + listOfNotNull(gbl.ref.takeIf { gblOn() }) + openChosen().map(open::ref) + localChosen().map(local::ref) + volleyChosen().map(source::ref))
    }

    override suspend fun league(leagueId: String, refresh: Boolean) =
        if (open.owns(leagueId)) open.league(leagueId) else if (isGbl(leagueId)) gbl.league() else if (local.owns(leagueId)) local.league(leagueId) else if (GreekLowerLogic.isLeague(leagueId)) source.league(leagueId) else base.league(leagueId, refresh)

    override suspend fun standings(leagueId: String, season: String?, refresh: Boolean): Sourced<Standings> {
        val found = rawStandings(leagueId, season, refresh)
        return found.copy(data = found.data.oneId())
    }

    private suspend fun rawStandings(leagueId: String, season: String?, refresh: Boolean): Sourced<Standings> =
        if (open.owns(leagueId)) open.standings(leagueId, refresh) else if (isGbl(leagueId)) gbl.standings(refresh) else if (local.owns(leagueId)) local.standings(leagueId, refresh) else if (GreekLowerLogic.isLeague(leagueId)) source.standings(leagueId, refresh) else base.standings(leagueId, season, refresh)

    override suspend fun fixtures(leagueId: String, season: String?, round: Int?, refresh: Boolean): Sourced<LeagueFixtures> {
        val found = rawFixtures(leagueId, season, round, refresh)
        return found.copy(data = found.data.copy(matches = found.data.matches.map { it.oneId() }))
    }

    private suspend fun rawFixtures(leagueId: String, season: String?, round: Int?, refresh: Boolean): Sourced<LeagueFixtures> =
        if (open.owns(leagueId)) open.fixtures(leagueId, round, refresh) else if (isGbl(leagueId)) gbl.fixtures(round, refresh) else if (local.owns(leagueId)) local.fixtures(leagueId, round, refresh) else if (GreekLowerLogic.isLeague(leagueId)) source.fixtures(leagueId, round, refresh) else base.fixtures(leagueId, season, round, refresh)

    override suspend fun team(teamId: String, refresh: Boolean): Sourced<Team> {
        val id = sameClub(teamId)
        val abroad = GreekLowerLogic.europeanId(id)
        if (abroad != null && isEnabled()) {
            return try {
                gbl.team(id)
            } catch (error: DataException) {
                val other = base.team(abroad, refresh)
                other.copy(data = other.data.copy(id = id))
            }
        }
        return if (open.owns(id)) open.team(id) else if (isGbl(id)) gbl.team(id) else if (local.owns(id)) local.team(id) else if (GreekLowerLogic.isTeam(id)) source.team(id) else base.team(id, refresh)
    }

    private fun playsInCup(id: String) = isEnabled() && GreekLowerLogic.packId(id) != null

    /** A club's games from its two sources, on one profile. One failing source does not hide the other. */
    private suspend fun fromTwo(
        first: suspend () -> Sourced<TeamMatches>,
        second: suspend () -> Sourced<TeamMatches>,
    ): Sourced<TeamMatches> {
        var problem: DataException? = null
        suspend fun read(part: suspend () -> Sourced<TeamMatches>) = try {
            part()
        } catch (error: DataException) {
            problem = error
            null
        }
        val a = read(first)
        val b = read(second)
        val main = a ?: b ?: throw problem ?: DataException.NotFound()
        val games = listOfNotNull(a, b)
        return main.copy(
            data = TeamMatches(
                recent = games.flatMap { it.data.recent }.map { it.oneId() }.distinctBy { it.id }.sortedByDescending { it.kickoff ?: Instant.MIN },
                upcoming = games.flatMap { it.data.upcoming }.map { it.oneId() }.distinctBy { it.id }.sortedBy { it.kickoff ?: Instant.MAX },
            ),
            isStale = games.size < 2 || games.any { it.isStale },
        )
    }

    override suspend fun teamMatches(teamId: String, refresh: Boolean): Sourced<TeamMatches> {
        val id = sameClub(teamId)
        val abroad = GreekLowerLogic.europeanId(id)
        return when {
            playsInCup(id) -> fromTwo({ base.teamMatches(id, refresh) }, { source.teamMatches(id, refresh) })
            abroad != null && isEnabled() -> fromTwo({ gbl.teamMatches(id, refresh) }, { base.teamMatches(abroad, refresh) })
            open.owns(id) -> open.teamMatches(id, refresh)
            isGbl(id) -> gbl.teamMatches(id, refresh)
            local.owns(id) -> local.teamMatches(id, refresh)
            GreekLowerLogic.isTeam(id) -> source.teamMatches(id, refresh)
            else -> base.teamMatches(id, refresh)
        }
    }

    override suspend fun teamHistory(teamId: String, seasons: Int): List<Match> {
        val id = sameClub(teamId)
        val abroad = GreekLowerLogic.europeanId(id)
        suspend fun safely(part: suspend () -> List<Match>) = try {
            part()
        } catch (_: DataException) {
            emptyList()
        }
        return when {
            playsInCup(id) -> (safely { base.teamHistory(id, seasons) } + source.history(id)).map { it.oneId() }.distinctBy { it.id }
            abroad != null && isEnabled() -> (gbl.history(id) + safely { base.teamHistory(abroad, seasons) }).map { it.oneId() }.distinctBy { it.id }
            open.owns(id) -> open.history(id)
            isGbl(id) -> gbl.history(id)
            local.owns(id) -> local.history(id)
            GreekLowerLogic.isTeam(id) -> source.history(id)
            else -> base.teamHistory(id, seasons)
        }
    }

    /** Gives the slow local sources a few seconds. What they read stays saved for the next search. */
    private suspend fun prepareSearch(query: String) {
        withTimeoutOrNull(SEARCH_WAIT_MS) {
            coroutineScope {
                launch { this@GreekLowerProvider.local.load(localChosen(), false) }
                if (gblOn()) launch { gbl.searchTeams(query) }
                launch { open.searchTeams(openChosen(), query) }
            }
        }
    }

    override suspend fun search(query: String): Sourced<SearchResults> {
        if (isEnabled()) prepareSearch(query)
        val local = if (isEnabled()) {
            source.searchTeams(query) + (if (gblOn()) gbl.knownTeams(query) else emptyList()) + this.local.searchTeams(query) + open.knownTeams(openChosen(), query)
        } else {
            emptyList()
        }
        val found = try {
            base.search(query)
        } catch (error: DataException) {
            if (local.isEmpty()) throw error
            return Sourced(SearchResults(local, emptyList()), Instant.now(), isStale = true)
        }
        val leagues = if (isEnabled()) found.data.leagues else found.data.leagues.filterNot { GreekLowerLogic.isLeague(it.id) }
        val others = found.data.teams.map { it.copy(id = sameClub(it.id)) }
        // A football club keeps the league's entry, a basketball club the Greek league's.
        val kept = local.filterNot { mine -> !isGbl(mine.id) && others.any { it.id == mine.id } }
        return found.copy(data = SearchResults(kept + others.filterNot { other -> kept.any { it.id == other.id } }, leagues))
    }
}

private const val SEARCH_WAIT_MS = 3_000L
