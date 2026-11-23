package app.kerkida.highlights

import android.content.Context
import android.util.Xml
import app.kerkida.data.DataException
import app.kerkida.data.model.Match
import app.kerkida.data.model.Sport
import app.kerkida.tv.TvLogic
import app.kerkida.tv.TvSport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

enum class SourceKind { LEAGUE, BROADCASTER, CLUB }

/** [sport] is null for broadcasters, whose channels cover several sports. */
data class OfficialChannel(
    val id: String,
    val name: String,
    val kind: SourceKind,
    val leagueId: String? = null,
    val sport: Sport? = Sport.FOOTBALL,
)

/** The fixed list of official channels. Videos are only linked to, never copied. */
val OfficialChannels = listOf(
    OfficialChannel("UCEIbXco8hU9oDHXGo1kwIlA", "Novasports", SourceKind.BROADCASTER, sport = null),
    OfficialChannel("UCgMUmr7uLDalwVk63Sf6pBw", "Magenta Sport GR", SourceKind.BROADCASTER, sport = null),
    OfficialChannel("UCG5qGWdu8nIRZqJ_GgDwQ-w", "Premier League", SourceKind.LEAGUE, "eng.1"),
    OfficialChannel("UCTv-XvfzLX3i4IGWAm4sbmA", "LALIGA", SourceKind.LEAGUE, "esp.1"),
    OfficialChannel("UCBJeMCIeLQos7wacox4hmLQ", "Serie A", SourceKind.LEAGUE, "ita.1"),
    OfficialChannel("UC6UL29enLNe4mqwTfAyeNuw", "Bundesliga", SourceKind.LEAGUE, "ger.1"),
    OfficialChannel("UCQsH5XtIc9hONE1BQjucM0g", "Ligue 1", SourceKind.LEAGUE, "fra.1"),
    OfficialChannel("UCyGa1YEx9ST66rYrJTGIKOw", "UEFA", SourceKind.LEAGUE, "uefa.champions"),
    OfficialChannel("UCGr3nR_XH9r6E5b09ZJAT9w", "EuroLeague", SourceKind.LEAGUE, sport = Sport.BASKETBALL),
    OfficialChannel("UCWJ2lWNubArHWmf3FIHbfcQ", "NBA", SourceKind.LEAGUE, sport = Sport.BASKETBALL),
    OfficialChannel("UCZUkv6tSC5OfX54gLgjV9aw", "Basketball Champions League", SourceKind.LEAGUE, sport = Sport.BASKETBALL),
    OfficialChannel("UCB_qr75-ydFVKSF9Dmo6izg", "Formula 1", SourceKind.LEAGUE, sport = Sport.MOTOR),
    OfficialChannel("UCY_5h5zaSwN7Or4kIJDYNXA", "ATP Tour", SourceKind.LEAGUE, sport = Sport.TENNIS),
    OfficialChannel("UCaBIVVpHjq6j3tSyxwTE-8Q", "WTA", SourceKind.LEAGUE, sport = Sport.TENNIS),
    OfficialChannel("UCLf7YXb-0PWEeq59Z_q318A", "Olympiacos FC", SourceKind.CLUB, "gre.1"),
    OfficialChannel("UCInZnZ8JYwmIvs8gtNriwSQ", "PAOK FC", SourceKind.CLUB, "gre.1"),
    OfficialChannel("UCX8HprRO1BYnQ6Mu2nB9VsQ", "AEK FC", SourceKind.CLUB, "gre.1"),
    OfficialChannel("UCy8t8HKIih3JQZygj4XTejA", "ARIS FC", SourceKind.CLUB, "gre.1"),
)

@Serializable
data class HighlightVideo(
    val videoId: String,
    val title: String,
    val channelId: String,
    val channelName: String,
    val publishedMs: Long,
    val thumbnail: String? = null,
) {
    val url: String get() = "https://www.youtube.com/watch?v=$videoId"
}

data class LinkedHighlight(val video: HighlightVideo, val match: Match?)

object HighlightLogic {
    // A score has at most three digits a side, so a season like "2026-27" does not count as one.
    private val Wanted = Regex(
        "highlight|resumen|sintesi|σύνοψη|στιγμιότυπα|matchday \\d+|(?<!\\d)\\d{1,3}\\s*[-–]\\s*\\d{1,3}(?!\\d)",
        RegexOption.IGNORE_CASE,
    )
    private val Unwanted = Regex(
        "#shorts?|συνέντευξη|press|προπόνηση|training|παρακάμερα|behind|top \\d+|best |goals of|skills?\\b|trailer|podcast|women|γυναικ|u-?\\d\\d",
        RegexOption.IGNORE_CASE,
    )
    private const val MAX_AFTER_KICKOFF_HOURS = 72L

    fun isHighlight(title: String): Boolean = Wanted.containsMatchIn(title) && !Unwanted.containsMatchIn(title)

    /** The match a video is about: both teams named, published after kick-off and soon after it. */
    fun matchFor(video: HighlightVideo, matches: List<Match>): Match? {
        val published = Instant.ofEpochMilli(video.publishedMs)
        return matches.filter { match ->
            val kickoff = match.kickoff ?: return@filter false
            published.isAfter(kickoff) && Duration.between(kickoff, published).toHours() <= MAX_AFTER_KICKOFF_HOURS &&
                fitsSport(video, match) &&
                TvLogic.mentionsTeam(video.title, match.home.name) && TvLogic.mentionsTeam(video.title, match.away.name)
        }.minByOrNull { Duration.between(it.kickoff, published).abs() }
    }

