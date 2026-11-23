package app.kerkida.planner

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.kerkida.R
import app.kerkida.alerts.AlertNotifier
import app.kerkida.data.SettingsStore
import app.kerkida.data.model.Match
import app.kerkida.stadium.Stadium
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Directions and a "time to leave" reminder. The position stays on the phone. */
object GroundTrip {
    private const val CHANNEL = "ground_trips"

    fun directions(stadium: Stadium): Intent? {
        val lat = stadium.lat ?: return null
        val lng = stadium.lng ?: return null
        return Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lng"))
    }

    /** Returns when the reminder will fire, or null when that moment has already passed. */
    fun remind(context: Context, match: Match, stadium: Stadium, km: Double, now: Instant = Instant.now()): Instant? {
        val kickoff = match.kickoff ?: return null
        val leave = WeekendLogic.leaveAt(kickoff, km)
        val wait = Duration.between(now, leave)
        if (wait.isNegative) return null
        val request = OneTimeWorkRequestBuilder<LeaveWorker>()
            .setInitialDelay(wait.toMinutes(), TimeUnit.MINUTES)
            .setInputData(
                workDataOf(
                    "title" to "${match.home.name} – ${match.away.name}",
                    "ground" to stadium.name,
                    "lat" to (stadium.lat ?: 0.0),
                    "lng" to (stadium.lng ?: 0.0),
                    "id" to match.id,
                ),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("leave-${match.id}", ExistingWorkPolicy.REPLACE, request)
        return leave
    }

    @SuppressLint("MissingPermission") // Checked by canNotify first.
    fun show(base: Context, id: String, title: String, ground: String, lat: Double, lng: Double) {
        if (!AlertNotifier.canNotify(base)) return
        val context = SettingsStore.localized(base)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.trip_channel), NotificationManager.IMPORTANCE_HIGH),
        )
        val open = PendingIntent.getActivity(
            context,
            id.hashCode(),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lng")),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.trip_leave_now))
            .setContentText("$title · $ground")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify("leave-$id".hashCode(), notification) }
    }
}

class LeaveWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        GroundTrip.show(
            applicationContext,
            inputData.getString("id").orEmpty(),
            inputData.getString("title").orEmpty(),
            inputData.getString("ground").orEmpty(),
            inputData.getDouble("lat", 0.0),
            inputData.getDouble("lng", 0.0),
        )
        return Result.success()
    }
}
