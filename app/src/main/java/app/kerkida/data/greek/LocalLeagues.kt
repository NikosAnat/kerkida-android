package app.kerkida.data.greek

import app.kerkida.data.DataException
import app.kerkida.data.Sourced
import app.kerkida.data.model.LeagueDetails
import app.kerkida.data.model.LeagueFixtures
import app.kerkida.data.model.LeagueRef
import app.kerkida.data.model.Match
import app.kerkida.data.model.MatchDetails
import app.kerkida.data.model.MatchStatus
import app.kerkida.data.model.StandingRow
import app.kerkida.data.model.Standings
import app.kerkida.data.model.Team
import app.kerkida.data.model.TeamMatches
import app.kerkida.data.model.TeamRef
import app.kerkida.data.net.CachedHttpClient
import app.kerkida.stadium.Stadium
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.ConcurrentHashMap

private val Athens: ZoneId = ZoneId.of("Europe/Athens")

private fun plainText(html: String): String = html
    .replace(Regex("<[^>]+>"), " ")
    .replace(Regex("&#(\\d+);")) { it.groupValues[1].toInt().toChar().toString() }
    .replace("&nbsp;", " ").replace("&amp;", "&").replace("&#8217;", "’").replace(" ", " ")
    .replace(Regex("\\s+"), " ").trim()

private fun localTeamId(prefix: String, name: String) =
    "${GreekLowerLogic.TEAM_PREFIX}$prefix-${Integer.toHexString(GreekLowerLogic.nameKey(name).hashCode())}"

/** What one local competition currently looks like. */
data class LocalData(val teams: List<TeamRef>, val games: List<Match>, val table: List<StandingRow>)

/** A local association or paper whose public pages list a competition. */
interface LocalFeed {
    val leagues: List<LeagueRef>
    suspend fun fetch(league: LeagueRef, refresh: Boolean): LocalData
    fun stadiumOf(game: Match): Stadium?
}

data class AchaiaGame(
    val id: String,
    val round: Int,
    val kickoff: Instant?,
    val home: String,
    val away: String,
    val homeScore: Int?,
    val awayScore: Int?,
    val venue: String?,
)

object AchaiaParsing {
    private val TeamLink = Regex("""<a class="ach-name ach-team-link" href="([^"]+)">\s*(?:\d+\.\s*)?([^<]+)</a>""")
    private val Row = Regex("""<tr>\s*<td class="tv-date">(.*?)</tr>""", RegexOption.DOT_MATCHES_ALL)
    private val Cell = Regex("""<td[^>]*>(.*?)</td>""", RegexOption.DOT_MATCHES_ALL)
    private val When = Regex("""(\d{2})\.(\d{2})\.(\d{4})(?:\s*<span[^>]*>\s*(\d{1,2})[.:](\d{2}))?""")
    private val Score = Regex("""(\d+)\s*[-–]\s*(\d+)""")

    fun teamLinks(html: String): List<Pair<String, String>> =
        TeamLink.findAll(html).map { it.groupValues[1].replace("&amp;", "&") to plainText(it.groupValues[2]) }.distinctBy { it.second }.toList()

    fun table(html: String, team: (String) -> TeamRef): List<StandingRow> {
        val body = html.substringAfter("epsm-standings-table", "").substringBefore("</tbody>")
        return body.split("<tr>").drop(1).mapNotNull { row ->
            val name = TeamLink.find(row)?.groupValues?.get(2)?.let(::plainText) ?: return@mapNotNull null
            val cells = Cell.findAll(row).map { plainText(it.groupValues[1]) }.toList()
            if (cells.size < 5) return@mapNotNull null
            val goals = Score.find(cells[3]) ?: return@mapNotNull null
            val record = cells[4].split('-').mapNotNull(String::toIntOrNull)
            if (record.size != 3) return@mapNotNull null
            val scored = goals.groupValues[1].toInt()
            val conceded = goals.groupValues[2].toInt()
            StandingRow(
                0, team(name), cells[2].toIntOrNull() ?: 0, record[0], record[1], record[2],
                scored, conceded, scored - conceded, cells[1].toIntOrNull() ?: 0,
            )
        }.mapIndexed { index, row -> row.copy(rank = index + 1) }
    }

