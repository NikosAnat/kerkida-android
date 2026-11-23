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
import app.kerkida.data.model.StandingRow
import app.kerkida.data.model.Standings
import app.kerkida.data.model.Team
import app.kerkida.data.model.TeamMatches
import app.kerkida.data.model.TeamRef
import app.kerkida.data.net.CachedHttpClient
import app.kerkida.stadium.Stadium
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

data class OpenCompetition(val id: String, val name: String, val sport: Sport, val page: String)

data class OpenGame(
    val round: Int,
    val date: LocalDate?,
    val kickoff: Instant?,
    val home: TeamRef,
    val away: TeamRef,
    val homeScore: Int?,
    val awayScore: Int?,
)

data class OpenRound(val number: Int, val key: String)

/** Reads the public competition pages of the-sports.org. Its times are Greek local time. */
object OpenResultsParsing {
    const val SITE = "https://www.the-sports.org/"
    const val TEAM_PREFIX = "grl:ts-"
    private val Athens: ZoneId = ZoneId.of("Europe/Athens")
    private val Months = listOf(
        "January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December",
    )
    private val DateHead = Regex("""daterenc">(\d{1,2}) (\p{L}+) (\d{4})""")
    private val TeamLink = Regex("""identity-equ(\d+)\.html" title="([^"]+)"""")
    private val Time = Regex("""tdcol-15">(\d{1,2})h(\d{2})""")
    private val Score = Regex("""<b>(\d{1,3}) - (\d{1,3})</b>""")
    private val RoundOption = Regex("""<option value="mancheid=(\d+)"( selected)?>Round (\d+)""")
    private val Badge = Regex("""images/equipes/(\d+)\.(png|jpg)""")
    private val Cell = Regex("""<td class="tdcol-\d+">([+-]?\d+)</td>""")

    private fun clean(raw: String) = raw.replace("&iuml;", "ï").replace("&eacute;", "é").replace("&amp;", "&")
        .replace("&#039;", "'").replace("&apos;", "'").trim()

    /** Crest files are named after the team. Clubs without one share a placeholder. */
    fun badges(html: String): Map<String, String> = Badge.findAll(html)
        .filter { it.groupValues[1].trimStart('0').isNotEmpty() }
        .associate { it.groupValues[1] to "${SITE}images/equipes/${it.groupValues[1]}.${it.groupValues[2]}" }

    fun rounds(html: String): List<OpenRound> = RoundOption.findAll(html)
        .map { OpenRound(it.groupValues[3].toInt(), it.groupValues[1]) }.distinctBy { it.number }.toList()

    fun currentRound(html: String): Int? =
        RoundOption.findAll(html).firstOrNull { it.groupValues[2].isNotEmpty() }?.groupValues?.get(3)?.toIntOrNull()

    private fun date(found: MatchResult): LocalDate? {
        val month = Months.indexOf(found.groupValues[2]) + 1
        if (month == 0) return null
        return runCatching { LocalDate.of(found.groupValues[3].toInt(), month, found.groupValues[1].toInt()) }.getOrNull()
    }

    fun games(html: String, round: Int, crests: Map<String, String> = emptyMap()): List<OpenGame> {
        var day: LocalDate? = null
        val out = mutableListOf<OpenGame>()
        html.split("<tr").forEach { row ->
            DateHead.find(row)?.let {
                day = date(it)
                return@forEach
            }
            val sides = TeamLink.findAll(row).toList()
            if (sides.size < 2) return@forEach
            fun team(found: MatchResult) = TeamRef(
                TEAM_PREFIX + found.groupValues[1],
                clean(found.groupValues[2]),
                crests[found.groupValues[1]],
            )
            val time = Time.find(row)
            val score = Score.find(row)
            val kickoff = if (time != null) {
                day?.let { runCatching { it.atTime(time.groupValues[1].toInt(), time.groupValues[2].toInt()).atZone(Athens).toInstant() }.getOrNull() }
            } else {
                null
            }
            out += OpenGame(
                round,
                day,
                kickoff,
                team(sides[0]),
                team(sides[1]),
                score?.groupValues?.get(1)?.toIntOrNull(),
                score?.groupValues?.get(2)?.toIntOrNull(),
            )
        }
        return out
    }

    /** The overall table only. The page also has home and away tables after it. */
    fun table(html: String): List<StandingRow> {
        val general = html.substringAfter("id=\"tab110\"", "").substringBefore("id=\"tab120\"")
        val crests = badges(general)
        return general.split("<tr").mapNotNull { row ->
            val side = TeamLink.find(row) ?: return@mapNotNull null
            val numbers = Cell.findAll(row).map { it.groupValues[1].removePrefix("+").toInt() }.toList()
            if (numbers.size < 8) return@mapNotNull null
            StandingRow(
                rank = numbers[0],
                team = TeamRef(TEAM_PREFIX + side.groupValues[1], clean(side.groupValues[2]), crests[side.groupValues[1]]),
                played = numbers[2],
                won = numbers[3],
                drawn = 0,
                lost = numbers[4],
                goalsFor = numbers[5],
                goalsAgainst = numbers[6],
                goalDifference = numbers[7],
                points = numbers[1],
            )
        }
    }

    /** The source has no live feed, so a score only counts as final well after the start. */
    fun status(game: OpenGame, now: Instant): MatchStatus {
        val hasScore = game.homeScore != null && game.awayScore != null
        val start = game.kickoff ?: return if (hasScore) MatchStatus.FINISHED else MatchStatus.UNKNOWN
        val since = Duration.between(start, now)
        return when {
            since.isNegative -> MatchStatus.SCHEDULED
            hasScore && since >= Duration.ofMinutes(90) -> MatchStatus.FINISHED
            else -> MatchStatus.UNKNOWN
        }
    }
}

/**
 * Elite League and Greek volleyball from public pages, read gently and for personal use only.
 * A failed read keeps the last saved copy.
 */
class OpenResultsSource(
    private val http: CachedHttpClient,
    private val clock: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val games = ConcurrentHashMap<String, OpenGame>()
    private val rounds = ConcurrentHashMap<String, List<OpenRound>>()
    private val currentRounds = ConcurrentHashMap<String, Int>()
    private val listed = ConcurrentHashMap<String, List<StandingRow>>()
    private val crests = ConcurrentHashMap<String, String>()

    val ids: Set<String> = Competitions.map { it.id }.toSet()

    fun owns(id: String) = id.startsWith(OpenResultsParsing.TEAM_PREFIX) || ids.any { id == it || id.startsWith("$it~") }

    fun ref(id: String): LeagueRef {
        val competition = competition(id)
        return LeagueRef(competition.id, competition.name, country = "Greece", sport = competition.sport)
    }

    fun sportOf(id: String): Sport? = Competitions.firstOrNull { it.id == id }?.sport

    private fun competition(id: String) = Competitions.firstOrNull { it.id == id.substringBefore('~') } ?: throw DataException.NotFound()

    private fun gameKey(id: String, game: OpenGame) = "$id~${game.round}-${game.home.id.removePrefix(OpenResultsParsing.TEAM_PREFIX)}-${game.away.id.removePrefix(OpenResultsParsing.TEAM_PREFIX)}"

    private fun toMatch(key: String, game: OpenGame, now: Instant): Match {
        val status = OpenResultsParsing.status(game, now)
        val show = status != MatchStatus.SCHEDULED
        return Match(
            id = key,
            league = ref(key),
            home = game.home,
            away = game.away,
            kickoff = game.kickoff,
            date = game.kickoff?.atZone(zone())?.toLocalDate() ?: game.date,
            status = status,
            homeScore = game.homeScore.takeIf { show },
            awayScore = game.awayScore.takeIf { show },
            round = game.round,
            season = SEASON,
        )
    }

    private fun all(id: String? = null): List<Match> {
        val now = clock()
        return games.entries.filter { id == null || it.key.startsWith("$id~") }
            .map { toMatch(it.key, it.value, now) }
            .sortedBy { it.kickoff ?: Instant.MAX }
    }

    private suspend fun readIndex(competition: OpenCompetition, refresh: Boolean): Boolean {
        val fetched = http.get(OpenResultsParsing.SITE + competition.page, TTL_OPEN, refresh)
        OpenResultsParsing.rounds(fetched.body).takeIf { it.isNotEmpty() }?.let { rounds[competition.id] = it }
        OpenResultsParsing.currentRound(fetched.body)?.let { currentRounds[competition.id] = it }
        crests.putAll(OpenResultsParsing.badges(fetched.body))
        OpenResultsParsing.table(fetched.body).takeIf { it.isNotEmpty() }?.let { listed[competition.id] = it }
        return fetched.isStale
    }

    private suspend fun readRound(competition: OpenCompetition, round: OpenRound, refresh: Boolean): Boolean {
        val current = currentRounds[competition.id] ?: round.number
        val settled = round.number < current - 1
        val url = "${OpenResultsParsing.SITE}ajax_php.php?majajax=resultats_manche_collectif&mancheid=${round.key}&langage=en"
        val fetched = http.get(url, if (settled) TTL_SETTLED else TTL_OPEN, refresh && !settled)
        OpenResultsParsing.games(fetched.body, round.number, crests).forEach { games[gameKey(competition.id, it)] = it }
        return fetched.isStale
    }

    /** Reads the rounds up to just after the current one, or every round when [everything] is set. */
    private suspend fun loadOne(competition: OpenCompetition, date: LocalDate?, refresh: Boolean, everything: Boolean): Boolean {
        var stale = readIndex(competition, refresh)
        val known = rounds[competition.id].orEmpty()
        val current = currentRounds[competition.id] ?: 1
        val near = if (everything) known else known.filter { it.number <= current + AHEAD }
        for (round in near) stale = readRound(competition, round, refresh) || stale
        val latest = all(competition.id).mapNotNull { it.date }.maxOrNull()
        if (!everything && date != null && (latest == null || date > latest)) {
            for (round in known - near.toSet()) stale = readRound(competition, round, false) || stale
        }
        return stale
    }

    suspend fun load(chosen: Collection<String>, date: LocalDate?, refresh: Boolean): Boolean {
        var stale = false
        for (id in chosen) {
            try {
                stale = loadOne(competition(id), date, refresh, everything = false) || stale
            } catch (error: DataException) {
                stale = true
                if (error is DataException.Offline || error is DataException.RateLimited) break
            }
        }
        return stale
    }

    fun day(chosen: Collection<String>, date: LocalDate): List<Match> =
        chosen.flatMap { id -> all(id).filter { it.date == date } }

    fun hasGames(chosen: Collection<String>) = chosen.any { id -> games.keys.any { it.startsWith("$id~") } }

    fun league(id: String) = Sourced(LeagueDetails(ref(id), SEASON), clock())

    private fun teamsOf(id: String): List<TeamRef> =
        (listed[id].orEmpty().map { it.team } + all(id).flatMap { listOf(it.home, it.away) }).distinctBy { it.id }.sortedBy { it.name }

    /** The source counts two points a win in every sport, so the volleyball table is worked out from the sets. */
    private fun tableOf(id: String): List<StandingRow> =
        if (competition(id).sport == Sport.VOLLEYBALL) {
            GreekLowerLogic.table(teamsOf(id), all(id), win = 3, draw = 0, fiveSets = true)
        } else {
            listed[id].orEmpty().ifEmpty { GreekLowerLogic.table(teamsOf(id), all(id), win = 2, draw = 0, loss = 1) }
        }

    suspend fun standings(id: String, refresh: Boolean): Sourced<Standings> {
        val competition = competition(id)
        val stale = if (competition.sport == Sport.VOLLEYBALL) loadOne(competition, null, refresh, false) else readIndex(competition, refresh)
        val rows = tableOf(id)
        if (rows.isEmpty()) throw DataException.Offline()
        return Sourced(Standings(ref(id), SEASON, rows, isPartial = false), clock(), stale)
    }

    suspend fun fixtures(id: String, round: Int?, refresh: Boolean): Sourced<LeagueFixtures> {
        val competition = competition(id)
        var stale = readIndex(competition, refresh)
        val known = rounds[id].orEmpty()
        val chosen = round ?: currentRounds[id] ?: known.firstOrNull()?.number ?: 1
        known.firstOrNull { it.number == chosen }?.let { stale = readRound(competition, it, refresh) || stale }
        return Sourced(
            LeagueFixtures(
                ref(id),
                SEASON,
                chosen,
                all(id).filter { it.round == chosen },
                known.any { it.number < chosen },
                known.any { it.number > chosen },
            ),
            clock(),
            stale,
        )
    }

    private suspend fun competitionOf(teamId: String): OpenCompetition {
        fun known() = Competitions.firstOrNull { c -> listed[c.id].orEmpty().any { it.team.id == teamId } || all(c.id).any { it.home.id == teamId || it.away.id == teamId } }
        known()?.let { return it }
        Competitions.forEach { runCatching { readIndex(it, false) } }
        return known() ?: throw DataException.NotFound()
    }

    suspend fun team(teamId: String): Sourced<Team> {
        val competition = competitionOf(teamId)
        val team = teamsOf(competition.id).firstOrNull { it.id == teamId } ?: throw DataException.NotFound()
        return Sourced(Team(teamId, team.name, badgeUrl = team.badgeUrl, country = "Greece", league = ref(competition.id)), clock())
    }

    suspend fun teamMatches(teamId: String, refresh: Boolean): Sourced<TeamMatches> {
        val competition = competitionOf(teamId)
        val stale = loadOne(competition, null, refresh, everything = true)
        val (played, coming) = all(competition.id).filter { it.home.id == teamId || it.away.id == teamId }
            .partition { it.status == MatchStatus.FINISHED }
        return Sourced(TeamMatches(played.sortedByDescending { it.kickoff }, coming), clock(), stale)
    }

    fun history(teamId: String) = all().filter { (it.home.id == teamId || it.away.id == teamId) && it.status == MatchStatus.FINISHED }

    suspend fun details(matchId: String, refresh: Boolean): Sourced<MatchDetails> {
        val id = matchId.substringBefore('~')
        val competition = competition(id)
        val round = matchId.substringAfter('~').substringBefore('-').toIntOrNull()
        var stale = readIndex(competition, refresh)
        rounds[id].orEmpty().firstOrNull { it.number == round }?.let { stale = readRound(competition, it, refresh) || stale }
        if (competition.sport == Sport.VOLLEYBALL) stale = loadOne(competition, null, false, false) || stale
        val game = all(id).firstOrNull { it.id == matchId } ?: throw DataException.NotFound()
        return Sourced(MatchDetails(game, emptyList(), Standings(ref(id), SEASON, tableOf(id), isPartial = false)), clock(), stale)
    }

    suspend fun searchTeams(chosen: Collection<String>, query: String): List<Team> {
        val needle = GreekLowerLogic.nameKey(query)
        if (needle.length < 2) return emptyList()
        chosen.forEach { id -> if (listed[id] == null) runCatching { readIndex(competition(id), false) } }
        return knownTeams(chosen, query)
    }

    /** Only what is already read, so a search never waits for a slow page. */
    fun knownTeams(chosen: Collection<String>, query: String): List<Team> {
        val needle = GreekLowerLogic.nameKey(query)
        if (needle.length < 2) return emptyList()
        return chosen.flatMap { id ->
            teamsOf(id).filter { GreekLowerLogic.nameKey(it.name).contains(needle) }
                .map { Team(it.id, it.name, badgeUrl = it.badgeUrl, country = "Greece", league = ref(id)) }
        }.distinctBy { it.id }
    }

    /** The town of the home club. A club whose base is not certain is left out rather than guessed. */
    fun stadiumOf(game: Match): Stadium? {
        if (ids.none { game.id.startsWith("$it~") }) return null
        val town = Towns.entries.firstOrNull { (name, _) -> game.home.name.contains(name, ignoreCase = true) }?.value ?: return null
        return Stadium(name = "${town.first} (κατά προσέγγιση)", city = town.first, country = "Greece", lat = town.second, lng = town.third)
    }

    companion object {
        const val ELITE = "grl.ts.elite"
        const val VOLLEY_MEN = "grl.ts.vlm"
        const val VOLLEY_WOMEN = "grl.ts.vlw"
        private const val SEASON = "2026-2027"
        private const val AHEAD = 2
        private const val TTL_OPEN = 15 * 60_000L
        private const val TTL_SETTLED = 24 * 3_600_000L

        val Competitions = listOf(
            OpenCompetition(ELITE, "Elite League", Sport.BASKETBALL, "basketball-greece-a2-ethniki-regular-season-2026-2027-results-eprd138798.html"),
            OpenCompetition(VOLLEY_MEN, "Volley League Ανδρών", Sport.VOLLEYBALL, "volleyball-greece-men-s-a1-ethniki-volleyball-regular-season-2026-2027-results-eprd139133.html"),
            OpenCompetition(VOLLEY_WOMEN, "Volley League Γυναικών", Sport.VOLLEYBALL, "volleyball-greece-women-s-a1-ethniki-regular-season-2026-2027-results-eprd139139.html"),
        )

        private val Towns = linkedMapOf(
            "Koroivos" to Triple("Αμαλιάδα", 37.7960, 21.3500),
            "Patras" to Triple("Πάτρα", 38.2466, 21.7346),
            "Lavrio" to Triple("Λαύριο", 37.7140, 24.0560),
            "Psychikou" to Triple("Ψυχικό", 38.0130, 23.7690),
            "Papagou" to Triple("Παπάγου", 37.9890, 23.7960),
            "Panionios" to Triple("Νέα Σμύρνη", 37.9450, 23.7140),
            "Milon" to Triple("Νέα Σμύρνη", 37.9450, 23.7140),
            "Peiramatiko" to Triple("Πειραιάς", 37.9429, 23.6469),
            "Piraeus" to Triple("Πειραιάς", 37.9429, 23.6469),
            "Proteas" to Triple("Βούλα", 37.8440, 23.7770),
            "Komotini" to Triple("Κομοτηνή", 41.1190, 25.4050),
            "Sofadon" to Triple("Σοφάδες", 39.3360, 22.0970),
            "Megaridas" to Triple("Μέγαρα", 37.9950, 23.3430),
            "Chalkida" to Triple("Χαλκίδα", 38.4636, 23.5994),
            "Kronos" to Triple("Άγιος Δημήτριος", 37.9340, 23.7300),
            "Esperos" to Triple("Καλλιθέα", 37.9550, 23.7020),
            "Flisvos" to Triple("Παλαιό Φάληρο", 37.9280, 23.7000),
            "OFI" to Triple("Ηράκλειο", 35.3387, 25.1442),
            "Foinikas" to Triple("Σύρος", 37.4440, 24.9430),
            "Kifisias" to Triple("Κηφισιά", 38.0740, 23.8110),
            "Rethymno" to Triple("Ρέθυμνο", 35.3660, 24.4820),
            "Kalamarias" to Triple("Καλαμαριά", 40.5830, 22.9500),
            "Thessaloniki" to Triple("Θεσσαλονίκη", 40.6401, 22.9444),
            "Aris" to Triple("Θεσσαλονίκη", 40.6401, 22.9444),
            "Athens" to Triple("Αθήνα", 37.9838, 23.7275),
        )
    }
}
