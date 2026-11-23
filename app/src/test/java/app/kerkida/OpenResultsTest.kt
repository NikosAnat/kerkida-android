package app.kerkida

import app.kerkida.data.greek.OpenGame
import app.kerkida.data.greek.OpenResultsParsing
import app.kerkida.data.model.MatchStatus
import app.kerkida.data.model.TeamRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class OpenResultsTest {
    private val round = """
        <div id="rencontres"><select>
        <option value="mancheid=632885">Round 9</option>
        <option value="mancheid=632886" selected>Round 10</option></select>
        <table class="table-style-2">
        <tr><td colspan="5"><h6 class="daterenc">20 November 2026</h6></td></tr>
        <tr><td class="tdcol-15">16h00</td><td class="tdcol-28 td-right"><a href="basketball-chalkida-results-identity-equ101003.html" title="Chalkida" class="nodecort">Chalkida</a></td><td class="tdcol-25 td-center"><b>69 - 73</b><br />(11-20, 23-22, 18-12, 17-19)</td><td class="tdcol-28"><img src="images/equipes/32141.png" alt="GS Lavrio BC" class="flag" /><b><a href="basketball-gs-lavrio-bc-results-identity-equ32141.html" title="GS Lavrio BC" class="nodecort">GS Lavrio BC</a></b></td></tr>
        <tr><td colspan="5"><h6 class="daterenc">28 November 2026</h6></td></tr>
        <tr><td class="tdcol-15">16h00</td><td class="tdcol-33 td-right"><a href="basketball-anagennisi-results-identity-equ101516.html" title="Anagennisi" class="nodecort">Anagennisi</a></td><td class="tdcol-15 td-center"> - </td><td class="tdcol-33"><a href="basketball-panathina-identity-equ642.html" title="Panathina&iuml;kos" class="nodecort">Panathina&iuml;kos</a></td></tr>
        </table></div>
    """

    private val table = """
        <div id="tab110" class="tab_content"><table>
        <tr><th></th><th>Team</th></tr>
        <tr class="promu1"><td class="tdcol-5">1</td><td class="tdcol-30"><img src="images/equipes/1563.jpg" alt="" class="flag" /><a href="volleyball-panathinaikos-athens-results-identity-equ1563.html" title="Panathinaikos Athens" class="nodecort">Panathinaikos Athens</a></td><td class="tdcol-10">10</td><td class="tdcol-5">5</td><td class="tdcol-5">5</td><td class="tdcol-5">0</td><td class="tdcol-5">15</td><td class="tdcol-5">4</td><td class="tdcol-10">+11</td></td></tr>
        </table></div><div id="tab120"><table>
        <tr><td class="tdcol-5">1</td><td class="tdcol-30"><a href="x-identity-equ9.html" title="Home only">Home only</a></td><td class="tdcol-10">1</td><td class="tdcol-5">1</td><td class="tdcol-5">1</td><td class="tdcol-5">0</td><td class="tdcol-5">3</td><td class="tdcol-5">0</td><td class="tdcol-10">+3</td></tr>
        </table></div>
    """

    @Test
    fun `played and coming games are read with greek time`() {
        val games = OpenResultsParsing.games(round, 10)
        assertEquals(2, games.size)
        assertEquals(Instant.parse("2026-11-20T14:00:00Z"), games[0].kickoff)
        assertEquals(69, games[0].homeScore)
        assertEquals(73, games[0].awayScore)
        assertEquals("GS Lavrio BC", games[0].away.name)
        assertEquals(LocalDate.parse("2026-11-28"), games[1].date)
        assertNull(games[1].homeScore)
        assertEquals("Panathinaïkos", games[1].away.name)
    }

    @Test
    fun `the round list and the current round are found`() {
        assertEquals(listOf(9, 10), OpenResultsParsing.rounds(round).map { it.number })
        assertEquals(10, OpenResultsParsing.currentRound(round))
    }

    @Test
    fun `only the overall table is read`() {
        val rows = OpenResultsParsing.table(table)
        assertEquals(1, rows.size)
        assertEquals(10, rows[0].points)
        assertEquals(5, rows[0].won)
        assertEquals(11, rows[0].goalDifference)
    }

    @Test
    fun `a score right after the start is not called final`() {
        val start = Instant.parse("2026-11-20T14:00:00Z")
        val game = OpenGame(1, null, start, TeamRef("a", "A"), TeamRef("b", "B"), 40, 38)
        assertEquals(MatchStatus.SCHEDULED, OpenResultsParsing.status(game, start.minusSeconds(60)))
        assertEquals(MatchStatus.UNKNOWN, OpenResultsParsing.status(game, start.plusSeconds(1800)))
        assertEquals(MatchStatus.FINISHED, OpenResultsParsing.status(game, start.plusSeconds(7200)))
    }
}
