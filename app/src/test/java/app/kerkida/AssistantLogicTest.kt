package app.kerkida

import app.kerkida.assistant.AssistantLogic
import app.kerkida.data.model.LeagueRef
import app.kerkida.data.model.Match
import app.kerkida.data.model.MatchStatus
import app.kerkida.data.model.TeamRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZonedDateTime

class AssistantLogicTest {
    private val deadline = Instant.parse("2026-11-28T11:00:00Z")

    private fun game(at: String, status: MatchStatus, home: Int? = null, away: Int? = null) = Match(
        id = at,
        league = LeagueRef("l", "L"),
        home = TeamRef("h", "Παναχαϊκή"),
        away = TeamRef("a", "Παναιγιάλειος"),
        kickoff = Instant.parse(at),
        date = null,
        status = status,
        homeScore = home,
        awayScore = away,
    )

    @Test
    fun `deadline reminders fire once per stage`() {
        assertNull(AssistantLogic.deadlineReminder(deadline, deadline.minusSeconds(3 * 86_400), emptySet()))
        val day = AssistantLogic.deadlineReminder(deadline, deadline.minusSeconds(20 * 3600), emptySet())
        assertNotNull(day)
        assertNull(AssistantLogic.deadlineReminder(deadline, deadline.minusSeconds(19 * 3600), setOf(day!!)))
        val last = AssistantLogic.deadlineReminder(deadline, deadline.minusSeconds(3600), setOf(day))
        assertNotNull(last)
        assertNull(AssistantLogic.deadlineReminder(deadline, deadline.plusSeconds(60), emptySet()))
    }

    @Test
    fun `countdown splits days and hours`() {
        assertEquals(2L to 5L, AssistantLogic.countdown(deadline, deadline.minusSeconds(2 * 86_400 + 5 * 3600)))
        assertNull(AssistantLogic.countdown(deadline, deadline.plusSeconds(1)))
    }

    @Test
    fun `a hidden result never reaches the summary`() {
        val played = game("2026-11-22T13:00:00Z", MatchStatus.FINISHED, 2, 1)
        assertEquals("Παναχαϊκή 2–1 Παναιγιάλειος", AssistantLogic.digestLine(played, hidden = false))
        assertFalse(AssistantLogic.digestLine(played, hidden = true).contains("2"))
    }

    @Test
    fun `the monday summary and evening check wait for their time`() {
        assertTrue(AssistantLogic.digestDue(ZonedDateTime.parse("2026-11-23T09:30:00+02:00")))
        assertFalse(AssistantLogic.digestDue(ZonedDateTime.parse("2026-11-23T08:30:00+02:00")))
        assertFalse(AssistantLogic.digestDue(ZonedDateTime.parse("2026-11-24T09:30:00+02:00")))
        assertTrue(AssistantLogic.nearbyDue(ZonedDateTime.parse("2026-11-24T18:00:00+02:00")))
    }

    @Test
    fun `the widget picks the soonest game to come`() {
        val now = Instant.parse("2026-11-22T12:00:00Z")
        val next = AssistantLogic.nextGame(
            listOf(
                game("2026-11-29T13:00:00Z", MatchStatus.SCHEDULED),
                game("2026-11-23T13:00:00Z", MatchStatus.SCHEDULED),
                game("2026-11-22T10:00:00Z", MatchStatus.FINISHED, 1, 0),
            ),
            now,
        )
        assertEquals(Instant.parse("2026-11-23T13:00:00Z"), next?.kickoff)
    }
}