    fun games(html: String): List<AchaiaGame> = Row.findAll(html).mapNotNull { found ->
        val row = "<td class=\"tv-date\">" + found.groupValues[1]
        val cells = Cell.findAll(row).map { it.groupValues[1] }.toList()
        if (cells.size < 7) return@mapNotNull null
        val id = Regex("""game_number=(\d+)""").find(row)?.groupValues?.get(1) ?: return@mapNotNull null
        val moment = When.find(cells[0])
        val kickoff = moment?.let {
            val (day, month, year, hour, minute) = it.destructured
            if (hour.isEmpty()) return@let null
            runCatching {
                LocalDateTime.of(year.toInt(), month.toInt(), day.toInt(), hour.toInt(), minute.toInt()).atZone(Athens).toInstant()
            }.getOrNull()
        }
        val score = Score.find(plainText(cells[3]))
        AchaiaGame(
            id = id,
            round = plainText(cells[1]).toIntOrNull() ?: return@mapNotNull null,
            kickoff = kickoff,
            home = plainText(cells[2]),
            away = plainText(cells[4]),
            homeScore = score?.groupValues?.get(1)?.toIntOrNull(),
            awayScore = score?.groupValues?.get(2)?.toIntOrNull(),
            venue = plainText(cells[5]).takeIf { it.isNotEmpty() },
        )
    }.toList()
}

private fun statusFor(kickoff: Instant?, hasScore: Boolean, now: Instant): MatchStatus = when {
    hasScore -> MatchStatus.FINISHED
    kickoff == null -> MatchStatus.UNKNOWN
    now.isBefore(kickoff) -> MatchStatus.SCHEDULED
    // These pages have no live feed, so a game past kick-off is never called live.
    else -> MatchStatus.UNKNOWN
}

