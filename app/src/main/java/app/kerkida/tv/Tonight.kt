package app.kerkida.tv

import app.kerkida.broadcast.Broadcast
import app.kerkida.broadcast.WatchRank
import app.kerkida.broadcast.WhatIHave
import app.kerkida.data.model.Match
import app.kerkida.data.model.Standings
import kotlin.math.abs

enum class WorthLevel { GOOD, TOP }

enum class WorthReason { TOP_CLASH, CLOSE_RACE, RELEGATION }

data class Worth(val level: WorthLevel, val reason: WorthReason)

data class TonightEntry(val match: Match, val broadcasts: List<Broadcast>, val worth: Worth?)

object Tonight {
    private const val CLOSE_POINTS = 3
    private const val TOP_PLACES = 4
    private const val BOTTOM_PLACES = 4
    private const val MATCH_MS = 115 * 60_000L

    /** Judged only from the table: what is at stake and how evenly matched the teams are. */
    fun worth(match: Match, standings: Standings?): Worth? {
        val rows = standings?.rows.orEmpty()
        val home = rows.firstOrNull { it.team.id == match.home.id } ?: return null
        val away = rows.firstOrNull { it.team.id == match.away.id } ?: return null
        val gap = abs(home.points - away.points)
        val bottom = rows.size - BOTTOM_PLACES
        return when {
            home.rank <= TOP_PLACES && away.rank <= TOP_PLACES -> Worth(WorthLevel.TOP, WorthReason.TOP_CLASH)
            gap <= CLOSE_POINTS && home.rank <= rows.size / 2 && away.rank <= rows.size / 2 ->
                Worth(WorthLevel.TOP, WorthReason.CLOSE_RACE)
            home.rank > bottom && away.rank > bottom -> Worth(WorthLevel.GOOD, WorthReason.RELEGATION)
            gap <= CLOSE_POINTS -> Worth(WorthLevel.GOOD, WorthReason.CLOSE_RACE)
            else -> null
        }
    }

    fun isMine(match: Match, myTeams: Set<String>) = match.home.id in myTeams || match.away.id in myTeams

    /** My teams first, then what I can watch, then what is worth it, then kick-off. */
    fun rank(entries: List<TonightEntry>, myTeams: Set<String>, owned: Set<String>): List<TonightEntry> =
        entries.sortedWith(
            compareBy<TonightEntry> { if (isMine(it.match, myTeams)) 0 else 1 }
                .thenBy { WhatIHave.rankOf(it.broadcasts, owned).let { rank -> if (rank <= WatchRank.FREE) 0 else 1 } }
                .thenBy {
                    when (it.worth?.level) {
                        WorthLevel.TOP -> 0
                        WorthLevel.GOOD -> 1
                        null -> 2
                    }
                }
                .thenBy { it.match.kickoff },
        )

    /** Matches I would care about that overlap with another one. */
    fun clashes(entries: List<TonightEntry>, myTeams: Set<String>): Set<String> {
        val relevant = entries.filter { (isMine(it.match, myTeams) || it.worth != null) && it.match.kickoff != null }
        val out = HashSet<String>()
        for (i in relevant.indices) for (j in i + 1 until relevant.size) {
            val a = relevant[i].match.kickoff!!.toEpochMilli()
            val b = relevant[j].match.kickoff!!.toEpochMilli()
            if (abs(a - b) < MATCH_MS) {
                out += relevant[i].match.id
                out += relevant[j].match.id
            }
        }
        return out
    }
}
