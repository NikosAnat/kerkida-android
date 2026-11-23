package app.kerkida

import app.kerkida.data.greek.GreekEvent
import app.kerkida.data.greek.GreekLeague
import app.kerkida.data.greek.GreekLowerLogic
import app.kerkida.data.greek.GreekLowerSource
import app.kerkida.data.greek.GreekPack
import app.kerkida.data.greek.GreekTeam
import app.kerkida.data.net.CachedHttpClient
import app.kerkida.data.net.RateLimiter
import app.kerkida.data.net.ResponseCache
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class MergedTeamsTest {
    private val pack = GreekPack(
        season = "2026-2027",
        leagues = listOf(GreekLeague("grl.5830", 5830, "Κύπελλο Ελλάδας")),
        teams = listOf(
            GreekTeam("133754", "Olympiacos", 5830),
            GreekTeam("133743", "OFI", 5830),
            GreekTeam("140000", "Panachaiki", 5847),
        ),
        events = listOf(
            GreekEvent("1", 5830, 1, "133754", "133743", "2026-12-02T18:00:00", "2026-12-02"),
            GreekEvent("2", 5830, 1, "140000", "133754", "2026-12-09T18:00:00", "2026-12-09"),
        ),
    )

    private val source = GreekLowerSource(
        CachedHttpClient(OkHttpClient(), ResponseCache(Files.createTempDirectory("merged").toFile()), RateLimiter(minGapMs = 1, maxPerMinute = 1000)),
        { pack },
        { Instant.parse("2026-11-22T10:00:00Z") },
        { ZoneOffset.UTC },
    )

    @Test
    fun `old cup links lead to the league profile`() {
        assertEquals("435", GreekLowerLogic.canonicalTeam("grl:133754"))
        assertEquals("grl:140000", GreekLowerLogic.canonicalTeam("grl:140000"))
    }

    @Test
    fun `a greek club in europe has one basketball profile`() {
        assertEquals("grl:gbl-00000002", GreekLowerLogic.EuropeanClubs["basketball/euroleague:OLY"])
        assertEquals("basketball/euroleague:OLY", GreekLowerLogic.europeanId("grl:gbl-00000002"))
        assertEquals(4, GreekLowerLogic.EuropeanClubs.values.toSet().size)
    }

    @Test
    fun `every merged club has its own league profile`() {
        assertEquals(12, GreekLowerLogic.MergedTeams.size)
        assertEquals(12, GreekLowerLogic.MergedTeams.values.toSet().size)
    }

    @Test
    fun `a cup game opens the same profile as the league`() {
        val game = source.bundledDay(listOf("grl.5830"), LocalDate.parse("2026-12-02")).single()
        assertEquals("435", game.home.id)
        assertEquals("1010", game.away.id)
        assertEquals("OFI Crete", game.away.name)
    }

    @Test
    fun `search gives the league profile and leaves other clubs alone`() {
        val club = source.searchTeams("olympiacos").single()
        assertEquals("435", club.id)
        assertEquals("gre.1", club.league?.id)
        assertEquals(listOf("grl:140000"), source.searchTeams("panachaiki").map { it.id })
    }

    @Test
    fun `the league profile finds its cup games`() = runBlocking {
        val games = source.teamMatches("435", false).data
        assertEquals(2, games.upcoming.size)
    }
}