/** The Achaia FA's public league pages. Each club page lists its whole season. */
class AchaiaFeed(
    private val http: CachedHttpClient,
    private val clock: () -> Instant = Instant::now,
) : LocalFeed {
    private val names = linkedMapOf(
        "grl.ach.a1" to ("ΕΠΣ Αχαΐας · Α' 1ος Όμιλος" to "Α ΕΡΑΣΙΤΕΧΝΙΚΗ - 1ος ΟΜΙΛΟΣ"),
        "grl.ach.a2" to ("ΕΠΣ Αχαΐας · Α' 2ος Όμιλος" to "Α ΕΡΑΣΙΤΕΧΝΙΚΗ - 2ος ΟΜΙΛΟΣ"),
        "grl.ach.b1" to ("ΕΠΣ Αχαΐας · Β' 1ος Όμιλος" to "Β ΕΡΑΣΙΤΕΧΝΙΚΗ - 1ος ΟΜΙΛΟΣ"),
        "grl.ach.b2" to ("ΕΠΣ Αχαΐας · Β' 2ος Όμιλος" to "Β ΕΡΑΣΙΤΕΧΝΙΚΗ - 2ος ΟΜΙΛΟΣ"),
        "grl.ach.g1" to ("ΕΠΣ Αχαΐας · Γ' 1ος Όμιλος" to "Γ ΕΡΑΣΙΤΕΧΝΙΚΗ - 1ος ΟΜΙΛΟΣ"),
        "grl.ach.g2" to ("ΕΠΣ Αχαΐας · Γ' 2ος Όμιλος" to "Γ ΕΡΑΣΙΤΕΧΝΙΚΗ - 2ος ΟΜΙΛΟΣ"),
    )

    override val leagues = names.map { (id, value) -> LeagueRef(id, value.first, country = "Greece") }

    override suspend fun fetch(league: LeagueRef, refresh: Boolean): LocalData {
        val page = names[league.id]?.second ?: throw DataException.NotFound()
        val query = URLEncoder.encode(page, "UTF-8")
        val main = http.get("$SITE/table.asp?diorganosi=$query", TTL_TABLE, refresh).body
        val links = AchaiaParsing.teamLinks(main)
        if (links.isEmpty()) throw DataException.BadData()
        fun ref(name: String) = TeamRef(localTeamId("ach", name), name)
        val found = LinkedHashMap<String, AchaiaGame>()
        for ((href, _) in links) {
            try {
                AchaiaParsing.games(http.get("$SITE/$href", TTL_TEAM, refresh).body).forEach { found[it.id] = it }
            } catch (error: DataException) {
                if (found.isEmpty()) throw error
            }
        }
        val now = clock()
        val games = found.values.map { game ->
            val hasScore = game.homeScore != null && game.awayScore != null
            Match(
                id = "${league.id}~${game.id}",
                league = league,
                home = ref(game.home),
                away = ref(game.away),
                kickoff = game.kickoff,
                date = game.kickoff?.atZone(Athens)?.toLocalDate(),
                status = statusFor(game.kickoff, hasScore, now),
                homeScore = game.homeScore,
                awayScore = game.awayScore,
                round = game.round,
                season = SEASON,
                venue = game.venue,
            )
        }
        return LocalData(links.map { ref(it.second) }, games, AchaiaParsing.table(main, ::ref))
    }

    /** Named places first. An unnamed ground is placed at Patras and labelled as approximate. */
    override fun stadiumOf(game: Match): Stadium? {
        if (!game.league.id.startsWith("grl.ach.")) return null
        val text = GreekLowerLogic.nameKey("${game.venue.orEmpty()} ${game.home.name}")
        val town = Towns.entries.firstOrNull { GreekLowerLogic.nameKey(it.key) in text }
        return if (town != null) {
            Stadium(game.venue ?: game.home.name, town.key, "Greece", town.value.first, town.value.second)
        } else {
            Stadium(game.venue ?: game.home.name, "Αχαΐα (κατά προσέγγιση)", "Greece", 38.2466, 21.7346)
        }
    }

    private companion object {
        const val SITE = "https://epsachaias.gr"
        const val SEASON = "2026-2027"
        const val TTL_TABLE = 1_800_000L
        const val TTL_TEAM = 3_600_000L
        val Towns = linkedMapOf(
            "Κάτω Αχαΐα" to (38.1375 to 21.5536), "Κατω Αχαιας" to (38.1375 to 21.5536),
            "Αίγιο" to (38.2505 to 22.0852), "Αιγίου" to (38.2505 to 22.0852), "Καλάβρυτα" to (38.0325 to 22.1103),
            "Ακράτα" to (38.1547 to 22.3122), "Διακοπτό" to (38.1958 to 22.1994), "Ρίο" to (38.2986 to 21.7886),
            "Παραλία" to (38.2197 to 21.7208), "Βραχνέικα" to (38.1800 to 21.6500), "Οβρυά" to (38.1950 to 21.7600),
            "Τριταία" to (38.0333 to 21.7000), "Χαλανδρίτσα" to (38.1100 to 21.7900), "Λάππα" to (38.1000 to 21.4800),
            "Κρήνη" to (38.2200 to 21.7700), "Μποζαΐτικα" to (38.2800 to 21.7600), "Πατρών" to (38.2466 to 21.7346),
            "Πάτρα" to (38.2466 to 21.7346),
        )
    }
}

data class LasithiGame(val round: Int, val home: String, val away: String, val homeScore: Int?, val awayScore: Int?, val kickoff: Instant?)

