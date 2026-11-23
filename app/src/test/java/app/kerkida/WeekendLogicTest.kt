package app.kerkida

import app.kerkida.data.model.LeagueRef
import app.kerkida.data.model.Match
import app.kerkida.data.model.MatchStatus
import app.kerkida.data.model.TeamRef
import app.kerkida.nearby.Place
import app.kerkida.planner.WeekendEntry
import app.kerkida.planner.WeekendLogic
import app.kerkida.season.SeasonLogic
import app.kerkida.season.WatchedMatch
import app.kerkida.stadium.Stadium
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class WeekendLogicTest {
    private val now = Instant.parse("2026-11-20T10:00:00Z")
    private val patras = Place("Πάτρα", 38.2466, 21.7346)

    private fun game(id: String, at: String, home: String = "h$id", status: MatchStatus = MatchStatus.SCHEDULED) = Match(
        id = id,
        league = LeagueRef("l", "League"),
        home = TeamRef(home, home),
        away = TeamRef("a$id", "a$id"),
        kickoff = Instant.parse(at),
        date = null,
        status = status,
    )

    @Test
    fun `the weekend is the coming saturday and sunday`() {
        assertEquals(listOf(LocalDate.parse("2026-11-28"), LocalDate.parse("2026-11-29")), WeekendLogic.days(LocalDate.parse("2026-11-24")))
        assertEquals(listOf(LocalDate.parse("2026-11-22")), WeekendLogic.days(LocalDate.parse("2026-11-22")))
        assertEquals(2, WeekendLogic.days(LocalDate.parse("2026-11-21")).size)
    }

    @Test
    fun `my team beats a nearby game at the same time`() {
        val mine = game("1", "2026-11-22T13:00:00Z", home = "mine")
        val close = game("2", "2026-11-22T13:30:00Z")
        val plan = WeekendLogic.plan(
            listOf(mine, close), emptyList(), patras, 50, setOf("mine"), now,
            { if (it.id == "2") Stadium("Near", lat = 38.25, lng = 21.74) else null }, { emptyList() },
        )
        assertTrue(plan.first { it.key == "1" }.isPick)
        val other = plan.first { it.key == "2" }
        assertFalse(other.isPick)
        assertEquals("mine – a1", other.clashesWith)
    }

    @Test
    fun `far games with no channel and no team of mine are left out`() {
        val far = game("1", "2026-11-22T13:00:00Z")
        val plan = WeekendLogic.plan(
            listOf(far), emptyList(), patras, 50, emptySet(), now,
            { Stadium("Far", lat = 40.64, lng = 22.94) }, { emptyList() },
        )
        assertTrue(plan.isEmpty())
    }

    @Test
    fun `games that do not overlap are all suggested`() {
        val entries = listOf(
            WeekendEntry("a", "A", 0, 100, score = 5.0),
            WeekendEntry("b", "B", 100, 200, score = 1.0),
        )
        assertTrue(WeekendLogic.markPicks(entries).all { it.isPick })
        assertNull(WeekendLogic.markPicks(entries).first().clashesWith)
    }

    @Test
    fun `finished listings go and the same game on two channels is one row`() {
        val start = now.toEpochMilli() + 3_600_000
        val listings = listOf(
            app.kerkida.planner.WeekendListing("old", "Α - Β", now.toEpochMilli() - 7_200_000, now.toEpochMilli() - 60_000, listOf("ΕΡΤ2")),
            app.kerkida.planner.WeekendListing("x", "Παναθηναϊκός - ΟΦΗ", start, start + 6_900_000, listOf("ΣΚΑΪ")),
            app.kerkida.planner.WeekendListing("y", "Παναθηναϊκός-ΟΦΗ", start, start + 6_900_000, listOf("ΣΠΟΡ FM TV 1")),
        )
        val plan = WeekendLogic.plan(emptyList(), listings, patras, 50, emptySet(), now, { null }, { emptyList() })
        assertEquals(1, plan.size)
        assertEquals(listOf("ΣΚΑΪ", "ΣΠΟΡ FM TV 1"), plan[0].channels)
    }

    @Test
    fun `leaving time adds the drive and a margin`() {
        val kickoff = Instant.parse("2026-11-22T13:00:00Z")
        assertEquals(Instant.parse("2026-11-22T11:40:00Z"), WeekendLogic.leaveAt(kickoff, 45.0))
    }

    @Test
    fun `grounds count only games attended`() {
        fun seen(id: String, venue: String, there: Boolean) =
            WatchedMatch(id, "h", "a", league = "l", sport = "FOOTBALL", kickoffMs = 1, venue = venue, attended = there)
        val grounds = SeasonLogic.grounds(listOf(seen("1", "Παμπελοποννησιακό", true), seen("2", "Παμπελοποννησιακό", true), seen("3", "TV", false)))
        assertEquals(1, grounds.size)
        assertEquals(2, grounds[0].visits)
    }
}
