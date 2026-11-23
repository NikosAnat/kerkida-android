package app.kerkida

import app.kerkida.data.greek.AchaiaParsing
import app.kerkida.data.greek.GblParsing
import app.kerkida.data.greek.LasithiParsing
import app.kerkida.data.model.TeamRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.URLEncoder

/** Reads the real public pages. Runs only with KERKIDA_LIVE_CHECK=1. */
class LiveLocalCheck {
    private val http = OkHttpClient()

    private fun get(url: String): String {
        Thread.sleep(1500)
        val request = Request.Builder().url(url).header("User-Agent", "Kerkida/0.1 (Android)").build()
        return http.newCall(request).execute().use { it.body?.string().orEmpty() }
    }

    @Test
    fun realPagesParse() {
        assumeTrue(System.getenv("KERKIDA_LIVE_CHECK") == "1")
        val out = StringBuilder()
        val gbl = (8..10).flatMap { round ->
            GblParsing.games(get("https://www.esake.gr/el/action/EsakeResults?idchampionship=184645B9&idteam=&idseason=00000001&series=%02d".format(round)), 2026)
        }
        out.appendLine("GBL ${gbl.size} ${gbl.take(2)}")
        val main = get("https://epsachaias.gr/table.asp?diorganosi=" + URLEncoder.encode("Α ΕΡΑΣΙΤΕΧΝΙΚΗ - 1ος ΟΜΙΛΟΣ", "UTF-8"))
        val links = AchaiaParsing.teamLinks(main)
        val table = AchaiaParsing.table(main) { TeamRef(it, it) }
        out.appendLine("ACHAIA teams ${links.size} table ${table.size} top ${table.firstOrNull()}")
        val games = AchaiaParsing.games(get("https://epsachaias.gr/" + links.first().first))
        out.appendLine("ACHAIA games ${games.size} first ${games.firstOrNull()} last ${games.lastOrNull()}")
        val query = URLEncoder.encode("αγωνιστική Α’ ΕΠΣΛ", "UTF-8")
        val posts = Json.parseToJsonElement(get("https://www.anatolh.com/wp-json/wp/v2/posts?search=$query&per_page=40&_fields=date,title,content")).jsonArray
        fun text(html: String) = html.replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ").replace("&#8217;", "’").replace(" ", " ").replace(Regex("\\s+"), " ").trim()
        var parsed = 0
        posts.forEach { post ->
            val title = text(post.jsonObject.getValue("title").jsonObject.getValue("rendered").jsonPrimitive.content)
            if ("ΕΠΣΛ" !in title || "Β’" in title) return@forEach
            val body = text(post.jsonObject.getValue("content").jsonObject.getValue("rendered").jsonPrimitive.content)
            val report = LasithiParsing.report(title, body)
            parsed += report.size
            if ("Η εικόνα της 8ης" in title) {
                out.appendLine("LASITHI r8 $report")
                out.appendLine("LASITHI table ${LasithiParsing.table(body) { TeamRef(it, it) }.map { "${it.team.name} ${it.points}" }}")
            }
            if ("9η" in title) out.appendLine("LASITHI times ${LasithiParsing.kickoffs(body, java.time.LocalDate.parse(post.jsonObject.getValue("date").jsonPrimitive.content.take(10)))}")
        }
        out.appendLine("LASITHI posts ${posts.size} games $parsed")
        File(System.getenv("KERKIDA_LIVE_OUT") ?: "build/live-local.txt").writeText(out.toString())
        assertTrue(gbl.isNotEmpty() && games.isNotEmpty() && parsed > 0)
    }
}
