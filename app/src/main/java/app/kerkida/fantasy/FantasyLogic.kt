package app.kerkida.fantasy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

@Serializable
data class FplClassicLeague(val id: Int, val name: String, @SerialName("league_type") val type: String = "x")

@Serializable
data class FplEntryLeagues(val classic: List<FplClassicLeague> = emptyList())

@Serializable
data class FplEntry(
    val id: Int,
    val name: String,
    @SerialName("player_first_name") val firstName: String = "",
    @SerialName("player_last_name") val lastName: String = "",
    @SerialName("current_event") val currentEvent: Int? = null,
    @SerialName("summary_overall_points") val overallPoints: Int = 0,
    @SerialName("summary_overall_rank") val overallRank: Int? = null,
    val leagues: FplEntryLeagues = FplEntryLeagues(),
)

@Serializable
data class FplPick(
    val element: Int,
    val position: Int,
    val multiplier: Int = 1,
    @SerialName("is_captain") val isCaptain: Boolean = false,
    @SerialName("is_vice_captain") val isVice: Boolean = false,
)

@Serializable
data class FplEntryHistory(
    val points: Int = 0,
    @SerialName("total_points") val totalPoints: Int = 0,
    @SerialName("event_transfers_cost") val transferCost: Int = 0,
)

@Serializable
data class FplPicks(
    @SerialName("active_chip") val activeChip: String? = null,
    @SerialName("entry_history") val history: FplEntryHistory = FplEntryHistory(),
    val picks: List<FplPick> = emptyList(),
)

@Serializable
data class FplStandingRow(
    val entry: Int,
    @SerialName("entry_name") val entryName: String,
    @SerialName("player_name") val playerName: String = "",
    val rank: Int = 0,
    val total: Int = 0,
    @SerialName("event_total") val eventTotal: Int = 0,
)

@Serializable
data class FplStandingsPage(val results: List<FplStandingRow> = emptyList())

@Serializable
data class FplLeagueInfo(val id: Int = 0, val name: String = "")

@Serializable
data class FplLeagueStandings(val league: FplLeagueInfo = FplLeagueInfo(), val standings: FplStandingsPage = FplStandingsPage())

data class FantasyLine(
    val id: Int,
    val name: String,
    val teamId: Int,
    val position: Position,
    val isCaptain: Boolean,
    val isVice: Boolean,
    val multiplier: Int,
    val minutes: Int,
    /** Points before the captain multiplier, with provisional bonus. Null while the match is hidden. */
    val points: Int?,
    val provisionalBonus: Int,
    val subbedIn: Boolean = false,
    val subbedOut: Boolean = false,
    val status: String = "a",
    val news: String = "",
) {
    val scored: Int? get() = points?.let { it * multiplier }
}

data class FantasySquad(
    val starters: List<FantasyLine>,
    val bench: List<FantasyLine>,
    val total: Int,
    val transferCost: Int,
    val hiddenPlayers: Int,
    val chip: String?,
)

/** The scoring rules applied live. Nothing here touches the network. */
object FantasyLogic {
    /** Top three by BPS earn 3, 2 and 1. Ties share the higher award and push the next one down. */
    fun provisionalBonus(bps: Map<Int, Int>): Map<Int, Int> {
        val awards = HashMap<Int, Int>()
        val groups = bps.entries.groupBy({ it.value }, { it.key }).toSortedMap(reverseOrder())
        var place = 0
        for ((_, players) in groups) {
            if (place >= 3) break
            val award = 3 - place
            players.forEach { awards[it] = award }
            place += players.size
        }
        return awards
    }

    fun bonusForGameweek(fixtures: List<FplFixture>): Map<Int, Int> {
        val out = HashMap<Int, Int>()
        fixtures.filter { it.started == true && !it.finished }.forEach { fixture ->
            val line = fixture.stats.firstOrNull { it.identifier == "bps" } ?: return@forEach
            val bps = (line.home + line.away).associate { it.element to it.value }
            provisionalBonus(bps).forEach { (player, award) -> out[player] = (out[player] ?: 0) + award }
        }
        return out
    }

    private fun stat(element: FplLiveElement, key: String): Int =
        (element.stats[key] as? JsonPrimitive)?.doubleOrNull?.toInt() ?: 0

