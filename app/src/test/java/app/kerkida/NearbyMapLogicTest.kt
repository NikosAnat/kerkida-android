package app.kerkida

import app.kerkida.data.model.LeagueRef
import app.kerkida.data.model.Match
import app.kerkida.data.model.MatchStatus
import app.kerkida.data.model.TeamRef
import app.kerkida.nearby.NearbyLogic
import app.kerkida.nearby.NearbyMapLogic
import app.kerkida.nearby.NearbyMatch
import app.kerkida.stadium.Stadium
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class NearbyMapLogicTest {
    private fun game(id: String, ground: String, lat: Double?, lng: Double?, hours: Long) = NearbyMatch(
        Match(
            id = id,
            league = LeagueRef("l", "L"),
            home = TeamRef("h", "H"),
            away = TeamRef("a", "A"),
            kickoff = Instant.parse("2026-11-23T12:00:00Z").plusSeconds(hours * 3600),
            date = null,
            status = MatchStatus.SCHEDULED,
        ),
        Stadium(name = ground, city = "C", country = "Greece", lat = lat, lng = lng),
        5.0,
    )

    @Test
    fun gamesAtOneGroundShareAPinSoonestFirst() {
        val pins = NearbyMapLogic.pins(
            listOf(
                game("later", "OAKA", 38.0361, 23.7874, 48),
                game("sooner", "Spyros Louis", 38.03612, 23.78741, 2),
                game("other", "Karaiskakis", 37.9464, 23.6645, 5),
            ),
        )
        assertEquals(2, pins.size)
        val shared = pins.first { it.games.size == 2 }
        assertEquals(listOf("sooner", "later"), shared.games.map { it.match.id })
        assertEquals("Spyros Louis", shared.name)
    }

    @Test
    fun aGroundWithoutCoordinatesGetsNoPin() {
        assertEquals(0, NearbyMapLogic.pins(listOf(game("x", "Unknown", null, null, 1))).size)
    }

    @Test
    fun theRadiusRingIsClosedAndAtTheRightDistance() {
        val ring = NearbyMapLogic.circle(38.2466, 21.7346, 50)
        assertEquals(ring.first().first, ring.last().first, 1e-9)
        assertEquals(ring.first().second, ring.last().second, 1e-9)
        ring.forEach { (lat, lng) -> assertEquals(50.0, NearbyLogic.distanceKm(38.2466, 21.7346, lat, lng), 0.01) }
    }
}