    /** Clubs like Olympiacos play several sports, so a basketball video must never land on a football match. */
    private fun fitsSport(video: HighlightVideo, match: Match): Boolean {
        val wanted = TvLogic.tvSportOf(match.league.sport)
        OfficialChannels.firstOrNull { it.id == video.channelId }?.sport?.let { return TvLogic.tvSportOf(it) == wanted }
        val named = TvLogic.classify(video.title, video.title)
        return named == wanted || named == TvSport.OTHER
    }

    /** The official videos of one match, the Greek broadcasters first, then the league, then the clubs. */
    fun forMatch(videos: List<HighlightVideo>, match: Match): List<HighlightVideo> =
        videos.filter { matchFor(it, listOf(match)) != null }
            .sortedWith(compareBy<HighlightVideo> { sourceRank(it) }.thenByDescending { it.publishedMs })

    private fun sourceRank(video: HighlightVideo) = when (OfficialChannels.firstOrNull { it.id == video.channelId }?.kind) {
        SourceKind.BROADCASTER -> 0
        SourceKind.LEAGUE -> 1
        SourceKind.CLUB -> 2
        null -> 3
    }

    fun link(videos: List<HighlightVideo>, matches: List<Match>): List<LinkedHighlight> =
        videos.sortedByDescending { it.publishedMs }.map { LinkedHighlight(it, matchFor(it, matches)) }

    fun parseFeed(input: InputStream, channel: OfficialChannel): List<HighlightVideo> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, "UTF-8")
        val out = mutableListOf<HighlightVideo>()
        var inEntry = false
        var id = ""
        var title = ""
        var published = ""
        var thumb: String? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "entry" -> {
                        inEntry = true
                        id = ""
                        title = ""
                        published = ""
                        thumb = null
                    }
                    "yt:videoId" -> if (inEntry) id = parser.nextText().trim()
                    "title" -> if (inEntry && title.isEmpty()) title = parser.nextText().trim()
                    "published" -> if (inEntry) published = parser.nextText().trim()
                    "media:thumbnail" -> if (inEntry) thumb = parser.getAttributeValue(null, "url")
                }
            } else if (event == XmlPullParser.END_TAG && parser.name == "entry") {
                inEntry = false
                val time = runCatching { OffsetDateTime.parse(published).toInstant().toEpochMilli() }.getOrNull()
                if (id.isNotEmpty() && time != null && isHighlight(title)) {
                    out += HighlightVideo(id, title, channel.id, channel.name, time, thumb)
                }
            }
            event = parser.next()
        }
        return out
    }
}

class HighlightsRepository(
    context: Context,
    private val http: OkHttpClient,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val file = File(context.filesDir, "kerkida-highlights.json")
    private val prefs = context.getSharedPreferences("kerkida_highlights", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val _videos = MutableStateFlow(load())
    val videos: StateFlow<List<HighlightVideo>> = _videos.asStateFlow()
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()
    private val _alertsOn = MutableStateFlow(prefs.getBoolean(KEY_ALERTS, false))
    val alertsOn: StateFlow<Boolean> = _alertsOn.asStateFlow()

    private fun load(): List<HighlightVideo> = runCatching {
        if (file.exists()) {
            json.decodeFromString<List<HighlightVideo>>(file.readText()).filter { HighlightLogic.isHighlight(it.title) }
        } else {
            emptyList()
        }
    }.getOrDefault(emptyList())

    fun setAlerts(on: Boolean) {
        _alertsOn.value = on
        prefs.edit().putBoolean(KEY_ALERTS, on).apply()
    }

    fun seenIds(): Set<String> = prefs.getStringSet(KEY_SEEN, emptySet()).orEmpty().toSet()

    fun markSeen(ids: Set<String>) {
        val keep = _videos.value.map { it.videoId }.toSet()
        prefs.edit().putStringSet(KEY_SEEN, (seenIds() + ids).filter { it in keep }.toSet()).apply()
    }

    suspend fun refresh(force: Boolean = false) = withContext(Dispatchers.IO) {
        if (!force && now() - prefs.getLong(KEY_UPDATED, 0) < MIN_GAP_MS && _videos.value.isNotEmpty()) return@withContext
        _isRefreshing.value = true
        try {
            val gate = Semaphore(PARALLEL_FEEDS)
            val fresh = coroutineScope {
                OfficialChannels.map { channel ->
                    async {
                        gate.withPermit {
                            try {
                                val request = Request.Builder().url("$FEED${channel.id}").build()
                                http.newCall(request).execute().use { response ->
                                    if (!response.isSuccessful) throw DataException.Server(response.code)
                                    response.body?.byteStream()?.let { HighlightLogic.parseFeed(it, channel) }.orEmpty()
                                }
                            } catch (_: IOException) {
                                emptyList()
                            } catch (_: DataException) {
                                emptyList()
                            } catch (_: org.xmlpull.v1.XmlPullParserException) {
                                emptyList()
                            }
                        }
                    }
                }.awaitAll().flatten()
            }
            if (fresh.isNotEmpty()) {
                val cutoff = now() - KEEP_MS
                val merged = (fresh + _videos.value).distinctBy { it.videoId }
                    .filter { it.publishedMs >= cutoff }
                    .sortedByDescending { it.publishedMs }
                _videos.value = merged
                file.writeText(json.encodeToString(merged))
                prefs.edit().putLong(KEY_UPDATED, now()).apply()
            }
        } finally {
            _isRefreshing.value = false
        }
    }

    private companion object {
        const val FEED = "https://www.youtube.com/feeds/videos.xml?channel_id="
        const val PARALLEL_FEEDS = 4
        const val KEY_UPDATED = "updated"
        const val KEY_ALERTS = "alerts"
        const val KEY_SEEN = "seen"
        const val MIN_GAP_MS = 20 * 60 * 1000L
        const val KEEP_MS = 14 * 24 * 60 * 60 * 1000L
    }
}
