package app.kerkida

import app.kerkida.data.CatalogLeague
import app.kerkida.data.LeagueCatalog
import app.kerkida.data.LeagueChoiceStore
import app.kerkida.data.model.LeagueRef
import app.kerkida.data.model.Match
import app.kerkida.data.model.MatchStatus
import app.kerkida.data.model.Sport
import app.kerkida.data.model.TeamRef
import app.kerkida.nearby.NearbyLogic
import app.kerkida.nearby.Place
import app.kerkida.stadium.Stadium
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class CatalogNearbyTest {
    private class MemoryChoices(var saved: Set<String>? = null) : LeagueChoiceStore {
        override fun load() = saved
        override fun save(ids: Set<String>) { saved = ids }
    }

    private val rows = listOf(
        CatalogLeague("gre.1", "Greek Super League", "FOOTBALL", "GR"),
        CatalogLeague("esp.2", "LaLiga 2", "FOOTBALL", "ES"),
        CatalogLeague("rugby/180659", "Six Nations", "RUGBY"),
        CatalogLeague("new/thing", "Unknown", "CURLING"),
    )

    @Test
    fun startsWithTheDefaultLeaguesAndRemembersChoices() {
        val store = MemoryChoices()
        val catalog = LeagueCatalog(store) { rows }
        assertTrue(catalog.isSelected("gre.1"))
        assertFalse(catalog.isSelected("esp.2"))
        catalog.toggle("esp.2")
        catalog.toggle("gre.1")
        val reopened = LeagueCatalog(store) { rows }
        assertTrue(reopened.isSelected("esp.2"))
        assertFalse(reopened.isSelected("gre.1"))
        reopened.reset()
        assertEquals(LeagueCatalog.Defaults, reopened.selected.value)
    }

    @Test
    fun aWholeCountryCanBeSwitchedAtOnce() {
        val catalog = LeagueCatalog(MemoryChoices(emptySet())) { rows }
        catalog.setAll(listOf("gre.1", "esp.2"), true)
        assertEquals(setOf("gre.1", "esp.2"), catalog.selected.value)
        catalog.setAll(listOf("esp.2"), false)
        assertEquals(setOf("gre.1"), catalog.selected.value)
    }

    @Test
    fun readsEachLeaguesSport() {
        val catalog = LeagueCatalog(MemoryChoices()) { rows }
        assertEquals(Sport.RUGBY, catalog.find("rugby/180659")?.sport)
        assertEquals(Sport.FOOTBALL, catalog.find("new/thing")?.sport)
    }

    @Test
    fun measuresDistanceBetweenCities() {
        val km = NearbyLogic.distanceKm(37.9838, 23.7275, 40.6401, 22.9444)
        assertTrue("Athens to Thessaloniki is about 300 km, got $km", km in 295.0..310.0)
        assertEquals(0.0, NearbyLogic.distanceKm(37.0, 23.0, 37.0, 23.0), 0.001)
    }

    private val now = Instant.parse("2026-11-22T12:00:00Z")

    private fun game(id: String, venue: String, hours: Long, status: MatchStatus = MatchStatus.SCHEDULED) = Match(
        id = id,
        league = LeagueRef("gre.1", "League"),
        home = TeamRef("h", "H"),
        away = TeamRef("a", "A"),
        kickoff = now.plusSeconds(hours * 3600),
        date = null,
        status = status,
        venue = venue,
    )

    private val grounds = mapOf(
        "piraeus" to Stadium("Karaiskakis", lat = 37.946, lng = 23.664),
        "toumba" to Stadium("Toumba", lat = 40.613, lng = 22.972),
        "unknown" to Stadium("No coordinates"),
    )

    @Test
    fun keepsOnlyUpcomingGamesInsideTheRadiusSoonestFirst() {
        val athens = Place("Athens", 37.9838, 23.7275)
        val games = listOf(
            game("later", "piraeus", 48),
            game("soon", "piraeus", 2),
            game("far", "toumba", 3),
            game("no-location", "unknown", 1),
            game("done", "piraeus", -1, MatchStatus.FINISHED),
            game("off", "piraeus", 5, MatchStatus.POSTPONED),
        )
        val found = NearbyLogic.filter(games, athens, 50, now) { grounds[it.venue] }
        assertEquals(listOf("soon", "later"), found.map { it.match.id })
        assertTrue(found[0].km < 15)
        assertEquals(3, NearbyLogic.filter(games, athens, 500, now) { grounds[it.venue] }.size)
    }
}
