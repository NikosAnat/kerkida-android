package app.kerkida.alerts

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.kerkida.KerkidaApp
import app.kerkida.MainActivity
import app.kerkida.R
import app.kerkida.data.DataException
import app.kerkida.data.FavouriteKind
import app.kerkida.data.SettingsStore
import app.kerkida.data.model.Match
import app.kerkida.data.model.MatchStatus
import app.kerkida.data.model.Side
import app.kerkida.data.model.isInPlay
import app.kerkida.features.Feature
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

class AlertStore(context: Context) {
    private val prefs = context.getSharedPreferences("kerkida_alerts", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _rules = MutableStateFlow(
        prefs.getString(KEY_RULES, null)
            ?.let { runCatching { json.decodeFromString<Map<String, TeamAlertRules>>(it) }.getOrNull() }
            .orEmpty(),
    )
    val rules: StateFlow<Map<String, TeamAlertRules>> = _rules.asStateFlow()

    fun rulesFor(teamId: String): TeamAlertRules = _rules.value[teamId] ?: TeamAlertRules()

    fun setRules(teamId: String, rules: TeamAlertRules) {
        _rules.value = _rules.value + (teamId to rules)
        prefs.edit().putString(KEY_RULES, json.encodeToString(_rules.value)).apply()
    }

    fun fantasyPlayers(): Set<String> = prefs.getStringSet(KEY_FANTASY, emptySet()).orEmpty().toSet()

    fun saveFantasyPlayers(names: Set<String>) {
        prefs.edit().putStringSet(KEY_FANTASY, names).apply()
    }

    @Synchronized
    fun memories(): MutableMap<String, AlertMemory> = prefs.getString(KEY_MEMORY, null)
        ?.let { runCatching { json.decodeFromString<Map<String, AlertMemory>>(it) }.getOrNull() }
        .orEmpty()
        .toMutableMap()

    /** Only matches still being followed are kept, so this cannot grow forever. */
    @Synchronized
    fun saveMemories(memories: Map<String, AlertMemory>) {
        prefs.edit().putString(KEY_MEMORY, json.encodeToString(memories)).apply()
    }

    private companion object {
        const val KEY_RULES = "rules"
        const val KEY_MEMORY = "memory"
        const val KEY_FANTASY = "fantasy_players"
    }
}

object AlertScheduler {
    private const val WORK = "kerkida-smart-alerts"

    private fun request(delayMinutes: Long) = OneTimeWorkRequestBuilder<AlertWorker>()
        .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .build()

    fun start(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, request(0))
    }

    fun next(context: Context, delayMinutes: Long) {
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request(delayMinutes))
    }

    fun stop(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }
}

/**
 * Checks the person's teams in the background. Android decides the exact timing, and there is no
 * push server, so an alert can arrive a few minutes after the event.
 */
class AlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as KerkidaApp).container
        if (!container.flags.isOn(Feature.SMART_ALERTS)) return Result.success()
        val provider = container.provider.value
        if (provider.info.isDemo) return finish(IDLE_MINUTES)

        val teamIds = container.favourites.favourites(provider.info.id).value
            .filter { it.favouriteKind == FavouriteKind.TEAM }
            .map { it.id }
            .take(MAX_TEAMS)
        if (teamIds.isEmpty()) return finish(IDLE_MINUTES)

        val now = Instant.now()
        val followed = LinkedHashMap<String, Match>()
        var nearest: Duration? = null
        for (teamId in teamIds) {
            try {
                val games = provider.teamMatches(teamId, refresh = true).data
                (games.recent + games.upcoming).forEach { game ->
                    val kickoff = game.kickoff ?: return@forEach
                    val untilKickoff = Duration.between(now, kickoff)
                    if (game.status == MatchStatus.SCHEDULED && !untilKickoff.isNegative) {
                        nearest = nearest?.let { minOf(it, untilKickoff) } ?: untilKickoff
                    }
                    val started = untilKickoff <= Duration.ofMinutes(5)
                    val recent = Duration.between(kickoff, now) < Duration.ofHours(3)
                    if (game.status.isInPlay || (game.status == MatchStatus.SCHEDULED && started && recent)) {
                        followed[game.id] = game
                    }
                }
            } catch (_: DataException) {
                // One team failing must not stop the others. The next run tries again.
            }
        }

        val previous = container.alerts.memories()
        val updated = HashMap<String, AlertMemory>()
        val fantasyPlayers = container.alerts.fantasyPlayers()
        for (id in followed.keys) {
            try {
                val details = provider.matchDetails(id, refresh = true).data
                val match = details.match
                val view = container.shield.view(match, details.events)
                val memory = previous[id]
                when {
                    // Still syncing: keep the old memory, so the event is announced once it is visible.
                    view.waitingForSync -> memory?.let { updated[id] = it }
                    // A hidden match stays silent, and revealing it later does not replay old events.
                    view.isShielded && !view.showEvents ->
                        updated[id] = SmartAlertEngine.remember(memory, match, details.events)
                    else -> {
                        val rules = mergedRules(container.alerts, match, teamIds)
                        val result = SmartAlertEngine.detect(memory, view.match, view.events, rules, fantasyPlayers)
                        result.alerts.forEach { AlertNotifier.show(applicationContext, view.match, it) }
                        updated[id] = result.memory
                    }
                }
            } catch (_: DataException) {
                previous[id]?.let { updated[id] = it }
            }
        }
        container.alerts.saveMemories(updated)
        notifyHighlights(container, teamIds)

        val soon = nearest?.let { it <= Duration.ofMinutes(IDLE_MINUTES) } == true
        return finish(if (followed.isNotEmpty() || soon) LIVE_MINUTES else IDLE_MINUTES)
    }

    /** "The highlights are out" for the person's teams. A hidden match gets a line with no result. */
    private suspend fun notifyHighlights(container: app.kerkida.data.AppContainer, teamIds: List<String>) {
        val repository = container.highlights
        if (!repository.alertsOn.value) return
        val provider = container.provider.value
        val recent = teamIds.flatMap { id ->
            try {
                provider.teamMatches(id).data.recent.take(2)
            } catch (_: DataException) {
                emptyList()
            }
        }.distinctBy { it.id }
        repository.refresh()
        val seen = repository.seenIds()
        val linked = app.kerkida.highlights.HighlightLogic.link(repository.videos.value, recent)
            .filter { it.match != null }
        // The first run only records what already exists.
        if (seen.isNotEmpty()) {
            linked.filter { it.video.videoId !in seen }
                .distinctBy { it.match?.id }
                .take(3)
                .forEach { AlertNotifier.showHighlights(applicationContext, it.match!!, it.video.url) }
        }
        repository.markSeen(repository.videos.value.map { it.videoId }.toSet())
    }

    private fun mergedRules(store: AlertStore, match: Match, teamIds: List<String>): TeamAlertRules {
        val rules = listOf(match.home.id, match.away.id).filter { it in teamIds }.map(store::rulesFor)
        return TeamAlertRules(
            redCards = rules.any { it.redCards },
            lateGoals = rules.any { it.lateGoals },
            comebacks = rules.any { it.comebacks },
            fantasyPlayers = rules.any { it.fantasyPlayers },
        )
    }

    private fun finish(delayMinutes: Long): Result {
        AlertScheduler.next(applicationContext, delayMinutes)
        return Result.success()
    }

    private companion object {
        const val MAX_TEAMS = 8
        const val LIVE_MINUTES = 3L
        const val IDLE_MINUTES = 30L
    }
}

object AlertNotifier {
    private const val CHANNEL = "smart_alerts"

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** The title names only the teams, so it is safe even for a match the person has not seen. */
    @SuppressLint("MissingPermission") // Checked by canNotify first.
    fun showHighlights(base: Context, match: Match, url: String) {
        if (!canNotify(base)) return
        val context = SettingsStore.localized(base)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.alerts_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            context,
            url.hashCode(),
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.highlights_out))
            .setContentText("${match.home.name} – ${match.away.name}")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(url.hashCode(), notification) }
    }

    @SuppressLint("MissingPermission") // Checked by canNotify first.
    fun show(base: Context, match: Match, alert: SmartAlert) {
        if (!canNotify(base)) return
        val context = SettingsStore.localized(base)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.alerts_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val score = if (alert.homeScore != null && alert.awayScore != null) "${alert.homeScore}–${alert.awayScore}" else "–"
        val title = "${match.home.name} $score ${match.away.name}"
        val minute = alert.minute?.let { " $it′" }.orEmpty()
        val team = when (alert.side) {
            Side.HOME -> match.home.name
            Side.AWAY -> match.away.name
            null -> ""
        }
        val who = alert.player ?: team
        val text = when (alert.kind) {
            AlertKind.RED_CARD -> context.getString(R.string.alert_red_card, minute, who)
            AlertKind.LATE_GOAL -> context.getString(R.string.alert_late_goal, minute, who)
            AlertKind.COMEBACK -> context.getString(R.string.alert_comeback, team)
            AlertKind.FANTASY_PLAYER -> context.getString(R.string.alert_fantasy, minute, who)
        }
        // The lock screen gets a neutral line, so a result is never shown to someone glancing at it.
        val public = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.alerts_public))
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .build()
        runCatching {
            NotificationManagerCompat.from(context)
                .notify("${alert.matchId}|${alert.kind}|${alert.minute}|${alert.player}".hashCode(), notification)
        }
    }
}
