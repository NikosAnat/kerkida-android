package app.kerkida.tv

import app.kerkida.broadcast.AccessType
import app.kerkida.data.model.Match
import app.kerkida.data.model.Sport
import kotlinx.serialization.Serializable
import java.text.Normalizer
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@Serializable
enum class TvSport {
    FOOTBALL, BASKETBALL, TENNIS, MOTOR, VOLLEYBALL, HANDBALL, WATER_POLO, RUGBY, AMERICAN_FOOTBALL, HOCKEY, BASEBALL, MMA, GOLF, OTHER,
}

@Serializable
data class TvItem(
    val date: String,
    val time: String,
    val startMs: Long,
    val channel: String,
    val title: String,
    val competition: String,
    val sport: TvSport,
    val durationMin: Int = 0,
    val sourceId: String,
) {
    val key: String get() = "$startMs|${TvLogic.channelKey(channel)}|${TvLogic.phonetic(title)}"
    val endMs: Long get() = startMs + (if (durationMin > 0) durationMin else TvLogic.usualMinutes(sport)) * 60_000L
}

enum class TvStatus { PAST, LIVE, UPCOMING }

/** One event, with every channel showing it. */
data class TvEvent(val item: TvItem, val channels: List<String>)

object TvLogic {
    val Athens: ZoneId = ZoneId.of("Europe/Athens")

    fun usualMinutes(sport: TvSport) = when (sport) {
        TvSport.FOOTBALL -> 115
        TvSport.BASKETBALL -> 125
        TvSport.TENNIS -> 180
        TvSport.MOTOR -> 120
        TvSport.VOLLEYBALL -> 120
        TvSport.HANDBALL -> 90
        TvSport.WATER_POLO -> 80
        TvSport.RUGBY -> 110
        TvSport.AMERICAN_FOOTBALL -> 200
        TvSport.HOCKEY -> 150
        TvSport.BASEBALL -> 180
        TvSport.MMA -> 180
        TvSport.GOLF -> 300
        TvSport.OTHER -> 110
    }

    fun statusOf(item: TvItem, nowMs: Long): TvStatus = when {
        nowMs >= item.endMs -> TvStatus.PAST
        nowMs >= item.startMs -> TvStatus.LIVE
        else -> TvStatus.UPCOMING
    }

    /** Lower case, no accents, with Latin look-alikes inside Greek words turned into Greek. */
    fun plain(text: String): String {
        val lower = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return lower.replace('ς', 'σ').replace(Regex("(?<=[α-ω])p|p(?=[α-ω])"), "ρ")
            .replace(Regex("^epτ"), "ερτ").replace(Regex("(?<=[α-ω])e|e(?=[α-ω])"), "ε")
    }

    /** The subscription or network a channel belongs to. */
    fun channelIdOf(channel: String): String {
        val c = plain(channel).trim()
        return when {
            c.startsWith("novasport") -> "nova"
            c.startsWith("magenta") -> "magenta"
            c.startsWith("eurosport") -> "eurosport"
            c.startsWith("dazn") -> "dazn"
            c.startsWith("αντ1+") || c.startsWith("ant1+") -> "ant1plus"
            c.startsWith("σπορ fm") || c.startsWith("sport fm") -> "sportfm"
            c.startsWith("ερτ") || c.startsWith("ert") -> "ert"
            c.startsWith("αντ1") || c.startsWith("ant1") -> "ant1"
            c.startsWith("mega") -> "mega"
            c.startsWith("alpha") -> "alpha"
            c.startsWith("σκαι") || c.startsWith("skai") -> "skai"
            c.startsWith("star") && "κεντρικ" !in c && "kentrik" !in c -> "star"
            c.startsWith("open") -> "open"
            c.startsWith("action 24") -> "action24"
            c.startsWith("one channel") -> "one"
            else -> "other"
        }
    }

    fun accessOf(channelId: String): AccessType = when (channelId) {
        "nova", "magenta", "eurosport", "dazn", "ant1plus", "other" -> AccessType.SUBSCRIPTION
        else -> AccessType.FREE
    }

    /** The same channel written two ways ("NOVASPORTS 5", "Novasports5 HD") gets one key. */
    fun channelKey(channel: String): String = plain(channelName(channel))
        .replace(Regex("\\b(hd|ηd|ηδ|4k)\\b"), "")
        .replace(Regex("[^a-z0-9α-ω+]"), "")