    private fun isValid(lineup: List<FantasyLine>): Boolean {
        fun count(position: Position) = lineup.count { it.position == position }
        return count(Position.GKP) == 1 && count(Position.DEF) >= 3 && count(Position.MID) >= 2 && count(Position.FWD) >= 1
    }

    fun build(
        picks: FplPicks,
        players: Map<Int, FplPlayer>,
        live: Map<Int, FplLiveElement>,
        fixtures: List<FplFixture>,
        hiddenTeams: Set<Int>,
    ): FantasySquad {
        val bonus = bonusForGameweek(fixtures)
        fun teamDone(team: Int): Boolean {
            val own = fixtures.filter { it.teamHome == team || it.teamAway == team }
            return own.all { it.finished || it.finishedProvisional }
        }
        val lines = picks.picks.sortedBy { it.position }.mapNotNull { pick ->
            val player = players[pick.element] ?: return@mapNotNull null
            val element = live[pick.element]
            val base = element?.let { stat(it, "total_points") } ?: 0
            val confirmed = element?.let { stat(it, "bonus") } ?: 0
            val extra = if (confirmed > 0) 0 else bonus[pick.element] ?: 0
            FantasyLine(
                id = player.id,
                name = player.name,
                teamId = player.team,
                position = player.position,
                isCaptain = pick.isCaptain,
                isVice = pick.isVice,
                multiplier = pick.multiplier,
                minutes = element?.let { stat(it, "minutes") } ?: 0,
                points = if (player.team in hiddenTeams) null else base + extra,
                provisionalBonus = extra,
                status = player.status,
                news = player.news,
            )
        }
        val benchBoost = picks.activeChip == "bboost"
        var starters = lines.take(STARTERS)
        var bench = lines.drop(STARTERS)

        if (!benchBoost) {
            // A hidden match must not reveal who played, so nobody is swapped until it is shown.
            fun missed(line: FantasyLine) =
                line.minutes == 0 && teamDone(line.teamId) && line.teamId !in hiddenTeams
            fun canCome(line: FantasyLine) =
                line.teamId !in hiddenTeams && (line.minutes > 0 || !teamDone(line.teamId))
            starters.filter(::missed).forEach { out ->
                val replacement = bench.firstOrNull { candidate ->
                    canCome(candidate) && !candidate.subbedOut && !candidate.subbedIn &&
                        isValid(starters.map { if (it.id == out.id) candidate else it })
                } ?: return@forEach
                starters = starters.map { if (it.id == out.id) replacement.copy(subbedIn = true, multiplier = 1) else it }
                bench = bench.map { if (it.id == replacement.id) out.copy(subbedOut = true, multiplier = 0) else it }
            }
            val captain = lines.firstOrNull { it.isCaptain }
            val onPitch = captain != null && starters.any { it.id == captain.id }
            if (captain != null && (!onPitch || (captain.minutes == 0 && teamDone(captain.teamId) && captain.teamId !in hiddenTeams))) {
                val vice = starters.firstOrNull { it.isVice && it.minutes > 0 }
                if (vice != null) {
                    starters = starters.map {
                        when (it.id) {
                            vice.id -> it.copy(multiplier = captain.multiplier.coerceAtLeast(2))
                            captain.id -> it.copy(multiplier = 1)
                            else -> it
                        }
                    }
                }
            }
        }
        val counted = if (benchBoost) starters + bench else starters
        return FantasySquad(
            starters = starters,
            bench = bench,
            total = counted.sumOf { it.scored ?: 0 } - picks.history.transferCost,
            transferCost = picks.history.transferCost,
            hiddenPlayers = counted.count { it.points == null },
            chip = picks.activeChip,
        )
    }

    /** Applies each manager's live gameweek score to the published table and re-ranks it. */
    fun liveTable(rows: List<FplStandingRow>, livePoints: Map<Int, Int>): List<LiveStanding> =
        rows.map { row ->
            val gameweek = livePoints[row.entry] ?: row.eventTotal
            LiveStanding(row, gameweek, row.total - row.eventTotal + gameweek)
        }.sortedWith(compareByDescending<LiveStanding> { it.liveTotal }.thenBy { it.row.rank })
            .mapIndexed { index, standing -> standing.copy(liveRank = index + 1) }

    private const val STARTERS = 11
}

data class LiveStanding(
    val row: FplStandingRow,
    val gameweek: Int,
    val liveTotal: Int,
    val liveRank: Int = 0,
)
