package app.kerkida

import app.kerkida.data.greek.GreekLowerLogic
import app.kerkida.data.greek.GreekTeam
import app.kerkida.data.model.LeagueRef
import app.kerkida.data.model.Match
import app.kerkida.data.model.MatchStatus
import app.kerkida.data.model.StandingRow
import app.kerkida.data.model.TeamRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class GreekLowerTest {
    private val a = TeamRef("grl:1", "Alpha")
    private val b = TeamRef("grl:2", "Beta")
    private val c = TeamRef("grl:3", "Gamma")

    private fun game(home: TeamRef, away: TeamRef, h: Int?, g: Int?, status: MatchStatus = MatchStatus.FINISHED) = Match(
        id = "grl.5847~${home.id}${away.id}",
        league = LeagueRef("grl.5847", "Group"),
        home = home,
        away = away,
        kickoff = Instant.parse("2026-11-15T13:00:00Z"),
        date = null,
        status = status,
        homeScore = h,
        awayScore = g,
    )

    @Test
    fun `greek and latin spellings of a club meet`() {
        assertEquals(GreekLowerLogic.nameKey("Panegialios"), GreekLowerLogic.nameKey("Παναιγιάλειος"))
        assertEquals(GreekLowerLogic.nameKey("Panachaiki"), GreekLowerLogic.nameKey("Παναχαϊκή"))
    }

    @Test
    fun `ids of lower leagues are told apart`() {
        assertTrue(GreekLowerLogic.isLeague("grl.5847"))
        assertFalse(GreekLowerLogic.isLeague("gre.1"))
        assertTrue(GreekLowerLogic.isMatch(GreekLowerLogic.matchId(5847, "9")))
        assertFalse(GreekLowerLogic.isLeague(GreekLowerLogic.matchId(5847, "9")))
        assertTrue(GreekLowerLogic.isTeam("grl:134256"))
    }

    @Test
    fun `table counts only finished games`() {
        val rows = GreekLowerLogic.table(
            listOf(a, b, c),
            listOf(game(a, b, 2, 0), game(b, c, 1, 1), game(c, a, 3, 3, MatchStatus.LIVE)),
        )
        assertEquals(listOf("Alpha", "Gamma", "Beta"), rows.map { it.team.name })
        assertEquals(3, rows[0].points)
        assertEquals(1, rows[0].played)
        assertEquals(1, rows[2].points)
    }

    @Test
    fun `volleyball gives two and one points after five sets`() {
        val rows = GreekLowerLogic.table(listOf(a, b), listOf(game(a, b, 3, 2)), win = 3, draw = 0, fiveSets = true)
        assertEquals(2, rows.first { it.team.id == a.id }.points)
        assertEquals(1, rows.first { it.team.id == b.id }.points)
    }

    @Test
    fun `rows from the source keep their points penalty`() {
        val listed = StandingRow(1, c, 1, 0, 1, 0, 1, 1, 0, 9)
        val rows = GreekLowerLogic.table(listOf(a, b, c), listOf(game(a, b, 2, 0)), listOf(listed))
        assertEquals("Gamma", rows[0].team.name)
        assertEquals(9, rows[0].points)
        assertEquals(listOf(1, 2, 3), rows.map { it.rank })
    }

    @Test
    fun `a neutral venue never borrows the home town`() {
        val patras = GreekTeam("1", "Home", 5847, venue = "Stadio Patras", lat = 38.2, lng = 21.7)
        val aigio = GreekTeam("2", "Other", 5847, venue = "Stadio Aigio", lat = 38.25, lng = 22.08)
        assertEquals(patras, GreekLowerLogic.groundOf("Stadio Patras", patras, listOf(patras, aigio)))
        assertEquals(aigio, GreekLowerLogic.groundOf("Stadio Aigio", patras, listOf(patras, aigio)))
        assertNull(GreekLowerLogic.groundOf("Unknown Ground", patras, listOf(patras, aigio)))
        assertEquals(patras, GreekLowerLogic.groundOf(null, patras, listOf(patras, aigio)))
    }
}

class GblParsingTest {
    private fun row(score: String) = """<div class="esake-program-game"><a href="/el/action/EsakegameView?idgame=3C7521D5&mode=3"></a><h5>08η Αγωνιστική</h5>
<div><img src="/skn/clock.svg" class="x">Σαβ 21 Νοε - 15:00</div><div><img src="/skn/pointer.svg" class="x">ΙΒΑΝΩΦΕΙΟ</div><div><img src="/skn/tv.svg" class="x">SPORT FM TV 3</div>
<div class="esake-program-game-final-score row equal"><div class="col-lg-5 text-right"><span>ΗΡΑΚΛΗΣ</span><img src="/dat/esaketeam/00000011/p.png"></div>
<div class="col-lg-2 text-center"><span>$score</span></div><div class="col-lg-5 text-left"><img src="/dat/esaketeam/00000010/q.png"><span>ΑΕΚ</span></div></div>
<div class="esake-program-game-hidden-row"></div></div>"""

    @Test
    fun `a played game is read in full`() {
        val game = app.kerkida.data.greek.GblParsing.games(row("84&nbsp;-&nbsp;87"), 2026).single()
        assertEquals("ΗΡΑΚΛΗΣ", game.home.name)
        assertEquals("ΑΕΚ", game.away.name)
        assertEquals(84, game.homeScore)
        assertEquals(87, game.awayScore)
        assertEquals(8, game.round)
        assertEquals("ΙΒΑΝΩΦΕΙΟ", game.venue)
        assertEquals("SPORT FM TV 3", game.channel)
        assertEquals(Instant.parse("2026-11-21T13:00:00Z"), game.kickoff)
    }

    @Test
    fun `january belongs to the second year of the season`() {
        assertEquals(Instant.parse("2027-01-10T16:00:00Z"), app.kerkida.data.greek.GblParsing.kickoff("Κυρ 10 Ιαν - 18:00", 2026))
    }

    @Test
    fun `a score is not final while the game can still be running`() {
        val game = app.kerkida.data.greek.GblParsing.games(row("40&nbsp;-&nbsp;38"), 2026).single()
        assertEquals(MatchStatus.UNKNOWN, app.kerkida.data.greek.GblParsing.status(game, Instant.parse("2026-11-21T14:00:00Z")))
        assertEquals(MatchStatus.FINISHED, app.kerkida.data.greek.GblParsing.status(game, Instant.parse("2026-11-21T16:00:00Z")))
    }
}
