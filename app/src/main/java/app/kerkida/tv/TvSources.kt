package app.kerkida.tv

import android.content.Context
import android.util.Xml
import app.kerkida.broadcast.Broadcast
import app.kerkida.broadcast.BroadcastProvider
import app.kerkida.broadcast.BroadcastScope
import app.kerkida.data.DataException
import app.kerkida.data.model.Match
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

/** A place to read Greek TV listings from. Add one and register it in the app container. */
interface TvScheduleSource {
    val id: String
    suspend fun fetch(today: LocalDate): List<TvItem>
}

/** Reads the ΣΠΟΡ FM listings page. Review its terms before any public release. */
class SportFmSource(private val http: OkHttpClient) : TvScheduleSource {
    override val id = ID

    override suspend fun fetch(today: LocalDate): List<TvItem> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(URL).header("Accept", "text/html").build()
        val body = try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw DataException.Server(response.code)
                response.body?.string().orEmpty()
            }
        } catch (error: IOException) {
            throw DataException.Offline(error)
        }
        parse(body, today)
    }

    companion object {
        const val ID = "sportfm"
        private const val URL = "https://www.sport-fm.gr/tv"
        private val DayHeader = Regex("(\\d{1,2})/(\\d{1,2})$")
        private val DaySplit = Regex("<div[^>]*class=\"tv-day(?:\\s[^\"]*)?\"", RegexOption.IGNORE_CASE)
        private val TileSplit = Regex("class=\"[^\"]*archive-row-tile", RegexOption.IGNORE_CASE)
        private val H2 = Regex("<h2[^>]*>([\\s\\S]*?)</h2>", RegexOption.IGNORE_CASE)
        private val H3 = Regex("<h3[^>]*>([\\s\\S]*?)</h3>", RegexOption.IGNORE_CASE)
        private val P = Regex("<p[^>]*>([\\s\\S]*?)</p>", RegexOption.IGNORE_CASE)
        private val Time = Regex("<time[^>]*>([\\s\\S]*?)</time>", RegexOption.IGNORE_CASE)
        private val Category = Regex("<span[^>]*post-category[^>]*>([\\s\\S]*?)</span>", RegexOption.IGNORE_CASE)
        private val Tag = Regex("<[^>]+>")

        private fun decode(text: String) = text
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">")

        private fun clean(html: String?) = decode(html.orEmpty().replace(Tag, " ")).replace(Regex("\\s+"), " ").trim()

        private fun dateOf(day: Int, month: Int, today: LocalDate): LocalDate? {
            var year = today.year
            if (month + 6 < today.monthValue) year += 1 else if (month - 6 > today.monthValue) year -= 1
            return runCatching { LocalDate.of(year, month, day) }.getOrNull()
        }

        fun parse(html: String, today: LocalDate): List<TvItem> {
            val items = mutableListOf<TvItem>()
            html.split(DaySplit).drop(1).forEach { chunk ->
                val header = clean(H2.find(chunk)?.groupValues?.get(1))
                val found = DayHeader.find(header) ?: return@forEach
                val date = dateOf(found.groupValues[1].toInt(), found.groupValues[2].toInt(), today) ?: return@forEach
                chunk.split(TileSplit).drop(1).forEach tile@{ tile ->
                    if ("tv_logos" !in tile) return@tile
                    val time = clean(Time.find(tile)?.groupValues?.get(1))
                    val title = clean(H3.find(tile)?.groupValues?.get(1))
                    val competition = clean(P.find(tile)?.groupValues?.get(1))
                    val channel = clean(Category.find(tile)?.groupValues?.get(1))
                    if (!Regex("\\d{1,2}:\\d{2}").matches(time) || title.isEmpty() || channel.isEmpty()) return@tile
                    val (hour, minute) = time.split(':').map { it.toInt() }
                    val start = date.atTime(hour, minute).atZone(TvLogic.Athens).toInstant().toEpochMilli()
                    items += TvItem(
                        date = date.toString(),
                        time = time.padStart(5, '0'),
                        startMs = start,
                        channel = channel,
                        title = title,
                        competition = competition,
                        sport = TvLogic.classify(competition, title),
                        sourceId = ID,
                    )
                }
            }
            return items
        }
    }
}

