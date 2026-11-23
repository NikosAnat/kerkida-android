package app.kerkida.data.espn

import app.kerkida.data.model.Match
import app.kerkida.data.model.MatchStatus

data class HeadToHeadRecord(val wins: Int, val draws: Int, val losses: Int, val games: List<Match>)

object HeadToHead {
    /** Finished meetings of the two teams, newest first, counted from [firstId]'s side. */
    fun of(games: List<Match>, firstId: String, secondId: String, excludeId: String): HeadToHeadRecord {
        val meetings = games.asSequence()
            .filter { it.id != excludeId && it.status == MatchStatus.FINISHED && it.homeScore != null && it.awayScore != null }
            .filter { (it.home.id == firstId && it.away.id == secondId) || (it.home.id == secondId && it.away.id == firstId) }
            .distinctBy { it.id }
            .sortedByDescending { it.kickoff }
            .toList()
        var wins = 0
        var draws = 0
        var losses = 0
        meetings.forEach { game ->
            val own = if (game.home.id == firstId) game.homeScore!! else game.awayScore!!
            val other = if (game.home.id == firstId) game.awayScore!! else game.homeScore!!
            when {
                own > other -> wins++
                own < other -> losses++
                else -> draws++
            }
        }
        return HeadToHeadRecord(wins, draws, losses, meetings)
    }
}
