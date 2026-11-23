package app.kerkida.data

import app.kerkida.data.model.LeagueDetails
import app.kerkida.data.model.LeagueFixtures
import app.kerkida.data.model.LeagueRef
import app.kerkida.data.model.MatchDay
import app.kerkida.data.model.Match
import app.kerkida.data.model.MatchDetails
import app.kerkida.data.model.SearchResults
import app.kerkida.data.model.Standings
import app.kerkida.data.model.Team
import app.kerkida.data.model.TeamMatches
import app.kerkida.data.model.Sport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.time.Instant
import java.time.LocalDate

/** Describes where the data on screen comes from. */
data class ProviderInfo(
    val id: String,
    val displayName: String,
    /** Demo data is invented. The UI must label it wherever it is shown. */
    val isDemo: Boolean,
    val attributionUrl: String? = null,
)

/** A value together with how fresh it is. */
data class Sourced<T>(
    val data: T,
    val fetchedAt: Instant,
    /** True when a refresh failed and this is an older saved copy. */
    val isStale: Boolean = false,
)

sealed class DataException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Offline(cause: Throwable? = null) : DataException("No connection", cause)
    class RateLimited(val retryAfterSeconds: Long) : DataException("Rate limited")
    class NotFound : DataException("Not found")
    class Server(val code: Int) : DataException("Server error $code")
    class BadData(cause: Throwable? = null) : DataException("Unreadable response", cause)
}

/**
 * The only door between the app and a sports data source.
 * To swap sources, implement this interface and register it in [ProviderRegistry].
 * Methods throw [DataException] when nothing usable is available.
 */
interface SportsDataProvider {
    val info: ProviderInfo

    /**
     * Emits the day's matches as they arrive, ending with a complete [MatchDay].
     * [priorityLeagueIds] (the person's favourites) are fetched first.
     */
    fun matchDay(
        date: LocalDate,
        priorityLeagueIds: List<String> = emptyList(),
        refresh: Boolean = false,
    ): Flow<MatchDay>

    /** The sports this source can show. Football is always first. */
    val sports: List<Sport> get() = listOf(Sport.FOOTBALL)

    /** One day of a sport other than football. */
    fun sportDay(sport: Sport, date: LocalDate, refresh: Boolean = false): Flow<MatchDay> = emptyFlow()

    /** Past seasons of a team, for head-to-head history. Empty when the source has none. */
    suspend fun teamHistory(teamId: String, seasons: Int): List<Match> = emptyList()

    suspend fun matchDetails(matchId: String, refresh: Boolean = false): Sourced<MatchDetails>

    suspend fun featuredLeagues(): Sourced<List<LeagueRef>>
    suspend fun league(leagueId: String, refresh: Boolean = false): Sourced<LeagueDetails>
    suspend fun standings(leagueId: String, season: String?, refresh: Boolean = false): Sourced<Standings>

    /** Fixtures for one round. A null [round] means the source's current round. */
    suspend fun fixtures(
        leagueId: String,
        season: String?,
        round: Int?,
        refresh: Boolean = false,
    ): Sourced<LeagueFixtures>

    suspend fun team(teamId: String, refresh: Boolean = false): Sourced<Team>
    suspend fun teamMatches(teamId: String, refresh: Boolean = false): Sourced<TeamMatches>

    suspend fun search(query: String): Sourced<SearchResults>
}
