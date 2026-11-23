package app.kerkida.assistant

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.kerkida.KerkidaApp
import app.kerkida.MainActivity
import app.kerkida.R
import app.kerkida.alerts.AlertNotifier
import app.kerkida.data.AppContainer
import app.kerkida.data.DataException
import app.kerkida.data.FavouriteKind
import app.kerkida.data.SettingsStore
import app.kerkida.data.model.Match
import app.kerkida.data.model.MatchStatus
import app.kerkida.data.model.Sport
import app.kerkida.features.Feature
import app.kerkida.nearby.NearbyLogic
import app.kerkida.nearby.Place
import kotlinx.coroutines.flow.lastOrNull
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** Decisions with no Android in them, so they can be tested. */
object AssistantLogic {
    /** Reminders go out once each, a day and two hours before the deadline. */
    fun deadlineReminder(deadline: Instant, now: Instant, sent: Set<String>): String? {
        val left = Duration.between(now, deadline)
        if (left.isNegative) return null
        val stage = when {
            left <= Duration.ofHours(2) -> "2h"
            left <= Duration.ofHours(24) -> "24h"
            else -> return null
        }
        val key = "$deadline|$stage"
        return key.takeIf { it !in sent }
    }

    fun countdown(deadline: Instant, now: Instant): Pair<Long, Long>? {
        val left = Duration.between(now, deadline)
        if (left.isNegative) return null
        return left.toDays() to left.minusDays(left.toDays()).toHours()
    }

    fun nearbyDue(now: ZonedDateTime) = now.hour >= 18

    fun digestDue(now: ZonedDateTime) = now.dayOfWeek == DayOfWeek.MONDAY && now.hour >= 9

    /** A result stays out of the summary while the person is keeping that match hidden. */
    fun digestLine(game: Match, hidden: Boolean): String {
        val score = if (!hidden && game.homeScore != null && game.awayScore != null) "${game.homeScore}–${game.awayScore}" else "–"
        return "${game.home.name} $score ${game.away.name}"
    }

    fun nextGame(games: List<Match>, now: Instant): Match? = games
        .filter { it.status == MatchStatus.SCHEDULED && it.kickoff?.isAfter(now) == true }
        .minByOrNull { it.kickoff!! }
}

object AssistantScheduler {
    private const val WORK = "kerkida-assistant"
    private const val KEY_SCHEDULED = "scheduled"

    private fun isNeeded(context: Context, container: AppContainer): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        val hasWidget = manager?.getAppWidgetIds(ComponentName(context, NextMatchWidget::class.java))?.isNotEmpty() == true
        return hasWidget || listOf(Feature.NEARBY_ALERTS, Feature.WEEKLY_DIGEST, Feature.FPL_DEADLINE).any(container.flags::isOn)
    }

    fun sync(context: Context) {
        val app = context.applicationContext
        val container = (app as KerkidaApp).container
        val prefs = app.getSharedPreferences("kerkida_assistant", Context.MODE_PRIVATE)
        if (!isNeeded(app, container)) {
            // Background work is only touched if it was ever started, so app launch stays light.
            if (prefs.getBoolean(KEY_SCHEDULED, false)) {
                WorkManager.getInstance(app).cancelUniqueWork(WORK)
                prefs.edit().putBoolean(KEY_SCHEDULED, false).apply()
            }
            return
        }
        val request = PeriodicWorkRequestBuilder<AssistantWorker>(1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        prefs.edit().putBoolean(KEY_SCHEDULED, true).apply()
    }

    fun refreshWidget(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "$WORK-widget",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<WidgetWorker>().build(),
        )
    }
}

class AssistantWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val prefs = context.getSharedPreferences("kerkida_assistant", Context.MODE_PRIVATE)

    override suspend fun doWork(): Result {
        val container = (applicationContext as KerkidaApp).container
        if (container.provider.value.info.isDemo) return Result.success()
        val now = ZonedDateTime.now()
        runStep { if (container.flags.isOn(Feature.FPL_DEADLINE)) deadline(container, now.toInstant()) }
        runStep { if (container.flags.isOn(Feature.NEARBY_ALERTS) && AssistantLogic.nearbyDue(now)) nearby(container, now) }
        runStep { if (container.flags.isOn(Feature.WEEKLY_DIGEST) && AssistantLogic.digestDue(now)) digest(container, now) }
        NextMatchWidget.update(applicationContext, container)
        return Result.success()
    }

    /** One step failing must not stop the others. The next hourly run tries again. */
    private inline fun runStep(step: () -> Unit) {
        try {
            step()
        } catch (_: DataException) {
            // Nothing to do until the next run.
        }
    }

    private suspend fun deadline(container: AppContainer, now: Instant) {
        val next = container.fantasyService.bootstrap().events.firstOrNull { it.isNext } ?: return
        val deadline = next.deadline?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return
        val sent = prefs.getStringSet(KEY_DEADLINES, emptySet()).orEmpty()
        val key = AssistantLogic.deadlineReminder(deadline, now, sent) ?: return
        val context = SettingsStore.localized(applicationContext)
        val time = deadline.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE HH:mm"))
        val doubts = applicationContext.getSharedPreferences("kerkida_fantasy", Context.MODE_PRIVATE).getInt("doubts", 0)
        val body = if (doubts > 0) context.getString(R.string.assistant_deadline_doubts, time, doubts) else context.getString(R.string.assistant_deadline_body, time)
        notify(key.hashCode(), context.getString(R.string.assistant_deadline_title), body)
        prefs.edit().putStringSet(KEY_DEADLINES, (sent.toList().takeLast(6) + key).toSet()).apply()
    }

    private suspend fun nearby(container: AppContainer, now: ZonedDateTime) {
        val tomorrow = now.toLocalDate().plusDays(1)
        if (prefs.getString(KEY_NEARBY, null) == tomorrow.toString()) return
        val saved = applicationContext.getSharedPreferences("kerkida_nearby", Context.MODE_PRIVATE)
        val name = saved.getString("name", null) ?: return
        val place = Place(name, saved.getFloat("lat", 0f).toDouble(), saved.getFloat("lng", 0f).toDouble())
        val games = dayGames(container, tomorrow)
        val found = NearbyLogic.filter(games, place, saved.getInt("radius", 50), now.toInstant(), container.stadiums::find)
        prefs.edit().putString(KEY_NEARBY, tomorrow.toString()).apply()
        if (found.isEmpty()) return
        val context = SettingsStore.localized(applicationContext)
        val zone = ZoneId.systemDefault()
        val lines = found.take(5).map { item ->
            val time = item.match.kickoff?.atZone(zone)?.format(DateTimeFormatter.ofPattern("HH:mm")).orEmpty()
            "$time ${item.match.home.name} – ${item.match.away.name}"
        }
        notify(NEARBY_ID, context.getString(R.string.assistant_nearby_title, found.size, place.name), lines.joinToString("\n"))
    }

    private suspend fun dayGames(container: AppContainer, date: LocalDate): List<Match> {
        val provider = container.provider.value
        val football = provider.matchDay(date).lastOrNull()?.matches.orEmpty()
        val basketball = if (Sport.BASKETBALL in provider.sports) provider.sportDay(Sport.BASKETBALL, date).lastOrNull()?.matches.orEmpty() else emptyList()
        return football + basketball
    }

    private suspend fun digest(container: AppContainer, now: ZonedDateTime) {
        val week = now.toLocalDate().toString()
        if (prefs.getString(KEY_DIGEST, null) == week) return
        val provider = container.provider.value
        val teams = container.favourites.favourites(provider.info.id).value.filter { it.favouriteKind == FavouriteKind.TEAM }.take(MAX_TEAMS)
        if (teams.isEmpty()) return
        val since = now.toInstant().minus(Duration.ofDays(7))
        val played = LinkedHashMap<String, String>()
        val coming = LinkedHashMap<String, Match>()
        teams.forEach { team ->
            try {
                val games = provider.teamMatches(team.id).data
                games.recent.filter { it.kickoff?.isAfter(since) == true }.forEach { game ->
                    val view = container.shield.view(game)
                    played[game.id] = AssistantLogic.digestLine(view.match, view.isShielded && !view.showEvents)
                }
                AssistantLogic.nextGame(games.upcoming, now.toInstant())?.let { coming[it.id] = it }
            } catch (_: DataException) {
                // A team that fails is left out of this week's summary.
            }
        }
        prefs.edit().putString(KEY_DIGEST, week).apply()
        if (played.isEmpty() && coming.isEmpty()) return
        val context = SettingsStore.localized(applicationContext)
        val zone = ZoneId.systemDefault()
        val next = coming.values.sortedBy { it.kickoff }.take(3).map { game ->
            val time = game.kickoff?.atZone(zone)?.format(DateTimeFormatter.ofPattern("EEE HH:mm")).orEmpty()
            "$time ${game.home.name} – ${game.away.name}"
        }
        val body = (played.values.take(6) + (if (next.isNotEmpty()) listOf(context.getString(R.string.assistant_digest_next)) + next else emptyList())).joinToString("\n")
        notify(DIGEST_ID, context.getString(R.string.assistant_digest_title), body)
    }

    @SuppressLint("MissingPermission") // Checked by canNotify first.
    private fun notify(id: Int, title: String, body: String) {
        if (!AlertNotifier.canNotify(applicationContext)) return
        val context = SettingsStore.localized(applicationContext)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.assistant_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val public = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.alerts_public))
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(body.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    private companion object {
        const val CHANNEL = "assistant"
        const val KEY_DEADLINES = "deadlines"
        const val KEY_NEARBY = "nearby_day"
        const val KEY_DIGEST = "digest_day"
        const val NEARBY_ID = 7101
        const val DIGEST_ID = 7102
        const val MAX_TEAMS = 8
    }
}

class WidgetWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        NextMatchWidget.update(applicationContext, (applicationContext as KerkidaApp).container)
        return Result.success()
    }
}

/** The next game of the person's teams. It shows no scores, so it can never spoil a result. */
class NextMatchWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        AssistantScheduler.refreshWidget(context)
    }

    override fun onEnabled(context: Context) {
        AssistantScheduler.sync(context)
        AssistantScheduler.refreshWidget(context)
    }

    override fun onDisabled(context: Context) = AssistantScheduler.sync(context)

    companion object {
        suspend fun update(base: Context, container: AppContainer) {
            val manager = AppWidgetManager.getInstance(base) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(base, NextMatchWidget::class.java))
            if (ids.isEmpty()) return
            val context = SettingsStore.localized(base)
            val provider = container.provider.value
            val now = Instant.now()
            val teams = container.favourites.favourites(provider.info.id).value.filter { it.favouriteKind == FavouriteKind.TEAM }.take(8)
            val next = teams.mapNotNull { team ->
                try {
                    AssistantLogic.nextGame(provider.teamMatches(team.id).data.upcoming, now)
                } catch (_: DataException) {
                    null
                }
            }.minByOrNull { it.kickoff!! }
            val views = RemoteViews(base.packageName, R.layout.widget_next_match)
            if (next != null) {
                val time = next.kickoff!!.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE d/M · HH:mm"))
                views.setTextViewText(R.id.widget_title, "${next.home.name} – ${next.away.name}")
                views.setTextViewText(R.id.widget_detail, "$time · ${next.league.name}")
            } else {
                views.setTextViewText(R.id.widget_title, context.getString(R.string.widget_empty_title))
                views.setTextViewText(R.id.widget_detail, context.getString(R.string.widget_empty_body))
            }
            val open = PendingIntent.getActivity(
                base,
                7200,
                Intent(base, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.widget_root, open)
            manager.updateAppWidget(ids, views)
        }
    }
}
