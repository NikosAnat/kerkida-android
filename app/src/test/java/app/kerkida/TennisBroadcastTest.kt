package app.kerkida

import app.kerkida.data.model.LeagueRef
import app.kerkida.data.model.Match
import app.kerkida.data.model.MatchStatus
import app.kerkida.data.model.Sport
import app.kerkida.data.model.TeamRef
import app.kerkida.tv.TvItem
import app.kerkida.tv.TvLogic
import app.kerkida.tv.TvSport
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TennisBroadcastTest {
    private val finals = LeagueRef("tennis/atp", "ATP · Nitto ATP Finals", sport = Sport.TENNIS)

    private fun game(
        home: String,
        away: String,
        utc: String,
        stage: String,
        draw: String = "Men's Singles",
        league: LeagueRef = finals,
    ) = Match(
        id = "$home-$away",
        league = league,
        home = TeamRef("", home),
        away = TeamRef("", away),
        kickoff = Instant.parse(utc),
        date = null,
        status = MatchStatus.FINISHED,
        statusDetail = draw,
        stage = stage,
    )

    private fun listing(title: String, competition: String, utc: String, minutes: Int = 150) = TvItem(
        date = utc.take(10),
        time = utc.substring(11, 16),
        startMs = Instant.parse(utc).toEpochMilli(),
        channel = "Magenta Sport 6",
        title = title,
        competition = competition,
        sport = TvSport.TENNIS,
        durationMin = minutes,
        sourceId = "test",
    )

    private val singlesFinal = game("Carlos Alcaraz", "Alexander Zverev", "2026-11-22T17:15:00Z", "Final")
    private val doublesFinal = game("Theo Arribage / Albano Olivetti", "Marcelo Arevalo / Mate Pavic", "2026-11-22T14:10:00Z", "Final", "Men's Doubles")

    @Test
    fun aRoundAndDrawFindTheRealGame() {
        val singles = listing("Τελικός Μονού", "Nitto ATP Finals", "2026-11-22T17:00:00Z")
        val doubles = listing("Τελικός Διπλού", "Nitto ATP Finals", "2026-11-22T14:00:00Z")
        assertNotNull(TvLogic.matchScore(singles, singlesFinal))
        assertNotNull(TvLogic.matchScore(doubles, doublesFinal))
        assertNull(TvLogic.matchScore(singles, doublesFinal))
        assertNull(TvLogic.matchScore(doubles, singlesFinal))
    }

    @Test
    fun aDoublesGameInTheSameHoursIsNotTheSinglesFinal() {
        val sameHours = game("A One / B Two", "C Three / D Four", "2026-11-22T17:20:00Z", "Final", "Men's Doubles")
        assertNull(TvLogic.matchScore(listing("Τελικός", "Nitto ATP Finals", "2026-11-22T17:00:00Z"), sameHours))
    }

    @Test
    fun theWrongRoundOrTournamentLinksNothing() {
        val semi = game("Daniil Medvedev", "Alexander Zverev", "2026-11-22T17:30:00Z", "Semifinal")
        assertNull(TvLogic.matchScore(listing("Τελικός Μονού", "Nitto ATP Finals", "2026-11-22T17:00:00Z"), semi))
        val other = game("A B", "C D", "2026-11-22T17:15:00Z", "Final", league = LeagueRef("tennis/wta", "WTA · IEB+ Argentina Open", sport = Sport.TENNIS))
        assertNull(TvLogic.matchScore(listing("Τελικός Μονού", "Nitto ATP Finals", "2026-11-22T17:00:00Z"), other))
    }

    @Test
    fun greekSurnamesMatchInEitherOrderButASharedFirstNameDoesNot() {
        val named = listing("Αλεξάντερ Ζβέρεφ - Κάρλος Αλκαράθ", "Τένις", "2026-11-22T17:00:00Z")
        assertNotNull(TvLogic.matchScore(named, singlesFinal))
        val sameFirstName = game("Carlos Alcaraz", "Alexander Bublik", "2026-11-22T17:15:00Z", "Final")
        assertNull(TvLogic.matchScore(named, sameFirstName))
    }

    @Test
    fun aCountryTieIsNotLinkedToAPlayer() {
        assertNull(TvLogic.matchScore(listing("Τσεχία - Καναδάς", "Τένις", "2026-11-22T17:00:00Z"), singlesFinal))
    }

    @Test
    fun aVagueDayIsLinkedOnlyAtASingleCourtEventAndOutsideItsHoursNever() {
        val day = listing("Nitto ATP Finals 2026", "Τορίνο, 4η Μέρα", "2026-11-22T17:00:00Z")
        assertTrue((TvLogic.matchScore(day, singlesFinal) ?: 0.0) > 0.0)
        val later = game("Carlos Alcaraz", "Alexander Zverev", "2026-11-23T17:15:00Z", "Final")
        assertNull(TvLogic.matchScore(day, later))
        val tour = LeagueRef("tennis/wta", "WTA · IEB+ Argentina Open", sport = Sport.TENNIS)
        assertNull(TvLogic.matchScore(listing("IEB+ Argentina Open", "4η Μέρα", "2026-11-22T17:00:00Z"), game("A B", "C D", "2026-11-22T17:15:00Z", "Round 2", league = tour)))
    }
}
