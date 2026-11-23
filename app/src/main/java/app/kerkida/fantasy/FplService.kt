package app.kerkida.fantasy

import app.kerkida.data.DataException
import app.kerkida.data.model.Match
import app.kerkida.data.net.CachedHttpClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.time.Duration
import java.time.Instant

@Serializable
data class FplTeam(val id: Int, val name: String, @SerialName("short_name") val shortName: String = "")

@Serializable
data class FplPlayer(
    val id: Int,
    @SerialName("web_name") val name: String,
    @SerialName("first_name") val firstName: String = "",
    @SerialName("second_name") val secondName: String = "",
    val team: Int,
    @SerialName("element_type") val elementType: Int,
    val status: String = "a",
    val news: String = "",
    @SerialName("chance_of_playing_next_round") val chanceNextRound: Int? = null,
    val starts: Int = 0,
    val minutes: Int = 0,
    @SerialName("goals_scored") val goals: Int = 0,
    val assists: Int = 0,
    @SerialName("expected_goals") val expectedGoals: String = "0",
    @SerialName("expected_assists") val expectedAssists: String = "0",
    val tackles: Int = 0,
    @SerialName("clearances_blocks_interceptions") val defensiveActions: Int = 0,
    val recoveries: Int = 0,
    val saves: Int = 0,
    @SerialName("yellow_cards") val yellowCards: Int = 0,
    @SerialName("red_cards") val redCards: Int = 0,
    val form: String = "0",
    @SerialName("total_points") val totalPoints: Int = 0,
    val code: Int = 0,
) {
    val position: Position
        get() = when (elementType) {
            1 -> Position.GKP
            2 -> Position.DEF
            3 -> Position.MID
            else -> Position.FWD
        }
}

@Serializable
data class FplEvent(
    val id: Int,
    @SerialName("is_current") val isCurrent: Boolean = false,
    @SerialName("is_next") val isNext: Boolean = false,
    @SerialName("deadline_time") val deadline: String? = null,
)

@Serializable
data class FplBootstrap(
    val teams: List<FplTeam>,
    val elements: List<FplPlayer>,
    val events: List<FplEvent>,
)

@Serializable
data class FplFixture(
    val id: Int,
    val event: Int? = null,
    @SerialName("team_h") val teamHome: Int,
    @SerialName("team_a") val teamAway: Int,
    @SerialName("kickoff_time") val kickoff: String? = null,
    val started: Boolean? = null,
    val finished: Boolean = false,
    @SerialName("finished_provisional") val finishedProvisional: Boolean = false,
    @SerialName("team_h_difficulty") val homeDifficulty: Int? = null,
    @SerialName("team_a_difficulty") val awayDifficulty: Int? = null,
    val stats: List<FplFixtureStat> = emptyList(),
)

@Serializable
data class FplStatValue(val value: Int = 0, val element: Int = 0)

@Serializable
data class FplFixtureStat(
    val identifier: String = "",
    @SerialName("h") val home: List<FplStatValue> = emptyList(),
    @SerialName("a") val away: List<FplStatValue> = emptyList(),
)

@Serializable
data class FplExplain(val fixture: Int)

@Serializable
data class FplLiveElement(
    val id: Int,
    val stats: Map<String, JsonElement> = emptyMap(),
    val explain: List<FplExplain> = emptyList(),
)

@Serializable
data class FplLive(val elements: List<FplLiveElement>)

/**
 * The only door to Fantasy Premier League data, so the source can be replaced.
 *
 * WARNING: the official FPL endpoints are unofficial, undocumented and carry no guarantee.
 * Review the Premier League's terms of use before any public release of this app.
 */
interface FantasyDataService {
    suspend fun bootstrap(): FplBootstrap
    suspend fun fixtures(): List<FplFixture>
    suspend fun live(gameweek: Int, refresh: Boolean = false): FplLive
    suspend fun entry(id: Int): FplEntry
    suspend fun picks(id: Int, gameweek: Int): FplPicks
    suspend fun classicLeague(id: Int): FplLeagueStandings
}

class FplHttpService(private val client: CachedHttpClient) : FantasyDataService {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    override suspend fun bootstrap(): FplBootstrap = fetch("$BASE/bootstrap-static/", HOUR_MS)