/** Reads the weekly round reports of the Anatoli newspaper for the Lasithi first division. */
object LasithiParsing {
    val Clubs = listOf(
        "Ίτανος Παλαικάστρου", "Ίτανος", "Νίκη Σητείας", "Νίκη", "ΑΟ Βραχασίου", "ΑΟ Κριτσάς", "Κόρφος Ελούντας",
        "Αναγέννηση", "ΑΟΑΝ Β΄", "ΑΟΑΝ Β", "ΑΟΣΚ", "ΟΦΙ", "ΑΣΙ",
    )
    private val Club = Clubs.joinToString("|") { Regex.escape(it) }
    private val Pair = Regex("""(?<![\p{L}])($Club)\s*[-–]\s*($Club)(?![\p{L}])(?:\s+(\d{1,2})-(\d{1,2}))?""")
    private const val DayNames = "Δευτέρα|Τρίτη|Τετάρτη|Πέμπτη|Παρασκευή|Σάββατο|Κυριακή"
    private val Heading = Regex("""($DayNames),\s*(\d{1,2})[.:](\d{2})""")
    private val Sentence = Regex("""($DayNames)\s+στις\s+(\d{1,2})[.:](\d{2})""")
    private const val HEADING_WINDOW = 70
    private const val SENTENCE_WINDOW = 130
    private val Round = Regex("""(\d{1,2})\s*η""")
    private val TableRow = Regex("""\d{1,2}\.\s*($Club)\s*(?:\((\d+)-(\d+)\)\s*)?(\d+)-(\d+)-(\d+)\s*(?:\((\d+)-(\d+)\)\s*)?(\d+)""")
    private val Days = mapOf(
        "Δευτέρα" to DayOfWeek.MONDAY, "Τρίτη" to DayOfWeek.TUESDAY, "Τετάρτη" to DayOfWeek.WEDNESDAY, "Πέμπτη" to DayOfWeek.THURSDAY,
        "Παρασκευή" to DayOfWeek.FRIDAY, "Σάββατο" to DayOfWeek.SATURDAY, "Κυριακή" to DayOfWeek.SUNDAY,
    )

    fun canonical(name: String) = when (name) {
        "Ίτανος" -> "Ίτανος Παλαικάστρου"
        "Νίκη" -> "Νίκη Σητείας"
        "ΑΟΑΝ Β΄" -> "ΑΟΑΝ Β"
        "ΟΦΙ" -> "ΟΦ Ιεράπετρας"
        else -> name
    }

    fun roundOf(title: String): Int? = Round.find(title)?.groupValues?.get(1)?.toIntOrNull()

    /** The finished games and the next round listed in one report. */
    fun report(title: String, body: String): List<LasithiGame> {
        val round = roundOf(title) ?: roundOf(body) ?: return emptyList()
        val tableAt = body.indexOf("Η βαθμολογία").takeIf { it >= 0 } ?: body.length
        val nextAt = body.indexOf("Η επόμενη").takeIf { it >= 0 }
        val results = Pair.findAll(body.substring(0, tableAt)).filter { it.groupValues[3].isNotEmpty() }.map {
            LasithiGame(round, canonical(it.groupValues[1]), canonical(it.groupValues[2]), it.groupValues[3].toInt(), it.groupValues[4].toInt(), null)
        }.toList()
        val coming = nextAt?.let { start ->
            val text = body.substring(start)
            val number = roundOf(text.take(30)) ?: (round + 1)
            Pair.findAll(text).filter { it.groupValues[3].isEmpty() }.map {
                LasithiGame(number, canonical(it.groupValues[1]), canonical(it.groupValues[2]), null, null, null)
            }.toList()
        }.orEmpty()
        return results + coming
    }

    /**
     * Kick-off times from a round preview, dated from the day the article was published.
     * A heading such as "Κυριακή, 15.00" wins over a sentence such as "την Κυριακή στις 15.00 με τους αγώνες".
     */
    fun kickoffs(body: String, published: LocalDate): Map<String, Instant> {
        val found = LinkedHashMap<String, Instant>()
        fun collect(anchors: Regex, window: Int) {
            val hits = anchors.findAll(body).toList()
            hits.forEachIndexed { index, hit ->
                val day = Days[hit.groupValues[1]] ?: return@forEachIndexed
                val at = runCatching {
                    published.with(TemporalAdjusters.nextOrSame(day))
                        .atTime(hit.groupValues[2].toInt(), hit.groupValues[3].toInt()).atZone(Athens).toInstant()
                }.getOrNull() ?: return@forEachIndexed
                val end = minOf(hit.range.last + window, hits.getOrNull(index + 1)?.range?.first ?: body.length, body.length)
                Pair.findAll(body.substring(hit.range.last + 1, end)).filter { it.groupValues[3].isEmpty() }.forEach {
                    found.putIfAbsent("${canonical(it.groupValues[1])}|${canonical(it.groupValues[2])}", at)
                }
            }
        }
        collect(Heading, HEADING_WINDOW)
        collect(Sentence, SENTENCE_WINDOW)
        return found
    }

