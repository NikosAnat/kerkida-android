package app.kerkida.fantasy

import kotlin.math.round

enum class Position { GKP, DEF, MID, FWD }

/** Actions that move a Kerkida rating. Key passes and duels are not offered by the free source. */
enum class RatingAction {
    GOALS, ASSISTS, EXPECTED_GOALS, EXPECTED_ASSISTS, SHOTS_ON_TARGET, SHOTS_OFF_TARGET, FOULS_WON, FOULS_COMMITTED, TACKLES, DEFENSIVE_ACTIONS, RECOVERIES, SAVES,
    PENALTIES_SAVED, CLEAN_SHEET, GOALS_CONCEDED, YELLOW_CARD, RED_CARD, OWN_GOALS, PENALTIES_MISSED,
}

data class PlayerMatchStats(
    val minutes: Int,
    val goals: Int = 0,
    val assists: Int = 0,
    val expectedGoals: Double = 0.0,
    val expectedAssists: Double = 0.0,
    val tackles: Int = 0,
    val defensiveActions: Int = 0,
    val recoveries: Int = 0,
    val saves: Int = 0,
    val penaltiesSaved: Int = 0,
    val cleanSheet: Boolean = false,
    val goalsConceded: Int = 0,
    val yellowCards: Int = 0,
    val redCards: Int = 0,
    val ownGoals: Int = 0,
    val penaltiesMissed: Int = 0,
    val shotsOnTarget: Int = 0,
    val shotsOffTarget: Int = 0,
    val foulsWon: Int = 0,
    val foulsCommitted: Int = 0,
)

data class RatingContribution(val action: RatingAction, val value: Double, val delta: Double)

data class RatingBreakdown(
    val rating: Double,
    val base: Double,
    val contributions: List<RatingContribution>,
    /** Under 20 minutes a rating says little, and the screen says so. */
    val isSmallSample: Boolean,
)

/**
 * Kerkida's own open formula: start at 6.0 and add or subtract for each action.
 * It is not an official rating and does not copy any other service's scale.
 */
object RatingCalculator {
    const val BASE = 6.0
    private const val MIN_RATING = 3.0
    private const val MAX_RATING = 10.0
    private const val SMALL_SAMPLE_MINUTES = 20

    fun calculate(stats: PlayerMatchStats, position: Position): RatingBreakdown {
        val defender = position == Position.GKP || position == Position.DEF
        val goalWeight = when (position) {
            Position.GKP, Position.DEF -> 1.4
            Position.MID -> 1.2
            Position.FWD -> 1.0
        }
        val cleanSheetWeight = when (position) {
            Position.GKP, Position.DEF -> 0.6
            Position.MID -> 0.15
            Position.FWD -> 0.0
        }
        val lines = listOf(
            line(RatingAction.GOALS, stats.goals, goalWeight),
            line(RatingAction.ASSISTS, stats.assists, 0.8),
            line(RatingAction.EXPECTED_GOALS, stats.expectedGoals, 0.3),
            line(RatingAction.EXPECTED_ASSISTS, stats.expectedAssists, 0.4),
            line(RatingAction.SHOTS_ON_TARGET, stats.shotsOnTarget, 0.15),
            line(RatingAction.SHOTS_OFF_TARGET, stats.shotsOffTarget, 0.03),
            line(RatingAction.FOULS_WON, stats.foulsWon, 0.05),
            line(RatingAction.FOULS_COMMITTED, stats.foulsCommitted, -0.06),
            line(RatingAction.TACKLES, stats.tackles, 0.10),
            line(RatingAction.DEFENSIVE_ACTIONS, stats.defensiveActions, 0.06),
            line(RatingAction.RECOVERIES, stats.recoveries, 0.03),
            line(RatingAction.SAVES, stats.saves, 0.15),
            line(RatingAction.PENALTIES_SAVED, stats.penaltiesSaved, 1.0),
            line(RatingAction.CLEAN_SHEET, if (stats.cleanSheet && stats.minutes >= 60) 1 else 0, cleanSheetWeight),
            line(RatingAction.GOALS_CONCEDED, stats.goalsConceded, if (defender) -0.2 else 0.0),
            line(RatingAction.YELLOW_CARD, stats.yellowCards, -0.3),
            line(RatingAction.RED_CARD, stats.redCards, -1.2),
            line(RatingAction.OWN_GOALS, stats.ownGoals, -1.0),
            line(RatingAction.PENALTIES_MISSED, stats.penaltiesMissed, -0.8),
        ).filter { it.value != 0.0 && it.delta != 0.0 }

        val total = BASE + lines.sumOf { it.delta }
        return RatingBreakdown(
            rating = round(total.coerceIn(MIN_RATING, MAX_RATING) * 10) / 10,
            base = BASE,
            contributions = lines.sortedByDescending { kotlin.math.abs(it.delta) },
            isSmallSample = stats.minutes < SMALL_SAMPLE_MINUTES,
        )
    }

    private fun line(action: RatingAction, value: Number, weight: Double): RatingContribution {
        val amount = value.toDouble()
        return RatingContribution(action, amount, amount * weight)
    }
}
