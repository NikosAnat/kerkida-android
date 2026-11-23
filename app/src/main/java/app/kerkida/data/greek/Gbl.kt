package app.kerkida.data.greek

import app.kerkida.data.DataException
import app.kerkida.data.Sourced
import app.kerkida.data.model.LeagueDetails
import app.kerkida.data.model.LeagueFixtures
import app.kerkida.data.model.LeagueRef
import app.kerkida.data.model.Match
import app.kerkida.data.model.MatchDetails
import app.kerkida.data.model.MatchStatus
import app.kerkida.data.model.Sport
import app.kerkida.data.model.Standings
import app.kerkida.data.model.Team
import app.kerkida.data.model.TeamMatches
import app.kerkida.data.model.TeamRef
import app.kerkida.data.net.CachedHttpClient
import app.kerkida.stadium.Stadium
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** One game as listed on the league's public results page. */
data class GblGame(
    val id: String,
    val round: Int,
    val kickoff: Instant?,
    val venue: String?,
    val channel: String?,
    val home: TeamRef,
    val away: TeamRef,
    val homeScore: Int?,
    val awayScore: Int?,
)

object GblParsing {
    private const val SITE = "https://www.esake.gr"
    private val Athens: ZoneId = ZoneId.of("Europe/Athens")
    private val Months = mapOf(
        "Ιαν" to 1, "Φεβ" to 2, "Μαρ" to 3, "Απρ" to 4, "Μαϊ" to 5, "Μαι" to 5, "Μάι" to 5, "Ιουν" to 6, "Ιουλ" to 7,
        "Αυγ" to 8, "Σεπ" to 9, "Οκτ" to 10, "Νοε" to 11, "Δεκ" to 12,
    )
    private val When = Regex("""(\d{1,2})\s+(\p{L}+)\s*-\s*(\d{1,2}):(\d{2})""")
    private val Side = Regex("""<span>(.*?)</span>""", RegexOption.DOT_MATCHES_ALL)
    private val Badge = Regex("""src="(/dat/esaketeam/([0-9A-Za-z]+)/[^"]+)"""")
    private val Score = Regex("""(\d{1,3})\s*(?:&nbsp;)?\s*-\s*(?:&nbsp;)?\s*(\d{1,3})""")

    /** The season starts in autumn, so months from August on belong to its first year. */
    fun kickoff(text: String, seasonStartYear: Int): Instant? {
        val found = When.find(text) ?: return null
        val month = Months[found.groupValues[2].take(4)] ?: Months[found.groupValues[2].take(3)] ?: return null
        val year = if (month >= 8) seasonStartYear else seasonStartYear + 1
        return runCatching {
            LocalDateTime.of(year, month, found.groupValues[1].toInt(), found.groupValues[3].toInt(), found.groupValues[4].toInt())
                .atZone(Athens).toInstant()
        }.getOrNull()
    }

    private fun clean(raw: String) = raw.replace(Regex("<br\\s*/?>"), " ").replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace(Regex("\\s+"), " ").trim()

    private fun info(block: String, icon: String): String? =
        Regex("""/skn/$icon\.svg"[^>]*>([^<]*)<""").find(block)?.groupValues?.get(1)?.let(::clean)?.takeIf { it.isNotEmpty() }

    fun games(html: String, seasonStartYear: Int): List<GblGame> =
        html.split("<div class=\"esake-program-game\">").drop(1).mapNotNull { block ->
            val id = Regex("""idgame=([0-9A-Za-z]+)""").find(block)?.groupValues?.get(1) ?: return@mapNotNull null
            val round = Regex("""<h5>\s*(\d+)""").find(block)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            val board = block.substringAfter("esake-program-game-final-score row", "").substringBefore("esake-program-game-hidden-row")
            val cells = board.split(Regex("""<div class="col-lg-""")).drop(1)
            if (cells.size < 3) return@mapNotNull null
            fun team(cell: String): TeamRef? {
                val badge = Badge.find(cell) ?: return null
                val name = Side.find(cell)?.groupValues?.get(1)?.let(::clean) ?: return null
                return TeamRef(GreekLowerLogic.teamId("gbl-${badge.groupValues[2]}"), name, SITE + badge.groupValues[1])
            }
            val score = Side.find(cells[1])?.groupValues?.get(1)?.let { Score.find(it) }
            GblGame(
                id = id,
                round = round,
                kickoff = info(block, "clock")?.let { kickoff(it, seasonStartYear) },
                venue = info(block, "pointer"),
                channel = info(block, "tv"),
                home = team(cells[0]) ?: return@mapNotNull null,
                away = team(cells[2]) ?: return@mapNotNull null,
                homeScore = score?.groupValues?.get(1)?.toIntOrNull(),
                awayScore = score?.groupValues?.get(2)?.toIntOrNull(),
            )
        }

    /** The page has no live feed. A score only counts as final well after tip-off. */
    fun status(game: GblGame, now: Instant): MatchStatus {
        val start = game.kickoff ?: return MatchStatus.UNKNOWN
        val since = Duration.between(start, now)
        return when {
            since.isNegative -> MatchStatus.SCHEDULED
            game.homeScore != null && game.awayScore != null && since >= Duration.ofMinutes(150) -> MatchStatus.FINISHED
            else -> MatchStatus.UNKNOWN
        }
    }
}

/**
 * Stoiximan GBL from the league's public pages, read gently and for personal use only.
 * The pages can change without notice, so a failed read keeps the last saved copy.
 */
class GblSource(
    private val http: CachedHttpClient,
    private val clock: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val games = ConcurrentHashMap<String, GblGame>()

    val ref = LeagueRef(LEAGUE_ID, "Stoiximan GBL", country = "Greece", sport = Sport.BASKETBALL)

    private fun url(round: Int) = "$PAGE?idchampionship=$CHAMPIONSHIP&idteam=&idseason=00000001&series=%02d".format(round)

    private fun toMatch(game: GblGame, now: Instant): Match {
        val status = GblParsing.status(game, now)
        val show = status != MatchStatus.SCHEDULED
        return Match(
            id = "$LEAGUE_ID~${game.id}",
            league = ref,
            home = game.home,
            away = game.away,
            kickoff = game.kickoff,
            date = game.kickoff?.atZone(zone())?.toLocalDate(),
            status = status,
            homeScore = game.homeScore.takeIf { show },
            awayScore = game.awayScore.takeIf { show },
            round = game.round,
            season = SEASON,
            venue = game.venue,
        )
    }

    private fun all(): List<Match> {
        val now = clock()
        return games.values.map { toMatch(it, now) }.sortedBy { it.kickoff ?: Instant.MAX }
    }

    private suspend fun read(round: Int, ttl: Long, refresh: Boolean): Boolean {
        val fetched = http.get(url(round), ttl, refresh)
        GblParsing.games(fetched.body, SEASON_START).forEach { games[it.id] = it }
        return fetched.isStale
    }

    /** Reads the season once, then only re-reads rounds around today. */
    suspend fun load(refresh: Boolean = false): Boolean {
        val anyStale = AtomicBoolean(false)
        val failures = AtomicInteger(0)
        val stop = AtomicBoolean(false)
        val gate = Semaphore(PARALLEL_ROUNDS)
        // Rounds are read side by side, so a slow answer no longer holds up the next request.
        coroutineScope {
            (1..ROUNDS).forEach { round ->
                launch {
                    gate.withPermit {
                        if (stop.get()) return@withPermit
                        try {
                            if (read(round, TTL_SEASON, false)) anyStale.set(true)
                        } catch (error: DataException) {
                            anyStale.set(true)
                            if (error is DataException.Offline || error is DataException.RateLimited || failures.incrementAndGet() >= 3) {
                                stop.set(true)
                            }
                        }
                    }
                }
            }
        }
        var stale = anyStale.get()
        val now = clock()
        val open = all().filter {
            it.status != MatchStatus.FINISHED && it.kickoff?.isBefore(now.plus(Duration.ofDays(3))) == true
        }.mapNotNull { it.round }.distinct()
        for (round in open) {
            try {
                stale = read(round, TTL_OPEN, refresh) || stale
            } catch (_: DataException) {
                stale = true
            }
        }
        return stale
    }

    fun day(date: LocalDate): List<Match> = all().filter { it.kickoff?.atZone(zone())?.toLocalDate() == date }

    fun league() = Sourced(LeagueDetails(ref, SEASON), clock())

    private fun teams(): List<TeamRef> = games.values.flatMap { listOf(it.home, it.away) }.distinctBy { it.id }.sortedBy { it.name }

    suspend fun standings(refresh: Boolean): Sourced<Standings> {
        val stale = load(refresh)
        if (games.isEmpty()) throw DataException.Offline()
        val rows = GreekLowerLogic.table(teams(), all(), win = 2, draw = 0, loss = 1)
        return Sourced(Standings(ref, SEASON, rows, isPartial = false), clock(), stale)
    }

    suspend fun fixtures(round: Int?, refresh: Boolean): Sourced<LeagueFixtures> {
        val stale = load(refresh)
        val matches = all()
        val chosen = round ?: matches.firstOrNull { it.status != MatchStatus.FINISHED }?.round ?: ROUNDS
        return Sourced(
            LeagueFixtures(ref, SEASON, chosen, matches.filter { it.round == chosen }, chosen > 1, chosen < ROUNDS),
            clock(),
            stale,
        )
    }

    suspend fun team(teamId: String): Sourced<Team> {
        if (games.isEmpty()) load()
        val team = teams().firstOrNull { it.id == teamId } ?: throw DataException.NotFound()
        val ground = games.values.firstOrNull { it.home.id == teamId }?.venue
        return Sourced(Team(teamId, team.name, badgeUrl = team.badgeUrl, country = "Greece", stadium = ground, league = ref), clock())
    }

    suspend fun teamMatches(teamId: String, refresh: Boolean): Sourced<TeamMatches> {
        val stale = load(refresh)
        val (played, coming) = all().filter { it.home.id == teamId || it.away.id == teamId }
            .partition { it.status == MatchStatus.FINISHED }
        return Sourced(TeamMatches(played.sortedByDescending { it.kickoff }, coming), clock(), stale)
    }

    fun history(teamId: String) = all().filter { (it.home.id == teamId || it.away.id == teamId) && it.status == MatchStatus.FINISHED }

    suspend fun details(matchId: String, refresh: Boolean): Sourced<MatchDetails> {
        val stale = load(refresh)
        val game = all().firstOrNull { it.id == matchId } ?: throw DataException.NotFound()
        val rows = GreekLowerLogic.table(teams(), all(), win = 2, draw = 0, loss = 1)
        return Sourced(MatchDetails(game, emptyList(), Standings(ref, SEASON, rows, isPartial = false)), clock(), stale)
    }

    suspend fun searchTeams(query: String): List<Team> {
        val needle = GreekLowerLogic.nameKey(query)
        if (needle.length < 2) return emptyList()
        if (games.isEmpty()) runCatching { load() }
        return knownTeams(query)
    }

    /** Only what is already read, so a search never waits for the whole season. */
    fun knownTeams(query: String): List<Team> {
        val needle = GreekLowerLogic.nameKey(query)
        if (needle.length < 2) return emptyList()
        return teams().filter { GreekLowerLogic.nameKey(it.name).contains(needle) }
            .map { Team(it.id, it.name, badgeUrl = it.badgeUrl, country = "Greece", league = ref) }
    }

    /** The town of the home club. A club whose base is not certain is left out rather than guessed. */
    fun stadiumOf(game: Match): Stadium? {
        if (!game.id.startsWith("$LEAGUE_ID~")) return null
        val town = Towns.entries.firstOrNull { (name, _) -> game.home.name.contains(name) }?.value ?: return null
        return Stadium(name = game.venue ?: game.home.name, city = town.first, country = "Greece", lat = town.second, lng = town.third)
    }

    companion object {
        const val LEAGUE_ID = "grl.gbl"
        private const val PAGE = "https://www.esake.gr/el/action/EsakeResults"
        private const val CHAMPIONSHIP = "184645B9"
        private const val SEASON = "2026-2027"
        private const val SEASON_START = 2026
        private const val ROUNDS = 26
        private const val PARALLEL_ROUNDS = 4

        // Rounds close to today are re-read every few minutes, so the full season only needs a daily pass.
        private const val TTL_SEASON = 24 * 3_600_000L
        private const val TTL_OPEN = 300_000L

        private val Towns = linkedMapOf(
            "ΠΡΟΜΗΘΕΑΣ" to Triple("Πάτρα", 38.2466, 21.7346),
            "ΠΑΝΑΘΗΝΑΪΚΟΣ" to Triple("Αθήνα", 38.0361, 23.7874),
            "ΟΛΥΜΠΙΑΚΟΣ" to Triple("Πειραιάς", 37.9429, 23.6469),
            "ΑΕΚ" to Triple("Αθήνα", 38.0361, 23.7874),
            "ΠΕΡΙΣΤΕΡΙ" to Triple("Περιστέρι", 38.0154, 23.6917),
            "ΜΑΡΟΥΣΙ" to Triple("Μαρούσι", 38.0500, 23.8064),
            "ΠΑΟΚ" to Triple("Θεσσαλονίκη", 40.6401, 22.9444),
            "ΑΡΗΣ" to Triple("Θεσσαλονίκη", 40.6401, 22.9444),
            "ΗΡΑΚΛΗΣ" to Triple("Θεσσαλονίκη", 40.6401, 22.9444),
            "ΚΟΛΟΣΣΟΣ" to Triple("Ρόδος", 36.4341, 28.2176),
            "ΜΥΚΟΝΟΣ" to Triple("Μύκονος", 37.4467, 25.3289),
            "ΚΑΡΔΙΤΣΑ" to Triple("Καρδίτσα", 39.3650, 21.9217),
            "ΛΕΥΚΑΔΑΣ" to Triple("Λευκάδα", 38.8336, 20.7069),
        )
    }
}
