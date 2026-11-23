package app.kerkida.season

import android.content.Context
import app.kerkida.data.model.Match
import app.kerkida.data.model.MatchEvent
import app.kerkida.data.model.MatchEventType
import app.kerkida.data.model.MatchStatus
import app.kerkida.data.model.Side
import app.kerkida.stadium.Stadium
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneOffset

@Serializable
data class WatchedMatch(
    val matchId: String,
    val home: String,
    val away: String,
    val homeBadge: String? = null,
    val awayBadge: String? = null,
    val league: String,
    val sport: String,
    val kickoffMs: Long,
    val homeScore: Int? = null,
    val awayScore: Int? = null,
    val venue: String? = null,
    /** True when the person was at the ground. */
    val attended: Boolean = false,
    val city: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
)

/** A ground the person has been to. */
data class VisitedGround(val name: String, val city: String?, val lat: Double?, val lng: Double?, val visits: Int)

data class SeasonSummary(
    val matches: Int,
    val goals: Int,
    val perMatch: Double,
    val biggest: WatchedMatch?,
    val topTeams: List<Pair<String, Int>>,
    val topLeagues: List<Pair<String, Int>>,
    val sports: Map<String, Int>,
    val grounds: Int,
)

object SeasonLogic {
    /** A football season runs July to June, so each match belongs to the year it started in. */
    fun seasonOf(kickoffMs: Long): Int {
        val date = Instant.ofEpochMilli(kickoffMs).atZone(ZoneOffset.UTC).toLocalDate()
        return if (date.monthValue >= 7) date.year else date.year - 1
    }

    fun summary(all: List<WatchedMatch>, season: Int): SeasonSummary {
        val games = all.filter { seasonOf(it.kickoffMs) == season }
        val goals = games.sumOf { (it.homeScore ?: 0) + (it.awayScore ?: 0) }
        fun top(values: List<String>) = values.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(3)
            .map { it.key to it.value }
        return SeasonSummary(
            matches = games.size,
            goals = goals,
            perMatch = if (games.isEmpty()) 0.0 else goals.toDouble() / games.size,
            biggest = games.filter { it.homeScore != null && it.awayScore != null }
                .maxByOrNull { (it.homeScore ?: 0) + (it.awayScore ?: 0) },
            topTeams = top(games.flatMap { listOf(it.home, it.away) }),
            topLeagues = top(games.map { it.league }),
            sports = games.groupingBy { it.sport }.eachCount(),
            grounds = games.mapNotNull { it.venue }.distinct().size,
        )
    }

    /** Every ground visited, across all seasons, most visited first. */
    fun grounds(all: List<WatchedMatch>): List<VisitedGround> = all.filter { it.attended && !it.venue.isNullOrBlank() }
        .groupBy { it.venue!! }
        .map { (name, games) ->
            val placed = games.firstOrNull { it.lat != null }
            VisitedGround(name, placed?.city ?: games.first().city, placed?.lat, placed?.lng, games.size)
        }
        .sortedWith(compareBy({ -it.visits }, { it.name }))

    fun record(match: Match) = WatchedMatch(
        matchId = match.id,
        home = match.home.name,
        away = match.away.name,
        homeBadge = match.home.badgeUrl,
        awayBadge = match.away.badgeUrl,
        league = match.league.name,
        sport = match.league.sport.name,
        kickoffMs = match.kickoff?.toEpochMilli() ?: 0L,
        homeScore = match.homeScore,
        awayScore = match.awayScore,
        venue = match.venue,
    )
}

/** The person's own log. It stays on this phone. */
class SeasonStore(context: Context) {
    private val prefs = context.getSharedPreferences("kerkida_season", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val _watched = MutableStateFlow(
        prefs.getString(KEY, null)?.let { runCatching { json.decodeFromString<List<WatchedMatch>>(it) }.getOrNull() }.orEmpty(),
    )
    val watched: StateFlow<List<WatchedMatch>> = _watched.asStateFlow()

    fun isWatched(id: String) = _watched.value.any { it.matchId == id }

    fun toggle(match: Match) {
        val updated = if (isWatched(match.id)) {
            _watched.value.filterNot { it.matchId == match.id }
        } else {
            _watched.value + SeasonLogic.record(match)
        }
        save(updated)
    }

    fun wasThere(id: String) = _watched.value.any { it.matchId == id && it.attended }

    /** "I was there" also logs the match, so it counts in the season too. */
    fun toggleAttended(match: Match, stadium: Stadium?) {
        val existing = _watched.value.firstOrNull { it.matchId == match.id }
        val base = existing ?: SeasonLogic.record(match)
        val updated = base.copy(
            attended = !base.attended,
            venue = base.venue ?: stadium?.name,
            city = stadium?.city ?: base.city,
            lat = stadium?.lat ?: base.lat,
            lng = stadium?.lng ?: base.lng,
        )
        save(if (existing != null) _watched.value.map { if (it.matchId == match.id) updated else it } else _watched.value + updated)
    }

    /** Fills in the final score of a match logged while it was still being played or hidden. */
    fun refresh(match: Match) {
        if (match.status != MatchStatus.FINISHED || match.homeScore == null) return
        val current = _watched.value.firstOrNull { it.matchId == match.id } ?: return
        if (current.homeScore == match.homeScore && current.awayScore == match.awayScore) return
        save(_watched.value.map { if (it.matchId == match.id) it.copy(homeScore = match.homeScore, awayScore = match.awayScore) else it })
    }

    private fun save(items: List<WatchedMatch>) {
        _watched.value = items
        prefs.edit().putString(KEY, json.encodeToString(items)).apply()
    }

    private companion object {
        const val KEY = "watched"
    }
}

/** Short spoken lines for key events. Only what the shield allows ever reaches this. */
object Announcer {
    private val Spoken = setOf(
        MatchEventType.GOAL, MatchEventType.PENALTY, MatchEventType.OWN_GOAL, MatchEventType.RED_CARD, MatchEventType.MISSED_PENALTY,
    )

    fun key(event: MatchEvent) = "${event.minute}|${event.type}|${event.side}|${event.player}"

    /** Events not yet spoken. The first look at a match stays silent. */
    fun fresh(previous: Set<String>?, events: List<MatchEvent>): List<MatchEvent> {
        previous ?: return emptyList()
        return events.filter { it.type in Spoken && key(it) !in previous }
    }

    data class Words(val goal: String, val penalty: String, val ownGoal: String, val red: String, val missed: String, val minute: String)

    fun line(event: MatchEvent, match: Match, words: Words): String {
        val team = when (event.side) {
            Side.HOME -> match.home.name
            Side.AWAY -> match.away.name
            null -> ""
        }
        val what = when (event.type) {
            MatchEventType.GOAL -> words.goal
            MatchEventType.PENALTY -> words.penalty
            MatchEventType.OWN_GOAL -> words.ownGoal
            MatchEventType.RED_CARD -> words.red
            else -> words.missed
        }
        val score = if (match.homeScore != null && match.awayScore != null) "${match.homeScore} ${match.awayScore}" else null
        return listOfNotNull(
            what,
            team.takeIf { it.isNotEmpty() },
            event.player,
            event.minute?.let { "$it ${words.minute}" },
            score,
        ).joinToString(", ")
    }
}
