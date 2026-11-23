package app.kerkida.data.net

import app.kerkida.data.DataException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

data class Fetched(val body: String, val fetchedAtMs: Long, val isStale: Boolean)

/**
 * Cache first, then the network through the rate limiter.
 * If the network fails, an older saved answer is returned and marked stale.
 */
class CachedHttpClient(
    private val http: OkHttpClient,
    private val cache: ResponseCache,
    private val limiter: RateLimiter,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<Fetched>>()

    suspend fun get(url: String, ttlMs: Long, refresh: Boolean = false): Fetched {
        val cached = cache.get(url)
        if (cached != null) {
            val age = now() - cached.savedAtMs
            val freshEnough = if (refresh) age < MIN_REFRESH_GAP_MS else age < ttlMs
            if (freshEnough) return Fetched(cached.body, cached.savedAtMs, isStale = false)
        }

        val mine = CompletableDeferred<Fetched>()
        val running = inFlight.putIfAbsent(url, mine)
        if (running != null) {
            try {
                return running.await()
            } catch (error: CancellationException) {
                // The screen that started this download left. Try again unless this caller left too.
                currentCoroutineContext().ensureActive()
                return get(url, ttlMs, refresh)
            }
        }

        try {
            val result = try {
                download(url)
            } catch (error: DataException) {
                if (cached == null || error is DataException.NotFound) throw error
                Fetched(cached.body, cached.savedAtMs, isStale = true)
            }
            mine.complete(result)
            return result
        } catch (error: Throwable) {
            mine.completeExceptionally(error)
            throw error
        } finally {
            inFlight.remove(url, mine)
        }
    }

    private suspend fun download(url: String): Fetched {
        limiter.acquire()
        val body = withContext(Dispatchers.IO) {
            try {
                http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    when {
                        response.isSuccessful -> response.body?.string().orEmpty()
                        response.code == 429 -> {
                            val wait = response.header("Retry-After")?.toLongOrNull() ?: DEFAULT_COOLDOWN_SECONDS
                            limiter.coolDown(wait)
                            throw DataException.RateLimited(wait)
                        }
                        response.code == 404 -> throw DataException.NotFound()
                        else -> throw DataException.Server(response.code)
                    }
                }
            } catch (error: IOException) {
                throw DataException.Offline(error)
            }
        }
        val savedAt = now()
        cache.put(url, body, savedAt)
        return Fetched(body, savedAt, isStale = false)
    }

    private companion object {
        const val MIN_REFRESH_GAP_MS = 30_000L
        const val DEFAULT_COOLDOWN_SECONDS = 120L
    }
}
