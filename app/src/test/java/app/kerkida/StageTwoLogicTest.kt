package app.kerkida

import app.kerkida.broadcast.AccessType
import app.kerkida.broadcast.Broadcast
import app.kerkida.broadcast.BroadcastScope
import app.kerkida.calendar.CalendarGame
import app.kerkida.calendar.CalendarSync
import app.kerkida.calendar.SavedGame
import app.kerkida.data.model.LeagueRef
import app.kerkida.data.model.Match
import app.kerkida.data.model.MatchEvent
import app.kerkida.data.model.MatchEventType
import app.kerkida.data.model.MatchStatus
import app.kerkida.data.model.Side
import app.kerkida.data.model.StandingRow
import app.kerkida.data.model.Standings
import app.kerkida.data.model.TeamRef
import app.kerkida.tv.Tonight
import app.kerkida.tv.TonightEntry
import app.kerkida.tv.Worth
import app.kerkida.tv.WorthLevel
import app.kerkida.tv.WorthReason
import app.kerkida.ui.favourites.scorersFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class StageTwoLogicTest {
    private val league = LeagueRef("l", "League")

    private fun match(id: String, home: String, away: String, minutes: Long = 0) = Match(
        id = id,
        league = league,
        home = TeamRef(home, home),
        away = TeamRef(away, away),
        kickoff = Instant.ofEpochSecond(1_800_000_000 + minutes * 60),
        date = null,
        status = MatchStatus.SCHEDULED,
    )

    private fun row(rank: Int, points: Int) =
        StandingRow(rank, TeamRef("t$rank", "t$rank"), 10, 0, 0, 0, 0, 0, 0, points)

    private val table = Standings(league, null, listOf(40, 38, 30, 28, 25, 22, 21, 20, 15, 14, 13, 5).mapIndexed { i, p -> row(i + 1, p) }, false)

    @Test
    fun aTopFourMeetingIsWorthIt() {
        assertEquals(Worth(WorthLevel.TOP, WorthReason.TOP_CLASH), Tonight.worth(match("m", "t1", "t4"), table))
    }

    @Test
    fun aBottomMeetingIsARelegationBattle() {
        assertEquals(WorthReason.RELEGATION, Tonight.worth(match("m", "t10", "t12"), table)?.reason)
    }

    @Test
    fun aOneSidedOrUnknownMatchGetsNoMark() {
        assertNull(Tonight.worth(match("m", "t1", "t12"), table))
        assertNull(Tonight.worth(match("m", "x", "t1"), table))
        assertNull(Tonight.worth(match("m", "t1", "t2"), null))
    }

    private fun channel(id: String, access: AccessType) =
        Broadcast(scope = BroadcastScope.MATCH, targetId = "x", channelId = id, channelName = id, access = access, updatedAtMs = 1)

    @Test
    fun myTeamsComeFirstThenWhatICanWatchThenWhatIsWorthIt() {
        val mine = TonightEntry(match("mine", "me", "b", 200), listOf(channel("magenta", AccessType.SUBSCRIPTION)), null)
        val free = TonightEntry(match("free", "c", "d", 100), listOf(channel("ert", AccessType.FREE)), null)
        val best = TonightEntry(match("best", "e", "f", 150), listOf(channel("nova", AccessType.SUBSCRIPTION)), Worth(WorthLevel.TOP, WorthReason.TOP_CLASH))
        val big = TonightEntry(match("big", "g", "h", 50), listOf(channel("magenta", AccessType.SUBSCRIPTION)), Worth(WorthLevel.TOP, WorthReason.TOP_CLASH))
        val order = Tonight.rank(listOf(big, free, mine, best), setOf("me"), setOf("nova")).map { it.match.id }
        assertEquals(listOf("mine", "best", "free", "big"), order)
    }

    @Test
    fun findsClashesOnlyBetweenMatchesICareAbout() {
        val worth = Worth(WorthLevel.GOOD, WorthReason.CLOSE_RACE)
        val a = TonightEntry(match("a", "me", "b", 0), emptyList(), null)
        val b = TonightEntry(match("b", "c", "d", 60), emptyList(), worth)
        val ignored = TonightEntry(match("c", "e", "f", 30), emptyList(), null)
        val later = TonightEntry(match("d", "g", "h", 300), emptyList(), worth)
        assertEquals(setOf("a", "b"), Tonight.clashes(listOf(a, b, ignored, later), setOf("me")))
    }

    private fun game(id: String, start: Long) = CalendarGame(id, "A – B", start, start + 100, "Ground")

    @Test
    fun calendarOnlyWritesWhatChanged() {
        val same = game("same", 1000)
        val moved = game("moved", 2000)
        val gone = game("gone", 3000)
        val fresh = game("new", 4000)
        val plan = CalendarSync.plan(
            wanted = listOf(same, moved.copy(startMs = 2500, endMs = 2600), fresh),
            saved = listOf(SavedGame(1, same), SavedGame(2, moved), SavedGame(3, gone)),
        )
        assertEquals(listOf(fresh), plan.add)
        assertEquals(listOf(2L), plan.update.map { it.rowId })
        assertEquals(2500L, plan.update.single().game.startMs)
        assertEquals(listOf(3L), plan.remove)
    }

    @Test
    fun aCalendarTitleNeverContainsAScore() {
        val played = match("m", "Home", "Away").copy(status = MatchStatus.FINISHED, homeScore = 3, awayScore = 1)
        val title = CalendarSync.gameOf(played)!!.title
        assertFalse(title.contains("3"))
        assertTrue(title.contains("Home"))
    }

    @Test
    fun aSummaryListsOnlyMyTeamsScorers() {
        val events = listOf(
            MatchEvent(12, MatchEventType.GOAL, Side.HOME, "Erling Haaland"),
            MatchEvent(30, MatchEventType.GOAL, Side.AWAY, "Other Player"),
            MatchEvent(50, MatchEventType.YELLOW_CARD, Side.HOME, "Card Man"),
            MatchEvent(70, MatchEventType.PENALTY, Side.HOME, "Phil Foden"),
        )
        assertEquals(listOf("Haaland 12′", "Foden 70′"), scorersFor(events, Side.HOME))
    }
}
