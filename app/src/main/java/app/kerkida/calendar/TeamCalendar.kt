package app.kerkida.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.kerkida.KerkidaApp
import app.kerkida.data.DataException
import app.kerkida.data.model.Match
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** One match as it should appear in the calendar. Titles never carry a score. */
data class CalendarGame(val matchId: String, val title: String, val startMs: Long, val endMs: Long, val location: String)

data class SavedGame(val rowId: Long, val game: CalendarGame)

data class CalendarPlan(val add: List<CalendarGame>, val update: List<SavedGame>, val remove: List<Long>)

object CalendarSync {
    private const val MATCH_MS = 115 * 60_000L

    fun gameOf(match: Match): CalendarGame? {
        val start = match.kickoff?.toEpochMilli() ?: return null
        return CalendarGame(match.id, "${match.home.name} – ${match.away.name}", start, start + MATCH_MS, match.venue.orEmpty())
    }

    /** Only what changed is written, so moved kick-offs update and cancelled games disappear. */
    fun plan(wanted: List<CalendarGame>, saved: List<SavedGame>): CalendarPlan {
        val savedById = saved.associateBy { it.game.matchId }
        val wantedIds = wanted.map { it.matchId }.toSet()
        return CalendarPlan(
            add = wanted.filter { it.matchId !in savedById },
            update = wanted.mapNotNull { game ->
                savedById[game.matchId]?.takeIf { it.game != game }?.let { SavedGame(it.rowId, game) }
            },
            remove = saved.filter { it.game.matchId !in wantedIds }.map { it.rowId },
        )
    }
}

/** Keeps the season of the chosen teams in a separate "Kerkida" calendar on the phone. */
class TeamCalendar(private val context: Context) {
    private val prefs = context.getSharedPreferences("kerkida_calendar", Context.MODE_PRIVATE)

    fun teams(): Set<String> = prefs.getStringSet(KEY_TEAMS, emptySet()).orEmpty().toSet()

    fun setTeam(teamId: String, on: Boolean) {
        val updated = if (on) teams() + teamId else teams() - teamId
        prefs.edit().putStringSet(KEY_TEAMS, updated).apply()
        val work = WorkManager.getInstance(context)
        if (updated.isEmpty()) {
            work.cancelUniqueWork(WORK)
        } else {
            val request = PeriodicWorkRequestBuilder<CalendarWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }

    fun hasPermission(): Boolean = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        .all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    private fun asSync(uri: Uri): Uri = uri.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, ACCOUNT)
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
        .build()

    private fun calendarId(): Long? {
        val resolver = context.contentResolver
        resolver.query(
            Calendars.CONTENT_URI,
            arrayOf(Calendars._ID),
            "${Calendars.ACCOUNT_NAME}=? AND ${Calendars.ACCOUNT_TYPE}=?",
            arrayOf(ACCOUNT, CalendarContract.ACCOUNT_TYPE_LOCAL),
            null,
        )?.use { if (it.moveToFirst()) return it.getLong(0) }
        val values = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, ACCOUNT)
            put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(Calendars.NAME, ACCOUNT)
            put(Calendars.CALENDAR_DISPLAY_NAME, ACCOUNT)
            put(Calendars.CALENDAR_COLOR, CALENDAR_COLOR)
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
            put(Calendars.OWNER_ACCOUNT, ACCOUNT)
            put(Calendars.VISIBLE, 1)
            put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
        }
        return resolver.insert(asSync(Calendars.CONTENT_URI), values)?.let(ContentUris::parseId)
    }

    private fun saved(calendar: Long): List<SavedGame> {
        val out = mutableListOf<SavedGame>()
        context.contentResolver.query(
            Events.CONTENT_URI,
            arrayOf(Events._ID, Events.SYNC_DATA1, Events.TITLE, Events.DTSTART, Events.DTEND, Events.EVENT_LOCATION),
            "${Events.CALENDAR_ID}=? AND ${Events.DELETED}=0",
            arrayOf(calendar.toString()),
            null,
        )?.use { row ->
            while (row.moveToNext()) {
                val id = row.getString(1) ?: continue
                out += SavedGame(
                    row.getLong(0),
                    CalendarGame(id, row.getString(2).orEmpty(), row.getLong(3), row.getLong(4), row.getString(5).orEmpty()),
                )
            }
        }
        return out
    }

    private fun values(game: CalendarGame) = ContentValues().apply {
        put(Events.TITLE, game.title)
        put(Events.DTSTART, game.startMs)
        put(Events.DTEND, game.endMs)
        put(Events.EVENT_LOCATION, game.location)
        put(Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        put(Events.SYNC_DATA1, game.matchId)
    }

    /** Past games stay as they are, so the calendar keeps the season's history. */
    fun apply(games: List<CalendarGame>, nowMs: Long = System.currentTimeMillis()) {
        val calendar = calendarId() ?: return
        val resolver = context.contentResolver
        val current = saved(calendar).filter { it.game.startMs >= nowMs }
        val plan = CalendarSync.plan(games.distinctBy { it.matchId }.filter { it.startMs >= nowMs }, current)
        plan.add.forEach { game ->
            resolver.insert(asSync(Events.CONTENT_URI), values(game).apply { put(Events.CALENDAR_ID, calendar) })
        }
        plan.update.forEach { resolver.update(asSync(ContentUris.withAppendedId(Events.CONTENT_URI, it.rowId)), values(it.game), null, null) }
        plan.remove.forEach { resolver.delete(asSync(ContentUris.withAppendedId(Events.CONTENT_URI, it)), null, null) }
    }

    suspend fun sync() {
        if (!hasPermission()) return
        val provider = (context.applicationContext as KerkidaApp).container.provider.value
        if (provider.info.isDemo) return
        val games = mutableListOf<CalendarGame>()
        for (team in teams()) {
            try {
                games += provider.teamMatches(team, refresh = true).data.upcoming.mapNotNull(CalendarSync::gameOf)
            } catch (_: DataException) {
                // A failed read must never wipe that team's games, so stop and retry next time.
                return
            }
        }
        apply(games)
    }

    private companion object {
        const val ACCOUNT = "Kerkida"
        const val KEY_TEAMS = "teams"
        const val WORK = "kerkida-team-calendar"
        const val CALENDAR_COLOR = 0xFF00B37A.toInt()
    }
}

class CalendarWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        (applicationContext as KerkidaApp).container.teamCalendar.sync()
        return Result.success()
    }
}
