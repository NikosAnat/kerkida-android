package app.kerkida

import app.kerkida.data.CatalogLeague
import app.kerkida.data.club.TeamCompetitions
import app.kerkida.data.model.LeagueRef
import app.kerkida.data.model.Match
import app.kerkida.data.model.MatchStatus
import app.kerkida.data.model.TeamRef
import org.junit.Assert.assertEquals
import org.junit.Test

class TeamCompetitionsTest {
    private val catalog = listOf(
        CatalogLeague("grl.5847", "Γ' Εθνική · 6ος Όμιλος", "FOOTBALL", "GR"),
        CatalogLeague("grl.5830", "Κύπελλο Ελλάδας", "FOOTBALL", "GR"),
        CatalogLeague("gre.1", "Super League", "FOOTBALL", "GR"),
        CatalogLeague("uefa.europa", "UEFA Europa League", "FOOTBALL"),
        CatalogLeague("club.friendly", "Club Friendly", "FOOTBALL"),
    ).associateBy { it.id }

    private fun game(league: String) = Match(
        id = league,
        league = LeagueRef(league, league),
        home = TeamRef("h", "h"),
        away = TeamRef("a", "a"),
        kickoff = null,
        date = null,
        status = MatchStatus.SCHEDULED,
    )

    @Test
    fun `the main league comes first and cups are left out`() {
        val found = TeamCompetitions.of(
            LeagueRef("grl.5847", "Γ' Εθνική"),
            listOf(game("grl.5830"), game("grl.5847"), game("club.friendly")),
            catalog::get,
        )
        assertEquals(listOf("grl.5847"), found.map { it.id })
    }

    @Test
    fun `a european league phase keeps its table`() {
        val found = TeamCompetitions.of(LeagueRef("gre.1", "Super League"), listOf(game("uefa.europa")), catalog::get)
        assertEquals(listOf("gre.1", "uefa.europa"), found.map { it.id })
    }

    @Test
    fun `a club with no league still gets the tables of its games`() {
        assertEquals(listOf("gre.1"), TeamCompetitions.of(null, listOf(game("gre.1")), catalog::get).map { it.id })
    }
}
