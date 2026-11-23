package app.kerkida

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.kerkida.alerts.AlertKind
import app.kerkida.alerts.AlertNotifier
import app.kerkida.alerts.SmartAlert
import app.kerkida.data.model.LeagueRef
import app.kerkida.data.model.Match
import app.kerkida.data.model.MatchStatus
import app.kerkida.data.model.Side
import app.kerkida.data.model.TeamRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AlertNotifierTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val manager get() = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val match = Match(
        id = "m",
        league = LeagueRef("l", "League"),
        home = TeamRef("h", "Home"),
        away = TeamRef("a", "Away"),
        kickoff = Instant.EPOCH,
        date = null,
        status = MatchStatus.LIVE,
        minute = 85,
        homeScore = 2,
        awayScore = 1,
    )
    private val alert = SmartAlert("m", AlertKind.LATE_GOAL, 85, "Scorer", Side.HOME, 2, 1)

    @Test
    fun theLockScreenVersionNeverContainsTheResult() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        AlertNotifier.show(app, match, alert)
        val posted = shadowOf(manager).allNotifications.single()
        assertEquals(Notification.VISIBILITY_PRIVATE, posted.visibility)
        assertTrue(posted.extras.getString(Notification.EXTRA_TITLE).orEmpty().contains("2–1"))
        val public = posted.publicVersion
        assertNotNull(public)
        val lockText = public.extras.getCharSequence(Notification.EXTRA_TITLE).toString() +
            public.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertFalse(lockText.contains("2–1"))
        assertFalse(lockText.contains("Scorer"))
        assertFalse(lockText.contains("Home"))
    }

    @Test
    fun nothingIsPostedWithoutPermission() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        AlertNotifier.show(app, match, alert)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }
}
