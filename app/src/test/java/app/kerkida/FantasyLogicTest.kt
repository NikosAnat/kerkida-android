package app.kerkida

import app.kerkida.fantasy.FantasyLogic
import app.kerkida.fantasy.FplEntryHistory
import app.kerkida.fantasy.FplFixture
import app.kerkida.fantasy.FplLiveElement
import app.kerkida.fantasy.FplPick
import app.kerkida.fantasy.FplPicks
import app.kerkida.fantasy.FplPlayer
import app.kerkida.fantasy.FplStandingRow
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FantasyLogicTest {
    // 1 GK, 4 DEF, 4 MID, 2 FWD, then bench: GK, DEF, MID, FWD.
    private val types = listOf(1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 1, 2, 3, 4)
    private val players = types.mapIndexed { index, type ->
        val id = index + 1
        id to FplPlayer(id, "P$id", team = id, elementType = type)
    }.toMap()

    private fun picks(captain: Int = 10, vice: Int = 9, chip: String? = null, cost: Int = 0) = FplPicks(
        activeChip = chip,
        history = FplEntryHistory(transferCost = cost),
        picks = (1..15).map { id ->
            FplPick(
                element = id,
                position = id,
                multiplier = if (id == captain) 2 else if (id <= 11 || chip == "bboost") 1 else 0,
                isCaptain = id == captain,
                isVice = id == vice,
            )
        },
    )

    private fun live(points: Int = 2, minutes: Int = 90, overrides: Map<Int, Pair<Int, Int>> = emptyMap()) =
        (1..15).associateWith { id ->
            val (p, m) = overrides[id] ?: (points to minutes)
            FplLiveElement(id, mapOf("total_points" to JsonPrimitive(p), "minutes" to JsonPrimitive(m)))
        }

    private fun finished(vararg open: Int) = (1..15).map { team ->
        FplFixture(team, 1, team, 100 + team, finished = team !in open, finishedProvisional = team !in open, started = true)
    }

    @Test
    fun countsStartersAndDoublesTheCaptain() {
        val squad = FantasyLogic.build(picks(), players, live(), finished(), emptySet())
        assertEquals(11 * 2 + 2, squad.total)
    }

    @Test
    fun transferHitsComeOffTheTotal() {
        assertEquals(20, FantasyLogic.build(picks(cost = 4), players, live(), finished(), emptySet()).total)
    }

    @Test
    fun benchBoostCountsEveryone() {
        assertEquals(15 * 2 + 2, FantasyLogic.build(picks(chip = "bboost"), players, live(), finished(), emptySet()).total)
    }

    @Test
    fun aPlayerWhoMissedIsReplacedFromTheBench() {
        val squad = FantasyLogic.build(picks(), players, live(overrides = mapOf(6 to (0 to 0), 14 to (7 to 90))), finished(), emptySet())
        assertTrue(squad.starters.any { it.id == 13 && it.subbedIn })
        assertTrue(squad.bench.any { it.id == 6 && it.subbedOut })
    }

    @Test
    fun aSubstitutionKeepsAValidFormation() {
        // Forward 11 misses and the only bench players who played are a keeper and a defender.
        val overrides = mapOf(10 to (0 to 0), 11 to (0 to 0), 13 to (5 to 90), 14 to (0 to 0), 15 to (0 to 0))
        val squad = FantasyLogic.build(picks(captain = 1, vice = 2), players, live(overrides = overrides), finished(), emptySet())
        assertTrue(squad.starters.count { it.position == app.kerkida.fantasy.Position.FWD } >= 1)
    }

    @Test
    fun nobodyIsSwappedWhileTheirMatchIsStillToFinish() {
        val squad = FantasyLogic.build(picks(), players, live(overrides = mapOf(6 to (0 to 0))), finished(6), emptySet())
        assertTrue(squad.starters.any { it.id == 6 })
    }

    @Test
    fun theViceTakesOverWhenTheCaptainMisses() {
        val overrides = mapOf(10 to (0 to 0), 9 to (5 to 90), 12 to (0 to 0), 13 to (0 to 0), 14 to (0 to 0), 15 to (0 to 0))
        val squad = FantasyLogic.build(picks(), players, live(overrides = overrides), finished(), emptySet())
        assertEquals(2, squad.starters.first { it.id == 9 }.multiplier)
    }

    @Test
    fun aHiddenMatchKeepsItsPointsAndMinutesSecret() {
        val squad = FantasyLogic.build(picks(), players, live(overrides = mapOf(5 to (15 to 0))), finished(), setOf(5))
        val hidden = squad.starters.first { it.id == 5 }
        assertNull(hidden.points)
        assertNull(hidden.scored)
        assertEquals(1, squad.hiddenPlayers)
        assertEquals(10 * 2 + 2, squad.total)
    }

    @Test
    fun bonusFollowsBpsIncludingTies() {
        assertEquals(mapOf(1 to 3, 2 to 2, 3 to 1), FantasyLogic.provisionalBonus(mapOf(1 to 40, 2 to 30, 3 to 20, 4 to 10)))
        assertEquals(mapOf(1 to 3, 2 to 3, 3 to 1), FantasyLogic.provisionalBonus(mapOf(1 to 40, 2 to 40, 3 to 20, 4 to 10)))
        assertEquals(mapOf(1 to 3, 2 to 2, 3 to 1, 4 to 1), FantasyLogic.provisionalBonus(mapOf(1 to 40, 2 to 30, 3 to 20, 4 to 20)))
    }

    @Test
    fun aMiniLeagueReRanksOnLivePoints() {
        val rows = listOf(
            FplStandingRow(1, "A", rank = 1, total = 100, eventTotal = 10),
            FplStandingRow(2, "B", rank = 2, total = 95, eventTotal = 10),
        )
        val table = FantasyLogic.liveTable(rows, mapOf(2 to 30))
        assertEquals(listOf(2, 1), table.map { it.row.entry })
        assertEquals(115, table[0].liveTotal)
        assertEquals(1, table[0].liveRank)
    }
}