    override suspend fun fixtures(): List<FplFixture> = fetch("$BASE/fixtures/", TEN_MINUTES_MS)

    override suspend fun live(gameweek: Int, refresh: Boolean): FplLive =
        fetch("$BASE/event/$gameweek/live/", MINUTE_MS, refresh)

    override suspend fun entry(id: Int): FplEntry = fetch("$BASE/entry/$id/", TEN_MINUTES_MS)

    override suspend fun picks(id: Int, gameweek: Int): FplPicks =
        fetch("$BASE/entry/$id/event/$gameweek/picks/", TEN_MINUTES_MS)

    override suspend fun classicLeague(id: Int): FplLeagueStandings =
        fetch("$BASE/leagues-classic/$id/standings/", TEN_MINUTES_MS)

    private suspend inline fun <reified T> fetch(url: String, ttlMs: Long, refresh: Boolean = false): T {
        val body = client.get(url, ttlMs, refresh).body
        return try {
            json.decodeFromString<T>(body)
        } catch (error: IllegalArgumentException) {
            throw DataException.BadData(error)
        }
    }

    private companion object {
        const val BASE = "https://fantasy.premierleague.com/api"
        const val MINUTE_MS = 60_000L
        const val TEN_MINUTES_MS = 600_000L
        const val HOUR_MS = 3_600_000L
    }
}

/** Connects a score source's team names to FPL's shorter ones. */
object FplTeamMatcher {
    private val Aliases = mapOf(
        "manchester united" to "man utd",
        "manchester utd" to "man utd",
        "manchester city" to "man city",
        "tottenham" to "spurs",
        "tottenham hotspur" to "spurs",
        "nottingham forest" to "nott'm forest",
        "newcastle united" to "newcastle",
        "brighton and hove albion" to "brighton",
        "brighton & hove albion" to "brighton",
        "leeds united" to "leeds",
        "afc bournemouth" to "bournemouth",
        "wolverhampton wanderers" to "wolves",
        "west ham united" to "west ham",
        "leicester city" to "leicester",
        "sheffield united" to "sheffield utd",
        "coventry" to "coventry city",
        "ipswich" to "ipswich town",
        "hull" to "hull city",
    )

    private fun normalise(name: String) = name.trim().lowercase()
        .removeSuffix(" fc").removePrefix("fc ").replace("’", "'")

    fun find(name: String, teams: List<FplTeam>): FplTeam? {
        val key = normalise(name)
        val target = Aliases[key] ?: key
        return teams.firstOrNull { normalise(it.name) == target }
    }
}

data class SquadPlayer(
    val id: Int,
    val name: String,
    val position: Position,
    val starts: Int,
    val minutes: Int,
    val isAvailable: Boolean,
    val chance: Int?,
    val news: String,
    val positionCode: String? = null,
    val jersey: String? = null,
)

data class PlayerRating(
    val id: Int,
    val name: String,
    val position: Position,
    val minutes: Int,
    val breakdown: RatingBreakdown,
    val photoUrl: String? = null,
)

enum class RatingSource { FANTASY, MATCH_STATS }

data class MatchRatings(
    val home: List<PlayerRating>,
    val away: List<PlayerRating>,
    val isFinal: Boolean,
    val source: RatingSource = RatingSource.FANTASY,
)

data class MatchSquads(val home: List<SquadPlayer>, val away: List<SquadPlayer>)

class FantasyInsights(private val service: FantasyDataService) {

    fun covers(match: Match): Boolean = match.league.id in PREMIER_LEAGUE_IDS

