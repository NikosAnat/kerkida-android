package app.kerkida.data.net

import app.kerkida.data.DataException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps requests well under the source's limit: a minimum gap between calls, a cap per minute,
 * and a full stop after the source answers "too many requests".
 */
class RateLimiter(
    // The service blocked about 12 calls in 35 seconds, but accepted steady calls 9 seconds apart.
    private val minGapMs: Long = 6_000,
    private val maxPerMinute: Int = 10,
    private val maxWaitMs: Long = 60_000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val recent = ArrayDeque<Long>()

    @Volatile
    private var blockedUntil = 0L

    suspend fun acquire() {
        val requestedAt = now()
        mutex.withLock {
            while (true) {
                val time = now()
                throwIfCoolingDown(time)
                while (recent.isNotEmpty() && time - recent.first() >= WINDOW_MS) recent.removeFirst()

                val gapWait = recent.lastOrNull()?.let { minGapMs - (time - it) } ?: 0L
                val windowWait = if (recent.size >= maxPerMinute) WINDOW_MS - (time - recent.first()) else 0L
                val wait = maxOf(gapWait, windowWait, 0L)
                if (wait == 0L) {
                    recent.addLast(time)
                    return
                }
                if (time + wait - requestedAt > maxWaitMs) {
                    throw DataException.RateLimited((wait / 1000).coerceAtLeast(1))
                }
                delay(wait)
            }
        }
    }

    fun coolDown(seconds: Long) {
        blockedUntil = maxOf(blockedUntil, now() + seconds * 1000)
    }

    private fun throwIfCoolingDown(time: Long) {
        val remaining = blockedUntil - time
        if (remaining > 0) throw DataException.RateLimited((remaining / 1000).coerceAtLeast(1))
    }

    private companion object {
        const val WINDOW_MS = 60_000L
    }
}
