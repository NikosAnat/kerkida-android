package app.kerkida.fantasy

import app.kerkida.data.model.LineupPlayer
import app.kerkida.data.model.Match
import app.kerkida.data.model.MatchExtras
import app.kerkida.data.model.MatchStatus
import kotlin.math.roundToInt

/** Builds Kerkida ratings from a match's own line-up statistics, for leagues without fantasy data. */
object LineupRatings {
    fun positionOf(code: String?): Position {
        val value = code.orEmpty().uppercase()
        return when {
            value == "G" || value == "GK" -> Position.GKP
            value.contains("B") || value.startsWith("CD") -> Position.DEF
            value.startsWith("F") || value.startsWith("CF") || value.startsWith("ST") || value.endsWith("W") -> Position.FWD
            else -> Position.MID
        }
    }

    fun build(match: Match, extras: MatchExtras?): MatchRatings? {
        extras ?: return null
        val home = side(extras.home?.players, match.awayScore, match)
        val away = side(extras.away?.players, match.homeScore, match)
        if (home.isEmpty() && away.isEmpty()) return null
        return MatchRatings(home, away, match.status == MatchStatus.FINISHED, RatingSource.MATCH_STATS)
    }

    private fun side(players: List<LineupPlayer>?, conceded: Int?, match: Match): List<PlayerRating> {
        val length = if (match.status == MatchStatus.FINISHED) 90 else (match.minute ?: 45).coerceAtLeast(1)
        return players.orEmpty().filter { it.minutes > 0 }.map { player ->
            val position = positionOf(player.positionCode)
            val stats = toStats(player, position, conceded ?: 0, length)
            PlayerRating(
                id = player.id.toIntOrNull() ?: player.id.hashCode(),
                name = player.name,
                position = position,
                minutes = player.minutes,
                breakdown = RatingCalculator.calculate(stats, position),
                photoUrl = player.photoUrl,
            )
        }.sortedByDescending { it.breakdown.rating }
    }

    private fun toStats(player: LineupPlayer, position: Position, teamConceded: Int, length: Int): PlayerMatchStats {
        fun count(key: String) = player.stats[key]?.toInt() ?: 0
        val onTarget = count("shotsOnTarget")
        val share = (player.minutes.toDouble() / length).coerceAtMost(1.0)
        val conceded = when (position) {
            Position.GKP -> player.stats["goalsConceded"]?.toInt() ?: teamConceded
            Position.DEF -> (teamConceded * share).roundToInt()
            else -> 0
        }
        return PlayerMatchStats(
            minutes = player.minutes,
            goals = count("totalGoals"),
            assists = count("goalAssists"),
            saves = count("saves"),
            cleanSheet = teamConceded == 0,
            goalsConceded = conceded,
            yellowCards = count("yellowCards"),
            redCards = count("redCards"),
            ownGoals = count("ownGoals"),
            shotsOnTarget = onTarget,
            shotsOffTarget = (count("totalShots") - onTarget).coerceAtLeast(0),
            foulsWon = count("foulsSuffered"),
            foulsCommitted = count("foulsCommitted"),
        )
    }
}
