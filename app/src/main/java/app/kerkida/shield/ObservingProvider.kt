package app.kerkida.shield

import app.kerkida.data.Sourced
import app.kerkida.data.SportsDataProvider
import app.kerkida.data.model.LeagueFixtures
import app.kerkida.data.model.MatchDay
import app.kerkida.data.model.MatchDetails
import app.kerkida.data.model.Sport
import app.kerkida.data.model.TeamMatches
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import java.time.LocalDate

/** Notes when each match update arrived, which is what the sync delay is measured from. */
class ObservingProvider(
    private val delegate: SportsDataProvider,
    private val shield: ShieldController,
) : SportsDataProvider by delegate {

    override fun matchDay(date: LocalDate, priorityLeagueIds: List<String>, refresh: Boolean): Flow<MatchDay> =
        delegate.matchDay(date, priorityLeagueIds, refresh).onEach { day -> day.matches.forEach(shield::observe) }

    override fun sportDay(sport: Sport, date: LocalDate, refresh: Boolean): Flow<MatchDay> =
        delegate.sportDay(sport, date, refresh).onEach { day -> day.matches.forEach(shield::observe) }

    override suspend fun matchDetails(matchId: String, refresh: Boolean): Sourced<MatchDetails> =
        delegate.matchDetails(matchId, refresh).also { shield.observeDetails(it.data.match, it.data.events) }

    override suspend fun fixtures(
        leagueId: String,
        season: String?,
        round: Int?,
        refresh: Boolean,
    ): Sourced<LeagueFixtures> =
        delegate.fixtures(leagueId, season, round, refresh).also { it.data.matches.forEach(shield::observe) }

    override suspend fun teamMatches(teamId: String, refresh: Boolean): Sourced<TeamMatches> =
        delegate.teamMatches(teamId, refresh).also {
            it.data.recent.forEach(shield::observe)
            it.data.upcoming.forEach(shield::observe)
        }
}
