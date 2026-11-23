package app.kerkida

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.kerkida.data.model.MatchStatus
import app.kerkida.data.net.CachedHttpClient
import app.kerkida.data.net.RateLimiter
import app.kerkida.data.net.ResponseCache
import app.kerkida.data.thesportsdb.TheSportsDbProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

/**
 * Calls the real free API through the app's limiter. Skipped unless KERKIDA_LIVE_CHECK=1,
 * so normal builds and tests never use the network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveProviderCheck {
    @Test
    fun realDataFlowsThroughEveryProviderCall() = runBlocking {
        assumeTrue(System.getenv("KERKIDA_LIVE_CHECK") == "1")
        // Lets any block from an earlier run expire, so this run measures the app's own pacing.
        delay(150_000)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val report = StringBuilder()
        val client = CachedHttpClient(
            http = OkHttpClient(),
            cache = ResponseCache(File(context.cacheDir, "live-check")),
            limiter = RateLimiter(),
        )
        val provider = TheSportsDbProvider(context, client)

        val day = provider.matchDay(LocalDate.now()).last()
        report.appendLine(
            "today: ${day.matches.size} matches, ${day.matches.map { it.league.id }.toSet().size} leagues, " +
                "loaded ${day.loadedSources}/${day.totalSources}, failed ${day.failedSources}, error ${day.firstError}",
        )
        report.appendLine("statuses: ${day.matches.groupingBy { it.status }.eachCount()}")
        assertTrue(day.matches.isNotEmpty())

        val table = provider.standings("4336", null).data
        report.appendLine("greek table: ${table.rows.size} rows, partial ${table.isPartial}, leader ${table.rows.first().team.name}")
        assertEquals(1, table.rows.first().rank)

        val fixtures = provider.fixtures("4328", null, null).data
        report.appendLine("epl round ${fixtures.round}: ${fixtures.matches.size} matches")
        assertTrue(fixtures.matches.isNotEmpty())

        val finished = fixtures.matches.firstOrNull { it.status == MatchStatus.FINISHED }
            ?: provider.fixtures("4328", null, (fixtures.round ?: 2) - 1).data.matches.first()
        val details = provider.matchDetails(finished.id).data
        report.appendLine(
            "match ${details.match.home.name} ${details.match.homeScore}-${details.match.awayScore} " +
                "${details.match.away.name}: ${details.match.status}, ${details.events.size} events, " +
                "maybe incomplete ${details.eventsMayBeIncomplete}, table ${details.standings?.rows?.size}",
        )
        assertEquals(finished.id, details.match.id)

        val team = provider.team(finished.home.id).data
        val teamMatches = provider.teamMatches(finished.home.id).data
        report.appendLine("team ${team.name}: ${teamMatches.recent.size} recent, ${teamMatches.upcoming.size} upcoming")
        assertEquals(finished.home.id, team.id)

        val greek = provider.search("Ολυμπιακός").data
        report.appendLine("greek search: ${greek.teams.map { it.name }}")
        assertTrue(greek.teams.any { it.name.contains("Olympiacos") })

        File("build/kerkida-live-check.txt").writeText(report.toString())
    }
}
