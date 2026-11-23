package app.kerkida

import app.kerkida.data.DataException
import app.kerkida.data.demo.DemoSportsDataProvider
import app.kerkida.data.model.MatchEventType
import app.kerkida.data.model.MatchStatus
import app.kerkida.data.model.Side
import app.kerkida.data.net.RateLimiter
import app.kerkida.data.thesportsdb.TheSportsDbParsing
import app.kerkida.data.thesportsdb.TheSportsDbParsing.toMatch
import app.kerkida.data.thesportsdb.TheSportsDbParsing.toMatchEvent
import app.kerkida.data.thesportsdb.TheSportsDbParsing.toStandingRow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class DataLayerTest {
    private val now = Instant.parse("2026-11-22T12:00:00Z")

    private fun event(status: String?, kickoff: String, home: String? = "2", away: String? = "1"): String {
        val statusField = status?.let { "\"strStatus\":\"$it\"," }.orEmpty()
        val scores = "\"intHomeScore\":${home?.let { "\"$it\"" }},\"intAwayScore\":${away?.let { "\"$it\"" }},"
        return """{"events":[{"idEvent":"1","strSport":"Soccer","idLeague":"4328","strLeague":"L",
            "idHomeTeam":"10","idAwayTeam":"20","strHomeTeam":"A","strAwayTeam":"B",$statusField$scores
            "strTimestamp":"$kickoff","dateEvent":"${kickoff.take(10)}","intRound":"11","strSeason":"2026-2027"}]}"""
    }

    private fun parse(body: String) = TheSportsDbParsing.rows(body, "events").single().toMatch(now)!!

    @Test
    fun finishedMatchKeepsItsScoreAndUtcKickoff() {
        val match = parse(event("FT", "2026-11-21T17:30:00"))
        assertEquals(MatchStatus.FINISHED, match.status)
        assertEquals(2, match.homeScore)
        assertEquals(Instant.parse("2026-11-21T17:30:00Z"), match.kickoff)
        assertEquals(11, match.round)
    }

    @Test
    fun scheduledMatchNeverShowsAScore() {
        val match = parse(event("NS", "2026-11-22T18:00:00", home = "0", away = "0"))
        assertEquals(MatchStatus.SCHEDULED, match.status)
        assertNull(match.homeScore)
    }

    @Test
    fun missingStatusIsNotCalledFinishedWhileTheMatchCouldBeRunning() {
        assertEquals(MatchStatus.UNKNOWN, parse(event(null, "2026-11-22T11:00:00")).status)
        assertEquals(MatchStatus.FINISHED, parse(event(null, "2026-11-22T06:00:00")).status)
    }

    @Test
    fun staleNotStartedIsReportedAsUnknown() {
        val match = parse(event("NS", "2026-11-22T06:00:00", home = null, away = null))
        assertEquals(MatchStatus.UNKNOWN, match.status)
    }

    @Test
    fun nullListAndBrokenBodyAreHandled() {
        assertTrue(TheSportsDbParsing.rows("""{"events":null}""", "events").isEmpty())
        assertThrows(DataException.BadData::class.java) { TheSportsDbParsing.rows("error code: 1015", "events") }
    }

    @Test
    fun otherSportsAreIgnored() {
        val body = """{"events":[{"idEvent":"9","strSport":"Basketball","strHomeTeam":"A","strAwayTeam":"B"}]}"""
        assertNull(TheSportsDbParsing.rows(body, "events").single().toMatch(now))
    }

    @Test
    fun timelineAndTableRowsAreParsed() {
        val timeline = """{"timeline":[{"strTimeline":"Goal","strTimelineDetail":"Own Goal","strHome":"No",
            "strPlayer":"P","intTime":"23"}]}"""
        val goal = TheSportsDbParsing.rows(timeline, "timeline").single().toMatchEvent()!!
        assertEquals(MatchEventType.OWN_GOAL, goal.type)
        assertEquals(Side.AWAY, goal.side)

        val table = """{"table":[{"idTeam":"1","strTeam":"A","intRank":"1","intPlayed":"11","intWin":"9",
            "intDraw":"1","intLoss":"1","intGoalsFor":"17","intGoalsAgainst":"7","intGoalDifference":"10","intPoints":"28"}]}"""
        val row = TheSportsDbParsing.rows(table, "table").single().toStandingRow()!!
        assertEquals(28, row.points)
        assertEquals(10, row.goalDifference)
    }

    @Test
    fun rateLimiterStopsDuringCooldown() = runTest {
        var time = 1_000_000L
        val limiter = RateLimiter(now = { time })
        limiter.acquire()
        limiter.coolDown(120)
        var blocked = false
        try {
            limiter.acquire()
        } catch (_: DataException.RateLimited) {
            blocked = true
        }
        assertTrue(blocked)
        time += 121_000
        limiter.acquire()
    }

    @Test
    fun rateLimiterSpacesRequestsAndCapsThePerMinuteCount() = runTest {
        var time = 0L
        val limiter = RateLimiter(minGapMs = 2_500, maxPerMinute = 3, maxWaitMs = 500, now = { time })
        limiter.acquire()
        var tooSoon = false
        try {
            limiter.acquire()
        } catch (_: DataException.RateLimited) {
            tooSoon = true
        }
        assertTrue(tooSoon)
        time += 2_500
        limiter.acquire()
        time += 2_500
        limiter.acquire()
        time += 2_500
        var capped = false
        try {
            limiter.acquire()
        } catch (_: DataException.RateLimited) {
            capped = true
        }
        assertTrue(capped)
    }

    @Test
    fun demoDataIsAlwaysMarkedAsDemo() = runTest {
        val clock = Instant.parse("2026-11-22T15:10:00Z")
        val demo = DemoSportsDataProvider(clock = { clock }, zone = { ZoneOffset.UTC })
        assertTrue(demo.info.isDemo)
        val date = LocalDate.parse("2026-11-22")
        val day = demo.matchDay(date).first()
        assertEquals(8, day.matches.size)
        assertFalse(day.isLimitedCoverage)
        day.matches.forEach { match ->
            assertTrue(match.id.startsWith("demo-"))
            assertTrue(match.league.name.startsWith("DEMO"))
            assertNull(match.home.badgeUrl)
        }
    }

    @Test
    fun demoRoundsPairEveryClubOnceAndDetailsMatchTheList() = runTest {
        val clock = Instant.parse("2026-11-22T23:30:00Z")
        val demo = DemoSportsDataProvider(clock = { clock }, zone = { ZoneOffset.UTC })
        val day = demo.matchDay(LocalDate.parse("2026-11-22")).first()
        day.matches.groupBy { it.league.id }.values.forEach { games ->
            val clubs = games.flatMap { listOf(it.home.id, it.away.id) }
            assertEquals(clubs.size, clubs.toSet().size)
        }
        day.matches.forEach { listed ->
            val details = demo.matchDetails(listed.id).data
            assertEquals(listed.homeScore, details.match.homeScore)
            val homeGoals = details.events.count { it.type == MatchEventType.GOAL && it.side == Side.HOME }
            assertEquals(listed.homeScore, homeGoals)
            val table = details.standings!!
            assertEquals(8, table.rows.size)
            assertFalse(table.isPartial)
            assertEquals(table.rows.sumOf { it.goalsFor }, table.rows.sumOf { it.goalsAgainst })
        }
    }
}
