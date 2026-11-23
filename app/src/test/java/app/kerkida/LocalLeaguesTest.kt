package app.kerkida

import app.kerkida.data.greek.AchaiaParsing
import app.kerkida.data.greek.LasithiParsing
import app.kerkida.data.model.TeamRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class LocalLeaguesTest {
    private val achaiaRows = """<table class="tv-table"><tbody>
<tr><td class="tv-date">27.09.2026<span class="tv-time">17.00</span></td> <td>1</td><td>ΑΧΑΙΚΗ</td> <td class="tv-score">4 - 0</td> <td>ΑΟ ΤΡΙΤΑΙΑΣ</td><td>ΔΗΜΟΤΙΚΟ ΣΤΑΔΙΟ ΚΑΤΩ ΑΧΑΙΑΣ</td> <td><a href="game.asp?game_number=1005">Λεπτομέρειες</a></td></tr>
<tr><td class="tv-date">29.11.2026<span class="tv-time">15.00</span></td> <td>10</td><td>ΘΥΕΛΛΑ ΠΑΤΡΩΝ</td> <td class="tv-score">-</td> <td>ΑΧΑΙΚΗ</td><td>ΓΗΠΕΔΟ</td> <td><a href="game.asp?game_number=1099">Λεπτομέρειες</a></td></tr>
</tbody></table>"""

    private val report = "Συνεχίστηκε με τα ακόλουθα αποτελέσματα: Νίκη-ΟΦΙ 1-1 ΑΟ Βραχασίου-ΑΟΑΝ Β 5-0 ΑΣΙ-Ίτανος Παλαικάστρου 0-2 " +
        "Η βαθμολογία 1.ΑΟ Βραχασίου 7-0-1 (36-4) 21 2.ΟΦΙ 6-2-0 (35-4) 20 7.Ίτανος Παλαικάστρου (7-28) 3-0-5 9 " +
        "Η επόμενη (9η) αγωνιστική Ίτανος Παλαικάστρου-Κόρφος Ελούντας ΟΦΙ-ΑΟΑΝ Β"

    @Test
    fun `achaia season rows are read with and without a score`() {
        val games = AchaiaParsing.games(achaiaRows)
        assertEquals(2, games.size)
        assertEquals("1005", games[0].id)
        assertEquals(4, games[0].homeScore)
        assertEquals("ΔΗΜΟΤΙΚΟ ΣΤΑΔΙΟ ΚΑΤΩ ΑΧΑΙΑΣ", games[0].venue)
        assertEquals(Instant.parse("2026-09-27T14:00:00Z"), games[0].kickoff)
        assertNull(games[1].homeScore)
        assertEquals(10, games[1].round)
    }

    @Test
    fun `a lasithi report gives results and the next round`() {
        val games = LasithiParsing.report("Η εικόνα της 8ης αγωνιστικής στην Α’ ΕΠΣΛ", report)
        val draw = games.first { it.home == "Νίκη Σητείας" }
        assertEquals("ΟΦ Ιεράπετρας", draw.away)
        assertEquals(1, draw.homeScore)
        assertEquals(8, draw.round)
        val next = games.first { it.home == "ΟΦ Ιεράπετρας" && it.homeScore == null }
        assertEquals("ΑΟΑΝ Β", next.away)
        assertEquals(9, next.round)
    }

    @Test
    fun `a lasithi table reads both ways the paper prints a row`() {
        val rows = LasithiParsing.table(report) { TeamRef(it, it) }
        assertEquals(3, rows.size)
        assertEquals("ΟΦ Ιεράπετρας", rows[1].team.name)
        assertEquals(20, rows[1].points)
        assertEquals(35, rows[1].goalsFor)
        assertEquals(7, rows[2].goalsFor)
        assertEquals(9, rows[2].points)
    }

    @Test
    fun `a preview gets its real kick off`() {
        val times = LasithiParsing.kickoffs("Κυριακή, 15.00 ΟΦΙ-ΑΟΑΝ Β και άλλα", LocalDate.parse("2026-11-21"))
        assertEquals(Instant.parse("2026-11-22T13:00:00Z"), times["ΟΦ Ιεράπετρας|ΑΟΑΝ Β"])
        assertTrue(LasithiParsing.Clubs.contains("ΟΦΙ"))
    }
}
