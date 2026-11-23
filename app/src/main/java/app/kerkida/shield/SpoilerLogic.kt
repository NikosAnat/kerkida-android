package app.kerkida.shield

import app.kerkida.data.model.Match
import app.kerkida.data.model.MatchEvent
import app.kerkida.data.model.MatchStatus
import kotlinx.serialization.Serializable

/** LIVE follows a broadcast with a sync delay. RECORDED stays hidden until declared watched. */
@Serializable
enum class ShieldMode { LIVE, RECORDED }

@Serializable
enum class RevealLevel { HIDDEN, TIME_ONLY, SCORE_ONLY, FULL }

@Serializable
data class ShieldEntry(
    val matchId: String,
    val mode: ShieldMode = ShieldMode.LIVE,
    val level: RevealLevel = RevealLevel.HIDDEN,
    val leagueId: String? = null,
    val kickoffMs: Long? = null,
)

@Serializable
data class ShieldSettings(
    val delaySeconds: Int = DEFAULT_DELAY,
    val autoForFavourites: Boolean = false,
) {
    companion object {
        const val MIN_DELAY = 10
        const val MAX_DELAY = 60
        const val DEFAULT_DELAY = 30
    }
}

/** The parts of a match that can spoil it. */
data class LiveSnapshot(
    val status: MatchStatus,
    val statusDetail: String?,
    val minute: Int?,
    val homeScore: Int?,
    val awayScore: Int?,
    val resultText: String? = null,
) {
    companion object {
        fun of(match: Match) =
            LiveSnapshot(match.status, match.statusDetail, match.minute, match.homeScore, match.awayScore, match.resultText)
    }
}

/**
 * Remembers values with the moment the app first received them.
 * A repeated value keeps its earlier time, so polling never pushes something further into the future.
 */
class DelayBuffer<T>(private val capacity: Int = 64) {
    private val items = ArrayDeque<Pair<Long, T>>()

    @Synchronized
    fun add(atMs: Long, value: T) {
        if (items.lastOrNull()?.second == value) return
        items.addLast(atMs to value)
        while (items.size > capacity) items.removeFirst()
    }

    @Synchronized
    fun latest(): T? = items.lastOrNull()?.second

    /** The newest value received at or before [cutoffMs], or null when nothing is old enough yet. */
    @Synchronized
    fun atOrBefore(cutoffMs: Long): T? = items.lastOrNull { it.first <= cutoffMs }?.second
}

/** What a screen, widget or notification is allowed to show for one match right now. */
data class ShieldedView(
    val match: Match,
    val events: List<MatchEvent>,
    val entry: ShieldEntry?,
    val showScore: Boolean,
    val showClock: Boolean,
    val showEvents: Boolean,
    val waitingForSync: Boolean,
    /** Live statistics may only use data received at or before this time. Null means no limit. */
    val statsCutoffMs: Long?,
) {
    val isShielded: Boolean get() = entry != null
    val isFullyOpen: Boolean get() = showScore && showClock && showEvents && !waitingForSync
}

object SpoilerLogic {
    /** After this long a match cannot still be running on someone's screen as a live broadcast. */
    const val LIVE_WINDOW_MS = 4 * 60 * 60 * 1000L

    fun isWithinLiveWindow(kickoffMs: Long?, nowMs: Long): Boolean =
        kickoffMs == null || nowMs - kickoffMs < LIVE_WINDOW_MS

    fun resolve(
        match: Match,
        entry: ShieldEntry?,
        settings: ShieldSettings,
        snapshots: DelayBuffer<LiveSnapshot>?,
        eventLog: DelayBuffer<List<MatchEvent>>?,
        sourceEvents: List<MatchEvent>,
        nowMs: Long,
    ): ShieldedView {
        if (entry == null) {
            return ShieldedView(match, sourceEvents, null, true, true, true, false, null)
        }
        if (entry.level == RevealLevel.HIDDEN) return closed(match, entry, waiting = false)

        val kickoffMs = match.kickoff?.toEpochMilli() ?: entry.kickoffMs
        val useDelay = entry.mode == ShieldMode.LIVE && isWithinLiveWindow(kickoffMs, nowMs)
        val cutoff = nowMs - settings.delaySeconds.coerceIn(ShieldSettings.MIN_DELAY, ShieldSettings.MAX_DELAY) * 1000L
        val snapshot = if (useDelay) snapshots?.atOrBefore(cutoff) else LiveSnapshot.of(match)
        snapshot ?: return closed(match, entry, waiting = true)

        val showClock = entry.level == RevealLevel.TIME_ONLY || entry.level == RevealLevel.FULL
        val showScore = entry.level == RevealLevel.SCORE_ONLY || entry.level == RevealLevel.FULL
        val showEvents = entry.level == RevealLevel.FULL
        val events = when {
            !showEvents -> emptyList()
            useDelay -> eventLog?.atOrBefore(cutoff).orEmpty()
            else -> sourceEvents
        }
        return ShieldedView(
            match = match.copy(
                status = if (showClock) snapshot.status else MatchStatus.UNKNOWN,
                statusDetail = if (showClock) snapshot.statusDetail else null,
                minute = if (showClock) snapshot.minute else null,
                homeScore = if (showScore) snapshot.homeScore else null,
                awayScore = if (showScore) snapshot.awayScore else null,
                resultText = if (showScore) snapshot.resultText else null,
            ),
            events = events,
            entry = entry,
            showScore = showScore,
            showClock = showClock,
            showEvents = showEvents,
            waitingForSync = false,
            statsCutoffMs = if (useDelay) cutoff else null,
        )
    }

    private fun closed(match: Match, entry: ShieldEntry, waiting: Boolean) = ShieldedView(
        match = match.copy(
            status = MatchStatus.UNKNOWN,
            statusDetail = null,
            minute = null,
            homeScore = null,
            awayScore = null,
            resultText = null,
        ),
        events = emptyList(),
        entry = entry,
        showScore = false,
        showClock = false,
        showEvents = false,
        waitingForSync = waiting,
        statsCutoffMs = null,
    )
}
