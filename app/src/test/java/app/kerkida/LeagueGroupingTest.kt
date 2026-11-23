package app.kerkida

import app.kerkida.data.CatalogLeague
import app.kerkida.data.LeagueGrouping
import app.kerkida.data.model.Sport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class LeagueGroupingTest {
    private val names = mapOf("GR" to "Ελλάδα", "US" to "ΗΠΑ", "DE" to "Γερμανία", "ES" to "Ισπανία")

    private fun grouped(vararg leagues: CatalogLeague) =
        LeagueGrouping.group(leagues.toList(), Locale.forLanguageTag("el"), "Διεθνείς") { names[it] ?: it }

    @Test
    fun `sports come first and countries sit inside them`() {
        val groups = grouped(
            CatalogLeague("basketball/nba", "NBA", "BASKETBALL"),
            CatalogLeague("esp.1", "La Liga", "FOOTBALL", "ES"),
            CatalogLeague("gre.1", "Super League", "FOOTBALL", "GR"),
            CatalogLeague("grl.gbl", "GBL", "BASKETBALL", "GR"),
        )
        assertEquals(listOf(Sport.FOOTBALL, Sport.BASKETBALL), groups.map { it.sport })
        assertEquals(listOf("Ελλάδα", "Ισπανία"), groups[0].countries.map { it.title })
        assertEquals(listOf("Ελλάδα", "ΗΠΑ"), groups[1].countries.map { it.title })
    }

    @Test
    fun `greece leads, then international, then countries in greek order`() {
        val groups = grouped(
            CatalogLeague("esp.1", "La Liga", "FOOTBALL", "ES"),
            CatalogLeague("ger.1", "Bundesliga", "FOOTBALL", "DE"),
            CatalogLeague("uefa.champions", "Champions League", "FOOTBALL"),
            CatalogLeague("gre.1", "Super League", "FOOTBALL", "GR"),
        )
        assertEquals(listOf("Ελλάδα", "Διεθνείς", "Γερμανία", "Ισπανία"), groups[0].countries.map { it.title })
    }

    @Test
    fun `domestic leagues without a catalog country still get one`() {
        assertEquals("US", LeagueGrouping.countryOf(CatalogLeague("basketball/nba", "NBA", "BASKETBALL")))
        assertNull(LeagueGrouping.countryOf(CatalogLeague("basketball/euroleague", "EuroLeague", "BASKETBALL")))
    }

    @Test
    fun `numbered groups keep their number order`() {
        val groups = grouped(
            CatalogLeague("grl.5966", "Όμιλος 10", "FOOTBALL", "GR"),
            CatalogLeague("grl.5843", "Όμιλος 2", "FOOTBALL", "GR"),
        )
        assertEquals(listOf("grl.5843", "grl.5966"), groups[0].leagues.map { it.id })
    }

    @Test
    fun `each country runs from the top division down`() {
        val groups = grouped(
            CatalogLeague("grl.5830", "Κύπελλο Ελλάδας", "FOOTBALL", "GR"),
            CatalogLeague("grl.ach.a1", "ΕΠΣ Αχαΐας · Α' 1ος Όμιλος", "FOOTBALL", "GR"),
            CatalogLeague("grl.5847", "Γ' Εθνική · 6ος Όμιλος", "FOOTBALL", "GR"),
            CatalogLeague("grl.4640", "Super League 2", "FOOTBALL", "GR"),
            CatalogLeague("gre.1", "Greek Super League", "FOOTBALL", "GR"),
        )
        assertEquals(listOf("gre.1", "grl.4640", "grl.5847", "grl.ach.a1", "grl.5830"), groups[0].leagues.map { it.id })
    }

    @Test
    fun `men's divisions and cups come before women, england before scotland`() {
        val groups = grouped(
            CatalogLeague("sco.1", "Scottish Premiership", "FOOTBALL", "GB"),
            CatalogLeague("eng.w.1", "English Women's Super League", "FOOTBALL", "GB"),
            CatalogLeague("eng.fa", "English FA Cup", "FOOTBALL", "GB"),
            CatalogLeague("eng.2", "English League Championship", "FOOTBALL", "GB"),
            CatalogLeague("eng.1", "English Premier League", "FOOTBALL", "GB"),
        )
        assertEquals(listOf("eng.1", "eng.2", "eng.fa", "eng.w.1", "sco.1"), groups[0].leagues.map { it.id })
    }

    @Test
    fun `the same country in two sports stays apart`() {
        val groups = grouped(
            CatalogLeague("gre.1", "Super League", "FOOTBALL", "GR"),
            CatalogLeague("grl.gbl", "GBL", "BASKETBALL", "GR"),
        )
        assertEquals(listOf("FOOTBALL/GR", "BASKETBALL/GR"), groups.flatMap { it.countries }.map { it.key })
    }
}