/**
 * Reads a public XMLTV guide covering Nova, Magenta, ΕΡΤ, Mega, Alpha, Star, ΑΝΤ1, ΣΚΑΪ, Open and others.
 * Only sports marked as live are kept, so repeats never appear as broadcasts.
 * It is a community feed with no guarantee: review its terms before any public release.
 */
class XmltvSource(
    private val http: OkHttpClient,
    private val cacheFile: File,
    private val url: String = DEFAULT_URL,
) : TvScheduleSource {
    override val id = ID

    override suspend fun fetch(today: LocalDate): List<TvItem> = withContext(Dispatchers.IO) {
        val fresh = cacheFile.exists() && System.currentTimeMillis() - cacheFile.lastModified() < TTL_MS
        if (!fresh) download()
        if (!cacheFile.exists()) throw DataException.Offline()
        cacheFile.inputStream().buffered().use { raw -> GZIPInputStream(raw).use { parse(it, today) } }
    }

    private fun download() {
        val temp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
        try {
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    if (cacheFile.exists()) return
                    throw DataException.Server(response.code)
                }
                val body = response.body ?: return
                temp.outputStream().use { body.byteStream().copyTo(it) }
            }
            if (temp.exists()) {
                cacheFile.delete()
                temp.renameTo(cacheFile)
            }
        } catch (error: IOException) {
            temp.delete()
            if (!cacheFile.exists()) throw DataException.Offline(error)
        }
    }

    companion object {
        const val ID = "xmltv"
        private const val DEFAULT_URL = "https://epgshare01.online/epgshare01/epg_ripper_GR1.xml.gz"
        private const val TTL_MS = 3 * 60 * 60 * 1000L
        private const val DAYS_AHEAD = 7L
        private val XmlTime = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z")
        private val LiveMark = Regex("\\((?:Z|Ζ|LIVE)\\)|^live\\s*:", RegexOption.IGNORE_CASE)
        private val SportCategory = Regex(
            "ποδοσφαιρο|μπασκετ|τενισ|βολει|χαντμπολ|μηχανοκινητ|formula|πολο|γκολφ|σπορ|αθλητ|moto|fight|mma|χοκει",
        )
        private val SportPrefix = Regex(
            "^(motorsports|ποδόσφαιρο|μπάσκετ|τένις|βόλεϊ|χάντμπολ|πόλο|μηχανοκίνητα|formula 1)\\s*:\\s*",
            RegexOption.IGNORE_CASE,
        )

        fun sportOf(category: String, competition: String, title: String): TvSport {
            val c = TvLogic.plain(category)
            return when {
                "ποδοσφαιρο" in c && "αμερικ" !in c -> TvSport.FOOTBALL
                "μπασκετ" in c -> TvSport.BASKETBALL
                "τενισ" in c -> TvSport.TENNIS
                "μηχανοκινητ" in c || "formula" in c || "moto" in c -> TvSport.MOTOR
                "βολει" in c -> TvSport.VOLLEYBALL
                "χαντμπολ" in c -> TvSport.HANDBALL
                "πολο" in c -> TvSport.WATER_POLO
                "γκολφ" in c -> TvSport.GOLF
                "αμερικ" in c -> TvSport.AMERICAN_FOOTBALL
                "fight" in c || "mma" in c || "πυγμαχ" in c -> TvSport.MMA
                else -> TvLogic.classify(competition, title)
            }
        }

        /** "Live: Ποδόσφαιρο: Α - Β (Ζ) : Serie A" becomes the title "Α - Β" and the competition "Serie A". */
        fun cleanTitle(raw: String): Pair<String, String> {
            val text = raw.replace(Regex("^live\\s*:\\s*", RegexOption.IGNORE_CASE), "")
                .replace(SportPrefix, "")
                .replace(Regex("\\s*\\((?:Z|Ζ|LIVE)\\)", RegexOption.IGNORE_CASE), "")
                .trim()
            val index = text.indexOf(" : ")
            if (index <= 0) return text to ""
            return text.substring(0, index).trim() to text.substring(index + 3).trim()
        }

        fun parse(input: InputStream, today: LocalDate): List<TvItem> {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(input, "UTF-8")
            val channels = HashMap<String, String>()
            val items = LinkedHashMap<String, TvItem>()
            val from = today.minusDays(1)
            val until = today.plusDays(DAYS_AHEAD)
            var channelId: String? = null
            var start: String? = null
            var stop: String? = null
            var programmeChannel: String? = null
            var title = ""
            var category = ""
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "channel" -> channelId = parser.getAttributeValue(null, "id")
                        "display-name" -> channelId?.let { id ->
                            val name = parser.nextText().trim()
                            channels.putIfAbsent(id, name)
                        }
                        "programme" -> {
                            start = parser.getAttributeValue(null, "start")
                            stop = parser.getAttributeValue(null, "stop")
                            programmeChannel = parser.getAttributeValue(null, "channel")
                            title = ""
                            category = ""
                        }
                        "title" -> if (programmeChannel != null && title.isEmpty()) title = parser.nextText().trim()
                        "category" -> if (programmeChannel != null && category.isEmpty()) category = parser.nextText().trim()
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    if (parser.name == "channel") channelId = null
                    if (parser.name == "programme") {
                        toItem(channels[programmeChannel], start, stop, title, category, from, until)
                            ?.let { items.putIfAbsent(it.key, it) }
                        programmeChannel = null
                    }
                }
                event = parser.next()
            }
            return items.values.toList()
        }

        private fun toItem(
            channelName: String?,
            start: String?,
            stop: String?,
            rawTitle: String,
            category: String,
            from: LocalDate,
            until: LocalDate,
        ): TvItem? {
            channelName ?: return null
            if (rawTitle.isEmpty() || !LiveMark.containsMatchIn(rawTitle)) return null
            if (!SportCategory.containsMatchIn(TvLogic.plain(category))) return null
            if (TvLogic.channelIdOf(channelName) == "other") return null
            val begin = runCatching { OffsetDateTime.parse(start, XmlTime).toInstant() }.getOrNull() ?: return null
            val local = begin.atZone(TvLogic.Athens)
            if (local.toLocalDate().isBefore(from) || local.toLocalDate().isAfter(until)) return null
            val end = runCatching { OffsetDateTime.parse(stop, XmlTime).toInstant() }.getOrNull()
            val (title, competition) = cleanTitle(rawTitle)
            if (title.isEmpty()) return null
            val minutes = end?.let { ((it.epochSecond - begin.epochSecond) / 60).toInt() }?.takeIf { it in 20..600 } ?: 0
            return TvItem(
                date = local.toLocalDate().toString(),
                time = "%02d:%02d".format(local.hour, local.minute),
                startMs = begin.toEpochMilli(),
                channel = channelName.replace(Regex("\\s+(HD|ΗD)$"), "").trim(),
                title = title,
                competition = competition.ifEmpty { category },
                sport = sportOf(category, competition, title),
                durationMin = minutes,
                sourceId = ID,
            )
        }
    }
}

