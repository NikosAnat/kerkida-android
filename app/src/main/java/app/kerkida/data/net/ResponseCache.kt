package app.kerkida.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Saves API answers in memory and in the app's private cache folder. */
class ResponseCache(
    private val directory: File,
    private val maxDiskBytes: Long = 16L * 1024 * 1024,
) {
    data class Entry(val body: String, val savedAtMs: Long)

    private val memory = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) = size > MAX_MEMORY_ENTRIES
    }

    private fun remembered(key: String): Entry? = synchronized(memory) { memory[key] }

    private fun remember(key: String, entry: Entry) {
        synchronized(memory) { memory[key] = entry }
    }

    suspend fun get(key: String): Entry? {
        remembered(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            val file = fileFor(key)
            if (!file.isFile) return@withContext null
            runCatching {
                val text = file.readText()
                val split = text.indexOf('\n')
                Entry(body = text.substring(split + 1), savedAtMs = text.substring(0, split).toLong())
            }.getOrNull()?.also { remember(key, it) }
        }
    }

    suspend fun put(key: String, body: String, savedAtMs: Long) {
        remember(key, Entry(body, savedAtMs))
        withContext(Dispatchers.IO) {
            runCatching {
                directory.mkdirs()
                val target = fileFor(key)
                val temp = File(directory, "${target.name}.tmp")
                temp.writeText("$savedAtMs\n$body")
                if (!temp.renameTo(target)) {
                    target.writeText("$savedAtMs\n$body")
                    temp.delete()
                }
                trim()
            }
        }
    }

    private fun trim() {
        val files = directory.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxDiskBytes) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= maxDiskBytes) break
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    private fun fileFor(key: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        return File(directory, digest.joinToString("") { "%02x".format(it) })
    }

    private companion object {
        const val MAX_MEMORY_ENTRIES = 48
    }
}
