package app.kerkida.alerts

import app.kerkida.data.model.Match
import app.kerkida.data.model.MatchEvent
import app.kerkida.data.model.MatchEventType
import app.kerkida.data.model.Side
import kotlinx.serialization.Serializable

@Serializable
data class TeamAlertRules(
    val redCards: Boolean = true,
    val lateGoals: Boolean = true,
    val comebacks: Boolean = true,
    val fantasyPlayers: Boolean = true,
)

enum class AlertKind { RED_CARD, LATE_GOAL, COMEBACK, FANTASY_PLAYER }

data class SmartAlert(
    val matchId: String,
    val kind: AlertKind,
    val minute: Int?,
    val player: String?,
    val side: Side?,
    val homeScore: Int?,
    val awayScore: Int?,
)

/** What has already been considered for a match, so nothing is announced twice. */
@Serializable
data class AlertMemory(
    val seenEvents: Set<String> = emptySet(),
    val homeScore: Int? = null,
    val awayScore: Int? = null,
    val trailed: Set<Side> = emptySet(),
    val comebackFor: Side? = null,
)

data class AlertResult(val alerts: List<SmartAlert>, val memory: AlertMemory)

object SmartAlertEngine {
    const val LATE_MINUTE = 80

    private val GoalTypes = setOf(MatchEventType.GOAL, MatchEventType.PENALTY, MatchEventType.OWN_GOAL)
    private val FantasyTypes = GoalTypes + setOf(
        MatchEventType.MISSED_PENALTY,
        MatchEventType.YELLOW_CARD,
        MatchEventType.RED_CARD,
    )

    fun eventKey(event: MatchEvent) = "${event.minute}|${event.type}|${event.side}|${event.player}"

    /** Records the current state silently. Used on first sight and while a match is hidden. */
    fun remember(previous: AlertMemory?, match: Match, events: List<MatchEvent>): AlertMemory {
        val base = previous ?: AlertMemory()
        return base.copy(
            seenEvents = base.seenEvents + events.map(::eventKey),
            homeScore = match.homeScore,
            awayScore = match.awayScore,
            trailed = base.trailed + trailingSide(match.homeScore, match.awayScore).let(::setOfNotNull),
            comebackFor = base.comebackFor ?: leadingSide(match.homeScore, match.awayScore)
                ?.takeIf { it in base.trailed },
        )
    }

    fun detect(
        previous: AlertMemory?,
        match: Match,
        events: List<MatchEvent>,
        rules: TeamAlertRules,
        fantasyPlayers: Set<String> = emptySet(),
    ): AlertResult {
        val memory = remember(previous, match, events)
        // The first look at a match sets the baseline, so opening the app never floods the phone.
        previous ?: return AlertResult(emptyList(), memory)

        val alerts = mutableListOf<SmartAlert>()
        val fresh = events.filter { eventKey(it) !in previous.seenEvents }
        val fantasyNames = fantasyPlayers.map { it.trim().lowercase() }.toSet()
        // FPL stores short names ("Haaland"), while match events carry full ones ("Erling Haaland").
        fun isFantasyPlayer(name: String?): Boolean {
            val full = name?.trim()?.lowercase() ?: return false
            return full in fantasyNames || full.substringAfterLast(' ') in fantasyNames
        }

        fresh.forEach { event ->
            if (rules.redCards && event.type == MatchEventType.RED_CARD) {
                alerts += alertOf(match, AlertKind.RED_CARD, event.minute, event.player, event.side)
            }
            if (rules.lateGoals && event.type in GoalTypes && (event.minute ?: 0) >= LATE_MINUTE) {
                alerts += alertOf(match, AlertKind.LATE_GOAL, event.minute, event.player, event.side)
            }
            val isFantasy = isFantasyPlayer(event.player)
            if (rules.fantasyPlayers && isFantasy && event.type in FantasyTypes) {
                alerts += alertOf(match, AlertKind.FANTASY_PLAYER, event.minute, event.player, event.side)
            }
        }

        // The free source can miss events, so a late score change still counts without a named scorer.
        val scoreRose = scoreTotal(match.homeScore, match.awayScore) > scoreTotal(previous.homeScore, previous.awayScore)
        val goalAlreadyAnnounced = alerts.any { it.kind == AlertKind.LATE_GOAL } || fresh.any { it.type in GoalTypes }
        if (rules.lateGoals && scoreRose && !goalAlreadyAnnounced && (match.minute ?: 0) >= LATE_MINUTE) {
            alerts += alertOf(match, AlertKind.LATE_GOAL, match.minute, null, null)
        }

        val leader = leadingSide(match.homeScore, match.awayScore)
        if (rules.comebacks && leader != null && leader in previous.trailed && previous.comebackFor != leader) {
            alerts += alertOf(match, AlertKind.COMEBACK, match.minute, null, leader)
            return AlertResult(alerts, memory.copy(comebackFor = leader))
        }
        return AlertResult(alerts, memory)
    }

    private fun alertOf(match: Match, kind: AlertKind, minute: Int?, player: String?, side: Side?) =
        SmartAlert(match.id, kind, minute, player, side, match.homeScore, match.awayScore)

    private fun scoreTotal(home: Int?, away: Int?) = (home ?: 0) + (away ?: 0)

    private fun leadingSide(home: Int?, away: Int?): Side? {
        if (home == null || away == null || home == away) return null
        return if (home > away) Side.HOME else Side.AWAY
    }

    private fun trailingSide(home: Int?, away: Int?): Side? = when (leadingSide(home, away)) {
        Side.HOME -> Side.AWAY
        Side.AWAY -> Side.HOME
        null -> null
    }
}