    suspend fun ratings(match: Match, refresh: Boolean = false): MatchRatings? {
        if (!covers(match)) return null
        val bootstrap = service.bootstrap()
        val (homeTeam, awayTeam) = teamsOf(match, bootstrap) ?: return null
        val fixture = fixtureOf(match, homeTeam.id, awayTeam.id, service.fixtures()) ?: return null
        if (fixture.started != true) return null
        val gameweek = fixture.event ?: return null
        val live = service.live(gameweek, refresh)
        val players = bootstrap.elements.associateBy { it.id }
        val rated = live.elements.mapNotNull { element ->
            // In a double gameweek FPL only totals the two matches, so those players are left out.
            val only = element.explain.singleOrNull() ?: return@mapNotNull null
            if (only.fixture != fixture.id) return@mapNotNull null
            val player = players[element.id] ?: return@mapNotNull null
            val stats = toStats(element.stats)
            if (stats.minutes <= 0) return@mapNotNull null
            player.team to PlayerRating(
                id = player.id,
                name = player.name,
                position = player.position,
                minutes = stats.minutes,
                breakdown = RatingCalculator.calculate(stats, player.position),
                photoUrl = player.code.takeIf { it > 0 }?.let { "https://resources.premierleague.com/premierleague25/photos/players/110x140/$it.png" },
            )
        }
        fun side(teamId: Int) = rated.filter { it.first == teamId }.map { it.second }
            .sortedByDescending { it.breakdown.rating }
        return MatchRatings(side(homeTeam.id), side(awayTeam.id), fixture.finished)
    }

    suspend fun squads(match: Match): MatchSquads? {
        if (!covers(match)) return null
        val bootstrap = service.bootstrap()
        val (homeTeam, awayTeam) = teamsOf(match, bootstrap) ?: return null
        fun squad(teamId: Int) = bootstrap.elements.filter { it.team == teamId }.map { player ->
            SquadPlayer(
                id = player.id,
                name = player.name,
                position = player.position,
                starts = player.starts,
                minutes = player.minutes,
                isAvailable = player.status == "a",
                chance = player.chanceNextRound,
                news = player.news,
            )
        }
        return MatchSquads(squad(homeTeam.id), squad(awayTeam.id))
    }

    /** Both clubs' FPL players, or null when the match is not in the Premier League. */
    suspend fun teamPlayers(match: Match): Pair<List<FplPlayer>, List<FplPlayer>>? {
        if (!covers(match)) return null
        val bootstrap = service.bootstrap()
        val (home, away) = teamsOf(match, bootstrap) ?: return null
        return bootstrap.elements.filter { it.team == home.id } to bootstrap.elements.filter { it.team == away.id }
    }

    private fun teamsOf(match: Match, bootstrap: FplBootstrap): Pair<FplTeam, FplTeam>? {
        val home = FplTeamMatcher.find(match.home.name, bootstrap.teams) ?: return null
        val away = FplTeamMatcher.find(match.away.name, bootstrap.teams) ?: return null
        return home to away
    }

    private fun fixtureOf(match: Match, homeId: Int, awayId: Int, fixtures: List<FplFixture>): FplFixture? {
        val candidates = fixtures.filter { it.teamHome == homeId && it.teamAway == awayId }
        val kickoff = match.kickoff ?: return candidates.singleOrNull()
        return candidates.firstOrNull { fixture ->
            val time = fixture.kickoff?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return@firstOrNull false
            Duration.between(time, kickoff).abs() <= Duration.ofHours(36)
        }
    }

    private fun toStats(raw: Map<String, JsonElement>): PlayerMatchStats {
        fun number(key: String) = (raw[key] as? JsonPrimitive)?.doubleOrNull ?: 0.0
        fun count(key: String) = number(key).toInt()
        return PlayerMatchStats(
            minutes = count("minutes"),
            goals = count("goals_scored"),
            assists = count("assists"),
            expectedGoals = number("expected_goals"),
            expectedAssists = number("expected_assists"),
            tackles = count("tackles"),
            defensiveActions = count("clearances_blocks_interceptions"),
            recoveries = count("recoveries"),
            saves = count("saves"),
            penaltiesSaved = count("penalties_saved"),
            cleanSheet = count("clean_sheets") > 0,
            goalsConceded = count("goals_conceded"),
            yellowCards = count("yellow_cards"),
            redCards = count("red_cards"),
            ownGoals = count("own_goals"),
            penaltiesMissed = count("penalties_missed"),
        )
    }

    companion object {
        /** The Premier League's id at ESPN and at TheSportsDB. */
        val PREMIER_LEAGUE_IDS = setOf("eng.1", "4328")
    }
}