    private val MagentaLeague = Regex("^\\s*magenta\\s*sport\\s*([1-9])\\s*(?:l|league)(?:\\s+(?:hd|ηd))?\\s*$", RegexOption.IGNORE_CASE)
    private val OldMagentaLeagueKey = Regex("\\|magentasport([1-9])l\\|")

    /** "Magenta Sport 3 L" and "Magenta Sport 3 League" are one channel, shown under its full name. */
    fun channelName(channel: String): String =
        MagentaLeague.matchEntire(channel)?.let { "Magenta Sport ${it.groupValues[1]} League" } ?: channel

    /** Favourites saved under the short "3 L" spelling keep pointing at the same listing. */
    fun currentItemKey(key: String): String = OldMagentaLeagueKey.replace(key) { "|magentasport${it.groupValues[1]}league|" }

    fun classify(competition: String, title: String): TvSport {
        val c = plain("$competition $title")
        return when {
            Regex("nba|euroleague|eurocup|acb|gbl|basket|bcl|μπασκετ|ευρωλιγκα|γιουροκαπ|tbsl|elite league").containsMatchIn(c) -> TvSport.BASKETBALL
            Regex("atp|wta|τενισ").containsMatchIn(c) -> TvSport.TENNIS
            Regex("formula|moto|erc|wrc|ραλι|γκραν πρι|μηχανοκινητ").containsMatchIn(c) -> TvSport.MOTOR
            Regex("βολει|volley").containsMatchIn(c) -> TvSport.VOLLEYBALL
            Regex("χαντμπολ|handball").containsMatchIn(c) -> TvSport.HANDBALL
            Regex("(^|[^α-ω])πολο([^α-ω]|$)|water polo").containsMatchIn(c) -> TvSport.WATER_POLO
            Regex("nfl|αμερικανικο ποδοσφαιρο|αμερικανικο ποδ").containsMatchIn(c) -> TvSport.AMERICAN_FOOTBALL
            Regex("nhl|χοκει|hockey").containsMatchIn(c) -> TvSport.HOCKEY
            Regex("mlb|μπειζμπολ|baseball").containsMatchIn(c) -> TvSport.BASEBALL
            Regex("ufc|mma|πυγμαχ|boxing|fight").containsMatchIn(c) -> TvSport.MMA
            Regex("γκολφ|golf|pga").containsMatchIn(c) -> TvSport.GOLF
            Regex("rugby|ραγκμπι").containsMatchIn(c) -> TvSport.RUGBY
            Regex("futsal|σνουκερ|snooker").containsMatchIn(c) -> TvSport.OTHER
            Regex("λιγκα|league|serie|σεριε|liga|bundesliga|μπουντεσλιγκα|eredivisie|πριμιερσιπ|premiership|κυπελλο|πρωταθλημα|ποδοσφαιρο|καπ|copa|κατηγορια").containsMatchIn(c) -> TvSport.FOOTBALL
            Regex(" [-–] |[α-ωa-z]-[α-ωa-z]").containsMatchIn(title.lowercase()) -> TvSport.FOOTBALL
            else -> TvSport.OTHER
        }
    }