    fun table(body: String, team: (String) -> TeamRef): List<StandingRow> {
        val text = body.substringAfter("Η βαθμολογία", "").substringBefore("Η επόμενη")
        return TableRow.findAll(text).mapIndexed { index, row ->
            val v = row.groupValues
            val scored = (v[2].ifEmpty { v[7] }).toIntOrNull() ?: 0
            val conceded = (v[3].ifEmpty { v[8] }).toIntOrNull() ?: 0
            val won = v[4].toInt()
            val drawn = v[5].toInt()
            val lost = v[6].toInt()
            StandingRow(index + 1, team(canonical(v[1])), won + drawn + lost, won, drawn, lost, scored, conceded, scored - conceded, v[9].toInt())
        }.toList()
    }
}

/** Lasithi first division, the league of OF Ierapetra. It depends on the wording of weekly articles. */
class LasithiFeed(
    private val http: CachedHttpClient,
    private val clock: () -> Instant = Instant::now,
) : LocalFeed {
    override val leagues = listOf(LeagueRef(LEAGUE_ID, "ΕΠΣ Λασιθίου · Α' Κατηγορία", country = "Greece"))
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun fetch(league: LeagueRef, refresh: Boolean): LocalData {
        val query = URLEncoder.encode("αγωνιστική Α’ ΕΠΣΛ", "UTF-8")
        val body = http.get("$POSTS?search=$query&per_page=40&_fields=date,title,content", TTL, refresh).body
        val posts = try {
            json.parseToJsonElement(body).jsonArray.map { it.jsonObject }
        } catch (error: IllegalArgumentException) {
            throw DataException.BadData(error)
        }.mapNotNull(::readPost).filter { it.isFirstDivision && it.date >= SeasonStart }.sortedBy { it.date }
        fun ref(name: String) = TeamRef(localTeamId("las", name), name)
        val times = HashMap<String, Instant>()
        val games = LinkedHashMap<String, LasithiGame>()
        posts.forEach { post ->
            times += LasithiParsing.kickoffs(post.text, post.date)
            LasithiParsing.report(post.title, post.text).forEach { game ->
                val key = "${game.round}|${game.home}|${game.away}"
                val known = games[key]
                if (known == null || game.homeScore != null) games[key] = game
            }
        }
        val now = clock()
        val matches = games.values.map { game ->
            val kickoff = times["${game.home}|${game.away}"]?.takeIf { game.homeScore == null || it.isBefore(now) }
            Match(
                id = "${league.id}~r${game.round}-${Integer.toHexString("${game.home}|${game.away}".hashCode())}",
                league = league,
                home = ref(game.home),
                away = ref(game.away),
                kickoff = kickoff,
                date = kickoff?.atZone(Athens)?.toLocalDate(),
                status = statusFor(kickoff, game.homeScore != null, now),
                homeScore = game.homeScore,
                awayScore = game.awayScore,
                round = game.round,
                season = SEASON,
            )
        }
        val table = posts.lastOrNull { "Η βαθμολογία" in it.text }?.let { LasithiParsing.table(it.text, ::ref) }.orEmpty()
        val teams = (table.map { it.team } + matches.flatMap { listOf(it.home, it.away) }).distinctBy { it.id }
        if (teams.isEmpty()) throw DataException.BadData()
        return LocalData(teams, matches, table)
    }

    private data class Post(val date: LocalDate, val title: String, val text: String) {
        val isFirstDivision get() = ("Α’" in title || "Α'" in title) && ("ΕΠΣΛ" in title || "κατηγορία" in title)
    }

    private fun readPost(row: JsonObject): Post? = runCatching {
        Post(
            LocalDate.parse(row.getValue("date").jsonPrimitive.content.take(10)),
            plainText(row.getValue("title").jsonObject.getValue("rendered").jsonPrimitive.content),
            plainText(row.getValue("content").jsonObject.getValue("rendered").jsonPrimitive.content),
        )
    }.getOrNull()

    override fun stadiumOf(game: Match): Stadium? {
        if (game.league.id != LEAGUE_ID) return null
        val town = Towns[game.home.name] ?: return null
        return Stadium(game.venue ?: game.home.name, town.first, "Greece", town.second, town.third)
    }

    companion object {
        const val LEAGUE_ID = "grl.las.a"
        private const val POSTS = "https://www.anatolh.com/wp-json/wp/v2/posts"
        private const val SEASON = "2026-2027"
        private const val TTL = 3_600_000L
        private val SeasonStart = LocalDate.of(2026, 8, 1)
        private val Towns = mapOf(
            "ΟΦ Ιεράπετρας" to Triple("Ιεράπετρα", 35.0100, 25.7420),
            "ΑΣΙ" to Triple("Ιεράπετρα", 35.0100, 25.7420),
            "Αναγέννηση" to Triple("Ιεράπετρα", 35.0100, 25.7420),
            "Νίκη Σητείας" to Triple("Σητεία", 35.2076, 26.1052),
            "ΑΟΣΚ" to Triple("Σητεία", 35.2076, 26.1052),
            "Ίτανος Παλαικάστρου" to Triple("Παλαίκαστρο", 35.1985, 26.2520),
            "ΑΟ Βραχασίου" to Triple("Σίσι", 35.3060, 25.5100),
            "ΑΟ Κριτσάς" to Triple("Κριτσά", 35.1570, 25.6450),
            "Κόρφος Ελούντας" to Triple("Ελούντα", 35.2610, 25.7240),
            "ΑΟΑΝ Β" to Triple("Άγιος Νικόλαος", 35.1900, 25.7160),
        )
    }
}

