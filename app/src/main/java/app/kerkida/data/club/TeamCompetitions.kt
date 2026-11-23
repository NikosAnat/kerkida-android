package app.kerkida.data.club

import app.kerkida.data.CatalogLeague
import app.kerkida.data.LeagueGrouping
import app.kerkida.data.model.LeagueRef
import app.kerkida.data.model.Match

/** The competitions of a club that have a league table. Knockout cups and friendlies have none. */
object TeamCompetitions {
    fun of(main: LeagueRef?, matches: List<Match>, find: (String) -> CatalogLeague?): List<LeagueRef> =
        (listOfNotNull(main) + matches.map { it.league })
            .distinctBy { it.id }
            .filterNot { league -> find(league.id)?.let(LeagueGrouping::isKnockout) ?: false }
}
