package app.kerkida

import app.kerkida.data.espn.HeadToHead
import app.kerkida.data.model.LeagueRef
import app.kerkida.data.model.Match
import app.kerkida.data.model.MatchEvent
import app.kerkida.data.model.MatchEventType
import app.kerkida.data.model.MatchStatus
import app.kerkida.data.model.Side
import app.kerkida.data.model.TeamRef
import app.kerkida.fantasy.FplPlayer
import app.kerkida.season.Announcer
import app.kerkida.season.SeasonLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class StageThreeLogicTest {
    private fun game(id: String, home: String, away: String, h: Int?, a: Int?, day: String = "2026-11-21", status: MatchStatus = MatchStatus.FINISHED) = Match(
        id = id,
        league = LeagueRef("l", "League"),
        home = TeamRef(home, home),
        away = TeamRef(away, away),
        kickoff = Instant.parse("${day}T18:00:00Z"),
        date = null,
        status = status,
        homeScore = h,
        awayScore = a,
        venue = "$home ground",
    )

    @Test
    fun aSeasonRunsFromJulyToJune() {
        assertEquals(2026, SeasonLogic.seasonOf(Instant.parse("2026-08-01T12:00:00Z").toEpochMilli()))
        assertEquals(2026, SeasonLogic.seasonOf(Instant.parse("2027-05-20T12:00:00Z").toEpochMilli()))
        assertEquals(2025, SeasonLogic.seasonOf(Instant.parse("2026-06-20T12:00:00Z").toEpochMilli()))
    }

    @Test
    fun summarisesOnlyThisSeason() {
        val log = listOf(
            game("1", "A", "B", 3, 2), game("2", "A", "C", 1, 0), game("3", "D", "A", 0, 0),
            game("old", "A", "Z", 9, 9, day = "2026-03-01"),
        ).map(SeasonLogic::record)
        val summary = SeasonLogic.summary(log, 2026)
        assertEquals(3, summary.matches)
        assertEquals(6, summary.goals)
        assertEquals(2.0, summary.perMatch, 0.001)
        assertEquals("1", summary.biggest?.matchId)
        assertEquals("A" to 3, summary.topTeams.first())
        assertEquals(2, summary.grounds)
    }

    @Test
    fun theFirstLookAtAMatchIsNeverReadOut() {
        val goal = MatchEvent(10, MatchEventType.GOAL, Side.HOME, "P")
        assertTrue(Announcer.fresh(null, listOf(goal)).isEmpty())
    }

    @Test
    fun readsOnlyNewKeyEvents() {
        val old = MatchEvent(10, MatchEventType.GOAL, Side.HOME, "P")
        val card = MatchEvent(20, MatchEventType.YELLOW_CARD, Side.AWAY, "Q")
        val red = MatchEvent(30, MatchEventType.RED_CARD, Side.AWAY, "R")
        assertEquals(listOf(red), Announcer.fresh(setOf(Announcer.key(old)), listOf(old, card, red)))
    }

    @Test
    fun aSpokenLineNamesTheEventTeamPlayerAndScore() {
        val words = Announcer.Words("Γκολ", "Πέναλτι", "Αυτογκόλ", "Κόκκινη", "Χαμένο πέναλτι", "λεπτό")
        val line = Announcer.line(MatchEvent(55, MatchEventType.GOAL, Side.AWAY, "Koita"), game("m", "Kalamata", "AEK", 0, 1), words)
        assertEquals("Γκολ, AEK, Koita, 55 λεπτό, 0 1", line)
    }

    @Test
    fun headToHeadCountsFromTheFirstTeamsSide() {
        val games = listOf(
            game("a", "X", "Y", 2, 0, day = "2026-10-01"),
            game("b", "Y", "X", 1, 1, day = "2026-02-01"),
            game("c", "Y", "X", 3, 1, day = "2025-09-01"),
            game("other", "X", "Z", 5, 0, day = "2026-09-01"),
            game("later", "X", "Y", null, null, day = "2027-01-01", status = MatchStatus.SCHEDULED),
        )
        val record = HeadToHead.of(games, "X", "Y", excludeId = "none")
        assertEquals(1, record.wins)
        assertEquals(1, record.draws)
        assertEquals(1, record.losses)
        assertEquals(listOf("a", "b", "c"), record.games.map { it.id })
    }

    @Test
    fun theCurrentMatchIsNotPartOfItsOwnHistory() {
        val games = listOf(game("now", "X", "Y", 2, 0), game("past", "X", "Y", 0, 1, day = "2026-01-01"))
        assertEquals(listOf("past"), HeadToHead.of(games, "X", "Y", excludeId = "now").games.map { it.id })
    }
}
