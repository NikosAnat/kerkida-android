package app.kerkida.data.club

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.kerkida.data.DataException
import app.kerkida.data.greek.GreekLowerLogic
import app.kerkida.data.model.Sport
import app.kerkida.data.model.Team
import app.kerkida.data.net.CachedHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

enum class MomentKind { FOUNDED, GROUND_OPENED, FIRST_TITLE, LATEST_TITLE, STORY }

/** One node on the club's timeline. */
data class ClubMoment(val year: Int, val kind: MomentKind, val text: String)

data class ClubPlace(
    val name: String,
    val about: String? = null,
    val photos: List<String> = emptyList(),
    val lat: Double? = null,
    val lng: Double? = null,
)

data class ClubProfile(
    val about: String?,
    val ground: ClubPlace?,
    val city: ClubPlace?,
    val honours: List<String>,
    val moments: List<ClubMoment>,
    val articleUrl: String?,
)

/** Picks the right club and turns Wikipedia's text into honours and a timeline. */
object ClubProfileLogic {
    private val FootballClubs = setOf("Q476028", "Q103229495", "Q15944511")
    private val BasketballClubs = setOf("Q13393265")
    private val VolleyballClubs = setOf("Q18558301", "Q1194951")
    private val AnyClub = setOf("Q847017", "Q12973014", "Q4438121")
    private val SportItems = mapOf(Sport.FOOTBALL to "Q2736", Sport.BASKETBALL to "Q5372", Sport.VOLLEYBALL to "Q1734")
    private const val GREECE = "Q41"
    private val Generic = setOf(
        "club", "team", "athens", "crete", "city", "patras", "thessaloniki", "piraeus", "women", "men",
        "bc", "fc", "vc", "ac", "as", "gs", "ae", "ao", "ne", "bk", "cf", "sc",
    )
    val HonoursHead = Regex("^(τίτλοι|τιμές|τιμητικές διακρίσεις|διακρίσεις|επιτυχίες|τρόπαια|honours|honors|achievements|titles)", RegexOption.IGNORE_CASE)
    val HistoryHead = Regex("^(ιστορία|ιστορικό|history)", RegexOption.IGNORE_CASE)
    private val Year = Regex("""\b(1[89]\d{2}|20\d{2})\b""")
    private val Milestone = Regex(
        "ιδρύθηκε|ίδρυση|ανέβηκε|άνοδο|υποβιβάστηκε|υποβιβασμ|κατέκτησε|πρωταθλήτρια|συγχων|τελικό|founded|promoted|relegated|won the|merged|champions|reached the final",
        RegexOption.IGNORE_CASE,
    )
    private const val MAX_MOMENTS = 14

    fun JsonElement?.asObject() = this as? JsonObject
    fun JsonElement?.asArray() = this as? JsonArray
    fun JsonElement?.asText() = (this as? JsonPrimitive)?.contentOrNull

    private fun snaks(entity: JsonObject, property: String): List<JsonElement> =
        entity["claims"].asObject()?.get(property).asArray().orEmpty().mapNotNull {
            it.asObject()?.get("mainsnak").asObject()?.get("datavalue").asObject()?.get("value")
        }

    fun itemIds(entity: JsonObject, property: String) = snaks(entity, property).mapNotNull { it.asObject()?.get("id").asText() }

    fun image(entity: JsonObject) = snaks(entity, "P18").firstNotNullOfOrNull { it.asText() }