/** Keeps local competitions in memory and answers for them in the app's usual shapes. */
class LocalLeagues(
    private val feeds: List<LocalFeed>,
    private val clock: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val data = ConcurrentHashMap<String, LocalData>()
    private val loadedAt = ConcurrentHashMap<String, Instant>()
    private val refs by lazy { feeds.flatMap { feed -> feed.leagues.map { it to feed } }.associateBy { it.first.id } }

    val ids: Set<String> get() = refs.keys

    fun ref(id: String): LeagueRef = refs[id]?.first ?: throw DataException.NotFound()

    fun owns(id: String): Boolean {
        val league = id.substringBefore('~')
        return league in refs || id.startsWith("${GreekLowerLogic.TEAM_PREFIX}ach-") || id.startsWith("${GreekLowerLogic.TEAM_PREFIX}las-")
    }

    private suspend fun ensure(id: String, refresh: Boolean): Boolean {
        val (league, feed) = refs[id] ?: throw DataException.NotFound()
        val age = loadedAt[id]?.let { Duration.between(it, clock()) }
        if (!refresh && age != null && age < FRESH) return false
        return try {
            data[id] = feed.fetch(league, refresh)
            loadedAt[id] = clock()
            false
        } catch (error: DataException) {
            if (!data.containsKey(id)) throw error
            true
        }
    }

    suspend fun load(chosen: Collection<String>, refresh: Boolean): Boolean {
        var stale = false
        chosen.forEach { id ->
            stale = try {
                ensure(id, refresh) || stale
            } catch (_: DataException) {
                true
            }
        }
        return stale
    }

    fun day(chosen: Collection<String>, date: LocalDate): List<Match> = chosen.flatMap { data[it]?.games.orEmpty() }
        .filter { (it.kickoff?.atZone(zone())?.toLocalDate() ?: it.date) == date }

    fun league(id: String) = Sourced(LeagueDetails(ref(id), SEASON), clock())

    private fun tableOf(id: String): List<StandingRow> {
        val current = data[id] ?: return emptyList()
        return current.table.ifEmpty { GreekLowerLogic.table(current.teams, current.games) }
    }

    suspend fun standings(id: String, refresh: Boolean): Sourced<Standings> {
        val stale = ensure(id, refresh)
        return Sourced(Standings(ref(id), SEASON, tableOf(id), isPartial = false), clock(), stale)
    }

    suspend fun fixtures(id: String, round: Int?, refresh: Boolean): Sourced<LeagueFixtures> {
        val stale = ensure(id, refresh)
        val games = data[id]?.games.orEmpty().sortedBy { it.kickoff ?: Instant.MAX }
        val last = games.maxOfOrNull { it.round ?: 0 } ?: 0
        val chosen = round ?: games.firstOrNull { it.status != MatchStatus.FINISHED }?.round ?: last
        return Sourced(
            LeagueFixtures(ref(id), SEASON, chosen, games.filter { it.round == chosen }, chosen > 1, chosen < last),
            clock(),
            stale,
        )
    }

    private fun leagueOfTeam(teamId: String) = data.entries.firstOrNull { (_, value) -> value.teams.any { it.id == teamId } }

    /** Team pages can open before their league was loaded this session. */
    private suspend fun findTeam(teamId: String, refresh: Boolean): Pair<String, LocalData> {
        leagueOfTeam(teamId)?.let { return it.key to it.value }
        val prefix = if ("ach-" in teamId) "grl.ach." else "grl.las."
        for (id in refs.keys.filter { it.startsWith(prefix) }) {
            runCatching { ensure(id, refresh) }
            data[id]?.takeIf { value -> value.teams.any { it.id == teamId } }?.let { return id to it }
        }
        throw DataException.NotFound()
    }

    suspend fun team(teamId: String): Sourced<Team> {
        val (id, value) = findTeam(teamId, false)
        val team = value.teams.first { it.id == teamId }
        val ground = value.games.firstOrNull { it.home.id == teamId }?.venue
        return Sourced(Team(teamId, team.name, country = "Greece", stadium = ground, league = ref(id)), clock())
    }

    suspend fun teamMatches(teamId: String, refresh: Boolean): Sourced<TeamMatches> {
        val (id, _) = findTeam(teamId, false)
        val stale = ensure(id, refresh)
        val (played, coming) = data[id]?.games.orEmpty().filter { it.home.id == teamId || it.away.id == teamId }
            .partition { it.status == MatchStatus.FINISHED }
        return Sourced(
            TeamMatches(
                played.sortedByDescending { it.round ?: 0 },
                coming.sortedWith(compareBy({ it.kickoff ?: Instant.MAX }, { it.round ?: 0 })),
            ),
            clock(),
            stale,
        )
    }

    suspend fun history(teamId: String): List<Match> {
        val (_, value) = findTeam(teamId, false)
        return value.games.filter { (it.home.id == teamId || it.away.id == teamId) && it.status == MatchStatus.FINISHED }
    }

    suspend fun details(matchId: String, refresh: Boolean): Sourced<MatchDetails> {
        val id = matchId.substringBefore('~')
        val stale = ensure(id, refresh)
        val game = data[id]?.games?.firstOrNull { it.id == matchId } ?: throw DataException.NotFound()
        return Sourced(MatchDetails(game, emptyList(), Standings(ref(id), SEASON, tableOf(id), isPartial = false)), clock(), stale)
    }

    fun searchTeams(query: String): List<Team> {
        val needle = GreekLowerLogic.nameKey(query)
        if (needle.length < 2) return emptyList()
        return data.entries.flatMap { (id, value) ->
            value.teams.filter { GreekLowerLogic.nameKey(it.name).contains(needle) }
                .map { Team(it.id, it.name, country = "Greece", league = ref(id)) }
        }
    }

    fun stadiumOf(game: Match): Stadium? = refs[game.league.id]?.second?.stadiumOf(game)

    private companion object {
        const val SEASON = "2026-2027"
        val FRESH: Duration = Duration.ofMinutes(20)
    }
}
