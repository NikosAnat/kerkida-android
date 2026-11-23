package app.kerkida

import app.kerkida.data.espn.EspnParsing
import app.kerkida.data.espn.MultiSportParsing
import app.kerkida.data.model.LeagueRef
import app.kerkida.data.model.Match
import app.kerkida.data.model.MatchStatus
import app.kerkida.data.model.Sport
import app.kerkida.data.model.TeamRef
import app.kerkida.shield.DelayBuffer
import app.kerkida.shield.LiveSnapshot
import app.kerkida.shield.RevealLevel
import app.kerkida.shield.ShieldEntry
import app.kerkida.shield.ShieldSettings
import app.kerkida.shield.SpoilerLogic
import app.kerkida.tv.DirectoryTeam
import app.kerkida.tv.TeamDirectory
import app.kerkida.tv.TvLogic
import app.kerkida.tv.TvSport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class MultiSportTest {
    private val nba = LeagueRef("basketball/nba", "NBA", sport = Sport.BASKETBALL)

    @Test
    fun readsATeamGameWithItsQuartersAndLeagueScopedIds() {
        val json = """{"id":"9","date":"2026-11-21T01:00Z","competitions":[{"date":"2026-11-21T01:00Z",
            "status":{"displayClock":"5:32","type":{"name":"STATUS_IN_PROGRESS","state":"in","shortDetail":"Q3 5:32"}},
            "competitors":[
             {"homeAway":"home","score":"70","linescores":[{"value":29.0},{"value":31.0},{"value":10.0}],"team":{"id":"1","displayName":"Hawks"}},
             {"homeAway":"away","score":"64","linescores":[{"value":20.0},{"value":25.0},{"value":19.0}],"team":{"id":"2","displayName":"Pistons"}}
            ]}]}"""
        val game = MultiSportParsing.teamGame(EspnParsing.root(json), nba)!!
        assertEquals("basketball/nba~9", game.id)
        assertEquals("basketball/nba:1", game.home.id)
        assertNull("A basketball clock is not a football minute", game.minute)
        assertEquals("Q3 5:32", game.statusDetail)
        assertEquals("29-20  31-25  10-19", game.resultText)
    }

    @Test
    fun readsATennisMatchCountingSetsWon() {
        val json = """{"id":"5","date":"2026-11-22T17:00Z","status":{"type":{"name":"STATUS_FINAL","state":"post"}},"competitors":[
            {"order":1,"winner":true,"athlete":{"displayName":"Jannik Sinner","flag":{"href":"ita.png"}},"linescores":[{"value":6.0},{"value":4.0},{"value":6.0}]},
            {"order":2,"athlete":{"displayName":"Carlos Alcaraz"},"linescores":[{"value":3.0},{"value":6.0},{"value":2.0}]}]}"""
        val league = LeagueRef("tennis/atp", "ATP", sport = Sport.TENNIS)
        val game = MultiSportParsing.duel(EspnParsing.root(json), league, sets = true, note = "Men's Singles")!!
        assertEquals("Jannik Sinner", game.home.name)
        assertEquals("ita.png", game.home.badgeUrl)
        assertEquals(2, game.homeScore)
        assertEquals(1, game.awayScore)
        assertEquals("6-3  4-6  6-2", game.resultText)
        assertEquals("", game.home.id)
    }

    @Test
    fun readsARaceSessionAndItsWinner() {
        val event = EspnParsing.root("""{"shortName":"Las Vegas GP"}""")
        val session = EspnParsing.root("""{"id":"3","date":"2026-11-22T04:00Z","type":{"abbreviation":"Race"},
            "status":{"type":{"name":"STATUS_FINAL","state":"post"}},
            "competitors":[{"order":2,"athlete":{"displayName":"Lando Norris"}},{"order":1,"athlete":{"displayName":"Max Verstappen"}}]}""")
        val game = MultiSportParsing.session(event, session, LeagueRef("racing/f1", "Formula 1", sport = Sport.MOTOR))!!
        assertEquals("Las Vegas GP · Race", game.eventTitle)
        assertEquals("Max Verstappen", game.resultText)
    }

    @Test
    fun readsAEuroLeagueGame() {
        val json = """{"identifier":"E2026_129","round":13,"played":true,"roundAlias":"Round 13","utcDate":"2026-11-20T19:45:00Z","venue":{"name":"Hall"},
            "local":{"club":{"code":"PAR","name":"Partizan Mozzart Bet Belgrade","editorialName":"Partizan","images":{"crest":"p.png"}},"score":91,"partials":{"partials1":20,"partials2":21,"partials3":22,"partials4":14}},
            "road":{"club":{"code":"OLY","name":"Olympiacos","editorialName":"Olympiacos","images":{"crest":"o.png"}},"score":88,"partials":{"partials1":25,"partials2":20,"partials3":20,"partials4":9}}}"""
        val game = MultiSportParsing.euroGame(EspnParsing.root(json), LeagueRef("basketball/euroleague", "EuroLeague", sport = Sport.BASKETBALL), Instant.parse("2026-11-22T10:00:00Z"))!!
        assertEquals(MatchStatus.FINISHED, game.status)
        assertEquals("basketball/euroleague:PAR", game.home.id)
        assertEquals(91, game.homeScore)
        assertEquals("o.png", game.away.badgeUrl)
        assertEquals("20-25  21-20  22-20  14-9", game.resultText)
    }

    @Test
    fun theShieldHidesSetAndQuarterScoresToo() {
        val game = Match(
            id = "m", league = nba, home = TeamRef("a", "A"), away = TeamRef("b", "B"), kickoff = Instant.ofEpochMilli(1000),
            date = null, status = MatchStatus.FINISHED, homeScore = 90, awayScore = 80, resultText = "20-10  25-30",
        )
        val hidden = SpoilerLogic.resolve(game, ShieldEntry("m"), ShieldSettings(), null, null, emptyList(), 2000)
        assertNull(hidden.match.resultText)
        val timeOnly = SpoilerLogic.resolve(
            game,
            ShieldEntry("m", level = RevealLevel.TIME_ONLY),
            ShieldSettings(),
            DelayBuffer<LiveSnapshot>().apply { add(1000, LiveSnapshot.of(game)) },
            null,
            emptyList(),
            100_000,
        )
        assertNull(timeOnly.match.resultText)
    }

    @Test
    fun classifiesEverySportInTheGuide() {
        assertEquals(TvSport.VOLLEYBALL, TvLogic.classify("Volley League", "Μίλωνας - Ολυμπιακός"))
        assertEquals(TvSport.HANDBALL, TvLogic.classify("Χάντμπολ Α1 Γυναικών", "Νέα Ιωνία - ΠΑΟΚ"))
        assertEquals(TvSport.WATER_POLO, TvLogic.classify("Champions League Πόλο", "Ολυμπιακός - Γιάντραν"))
        assertEquals(TvSport.AMERICAN_FOOTBALL, TvLogic.classify("NFL", "Σιάτλ Σίχοκς - Κάνσας Σίτι Τσιφς"))
        assertEquals(TvSport.BASKETBALL, TvLogic.classify("EuroLeague", "Ντουμπάι - Παρί"))
        assertEquals(TvSport.MOTOR, TvLogic.classify("Moto GP", "Γκραν Πρι Πορτογαλίας"))
        assertEquals(TvSport.TENNIS, TvLogic.classify("ATP 500", "Βασιλεία"))
    }

    @Test
    fun findsACrestFromAGreekNameWithinTheRightSport() {
        val directory = TeamDirectory {
            listOf(
                DirectoryTeam("FOOTBALL", "Olympiacos", "football.png"),
                DirectoryTeam("BASKETBALL", "Olympiacos Piraeus", "basket.png", listOf("Olympiacos")),
                DirectoryTeam("FOOTBALL", "Liverpool", "lfc.png"),
            )
        }
        assertEquals("football.png", directory.crest("Ολυμπιακός", TvSport.FOOTBALL))
        assertEquals("basket.png", directory.crest("Ολυμπιακός", TvSport.BASKETBALL))
        assertEquals("lfc.png", directory.crest("Λίβερπουλ", TvSport.FOOTBALL))
        assertNull(directory.crest("Κιλκίς", TvSport.FOOTBALL))
        assertNull(directory.crest("Ολυμπιακός", TvSport.HANDBALL))
        assertNotEquals(directory.crest("Ολυμπιακός", TvSport.FOOTBALL), directory.crest("Ολυμπιακός", TvSport.BASKETBALL))
    }
}
