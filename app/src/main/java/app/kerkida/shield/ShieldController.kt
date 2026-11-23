package app.kerkida.shield

import android.content.Context
import app.kerkida.data.model.Match
import app.kerkida.data.model.MatchEvent
import app.kerkida.data.model.isInPlay
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

interface ShieldStore {
    fun loadEntries(): List<ShieldEntry>
    fun saveEntries(entries: List<ShieldEntry>)
    fun loadSettings(): ShieldSettings
    fun saveSettings(settings: ShieldSettings)
}

class PrefsShieldStore(context: Context) : ShieldStore {
    private val prefs = context.getSharedPreferences("kerkida_shield", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    override fun loadEntries(): List<ShieldEntry> = prefs.getString(KEY_ENTRIES, null)
        ?.let { runCatching { json.decodeFromString<List<ShieldEntry>>(it) }.getOrNull() }
        .orEmpty()

    override fun saveEntries(entries: List<ShieldEntry>) {
        prefs.edit().putString(KEY_ENTRIES, json.encodeToString(entries)).apply()
    }

    override fun loadSettings(): ShieldSettings = prefs.getString(KEY_SETTINGS, null)
        ?.let { runCatching { json.decodeFromString<ShieldSettings>(it) }.getOrNull() }
        ?: ShieldSettings()

    override fun saveSettings(settings: ShieldSettings) {
        prefs.edit().putString(KEY_SETTINGS, json.encodeToString(settings)).apply()
    }

    private companion object {
        const val KEY_ENTRIES = "entries"
        const val KEY_SETTINGS = "settings"
    }
}

/** The single place that decides what may be shown about a match. */
class ShieldController(
    private val store: ShieldStore,
    private val favouriteTeamIds: () -> Set<String>,
    private val isEnabled: () -> Boolean = { true },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _entries = MutableStateFlow(store.loadEntries().associateBy { it.matchId })
    val entries: StateFlow<Map<String, ShieldEntry>> = _entries.asStateFlow()

    private val _settings = MutableStateFlow(store.loadSettings())
    val settings: StateFlow<ShieldSettings> = _settings.asStateFlow()

    /** Moves forward while a delayed match may have something new to reveal. */
    private val _clock = MutableStateFlow(now())
    val clock: StateFlow<Long> = _clock.asStateFlow()

    private val snapshots = ConcurrentHashMap<String, DelayBuffer<LiveSnapshot>>()
    private val eventLogs = ConcurrentHashMap<String, DelayBuffer<List<MatchEvent>>>()
    private val seen = ConcurrentHashMap<String, Match>()

    fun observe(match: Match) {
        seen[match.id] = match
        snapshots.getOrPut(match.id) { DelayBuffer() }.add(now(), LiveSnapshot.of(match))
    }

    fun observeDetails(match: Match, events: List<MatchEvent>) {
        observe(match)
        eventLogs.getOrPut(match.id) { DelayBuffer() }.add(now(), events)
    }

    fun entryFor(match: Match): ShieldEntry? {
        if (!isEnabled()) return null
        _entries.value[match.id]?.let { return it }
        return autoEntry(match)
    }

    private fun autoEntry(match: Match): ShieldEntry? {
        if (!_settings.value.autoForFavourites) return null
        val kickoff = match.kickoff?.toEpochMilli() ?: return null
        if (now() - kickoff >= AUTO_WINDOW_MS) return null
        val teams = favouriteTeamIds()
        if (match.home.id !in teams && match.away.id !in teams) return null
        return entryOf(match, ShieldMode.LIVE, RevealLevel.HIDDEN)
    }

    fun view(match: Match, events: List<MatchEvent> = emptyList()): ShieldedView = SpoilerLogic.resolve(
        match = match,
        entry = entryFor(match),
        settings = _settings.value,
        snapshots = snapshots[match.id],
        eventLog = eventLogs[match.id],
        sourceEvents = events,
        nowMs = now(),
    )

    /** Eye tap: an open match gets hidden, a hidden or partly open one opens fully. */
    fun toggle(match: Match) {
        val current = entryFor(match)
        if (current == null || current.level == RevealLevel.FULL) {
            put(entryOf(match, current?.mode ?: ShieldMode.LIVE, RevealLevel.HIDDEN))
        } else {
            put(current.copy(level = RevealLevel.FULL))
        }
    }

    fun setLevel(match: Match, level: RevealLevel) {
        val current = entryFor(match)
        put(current?.copy(level = level) ?: entryOf(match, ShieldMode.LIVE, level))
    }

    fun markRecorded(match: Match) = put(entryOf(match, ShieldMode.RECORDED, RevealLevel.HIDDEN))

    fun markWatched(match: Match) = put(entryOf(match, ShieldMode.RECORDED, RevealLevel.FULL))

    /** Removes protection. A match kept hidden by the auto rule stays open but still follows the delay. */
    fun clear(match: Match) {
        if (autoEntry(match) != null) {
            put(entryOf(match, ShieldMode.LIVE, RevealLevel.FULL))
        } else {
            _entries.value = _entries.value - match.id
            store.saveEntries(_entries.value.values.toList())
        }
    }

    fun setDelay(seconds: Int) = updateSettings(
        _settings.value.copy(delaySeconds = seconds.coerceIn(ShieldSettings.MIN_DELAY, ShieldSettings.MAX_DELAY)),
    )

    fun setAutoForFavourites(on: Boolean) = updateSettings(_settings.value.copy(autoForFavourites = on))

    /** Live filters must not reveal that a hidden match has ended, so they go by the clock instead. */
    fun looksInPlay(match: Match): Boolean {
        val view = view(match)
        if (view.showClock) return view.match.status.isInPlay
        val kickoff = match.kickoff?.toEpochMilli() ?: return false
        return now() in kickoff until kickoff + HIDDEN_IN_PLAY_MS
    }

    /** A league table can give away the result of a hidden match that has kicked off. */
    fun hidesTable(leagueId: String): Boolean {
        if (!isEnabled()) return false
        val time = now()
        val explicit = _entries.value.values.any {
            it.leagueId == leagueId && it.level != RevealLevel.FULL && (it.kickoffMs ?: Long.MAX_VALUE) <= time
        }
        if (explicit) return true
        return seen.values.any { match ->
            match.league.id == leagueId &&
                match.id !in _entries.value &&
                (match.kickoff?.toEpochMilli() ?: Long.MAX_VALUE) <= time &&
                autoEntry(match) != null
        }
    }

    suspend fun runClock() {
        while (true) {
            delay(CLOCK_MS)
            val time = now()
            val needsTick = _entries.value.values.any {
                it.mode == ShieldMode.LIVE && it.level != RevealLevel.HIDDEN &&
                    SpoilerLogic.isWithinLiveWindow(it.kickoffMs, time)
            }
            if (needsTick) _clock.value = time
        }
    }

    private fun entryOf(match: Match, mode: ShieldMode, level: RevealLevel) = ShieldEntry(
        matchId = match.id,
        mode = mode,
        level = level,
        leagueId = match.league.id,
        kickoffMs = match.kickoff?.toEpochMilli(),
    )

    private fun put(entry: ShieldEntry) {
        _entries.value = _entries.value + (entry.matchId to entry)
        store.saveEntries(_entries.value.values.toList())
    }

    private fun updateSettings(settings: ShieldSettings) {
        _settings.value = settings
        store.saveSettings(settings)
    }

    private companion object {
        const val CLOCK_MS = 1_000L
        const val AUTO_WINDOW_MS = 6 * 60 * 60 * 1000L
        const val HIDDEN_IN_PLAY_MS = 3 * 60 * 60 * 1000L
    }
}
