package app.kerkida.data.thesportsdb

import app.kerkida.data.DataException
import app.kerkida.data.model.LeagueRef
import app.kerkida.data.model.Match
import app.kerkida.data.model.MatchEvent
import app.kerkida.data.model.MatchEventType
import app.kerkida.data.model.MatchStatus
import app.kerkida.data.model.Side
import app.kerkida.data.model.StandingRow
import app.kerkida.data.model.Team
import app.kerkida.data.model.TeamRef
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

internal object TheSportsDbParsing {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Reads the named list. The API answers `null` instead of an empty list. */
    fun rows(body: String, key: String): List<JsonObject> {
        val root = try {
            json.parseToJsonElement(body) as? JsonObject
        } catch (error: SerializationException) {
            throw DataException.BadData(error)
        } ?: throw DataException.BadData()
        return when (val value = root[key]) {
            is JsonArray -> value.filterIsInstance<JsonObject>()
            else -> emptyList()
        }
    }

    fun JsonObject.text(key: String): String? {
        val value = this[key]
        if (value == null || value is JsonNull || value !is JsonPrimitive) return null
        return value.content.trim().takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
    }

    fun JsonObject.int(key: String): Int? = text(key)?.toIntOrNull()

    private fun parseDate(raw: String?): LocalDate? = raw?.let {
        try {
            LocalDate.parse(it.take(10))
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /** The API's timestamps are UTC without an offset. */
    private fun parseKickoff(row: JsonObject): Instant? {
        row.text("strTimestamp")?.let { raw ->
            try {
                return LocalDateTime.parse(raw.removeSuffix("Z").take(19)).toInstant(ZoneOffset.UTC)
            } catch (_: DateTimeParseException) {
                // Fall through to the separate date and time fields.
            }
        }
        val date = parseDate(row.text("dateEvent")) ?: return null
        val time = row.text("strTime") ?: row.text("strEventTime") ?: return null
        return try {
            date.atTime(LocalTime.parse(time.take(8))).toInstant(ZoneOffset.UTC)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    fun classifyStatus(raw: String?, hasScore: Boolean, kickoff: Instant?, now: Instant): MatchStatus {
        val sinceKickoff = kickoff?.let { Duration.between(it, now) }
        val longPast = sinceKickoff != null && sinceKickoff > Duration.ofHours(3)
        // A saved "in play" status must not keep a finished game live forever.
        val tooLateToBeLive = sinceKickoff != null && sinceKickoff > Duration.ofHours(4)
        return when (raw?.trim()?.uppercase()) {
            "1H", "2H", "ET", "P", "LIVE", "IN PLAY", "FIRST HALF", "SECOND HALF", "EXTRA TIME" ->
                if (tooLateToBeLive) MatchStatus.UNKNOWN else MatchStatus.LIVE
            "HT", "BT", "HALF TIME", "HALFTIME", "BREAK TIME" ->
                if (tooLateToBeLive) MatchStatus.UNKNOWN else MatchStatus.HALF_TIME
            "FT", "AET", "PEN", "AP", "MATCH FINISHED", "FINISHED", "AFTER EXTRA TIME", "AFTER PENALTIES" ->
                MatchStatus.FINISHED
            "PST", "POSTPONED", "DELAYED" -> MatchStatus.POSTPONED
            "CANC", "CANCELLED", "CANCELED", "ABD", "ABANDONED", "AWD", "WO" -> MatchStatus.CANCELLED
            "NS", "NOT STARTED", "TBD", "TIME TO BE DEFINED" ->
                if (longPast) MatchStatus.UNKNOWN else MatchStatus.SCHEDULED
            null, "" -> when {
                // Some lists omit the status. Never call a match finished while it could still be running.
                hasScore && longPast -> MatchStatus.FINISHED
                hasScore -> MatchStatus.UNKNOWN
                sinceKickoff == null || sinceKickoff.isNegative -> MatchStatus.SCHEDULED
                else -> MatchStatus.UNKNOWN
            }
            else -> MatchStatus.UNKNOWN
        }
    }

    fun JsonObject.toMatch(now: Instant, wantedSport: String = "Soccer"): Match? {
        val id = text("idEvent") ?: return null
        val sport = text("strSport")
        if (sport != null && !sport.equals(wantedSport, ignoreCase = true)) return null
        val homeName = text("strHomeTeam") ?: return null
        val awayName = text("strAwayTeam") ?: return null
        val kickoff = parseKickoff(this)
        val rawHome = int("intHomeScore")
        val rawAway = int("intAwayScore")
        val rawStatus = text("strStatus")
        val status = classifyStatus(rawStatus, rawHome != null && rawAway != null, kickoff, now)
        val showScore = status != MatchStatus.SCHEDULED && status != MatchStatus.POSTPONED
        return Match(
            id = id,
            league = LeagueRef(
                id = text("idLeague").orEmpty(),
                name = text("strLeague").orEmpty(),
                badgeUrl = text("strLeagueBadge"),
                country = text("strCountry"),
            ),
            home = TeamRef(text("idHomeTeam").orEmpty(), homeName, text("strHomeTeamBadge")),
            away = TeamRef(text("idAwayTeam").orEmpty(), awayName, text("strAwayTeamBadge")),
            kickoff = kickoff,
            date = parseDate(text("dateEvent")),
            status = status,
            statusDetail = rawStatus,
            minute = if (status == MatchStatus.LIVE) text("strProgress")?.filter(Char::isDigit)?.toIntOrNull() else null,
            homeScore = rawHome.takeIf { showScore },
            awayScore = rawAway.takeIf { showScore },
            round = int("intRound"),
            season = text("strSeason"),
            venue = text("strVenue"),
        )
    }

    fun JsonObject.toStandingRow(): StandingRow? {
        val teamId = text("idTeam") ?: return null
        return StandingRow(
            rank = int("intRank") ?: return null,
            team = TeamRef(teamId, text("strTeam").orEmpty(), text("strBadge")),
            played = int("intPlayed") ?: 0,
            won = int("intWin") ?: 0,
            drawn = int("intDraw") ?: 0,
            lost = int("intLoss") ?: 0,
            goalsFor = int("intGoalsFor") ?: 0,
            goalsAgainst = int("intGoalsAgainst") ?: 0,
            goalDifference = int("intGoalDifference") ?: 0,
            points = int("intPoints") ?: 0,
            form = text("strForm"),
            note = text("strDescription"),
        )
    }

    fun JsonObject.toTeam(): Team? {
        val id = text("idTeam") ?: return null
        val sport = text("strSport")
        if (sport != null && !sport.equals("Soccer", ignoreCase = true)) return null
        val leagueId = text("idLeague")
        val leagueName = text("strLeague")
        return Team(
            id = id,
            name = text("strTeam") ?: return null,
            shortName = text("strTeamShort"),
            badgeUrl = text("strBadge") ?: text("strTeamBadge"),
            country = text("strCountry"),
            stadium = text("strStadium"),
            location = text("strLocation") ?: text("strStadiumLocation"),
            foundedYear = int("intFormedYear")?.takeIf { it > 0 },
            league = if (leagueId != null && leagueName != null) LeagueRef(leagueId, leagueName) else null,
            description = text("strDescriptionEN"),
        )
    }

    fun JsonObject.toMatchEvent(): MatchEvent? {
        val kind = text("strTimeline")?.lowercase() ?: return null
        val detail = text("strTimelineDetail")
        val lowerDetail = detail?.lowercase().orEmpty()
        val type = when {
            kind.contains("goal") && lowerDetail.contains("own") -> MatchEventType.OWN_GOAL
            kind.contains("goal") && lowerDetail.contains("missed") -> MatchEventType.MISSED_PENALTY
            kind.contains("goal") && lowerDetail.contains("penalty") -> MatchEventType.PENALTY
            kind.contains("goal") -> MatchEventType.GOAL
            kind.contains("card") && lowerDetail.contains("red") -> MatchEventType.RED_CARD
            kind.contains("card") -> MatchEventType.YELLOW_CARD
            kind.contains("subst") -> MatchEventType.SUBSTITUTION
            kind.contains("var") -> MatchEventType.VAR
            else -> MatchEventType.OTHER
        }
        val side = when (text("strHome")?.lowercase()) {
            "yes" -> Side.HOME
            "no" -> Side.AWAY
            else -> null
        }
        return MatchEvent(
            minute = int("intTime"),
            type = type,
            side = side,
            player = text("strPlayer"),
            detail = text("strAssist") ?: detail,
        )
    }
}
