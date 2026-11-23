package app.kerkida.data.model

import java.time.Instant
import java.time.LocalDate

enum class MatchStatus { SCHEDULED, LIVE, HALF_TIME, FINISHED, POSTPONED, CANCELLED, UNKNOWN }

val MatchStatus.isInPlay: Boolean get() = this == MatchStatus.LIVE || this == MatchStatus.HALF_TIME

data class TeamRef(
    val id: String,
    val name: String,
    val badgeUrl: String? = null,
)

data class LeagueRef(
    val id: String,
    val name: String,
    val badgeUrl: String? = null,
    val country: String? = null,
    val sport: Sport = Sport.FOOTBALL,
)

data class Match(
    val id: String,
    val league: LeagueRef,
    val home: TeamRef,
    val away: TeamRef,
    val kickoff: Instant?,
    val date: LocalDate?,
    val status: MatchStatus,
    /** Raw status text from the source, shown when we cannot classify it. */
    val statusDetail: String? = null,
    val minute: Int? = null,
    val homeScore: Int? = null,
    val awayScore: Int? = null,
    val round: Int? = null,
    val season: String? = null,
    val venue: String? = null,
    /** Set for events with no two sides, such as a race session. */
    val eventTitle: String? = null,
    /** Period scores, set scores or a winner. It can spoil a result, so the shield hides it. */
    val resultText: String? = null,
    /** The tournament round as the source names it, such as "Semifinal". */
    val stage: String? = null,
)

enum class MatchEventType { GOAL, OWN_GOAL, PENALTY, MISSED_PENALTY, YELLOW_CARD, RED_CARD, SUBSTITUTION, VAR, OTHER }

enum class Side { HOME, AWAY }

data class MatchEvent(
    val minute: Int?,
    val type: MatchEventType,
    val side: Side?,
    val player: String?,
    val detail: String? = null,
)

data class MatchDetails(
    val match: Match,
    val events: List<MatchEvent>,
    val standings: Standings?,
    val description: String? = null,
    /** True when the source may have returned only some of the match events. */
    val eventsMayBeIncomplete: Boolean = false,
    /** Line-ups and statistics, when the source offers them. */
    val extras: MatchExtras? = null,
)

data class LineupPlayer(
    val id: String,
    val name: String,
    val jersey: String? = null,
    val isStarter: Boolean,
    val positionCode: String? = null,
    val minutes: Int = 0,
    val stats: Map<String, Double> = emptyMap(),
    val photoUrl: String? = null,
)

data class TeamLineup(val formation: String?, val players: List<LineupPlayer>)

data class MatchExtras(val home: TeamLineup?, val away: TeamLineup?)

/** One day's matches, delivered step by step because the free source is slow to query politely. */
data class MatchDay(
    val matches: List<Match>,
    val fetchedAt: Instant,
    val loadedSources: Int,
    val totalSources: Int,
    val failedSources: Int = 0,
    val isStale: Boolean = false,
    /** True when the source cannot list every match in the world for a day. */
    val isLimitedCoverage: Boolean = false,
    val firstError: Exception? = null,
) {
    val isComplete: Boolean get() = loadedSources + failedSources >= totalSources
}

data class StandingRow(
    val rank: Int,
    val team: TeamRef,
    val played: Int,
    val won: Int,
    val drawn: Int,
    val lost: Int,
    val goalsFor: Int,
    val goalsAgainst: Int,
    val goalDifference: Int,
    val points: Int,
    val form: String? = null,
    val note: String? = null,
)

data class Standings(
    val league: LeagueRef,
    val season: String?,
    val rows: List<StandingRow>,
    /** True when the source only returned part of the table. */
    val isPartial: Boolean,
)

data class LeagueDetails(
    val league: LeagueRef,
    val currentSeason: String?,
    val description: String? = null,
)

data class LeagueFixtures(
    val league: LeagueRef,
    val season: String?,
    val round: Int?,
    val matches: List<Match>,
    val hasPreviousRound: Boolean,
    val hasNextRound: Boolean,
)

data class Team(
    val id: String,
    val name: String,
    val shortName: String? = null,
    val badgeUrl: String? = null,
    val country: String? = null,
    val stadium: String? = null,
    val location: String? = null,
    val foundedYear: Int? = null,
    val league: LeagueRef? = null,
    val description: String? = null,
)

data class TeamMatches(
    val recent: List<Match>,
    val upcoming: List<Match>,
)

data class SearchResults(
    val teams: List<Team>,
    val leagues: List<LeagueRef>,
) {
    val isEmpty: Boolean get() = teams.isEmpty() && leagues.isEmpty()
}
