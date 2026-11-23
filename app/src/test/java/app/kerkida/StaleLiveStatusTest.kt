package app.kerkida

import app.kerkida.data.model.MatchStatus
import app.kerkida.data.thesportsdb.TheSportsDbParsing
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class StaleLiveStatusTest {
    private val kickoff = Instant.parse("2026-11-22T12:00:00Z")

    @Test
    fun `a game can be live soon after kickoff`() {
        assertEquals(MatchStatus.LIVE, TheSportsDbParsing.classifyStatus("2H", true, kickoff, kickoff.plusSeconds(5_400)))
        assertEquals(MatchStatus.HALF_TIME, TheSportsDbParsing.classifyStatus("HT", true, kickoff, kickoff.plusSeconds(3_000)))
    }

    @Test
    fun `an old live status is never shown as live`() {
        val later = kickoff.plusSeconds(5 * 3_600)
        assertEquals(MatchStatus.UNKNOWN, TheSportsDbParsing.classifyStatus("2H", true, kickoff, later))
        assertEquals(MatchStatus.UNKNOWN, TheSportsDbParsing.classifyStatus("HT", true, kickoff, later))
    }
}