    /** One pronunciation-like spelling, so Greek and Latin team names can be compared. */
    fun phonetic(text: String): String {
        var s = plain(text)
        listOf(
            "ου" to "u", "μπ" to "b", "ντ" to "d", "γκ" to "g", "γγ" to "g", "τσ" to "ts", "τζ" to "dz",
            "αι" to "e", "ει" to "i", "οι" to "i", "αυ" to "av", "ευ" to "ev",
            "α" to "a", "β" to "v", "γ" to "g", "δ" to "d", "ε" to "e", "ζ" to "z", "η" to "i", "θ" to "t", "ι" to "i",
            "κ" to "k", "λ" to "l", "μ" to "m", "ν" to "n", "ξ" to "ks", "ο" to "o", "π" to "p", "ρ" to "r", "σ" to "s",
            "τ" to "t", "υ" to "i", "φ" to "f", "χ" to "h", "ψ" to "ps", "ω" to "o",
        ).forEach { (from, to) -> s = s.replace(from, to) }
        listOf(
            "ch" to "ts", "ph" to "f", "th" to "t", "ou" to "u", "oo" to "u", "ck" to "k", "mp" to "b",
            "nt" to "d", "nd" to "d", "x" to "ks", "c" to "k", "q" to "k", "w" to "v", "y" to "i", "j" to "i", "h" to "",
        ).forEach { (from, to) -> s = s.replace(from, to) }
        return s.replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("(.)\\1+"), "$1").replace(Regex("\\s+"), " ").trim()
    }

    fun ratio(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val row = IntArray(b.length + 1)
            row[0] = i
            for (j in 1..b.length) {
                row[j] = min(min(row[j - 1] + 1, previous[j] + 1), previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            previous = row
        }
        return 1.0 - previous[b.length].toDouble() / max(a.length, b.length)
    }

    private val CommonWords = setOf(
        "fk", "fc", "afk", "ak", "sk", "as", "ac", "sv", "vfl", "vfb", "rb", "kf", "manshester", "matsester", "tsester",
        "uited", "unted", "ied", "uaited", "iunaited", "iuaited", "siti", "kiti", "real", "reak", "atletik", "atletiko",
        "sporting", "sportig", "spor", "town", "tovn", "kounty", "united", "kity", "hotspur", "albion",
    )

    /** Names that sound nothing alike in the two alphabets. */
    private val Aliases = mapOf(
        "χαλ" to "hull city", "κπρ" to "queens park rangers", "ιντερ" to "internazionale", "λιντσερ" to "lask linz", "μποντο" to "bodo", "αγιαξ" to "ajax", "αιντχοφεν" to "eindhoven", "μπαγερν" to "bayern", "γκλαντμπαχ" to "gladbach",
        "μενχενγκλαντμπαχ" to "gladbach", "μπορουσια" to "borussia", "λειψια" to "leipzig", "κολωνια" to "koln", "κολονια" to "koln",
        "αμβουργο" to "hamburg", "βρεμη" to "bremen", "βερντερ" to "werder", "στουτγκαρδη" to "stuttgart", "φρανκφουρτη" to "frankfurt",
        "αιντραχτ" to "frankfurt", "φραιμπουργκ" to "freiburg", "χοφενχαιμ" to "hoffenheim", "μαιντσ" to "mainz", "βολφσμπουργκ" to "wolfsburg",
        "παρι" to "paris", "λυων" to "lyon", "μασσαλια" to "marseille", "μαρσειγ" to "marseille", "νιcαια" to "nice", "νικαια" to "nice",
        "λιλ" to "lille", "ρεν" to "rennes", "μονακο" to "monaco", "στρασβουργο" to "strasbourg", "νατη" to "nantes", "ναντ" to "nantes",
        "ανζε" to "angers", "τουλουζ" to "toulouse", "λανσ" to "lens", "λοριαν" to "lorient", "ζενοα" to "genoa", "τζενοα" to "genoa",
        "φλωρεντια" to "fiorentina", "φιορεντινα" to "fiorentina", "λατσιο" to "lazio", "γιουβεντουσ" to "juventus", "μιλαν" to "milan",
        "σεβιλλη" to "sevilla", "σεβιγια" to "sevilla", "μπιλμπαο" to "bilbao", "μπετισ" to "betis", "θελτα" to "celta", "βιγιαρεαλ" to "villarreal",
        "μαγιορκα" to "mallorca", "χιρονα" to "girona", "βαλενθια" to "valencia", "οσασουνα" to "osasuna", "ουεστ χαμ" to "west ham",
        "γουεστ χαμ" to "west ham", "τοτεναμ" to "tottenham", "τσελσι" to "chelsea", "γουλβσ" to "wolves", "νιουκαστλ" to "newcastle",
        "μπορνμουθ" to "bournemouth", "μπραιτον" to "brighton", "νοτιγχαμ" to "nottingham", "λιντσ" to "leeds", "ιπσουιτσ" to "ipswich",
        "κοβεντρι" to "coventry", "σαντερλαντ" to "sunderland", "ολυμπιακοσ" to "olympiacos", "ηρακλησ" to "iraklis", "αρησ" to "aris",
        "παοκ" to "paok", "αεκ" to "aek", "οφη" to "ofi", "αστερασ" to "asteras", "ατρομητοσ" to "atromitos", "βολοσ" to "volos",
        "κηφισια" to "kifisia", "λεβαδειακοσ" to "levadiakos", "παναιτωλικοσ" to "panetolikos", "παναθηναικοσ" to "panathinaikos",
        "καλαματα" to "kalamata", "μπενφικα" to "benfica", "πορτο" to "porto", "μπρατσα" to "braga", "μπρακα" to "braga", "γκιμαραες" to "guimaraes",
        "φενερμπαχτσε" to "fenerbahce", "μπεσικτασ" to "besiktas", "γαλατασαραι" to "galatasaray", "τραμπζονσπορ" to "trabzonspor",
        "βασιλεια" to "basel", "βελιγραδι" to "belgrade", "ερυθροσ αστερασ" to "crvena zvezda", "λισαβονασ" to "lisbon", "ζαγκρεμπ" to "zagreb",
    )

    /** All aliases in one pattern, longest first, so a name is scanned once instead of once per alias. */
    private val AliasPattern by lazy {
        Regex("(?<![α-ω])(" + Aliases.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + ")(?![α-ω])")
    }
    private val canonicalMemo = ConcurrentHashMap<String, String>()
    private val similarityMemo = ConcurrentHashMap<String, Double>()
    private const val MEMO_LIMIT = 20_000

    private fun canonical(text: String): String {
        if (canonicalMemo.size > MEMO_LIMIT) canonicalMemo.clear()
        return canonicalMemo.getOrPut(text) {
            phonetic(AliasPattern.replace(plain(text)) { Aliases.getValue(it.value) })
        }
    }

    /** True when a free-text title names this team, in either alphabet. */
    fun mentionsTeam(text: String, team: String): Boolean {
        val words = canonical(text).split(' ').filter { it.length >= 3 }
        val wanted = canonical(team).split(' ').filter { it.length >= 3 && it !in CommonWords }
        if (wanted.isEmpty()) return false
        return wanted.any { name ->
            words.any { word ->
                word == name || (min(word.length, name.length) >= 5 && (ratio(word, name) >= 0.8 || word.contains(name) || name.contains(word)))
            }
        }
    }

    fun teamSimilarity(first: String, second: String): Double {
        if (similarityMemo.size > MEMO_LIMIT) similarityMemo.clear()
        return similarityMemo.getOrPut("$first\u0000$second") { computeSimilarity(first, second) }
    }

    private fun computeSimilarity(first: String, second: String): Double {
        val a = canonical(first)
        val b = canonical(second)
        var best = ratio(a, b)
        val tokensA = a.split(' ').filter { it.length >= 3 && it !in CommonWords }
        val tokensB = b.split(' ').filter { it.length >= 3 && it !in CommonWords }
        for (x in tokensA) for (y in tokensB) {
            best = when {
                x == y -> 1.0
                min(x.length, y.length) >= 5 && (x.contains(y) || y.contains(x)) -> max(best, 0.95)
                min(x.length, y.length) >= 4 -> max(best, ratio(x, y))
                else -> best
            }
        }
        return best
    }

    fun splitTeams(title: String): Pair<String, String>? {
        val parts = title.split(Regex("\\s*[-–—]\\s+|\\s+[-–—]\\s*|(?<=[\\p{L}.)])[-–—](?=\\p{Lu})"))
        if (parts.size != 2 || parts.any { it.isBlank() }) return null
        return parts[0].trim() to parts[1].trim()
    }

    /** The league a Greek competition label points to, or null when it is unclear. */
    fun leagueHint(competition: String): String? {
        val c = plain(competition)
        return when {
            "youth" in c || "γυναικ" in c || "womens" in c || "δευτερη" in c || "league 2" in c || "league one" in c ||
                "league two" in c || "bundesliga 2" in c || "u-1" in c || "u-2" in c -> NOT_COVERED
            "championship" in c -> "eng.2"
            "premier league" in c || "αγγλικο" in c || "αγγλιασ" in c -> "eng.1"
            "super league" in c || "ελληνικο" in c -> if ("τουρκ" in c) "tur.1" else "gre.1"
            "ιταλικο" in c || "serie a" in c || "σεριε α" in c -> "ita.1"
            "ισπανικο" in c || "laliga" in c || "la liga" in c -> "esp.1"
            "γερμανικο" in c || "bundesliga" in c || "μπουντεσλιγκα" in c -> "ger.1"
            "γαλλικο" in c || "ligue 1" in c -> "fra.1"
            "ολλανδικο" in c || "eredivisie" in c -> "ned.1"
            "πορτογαλ" in c || "primeira" in c -> "por.1"
            "τουρκικο" in c -> "tur.1"
            "champions league" in c || "τσαμπιονσ" in c -> "uefa.champions"
            "europa league" in c || "γιουροπα" in c -> "uefa.europa"
            "conference" in c -> "uefa.europa.conf"
            else -> null
        }
    }

    private const val NOT_COVERED = "__none"
    private const val SLOT_MS = 20 * 60_000L

    fun tvSportOf(sport: Sport): TvSport = when (sport) {
        Sport.FOOTBALL -> TvSport.FOOTBALL
        Sport.BASKETBALL -> TvSport.BASKETBALL
        Sport.TENNIS -> TvSport.TENNIS
        Sport.MOTOR -> TvSport.MOTOR
        Sport.AMERICAN_FOOTBALL -> TvSport.AMERICAN_FOOTBALL
        Sport.HOCKEY -> TvSport.HOCKEY
        Sport.BASEBALL -> TvSport.BASEBALL
        Sport.MMA -> TvSport.MMA
        Sport.VOLLEYBALL -> TvSport.VOLLEYBALL
        Sport.RUGBY -> TvSport.RUGBY
        Sport.GOLF -> TvSport.GOLF
        Sport.LACROSSE, Sport.AUSSIE_RULES -> TvSport.OTHER
    }

    /** How alike two written names are, in either alphabet. */
    fun nameSimilarity(first: String, second: String): Double = teamSimilarity(first, second)

    /** How well a listing fits a match, or null if it is not that match. */
    fun matchScore(item: TvItem, match: Match): Double? {
        if (item.sport != tvSportOf(match.league.sport)) return null
        if (match.league.sport == Sport.TENNIS) return tennisScore(item, match)
        val kickoff = match.kickoff?.toEpochMilli() ?: return null
        if (abs(item.startMs - kickoff) > SLOT_MS) return null
        val hint = if (match.league.sport == Sport.FOOTBALL) leagueHint(item.competition) else null
        if (hint != null && hint != match.league.id) return null
        val (home, away) = splitTeams(item.title) ?: return null
        val homeScore = teamSimilarity(home, match.home.name)
        val awayScore = teamSimilarity(away, match.away.name)
        val high = max(homeScore, awayScore)
        val low = min(homeScore, awayScore)
        val fits = if (hint == null) low >= 0.72 else low >= 0.62 || (high >= 0.85 && low >= 0.45)
        return if (fits) homeScore + awayScore else null
    }

    private enum class TennisStage { QUARTER, SEMI, FINAL }

    private val Slams = listOf(
        Regex("roland garros|ρολαν γκαροσ"),
        Regex("wimbledon|γουιμπλεντον"),
        Regex("australian open|αυστραλιανο οπεν"),
        Regex("us open|αμερικανικο οπεν"),
    )
    private val TennisGeneric = setOf("tenis", "open", "finals", "masters", "tour", "series", "next", "gen", "atp", "wta", "cup")

    private fun tennisStage(text: String): TennisStage? {
        val plainText = plain(text)
        return when {
            "quarter" in plainText || "προημιτελικ" in plainText -> TennisStage.QUARTER
            "semi" in plainText || "ημιτελικ" in plainText -> TennisStage.SEMI
            Regex("\\bfinal\\b|(?<![α-ω])τελικ").containsMatchIn(plainText) -> TennisStage.FINAL
            else -> null
        }
    }

    private fun isDoubles(text: String) = plain(text).let { "double" in it || "διπλ" in it }

    /** Sponsors and cities identify a tournament. Words every tournament shares do not. */
    private fun sameTournament(listing: String, tournament: String): Boolean {
        val a = plain(listing)
        val b = plain(tournament)
        val slam = Slams.firstOrNull { it.containsMatchIn(a) || it.containsMatchIn(b) }
        if (slam != null) return slam.containsMatchIn(a) && slam.containsMatchIn(b)
        fun words(text: String) = phonetic(text).split(' ').filter { it.length >= 4 && it !in TennisGeneric && !it.all(Char::isDigit) }
        val theirs = words(b)
        return words(a).any { word -> theirs.any { it == word || ratio(it, word) >= 0.85 } }
    }

    private fun sameSurname(first: String, second: String): Boolean {
        fun last(name: String) = canonical(name).substringAfterLast(' ')
        val a = last(first)
        val b = last(second)
        return min(a.length, b.length) >= 3 && (a == b || ratio(a, b) >= 0.72)
    }

    /**
     * Tennis listings rarely name the players. They say "Final, Doubles" and a tournament, so a game
     * is found by its two surnames, or by its tournament, round, draw and time. A vague listing links nothing.
     */
    private fun tennisScore(item: TvItem, match: Match): Double? {
        val kickoff = match.kickoff?.toEpochMilli() ?: return null
        if (kickoff < item.startMs - TENNIS_EARLY_MS || kickoff >= item.endMs) return null
        val closeness = min(abs(kickoff - item.startMs).toDouble() / 3_600_000, 1.0) * 0.5
        splitTeams(item.title)?.let { (first, second) ->
            val named = (sameSurname(first, match.home.name) && sameSurname(second, match.away.name)) ||
                (sameSurname(first, match.away.name) && sameSurname(second, match.home.name))
            if (named) return 3.0 - closeness
        }
        val text = "${item.title} ${item.competition}"
        if (!sameTournament(text, match.league.name)) return null
        val stage = tennisStage(text)
        if (stage != null) {
            val sameDraw = isDoubles(text) == isDoubles(match.statusDetail.orEmpty())
            return if (sameDraw && tennisStage(match.stage.orEmpty()) == stage) 2.0 - closeness else null
        }
        // The season finals are played on one court, so a day's broadcast shows every game in its hours.
        val oneCourt = "finals" in plain(match.league.name)
        return if (oneCourt && Regex("μερα|day").containsMatchIn(plain(text))) 1.0 - closeness else null
    }

    private const val TENNIS_EARLY_MS = 45 * 60_000L

    private fun sameEvent(a: TvItem, b: TvItem): Boolean {
        if (a.sport != b.sport || abs(a.startMs - b.startMs) > 20 * 60_000L) return false
        val teamsA = splitTeams(a.title)
        val teamsB = splitTeams(b.title)
        if (teamsA != null && teamsB != null) {
            return teamSimilarity(teamsA.first, teamsB.first) >= 0.7 && teamSimilarity(teamsA.second, teamsB.second) >= 0.7
        }
        return ratio(phonetic(a.title), phonetic(b.title)) >= 0.8
    }

    /** Folds repeated listings of one event into a single row. The most descriptive listing leads. */
    fun groupEvents(items: List<TvItem>): List<TvEvent> {
        val groups = mutableListOf<MutableList<TvItem>>()
        items.sortedBy { it.startMs }.forEach { item ->
            val group = groups.firstOrNull { list -> list.any { sameEvent(it, item) } }
            if (group != null) group += item else groups += mutableListOf(item)
        }
        val generic = setOf("ποδοσφαιρο", "μπασκετ", "τενισ", "μηχανοκινητα", "βολει")
        return groups.map { group ->
            val lead = group.firstOrNull { plain(it.competition).trim() !in generic } ?: group.first()
            val names = group.map { it.channel }.distinctBy(::channelKey)
            TvEvent(lead.copy(startMs = group.minOf { it.startMs }), names)
        }.sortedBy { it.item.startMs }
    }

    /** Merges sources in priority order. A later source only adds what the earlier ones lack. */
    fun merge(sources: List<List<TvItem>>): List<TvItem> {
        val out = mutableListOf<TvItem>()
        sources.forEach { list ->
            list.forEach { listed ->
                val item = listed.copy(channel = channelName(listed.channel))
                val duplicate = out.any { kept ->
                    abs(kept.startMs - item.startMs) <= 15 * 60_000L &&
                        channelKey(kept.channel) == channelKey(item.channel) &&
                        ratio(phonetic(kept.title), phonetic(item.title)) >= 0.45
                }
                if (!duplicate) out += item
            }
        }
        return out.sortedWith(compareBy({ it.startMs }, { it.channel }))
    }

    /** Fresh days replace saved ones. Days older than yesterday are dropped. */
    fun keepSaved(previous: List<TvItem>, fresh: List<TvItem>, today: LocalDate): List<TvItem> {
        val days = fresh.map { it.date }.toSet()
        val from = today.minusDays(1).toString()
        return (fresh + previous.filter { it.date !in days }).filter { it.date >= from }
    }
}
