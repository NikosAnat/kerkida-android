package app.kerkida

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.kerkida.data.AppLanguage
import app.kerkida.data.ThemeMode
import app.kerkida.data.demo.DemoSportsDataProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

/** Opens every main screen in demo mode, without a device, and saves what it drew. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w392dp-h850dp-xxhdpi")
class ScreensTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<Context>() as KerkidaApp

    @Before
    fun useDemoData() {
        compose.runOnUiThread {
            app.container.settings.setProvider(DemoSportsDataProvider.ID)
            app.container.settings.setTheme(ThemeMode.LIGHT)
        }
        compose.waitForIdle()
    }

    private fun text(id: Int) = compose.activity.getString(id)

    private fun shot(name: String) {
        compose.waitForIdle()
        val folder = File("build/kerkida-screens").apply { mkdirs() }
        val root = compose.activity.findViewById<View>(android.R.id.content)
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { root.draw(Canvas(bitmap)) }
        File(folder, "$name.png").outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    private fun waitFor(label: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun walksThroughEveryScreenInGreekWithTheDemoLabelVisible() {
        val banner = text(R.string.demo_banner)
        assertTrue(banner.startsWith("ΔΟΚΙΜΑΣΤΙΚΑ"))
        compose.onNodeWithText(banner).assertIsDisplayed()

        waitFor("DEMO · Πρωτάθλημα Αιγαίου")
        shot("01-matches-light")

        compose.onAllNodesWithText("DEMO · Πρωτάθλημα Αιγαίου").onFirst().performClick()
        waitFor(text(R.string.league_tab_fixtures))
        compose.onNodeWithText(banner).assertIsDisplayed()
        shot("02-league-table")

        compose.onNodeWithText(text(R.string.league_tab_fixtures)).performClick()
        compose.waitForIdle()
        shot("03-league-fixtures")

        compose.onNode(hasText(text(R.string.status_finished)).or(hasText(text(R.string.status_scheduled))), useUnmergedTree = true)
        compose.onAllNodesWithText(
            compose.activity.getString(R.string.nav_matches),
        ).onFirst().performClick()
        waitFor("DEMO · Harbour League")

        compose.onAllNodesWithText("Saltmere Rovers").apply {
            if (fetchSemanticsNodes().isNotEmpty()) onFirst().performClick()
        }
        compose.waitForIdle()
        shot("04-after-open")

        compose.onAllNodesWithText(text(R.string.nav_search)).onFirst().performClick()
        waitFor(text(R.string.search_idle_title))
        shot("05-search")

        compose.onAllNodesWithText(text(R.string.nav_favourites)).onFirst().performClick()
        waitFor(text(R.string.favourites_empty_title))
        shot("06-favourites-empty")

        compose.onAllNodesWithText(text(R.string.nav_leagues)).onFirst().performClick()
        waitFor(text(R.string.leagues_subtitle))
        shot("07-leagues")
    }

    @Test
    fun matchTeamAndDarkThemeRender() {
        compose.runOnUiThread { app.container.settings.setTheme(ThemeMode.DARK) }
        waitFor("DEMO · Harbour League")
        shot("08-matches-dark")

        val demo = DemoSportsDataProvider()
        val match = runBlocking {
            demo.matchDay(LocalDate.now().minusDays(1)).first()
        }.matches.first()

        compose.onNode(hasContentDescription(text(R.string.action_pick_date))).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.date_yesterday)).performClick()
        waitFor(match.home.name)
        compose.onAllNodesWithText(match.home.name).onFirst().performClick()
        waitFor(text(R.string.match_tab_events))
        compose.waitForIdle()
        shot("09-match-events-dark")

        compose.onNodeWithText(text(R.string.match_tab_table)).performClick()
        compose.waitForIdle()
        shot("10-match-table-dark")

        compose.onNodeWithText(text(R.string.match_tab_info)).performClick()
        compose.waitForIdle()
        shot("11-match-info-dark")

        compose.onNodeWithText(text(R.string.match_tab_table)).performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText(match.home.name).onFirst().performClick()
        waitFor(text(R.string.club_tab_about))
        compose.onNodeWithText(text(R.string.club_tab_about)).performClick()
        waitFor(text(R.string.team_info))
        shot("12-team-dark")
    }

    @Test
    fun englishFallbackAndSettingsRender() {
        compose.runOnUiThread { app.container.settings.setLanguage(AppLanguage.ENGLISH) }
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        waitFor("DEMO data".uppercase().replace("DATA", "DATA · invented matches, not real results"))
        shot("13-matches-english")
        compose.onNode(hasContentDescription("Settings")).performClick()
        waitFor("Data source")
        shot("14-settings-english")
        compose.runOnUiThread { app.container.settings.setLanguage(AppLanguage.GREEK) }
    }
}
