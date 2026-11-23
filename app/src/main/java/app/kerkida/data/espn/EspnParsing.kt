package app.kerkida.data.espn

import app.kerkida.data.DataException
import app.kerkida.data.model.LeagueRef
import app.kerkida.data.model.LineupPlayer
import app.kerkida.data.model.Match
import app.kerkida.data.model.MatchEvent
import app.kerkida.data.model.MatchEventType
import app.kerkida.data.model.MatchExtras
import app.kerkida.data.model.MatchStatus
import app.kerkida.data.model.Side
import app.kerkida.data.model.StandingRow
import app.kerkida.data.model.TeamLineup
import app.kerkida.data.model.TeamRef
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Reads ESPN's JSON. Betting fields such as odds are never read. */
internal object EspnParsing {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val Digits = Regex("\\d+")

    fun root(body: String): JsonObject = try {
        json.parseToJsonElement(body) as? JsonObject ?: throw DataException.BadData()
    } catch (error: SerializationException) {
        throw DataException.BadData(error)
    }

    fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    fun JsonElement?.list(): List<JsonElement> = (this as? JsonArray).orEmpty()
    fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
    fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true

    /** ESPN writes times without seconds, which Instant.parse does not accept on every version. */
    fun instant(text: String?): Instant? = text?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }

    /** "63'" is 63 and "90'+8'" is 98. */
    fun minute(text: String?): Int? {
        val parts = Digits.findAll(text ?: return null).map { it.value.toInt() }.toList()
        return if (parts.isEmpty()) null else parts.sum()
    }

    fun logo(team: JsonObject): String? =
        team.str("logo") ?: team["logos"].list().firstOrNull().obj()?.str("href")

    private fun score(competitor: JsonObject): Int? = when (val value = competitor["score"]) {
        is JsonPrimitive -> value.contentOrNull?.toDoubleOrNull()?.toInt()
        is JsonObject -> value.num("value")?.toInt()
        else -> null
    }

    fun statusOf(status: JsonObject?): MatchStatus {
        val type = status?.get("type").obj() ?: return MatchStatus.UNKNOWN
        val name = type.str("name").orEmpty().uppercase()
        return when {
            "POSTPONED" in name || "SUSPENDED" in name || "DELAYED" in name -> MatchStatus.POSTPONED
            "CANCEL" in name || "ABANDON" in name || "FORFEIT" in name -> MatchStatus.CANCELLED
            "HALFTIME" in name || "HALF_TIME" in name -> MatchStatus.HALF_TIME
            else -> when (type.str("state")) {
                "pre" -> MatchStatus.SCHEDULED
                "in" -> MatchStatus.LIVE
                "post" -> MatchStatus.FINISHED
                else -> MatchStatus.UNKNOWN
            }
        }
    }

    fun matchId(leagueId: String, eventId: String) = "$leagueId$SEPARATOR$eventId"

    fun splitMatchId(id: String): Pair<String, String> {
        val index = id.indexOf(SEPARATOR)
        if (index <= 0 || index == id.lastIndex) throw DataException.NotFound()
        return id.substring(0, index) to id.substring(index + 1)
    }

    /** Works for scoreboard events, team schedule events and a summary's header. */
    fun toMatch(event: JsonObject, league: LeagueRef, season: String?): Match? {
        val eventId = event.str("id") ?: return null
        val competition = event["competitions"].list().firstOrNull().obj() ?: return null
        val competitors = competition["competitors"].list().mapNotNull { it.obj() }
        val home = competitors.firstOrNull { it.str("homeAway") == "home" } ?: return null
        val away = competitors.firstOrNull { it.str("homeAway") == "away" } ?: return null
        val statusObject = competition["status"].obj() ?: event["status"].obj()
        val status = statusOf(statusObject)
        val played = status == MatchStatus.LIVE || status == MatchStatus.HALF_TIME || status == MatchStatus.FINISHED
        val kickoff = instant(competition.str("date") ?: event.str("date"))
        return Match(
            id = matchId(league.id, eventId),
            league = league,
            home = teamRef(home) ?: return null,
            away = teamRef(away) ?: return null,
            kickoff = kickoff,
            date = kickoff?.atZone(ZoneOffset.UTC)?.toLocalDate(),
            status = status,
            statusDetail = statusObject?.get("type").obj()?.str("description"),
            minute = if (status == MatchStatus.LIVE) minute(statusObject?.str("displayClock")) else null,
            homeScore = if (played) score(home) else null,
            awayScore = if (played) score(away) else null,
            season = season,
            venue = competition["venue"].obj()?.str("fullName"),
        )
    }

    private fun teamRef(competitor: JsonObject): TeamRef? {
        val team = competitor["team"].obj() ?: return null
        val id = team.str("id") ?: competitor.str("id") ?: return null
        return TeamRef(id, team.str("displayName") ?: team.str("name") ?: return null, logo(team))
    }

    fun toEvents(summary: JsonObject, match: Match): List<MatchEvent> =
        summary["keyEvents"].list().mapNotNull { element ->
            val event = element.obj() ?: return@mapNotNull null
            if (event.flag("shootout")) return@mapNotNull null
            val type = eventType(event) ?: return@mapNotNull null
            val people = event["participants"].list().mapNotNull { it.obj()?.get("athlete").obj()?.str("displayName") }
            val teamId = event["team"].obj()?.str("id")
            MatchEvent(
                minute = minute(event["clock"].obj()?.str("displayValue")),
                type = type,
                side = when (teamId) {
                    match.home.id -> Side.HOME
                    match.away.id -> Side.AWAY
                    else -> null
                },
                player = people.firstOrNull(),
                detail = if (type == MatchEventType.SUBSTITUTION) people.getOrNull(1) else null,
            )
        }

    private fun eventType(event: JsonObject): MatchEventType? {
        val text = event["type"].obj()?.str("text").orEmpty().lowercase()
        return when {
            "own goal" in text -> MatchEventType.OWN_GOAL
            "penalty" in text && ("miss" in text || "saved" in text) -> MatchEventType.MISSED_PENALTY
            "penalty" in text -> MatchEventType.PENALTY
            text.startsWith("goal") || event.flag("scoringPlay") -> MatchEventType.GOAL
            "red card" in text -> MatchEventType.RED_CARD
            "yellow card" in text -> MatchEventType.YELLOW_CARD
            "substitution" in text -> MatchEventType.SUBSTITUTION
            "var" == text || text.startsWith("var ") -> MatchEventType.VAR
            else -> null
        }
    }

    fun toExtras(summary: JsonObject, match: Match): MatchExtras? {
        val rosters = summary["rosters"].list().mapNotNull { it.obj() }
        if (rosters.all { it["roster"].list().isEmpty() }) return null
        val substitutionMinutes = HashMap<String, Int>()
        summary["keyEvents"].list().mapNotNull { it.obj() }
            .filter { "substitution" in it["type"].obj()?.str("text").orEmpty().lowercase() }
            .forEach { event ->
                val at = minute(event["clock"].obj()?.str("displayValue")) ?: return@forEach
                event["participants"].list().forEach { person ->
                    person.obj()?.get("athlete").obj()?.str("id")?.let { substitutionMinutes[it] = at }
                }
            }
        val length = when (match.status) {
            MatchStatus.FINISHED -> maxOf(90, match.minute ?: 90)
            MatchStatus.HALF_TIME -> 45
            else -> match.minute ?: 0
        }
        fun side(name: String) = rosters.firstOrNull { it.str("homeAway") == name }?.let { lineup(it, substitutionMinutes, length) }
        return MatchExtras(side("home"), side("away"))
    }

    private fun lineup(roster: JsonObject, substitutions: Map<String, Int>, length: Int): TeamLineup? {
        val players = roster["roster"].list().mapNotNull { element ->
            val entry = element.obj() ?: return@mapNotNull null
            val athlete = entry["athlete"].obj() ?: return@mapNotNull null
            val id = athlete.str("id") ?: return@mapNotNull null
            val starter = entry.flag("starter")
            val changedAt = substitutions[id]
            val minutes = when {
                starter && entry.flag("subbedOut") -> (changedAt ?: length).coerceAtMost(length)
                starter -> length
                entry.flag("subbedIn") -> (length - (changedAt ?: length)).coerceAtLeast(1)
                else -> 0
            }
            LineupPlayer(
                id = id,
                name = athlete.str("displayName") ?: athlete.str("fullName") ?: return@mapNotNull null,
                jersey = entry.str("jersey"),
                isStarter = starter,
                positionCode = entry["position"].obj()?.str("abbreviation"),
                minutes = minutes,
                stats = entry["stats"].list().mapNotNull { stat ->
                    val line = stat.obj() ?: return@mapNotNull null
                    val key = line.str("name") ?: return@mapNotNull null
                    line.num("value")?.let { key to it }
                }.toMap(),
                photoUrl = app.kerkida.data.photos.PlayerPhotoLogic.smallEspn(athlete["headshot"].obj()?.str("href")),
            )
        }
        if (players.isEmpty()) return null
        return TeamLineup(roster.str("formation"), players)
    }

    fun toStandingRows(root: JsonObject): List<StandingRow> {
        val groups = root["children"].list().mapNotNull { it.obj() }
        val entries = groups.flatMap { it["standings"].obj()?.get("entries").list() }.mapNotNull { it.obj() }
        return entries.mapNotNull { entry ->
            val team = entry["team"].obj() ?: return@mapNotNull null
            val id = team.str("id") ?: return@mapNotNull null
            val stats = entry["stats"].list().mapNotNull { it.obj() }.mapNotNull { stat ->
                val key = stat.str("name") ?: return@mapNotNull null
                stat.num("value")?.let { key to it.toInt() }
            }.toMap()
            StandingRow(
                rank = stats["rank"] ?: return@mapNotNull null,
                team = TeamRef(id, team.str("displayName") ?: return@mapNotNull null, logo(team)),
                played = stats["gamesPlayed"] ?: 0,
                won = stats["wins"] ?: 0,
                drawn = stats["ties"] ?: 0,
                lost = stats["losses"] ?: 0,
                goalsFor = stats["pointsFor"] ?: 0,
                goalsAgainst = stats["pointsAgainst"] ?: 0,
                goalDifference = stats["pointDifferential"] ?: 0,
                points = stats["points"] ?: 0,
                note = entry["note"].obj()?.str("description"),
            )
        }.sortedBy { it.rank }
    }

    /** The days a league plays on, whichever of ESPN's two calendar shapes it uses. */
    fun calendarDays(league: JsonObject): List<LocalDate> {
        val days = league["calendar"].list().flatMap { item ->
            when (item) {
                is JsonPrimitive -> listOfNotNull(instant(item.contentOrNull))
                is JsonObject -> item["entries"].list().mapNotNull { instant(it.obj()?.str("startDate")) }
                else -> emptyList()
            }
        }
        return days.map { it.atZone(ZoneOffset.UTC).toLocalDate() }.distinct().sorted()
    }

    /** Groups playing days into rounds: a gap of three days or more starts a new one. */
    fun roundsOf(days: List<LocalDate>): List<List<LocalDate>> {
        val rounds = mutableListOf<MutableList<LocalDate>>()
        days.sorted().forEach { day ->
            val current = rounds.lastOrNull()
            if (current != null && day.toEpochDay() - current.last().toEpochDay() < ROUND_GAP_DAYS) {
                current += day
            } else {
                rounds += mutableListOf(day)
            }
        }
        return rounds
    }

    private const val ROUND_GAP_DAYS = 3
    private const val SEPARATOR = "~"
}