    fun year(entity: JsonObject, property: String): Int? = snaks(entity, property).firstNotNullOfOrNull { value ->
        value.asObject()?.get("time").asText()?.let { Regex("""^\+(\d{4})""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
    }

    fun coordinates(entity: JsonObject): Pair<Double, Double>? = snaks(entity, "P625").firstNotNullOfOrNull { value ->
        val spot = value.asObject() ?: return@firstNotNullOfOrNull null
        val lat = spot["latitude"].asText()?.toDoubleOrNull()
        val lng = spot["longitude"].asText()?.toDoubleOrNull()
        if (lat != null && lng != null) lat to lng else null
    }

    fun label(entity: JsonObject): String? {
        val labels = entity["labels"].asObject() ?: return null
        return (labels["el"] ?: labels["en"]).asObject()?.get("value").asText()
    }

    private fun names(entity: JsonObject): List<String> {
        val labels = entity["labels"].asObject()?.values.orEmpty().mapNotNull { it.asObject()?.get("value").asText() }
        val aliases = entity["aliases"].asObject()?.values.orEmpty().flatMap { list ->
            list.asArray().orEmpty().mapNotNull { it.asObject()?.get("value").asText() }
        }
        return labels + aliases
    }

    private fun fitsSport(entity: JsonObject, sport: Sport): Boolean {
        val kinds = itemIds(entity, "P31").toSet()
        val wanted = when (sport) {
            Sport.BASKETBALL -> BasketballClubs
            Sport.VOLLEYBALL -> VolleyballClubs
            else -> FootballClubs
        }
        val played = itemIds(entity, "P641")
        val item = SportItems[sport] ?: SportItems.getValue(Sport.FOOTBALL)
        if (played.isNotEmpty() && item !in played) return false
        return kinds.any { it in wanted } || (item in played && kinds.any { it in AnyClub })
    }

    private fun fitsName(entity: JsonObject, teamName: String): Boolean {
        val words = GreekLowerLogic.nameKey(teamName).split(' ').filter { it.length >= 3 && it !in Generic }
        if (words.isEmpty()) return true
        val known = names(entity).map(GreekLowerLogic::nameKey)
        return words.any { word -> known.any { word in it } }
    }

    /** A wrong club is worse than none, so sport, name and country must all agree. */
    fun pick(candidates: List<JsonObject>, teamName: String, sport: Sport, mustBeGreek: Boolean): JsonObject? =
        candidates.firstOrNull { entity ->
            fitsSport(entity, sport) && fitsName(entity, teamName) && (!mustBeGreek || GREECE in itemIds(entity, "P17"))
        }

    fun plain(html: String): String = html
        .replace(Regex("<(style|script|sup)[^>]*>.*?</\\1>", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&#160;", " ").replace("&nbsp;", " ").replace("&amp;", "&").replace("&#39;", "'")
        .replace("&gt;", ">").replace("&lt;", "<").replace("&quot;", "\"").replace("&#91;", "[").replace("&#93;", "]")
        .replace(Regex("\\[[^]]{0,24}]"), " ")
        .replace(Regex("\\s+"), " ")
        .replace(" ,", ",").replace(" .", ".")
        .trim()

    fun honours(html: String): List<String> {
        val body = html.replace(Regex("<(style|script|sup)[^>]*>.*?</\\1>", RegexOption.DOT_MATCHES_ALL), " ")
        val listed = Regex("<li\\b([^>]*)>(.*?)</li>", RegexOption.DOT_MATCHES_ALL).findAll(body)
            .filter { "toclevel" !in it.groupValues[1] }
            .map { plain(it.groupValues[2]) }
        val tabled = Regex("<tr[^>]*>(.*?)</tr>", RegexOption.DOT_MATCHES_ALL).findAll(body).map { row ->
            Regex("<t[dh][^>]*>(.*?)</t[dh]>", RegexOption.DOT_MATCHES_ALL).findAll(row.groupValues[1])
                .map { plain(it.groupValues[1]) }.filter(String::isNotEmpty).joinToString(": ")
        }
        return (listed + tabled)
            .filter { it.length in 6..170 && it.any(Char::isDigit) && it.any(Char::isLetter) && !it.first().isDigit() }
            .distinct().take(30).toList()
    }

    /** The first and the latest win of each competition, read from a line like "Cup (3): 1990, 2001, 2015". */
    fun titleMoments(honours: List<String>): List<ClubMoment> = honours.flatMap { line ->
        val cut = line.indexOfFirst { it == '(' || it == ':' }
        if (cut <= 2) return@flatMap emptyList()
        val name = line.take(cut).trim().trimEnd(':', '-', '–').trim()
            .substringAfterLast('>').trim().trimStart('↑', ' ')
            .replace(Regex("\\s+(Πρωταθλήτρια|Πρωταθλητής|Κυπελλούχος)$"), "")
        val years = Year.findAll(line.drop(cut)).map { it.value.toInt() }.toList()
        if (years.isEmpty()) return@flatMap emptyList()
        val first = years.min()
        val last = years.max()
        listOfNotNull(
            ClubMoment(first, MomentKind.FIRST_TITLE, name),
            ClubMoment(last, MomentKind.LATEST_TITLE, name).takeIf { last != first },
        )
    }

    private val AboutTheClub = Regex("ομάδα|σύλλογ|club|team", RegexOption.IGNORE_CASE)
    private val LocalTitle = Regex("Πατρών|Ε[.]Π[.]Σ|ΕΠΣ|Αχαΐας|Τοπικ|Περιφερειακ|Regional|Local", RegexOption.IGNORE_CASE)

    /** Word stems of the club's name, so that "Ολυμπιακός" also finds "Ολυμπιακού". */
    fun clubStems(vararg names: String?): List<String> = names.filterNotNull()
        .flatMap { it.substringBefore('(').split(' ', '.', '-') }
        .map { GreekLowerLogic.nameKey(it) }
        .filter { it.length >= 4 && it !in Generic }
        .map { it.dropLast(2) }
        .distinct()

    private fun aboutClub(sentence: String, stems: List<String>): Boolean {
        if (stems.isEmpty() || AboutTheClub.containsMatchIn(sentence)) return true
        val key = GreekLowerLogic.nameKey(sentence)
        return stems.any { it in key }
    }

    fun storyMoments(historyHtml: String, stems: List<String> = emptyList()): List<ClubMoment> {
        val paragraphs = Regex("<p\\b[^>]*>(.*?)</p>", RegexOption.DOT_MATCHES_ALL).findAll(historyHtml).map { plain(it.groupValues[1]) }
        return paragraphs.flatMap { it.split(Regex("(?<=[.;])\\s+(?=[\\p{Lu}Α-Ω])")).asSequence() }
            .filter { it.length in 30..190 && Milestone.containsMatchIn(it) && aboutClub(it, stems) }
            .mapNotNull { sentence -> Year.find(sentence)?.let { ClubMoment(it.value.toInt(), MomentKind.STORY, sentence.trim()) } }
            .distinctBy { it.year }
            .toList()
    }

    /** Founding first, then titles and story in date order, keeping the list short enough to read. */
    fun timeline(founded: Int?, groundName: String?, groundOpened: Int?, titles: List<ClubMoment>, story: List<ClubMoment>): List<ClubMoment> {
        val fixed = listOfNotNull(
            founded?.let { ClubMoment(it, MomentKind.FOUNDED, "") },
            if (groundOpened != null && groundName != null && (founded == null || groundOpened >= founded)) ClubMoment(groundOpened, MomentKind.GROUND_OPENED, groundName) else null,
        )
        val after = { moment: ClubMoment -> founded == null || moment.year >= founded }
        val wins = titles.filter(after).sortedBy { it.year }.distinctBy { it.year to it.text }
        val taken = (fixed + wins).map { it.year }.toSet()
        val tales = story.filter(after).filter { it.year !in taken }
        val room = (MAX_MOMENTS - fixed.size).coerceAtLeast(0)
        val chosenWins = wins.sortedWith(
            compareBy({ LocalTitle.containsMatchIn(it.text) }, { if (it.kind == MomentKind.FIRST_TITLE) 0 else 1 }),
        ).take(room)
        val chosenTales = tales.take((room - chosenWins.size).coerceAtLeast(0))
        return (fixed + chosenWins + chosenTales).sortedWith(compareBy({ it.year }, { it.kind.ordinal }))
    }

    /** The article's own founding year wins, because Wikidata sometimes dates a later section of the club. */
    fun foundedIn(text: String?): Int? = text?.let {
        Regex("""(?:[Ιι]δρύθηκε|[Ff]ounded|[Ee]stablished)[^.]{0,60}?\b(1[89]\d{2}|20\d{2})\b""")
            .find(it)?.groupValues?.get(1)?.toIntOrNull()
    }

    fun photoUrl(file: String): String =
        "https://commons.wikimedia.org/wiki/Special:FilePath/" +
            URLEncoder.encode(file.replace(' ', '_'), "UTF-8").replace("+", "%20") + "?width=720"

    private val NotAPhoto = Regex("map|flag|coat|logo|seal|icon|locator|emblem|wappen|χάρτης|σημαία|σήμα|[.]svg|[.]png", RegexOption.IGNORE_CASE)

    /** Wide photos from the article. Maps, flags, crests and portraits are left out. */
    fun gallery(root: JsonObject, mainFile: String?): List<String> {
        val main = mainFile?.replace('_', ' ')
        return root["query"].asObject()?.get("pages").asArray().orEmpty()
            .mapNotNull { it.asObject() }
            .sortedBy { it["index"].asText()?.toIntOrNull() ?: Int.MAX_VALUE }
            .mapNotNull { page ->
                val title = page["title"].asText() ?: return@mapNotNull null
                if (NotAPhoto.containsMatchIn(title) || (main != null && title.endsWith(main))) return@mapNotNull null
                val info = page["imageinfo"].asArray()?.firstOrNull().asObject() ?: return@mapNotNull null
                val width = info["width"].asText()?.toIntOrNull() ?: 0
                val height = info["height"].asText()?.toIntOrNull() ?: 0
                if (info["mime"].asText() != "image/jpeg" || width < 640 || width < height) return@mapNotNull null
                info["thumburl"].asText()
            }
    }

    /** Town articles often lead with a grid of photos, which looks wrong inside a carousel. */
    fun isCollage(file: String) = Regex("collage|montage|montaje|mosaic|κολάζ|μοντάζ", RegexOption.IGNORE_CASE).containsMatchIn(file)

    fun searchNames(name: String): List<String> {
        val short = name.split(' ').filter { it.lowercase() !in Generic }.joinToString(" ")
        return listOf(name, short).filter(String::isNotBlank).distinct()
    }
}

/** Club, ground and town details from Wikidata and Wikipedia, read rarely and kept for a month. */
class ClubProfiles(
    private val http: CachedHttpClient,
    private val images: OkHttpClient,
    private val imageDir: File,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val known = ConcurrentHashMap<String, ClubProfile>()

    private suspend fun read(url: String): JsonObject =
        json.parseToJsonElement(http.get(url, MONTH).body) as? JsonObject ?: throw DataException.BadData()

    private fun encode(text: String) = URLEncoder.encode(text, "UTF-8")

    private suspend fun entities(ids: List<String>, props: String): Map<String, JsonObject> {
        if (ids.isEmpty()) return emptyMap()
        val root = read("$WIKIDATA?format=json&action=wbgetentities&ids=${encode(ids.joinToString("|"))}&props=${encode(props)}&languages=${encode("el|en")}&sitefilter=${encode("elwiki|enwiki")}")
        return with(ClubProfileLogic) {
            root["entities"].asObject().orEmpty().mapNotNull { (id, value) -> value.asObject()?.let { id to it } }.toMap()
        }
    }

    private suspend fun findClub(team: Team): JsonObject? {
        val sport = team.league?.sport ?: Sport.FOOTBALL
        val greek = team.id.startsWith("grl:") || team.country == "Greece"
        for (name in ClubProfileLogic.searchNames(team.name)) {
            val found = linkedSetOf<String>()
            for (language in listOf("el", "en")) {
                val root = read("$WIKIDATA?format=json&action=wbsearchentities&search=${encode(name)}&language=$language&uselang=$language&type=item&limit=8")
                with(ClubProfileLogic) {
                    root["search"].asArray().orEmpty().mapNotNullTo(found) { it.asObject()?.get("id").asText() }
                }
            }
            if (found.isEmpty()) continue
            val loaded = entities(found.take(20), "claims|sitelinks|labels|aliases")
            ClubProfileLogic.pick(found.mapNotNull { loaded[it] }, team.name, sport, greek)?.let { return it }
        }
        return null
    }

    private fun article(entity: JsonObject): Pair<String, String>? = with(ClubProfileLogic) {
        val links = entity["sitelinks"].asObject() ?: return null
        links["elwiki"].asObject()?.get("title").asText()?.let { return "el" to it }
        links["enwiki"].asObject()?.get("title").asText()?.let { "en" to it }
    }

    private suspend fun intro(page: Pair<String, String>?, sentences: Int): String? {
        page ?: return null
        val root = read("https://${page.first}.wikipedia.org/w/api.php?format=json&formatversion=2&action=query&prop=extracts&exintro=1&explaintext=1&exsentences=$sentences&titles=${encode(page.second)}")
        return with(ClubProfileLogic) {
            root["query"].asObject()?.get("pages").asArray()?.firstOrNull().asObject()?.get("extract").asText()?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    private suspend fun section(page: Pair<String, String>, head: Regex): String? = with(ClubProfileLogic) {
        val base = "https://${page.first}.wikipedia.org/w/api.php?format=json&formatversion=2&action=parse&page=${encode(page.second)}"
        val index = read("$base&prop=sections")["parse"].asObject()?.get("sections").asArray().orEmpty()
            .mapNotNull { it.asObject() }
            .firstOrNull { head.containsMatchIn(plain(it["line"].asText().orEmpty())) }
            ?.get("index").asText() ?: return null
        read("$base&prop=text&section=$index&disableeditsection=1")["parse"].asObject()?.get("text").asText()
    }

    private suspend fun photosOf(page: Pair<String, String>?, main: String?): List<String> {
        page ?: return emptyList()
        val root = read("https://${page.first}.wikipedia.org/w/api.php?format=json&formatversion=2&action=query&generator=images&gimlimit=30&prop=imageinfo&iiprop=${encode("url|mime|size")}&iiurlwidth=720&titles=${encode(page.second)}")
        return ClubProfileLogic.gallery(root, main)
    }

    private suspend fun place(entity: JsonObject?, fallback: String?): ClubPlace? {
        val name = entity?.let(ClubProfileLogic::label) ?: fallback ?: return null
        val about = entity?.let { runCatching { intro(article(it), 3) }.getOrNull() }
        val spot = entity?.let(ClubProfileLogic::coordinates)
        val main = entity?.let(ClubProfileLogic::image)
        val more = entity?.let { runCatching { photosOf(article(it), main) }.getOrNull() }.orEmpty()
        val first = main?.takeUnless { ClubProfileLogic.isCollage(it) && more.isNotEmpty() }?.let(ClubProfileLogic::photoUrl)
        val photos = (listOfNotNull(first) + more).distinct().take(MAX_PHOTOS)
        return ClubPlace(name, about, photos, spot?.first, spot?.second)
    }

    suspend fun load(team: Team): ClubProfile? {
        known[team.id]?.let { return it }
        return try {
            val club = findClub(team) ?: return null
            val groundId = ClubProfileLogic.itemIds(club, "P115").firstOrNull()
            val cityId = (ClubProfileLogic.itemIds(club, "P159") + ClubProfileLogic.itemIds(club, "P131")).firstOrNull()
            val around = entities(listOfNotNull(groundId, cityId).distinct(), "claims|sitelinks|labels")
            val groundEntity = groundId?.let(around::get)
            val page = article(club)
            val honoursHtml = page?.let { runCatching { section(it, ClubProfileLogic.HonoursHead) }.getOrNull() }
            val historyHtml = page?.let { runCatching { section(it, ClubProfileLogic.HistoryHead) }.getOrNull() }
            val honours = honoursHtml?.let(ClubProfileLogic::honours).orEmpty()
            val ground = place(groundEntity, team.stadium)
            val opened = groundEntity?.let { ClubProfileLogic.year(it, "P1619") ?: ClubProfileLogic.year(it, "P571") }
            val about = runCatching { intro(page, 5) }.getOrNull() ?: team.description
            val profile = ClubProfile(
                about = about,
                ground = ground,
                city = place(cityId?.let(around::get), team.location),
                honours = honours,
                moments = ClubProfileLogic.timeline(
                    ClubProfileLogic.foundedIn(about) ?: ClubProfileLogic.year(club, "P571") ?: team.foundedYear,
                    ground?.name,
                    opened,
                    ClubProfileLogic.titleMoments(honours),
                    historyHtml?.let { ClubProfileLogic.storyMoments(it, ClubProfileLogic.clubStems(team.name, ClubProfileLogic.label(club))) }.orEmpty(),
                ),
                articleUrl = page?.let { "https://${it.first}.wikipedia.org/wiki/${encode(it.second.replace(' ', '_'))}" },
            )
            known[team.id] = profile
            profile
        } catch (_: DataException) {
            null
        }
    }

    /** Photos are saved so that each one is downloaded only once. */
    suspend fun photo(url: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            imageDir.mkdirs()
            val file = File(imageDir, url.hashCode().toUInt().toString(16))
            if (!file.exists()) {
                images.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) return@runCatching null
                    val bytes = response.body?.bytes() ?: return@runCatching null
                    file.writeBytes(bytes)
                }
            }
            BitmapFactory.decodeFile(file.path)
        }.getOrNull()
    }

    private companion object {
        const val WIKIDATA = "https://www.wikidata.org/w/api.php"
        const val MONTH = 30L * 24 * 3_600_000
        const val MAX_PHOTOS = 8
    }
}