/** All Greek listings in one place, saved so they still work offline. */
class TvScheduleRepository(
    context: Context,
    private val sources: List<TvScheduleSource>,
    private val now: () -> Long = System::currentTimeMillis,
) : BroadcastProvider {
    override val id = "tv-guide"

    private val file = File(context.filesDir, "kerkida-tv-guide.json")
    private val prefs = context.getSharedPreferences("kerkida_tv", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val matchMemo = ConcurrentHashMap<String, List<Broadcast>>()
    private val saved = mutableMapOf<String, List<TvItem>>()

    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<TvItem>> = _items.asStateFlow()
    private val _updatedAt = MutableStateFlow(prefs.getLong(KEY_UPDATED, 0L))
    val updatedAt: StateFlow<Long> = _updatedAt.asStateFlow()
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()
    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed.asStateFlow()
    private val _favourites = MutableStateFlow(
        prefs.getStringSet(KEY_FAVOURITES, emptySet()).orEmpty().map(TvLogic::currentItemKey).toSet(),
    )
    val favourites: StateFlow<Set<String>> = _favourites.asStateFlow()

    private fun load(): List<TvItem> = runCatching {
        if (!file.exists()) return@runCatching emptyList()
        // Listings saved before a sport was added get their proper sport now.
        json.decodeFromString<List<TvItem>>(file.readText()).map { saved ->
            val item = saved.copy(channel = TvLogic.channelName(saved.channel))
            if (item.sport == TvSport.OTHER) item.copy(sport = TvLogic.classify(item.competition, item.title)) else item
        }
    }.getOrDefault(emptyList())

    fun toggleFavourite(key: String) {
        val updated = if (key in _favourites.value) _favourites.value - key else _favourites.value + key
        _favourites.value = updated
        prefs.edit().putStringSet(KEY_FAVOURITES, updated).apply()
    }

    suspend fun refresh(force: Boolean = false) {
        if (!force && now() - _updatedAt.value < MIN_GAP_MS && _items.value.isNotEmpty()) return
        mutex.withLock {
            if (!force && now() - _updatedAt.value < MIN_GAP_MS && _items.value.isNotEmpty()) return
            _isRefreshing.value = true
            try {
                val today = LocalDate.now(TvLogic.Athens)
                var anyWorked = false
                val results = sources.map { source ->
                    try {
                        source.fetch(today).also {
                            anyWorked = anyWorked || it.isNotEmpty()
                            saved[source.id] = it
                        }
                    } catch (_: DataException) {
                        saved[source.id].orEmpty()
                    } catch (_: org.xmlpull.v1.XmlPullParserException) {
                        saved[source.id].orEmpty()
                    } catch (_: IOException) {
                        saved[source.id].orEmpty()
                    }
                }
                _failed.value = !anyWorked
                if (anyWorked) {
                    val previous = _items.value
                    val kept = withContext(Dispatchers.Default) {
                        TvLogic.merge(listOf(TvLogic.keepSaved(previous, TvLogic.merge(results), today)))
                    }
                    matchMemo.clear()
                    _items.value = kept
                    withContext(Dispatchers.IO) { file.writeText(json.encodeToString(kept)) }
                    _updatedAt.value = now()
                    prefs.edit().putLong(KEY_UPDATED, _updatedAt.value).apply()
                }
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    /** Every listing that is this match, one per channel. */
    fun itemsFor(match: Match): List<TvItem> {
        val kickoff = match.kickoff ?: return emptyList()
        val day = kickoff.atZone(TvLogic.Athens).toLocalDate().toString()
        val scored = _items.value.asSequence()
            .filter { it.date == day || kotlin.math.abs(it.startMs - kickoff.toEpochMilli()) < 3_600_000 }
            .mapNotNull { item -> TvLogic.matchScore(item, match)?.let { item to it } }
            .toList()
        val best = scored.maxOfOrNull { it.second } ?: return emptyList()
        // Two different games can share a slot, so only listings about as good as the best one count.
        return scored.filter { it.second >= best - 0.35 }.map { it.first }.distinctBy { TvLogic.channelKey(it.channel) }
    }

    fun broadcastsNow(match: Match): List<Broadcast> = matchMemo.getOrPut(match.id) {
        val updated = _updatedAt.value
        itemsFor(match).map { item ->
            val channelId = TvLogic.channelIdOf(item.channel)
            Broadcast(
                id = "tv:${item.key}",
                scope = BroadcastScope.MATCH,
                targetId = match.id,
                channelId = channelId,
                channelName = item.channel,
                access = TvLogic.accessOf(channelId),
                updatedAtMs = updated,
                sourceId = item.sourceId,
            )
        }
    }

    override suspend fun forMatch(match: Match): List<Broadcast> = broadcastsNow(match)

    /** The match a listing is about, from a list of the day's matches. */
    fun matchFor(item: TvItem, matches: List<Match>): Match? = matches
        .mapNotNull { match -> TvLogic.matchScore(item, match)?.let { match to it } }
        .maxByOrNull { it.second }?.first

    private companion object {
        const val KEY_UPDATED = "updated"
        const val KEY_FAVOURITES = "favourites"
        const val MIN_GAP_MS = 30 * 60 * 1000L
    }
}
