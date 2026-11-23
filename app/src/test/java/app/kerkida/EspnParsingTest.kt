package app.kerkida

import app.kerkida.data.DataException
import app.kerkida.data.espn.EspnParsing
import app.kerkida.data.espn.EspnProvider
import app.kerkida.data.espn.EspnParsing.obj
import app.kerkida.data.espn.EspnParsing.list
import app.kerkida.data.model.LeagueRef
import app.kerkida.data.model.MatchEventType
import app.kerkida.data.model.MatchStatus
import app.kerkida.data.model.Side
import app.kerkida.fantasy.LineupRatings
import app.kerkida.fantasy.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class EspnParsingTest {
    private val league = LeagueRef("gre.1", "Super League")

    private fun event(state: String, name: String, clock: String = "0'", homeScore: String = "2", awayScore: String = "1") = """
        {"id":"100","date":"2026-11-21T17:30Z","competitions":[{"date":"2026-11-21T17:30Z",
        "status":{"displayClock":"$clock","type":{"name":"$name","state":"$state","description":"Desc"}},
        "venue":{"fullName":"Ground"},
        "odds":[{"provider":{"name":"must-not-be-read"}}],
        "competitors":[
          {"homeAway":"home","score":"$homeScore","team":{"id":"1","displayName":"Home FC","logo":"h.png"}},
          {"homeAway":"away","score":{"value":${awayScore}.0},"team":{"id":"2","displayName":"Away FC","logos":[{"href":"a.png"}]}}
        ]}]}
    """.trimIndent()

    private fun match(state: String, name: String, clock: String = "0'") =
        EspnParsing.toMatch(EspnParsing.root(event(state, name, clock)), league, "2026-27")!!

    @Test
    fun readsAFinishedMatchWithBothScoreShapes() {
        val parsed = match("post", "STATUS_FULL_TIME")
        assertEquals("gre.1~100", parsed.id)
        assertEquals(MatchStatus.FINISHED, parsed.status)
        assertEquals(2, parsed.homeScore)
        assertEquals(1, parsed.awayScore)
        assertEquals(Instant.parse("2026-11-21T17:30:00Z"), parsed.kickoff)
        assertEquals("h.png", parsed.home.badgeUrl)
        assertEquals("a.png", parsed.away.badgeUrl)
        assertEquals("Ground", parsed.venue)
    }

    @Test
    fun aMatchNotStartedHasNoScoreEvenThoughTheFeedSaysZero() {
        val parsed = match("pre", "STATUS_SCHEDULED")
        assertEquals(MatchStatus.SCHEDULED, parsed.status)
        assertNull(parsed.homeScore)
        assertNull(parsed.awayScore)
    }

    @Test
    fun readsLiveClockIncludingAddedTime() {
        assertEquals(67, match("in", "STATUS_SECOND_HALF", "67'").minute)
        assertEquals(93, match("in", "STATUS_SECOND_HALF", "90'+3'").minute)
        assertEquals(MatchStatus.HALF_TIME, match("in", "STATUS_HALFTIME").status)
        assertEquals(MatchStatus.POSTPONED, match("pre", "STATUS_POSTPONED").status)
        assertEquals(MatchStatus.CANCELLED, match("post", "STATUS_CANCELED").status)
    }

    @Test
    fun aBadMatchIdIsNotFound() {
        assertEquals("gre.1" to "55", EspnParsing.splitMatchId("gre.1~55"))
        assertThrows(DataException.NotFound::class.java) { EspnParsing.splitMatchId("55") }
    }

    private val summary = """
        {"keyEvents":[
          {"type":{"text":"Kickoff"},"clock":{"displayValue":"0'"}},
          {"type":{"text":"Goal - Volley"},"scoringPlay":true,"clock":{"displayValue":"55'"},"team":{"id":"2"},
           "participants":[{"athlete":{"id":"20","displayName":"Scorer"}}]},
          {"type":{"text":"Penalty - Saved"},"clock":{"displayValue":"70'"},"team":{"id":"1"},
           "participants":[{"athlete":{"id":"10","displayName":"Taker"}}]},
          {"type":{"text":"Red Card"},"clock":{"displayValue":"90'+2'"},"team":{"id":"1"},
           "participants":[{"athlete":{"id":"10","displayName":"Taker"}}]},
          {"type":{"text":"Substitution"},"clock":{"displayValue":"63'"},"team":{"id":"1"},
           "participants":[{"athlete":{"id":"12","displayName":"Comes On"}},{"athlete":{"id":"11","displayName":"Goes Off"}}]},
          {"type":{"text":"Goal"},"shootout":true,"clock":{"displayValue":"120'"},"team":{"id":"1"},"participants":[]}
        ],
        "rosters":[
          {"homeAway":"home","formation":"4-3-3","roster":[
            {"starter":true,"subbedOut":false,"subbedIn":false,"jersey":"1","position":{"abbreviation":"G"},
             "athlete":{"id":"9","displayName":"Keeper"},"stats":[{"name":"saves","value":4.0},{"name":"goalsConceded","value":1.0}]},
            {"starter":true,"subbedOut":true,"subbedIn":false,"position":{"abbreviation":"CD-L"},
             "athlete":{"id":"11","displayName":"Goes Off"},"stats":[{"name":"foulsCommitted","value":2.0}]},
            {"starter":false,"subbedOut":false,"subbedIn":true,"position":{"abbreviation":"SUB"},
             "athlete":{"id":"12","displayName":"Comes On"},"stats":[{"name":"totalGoals","value":1.0},{"name":"totalShots","value":3.0},{"name":"shotsOnTarget","value":2.0}]},
            {"starter":false,"subbedOut":false,"subbedIn":false,"position":{"abbreviation":"SUB"},
             "athlete":{"id":"13","displayName":"Unused"},"stats":[]}
          ]},
          {"homeAway":"away","formation":"4-4-2","roster":[]}
        ]}
    """.trimIndent()

    @Test
    fun keepsOnlyRealMatchEventsWithTheRightSide() {
        val parsed = match("post", "STATUS_FULL_TIME")
        val events = EspnParsing.toEvents(EspnParsing.root(summary), parsed)
        assertEquals(
            listOf(MatchEventType.GOAL, MatchEventType.MISSED_PENALTY, MatchEventType.RED_CARD, MatchEventType.SUBSTITUTION),
            events.map { it.type },
        )
        assertEquals(Side.AWAY, events[0].side)
        assertEquals(92, events[2].minute)
        assertEquals("Comes On", events[3].player)
        assertEquals("Goes Off", events[3].detail)
    }

    @Test
    fun readsLineupsAndWorksOutMinutes() {
        val parsed = match("post", "STATUS_FULL_TIME")
        val extras = EspnParsing.toExtras(EspnParsing.root(summary), parsed)!!
        val home = extras.home!!
        assertEquals("4-3-3", home.formation)
        assertEquals(90, home.players.first { it.name == "Keeper" }.minutes)
        assertEquals(63, home.players.first { it.name == "Goes Off" }.minutes)
        assertEquals(27, home.players.first { it.name == "Comes On" }.minutes)
        assertEquals(0, home.players.first { it.name == "Unused" }.minutes)
        assertNull(extras.away)
    }

    @Test
    fun noRostersMeansNoLineup() {
        val parsed = match("pre", "STATUS_SCHEDULED")
        assertNull(EspnParsing.toExtras(EspnParsing.root("""{"rosters":[{"homeAway":"home","roster":[]}]}"""), parsed))
    }

    @Test
    fun ratesOnlyPlayersWhoPlayedAndExplainsTheirActions() {
        val parsed = match("post", "STATUS_FULL_TIME")
        val ratings = LineupRatings.build(parsed, EspnParsing.toExtras(EspnParsing.root(summary), parsed))
        assertNotNull(ratings)
        assertEquals(3, ratings!!.home.size)
        assertFalse(ratings.home.any { it.name == "Unused" })
        val scorer = ratings.home.first { it.name == "Comes On" }
        assertTrue(scorer.breakdown.rating > 6.0)
        assertEquals(1, ratings.home.count { it.position == Position.GKP })
    }

    @Test
    fun mapsPositionCodes() {
        assertEquals(Position.GKP, LineupRatings.positionOf("G"))
        assertEquals(Position.DEF, LineupRatings.positionOf("CD-R"))
        assertEquals(Position.DEF, LineupRatings.positionOf("LB"))
        assertEquals(Position.MID, LineupRatings.positionOf("DM"))
        assertEquals(Position.MID, LineupRatings.positionOf("CM-L"))
        assertEquals(Position.FWD, LineupRatings.positionOf("CF-R"))
        assertEquals(Position.FWD, LineupRatings.positionOf("F"))
    }

    @Test
    fun readsTheFullTable() {
        val body = """{"children":[{"standings":{"entries":[
            {"team":{"id":"2","displayName":"B"},"stats":[{"name":"rank","value":2.0},{"name":"points","value":20.0},{"name":"overall"}]},
            {"team":{"id":"1","displayName":"A"},"note":{"description":"Playoffs"},"stats":[
              {"name":"rank","value":1.0},{"name":"gamesPlayed","value":10.0},{"name":"wins","value":9.0},{"name":"ties","value":0.0},
              {"name":"losses","value":1.0},{"name":"pointsFor","value":22.0},{"name":"pointsAgainst","value":5.0},
              {"name":"pointDifferential","value":17.0},{"name":"points","value":27.0}]}
        ]}}]}"""
        val rows = EspnParsing.toStandingRows(EspnParsing.root(body))
        assertEquals(listOf("A", "B"), rows.map { it.team.name })
        assertEquals(27, rows[0].points)
        assertEquals(17, rows[0].goalDifference)
        assertEquals("Playoffs", rows[0].note)
    }

    @Test
    fun groupsPlayingDaysIntoRounds() {
        val days = listOf("2026-08-22", "2026-08-23", "2026-08-24", "2026-08-29", "2026-08-30", "2026-09-12")
            .map(LocalDate::parse)
        val rounds = EspnParsing.roundsOf(days)
        assertEquals(listOf(3, 2, 1), rounds.map { it.size })
    }

    @Test
    fun readsBothCalendarShapes() {
        val flat = EspnParsing.root("""{"calendar":["2026-08-22T07:00Z","2026-08-23T07:00Z"]}""")
        assertEquals(2, EspnParsing.calendarDays(flat).size)
        val nested = EspnParsing.root("""{"calendar":[{"entries":[{"startDate":"2026-09-16T07:00Z"}]}]}""")
        assertEquals(LocalDate.parse("2026-09-16"), EspnParsing.calendarDays(nested).single())
    }

    @Test
    fun searchFindsGreekAndLatinSpellings() {
        val team = EspnProvider.searchKey("Olympiacos")
        assertEquals(team, EspnProvider.searchKey("Ολυμπιακός"))
        assertEquals(team, EspnProvider.searchKey("olympiakos"))
        assertTrue(EspnProvider.searchKey("PAOK").contains(EspnProvider.searchKey("παοκ")))
    }

    @Test
    fun oddsAreNotPartOfTheParsedMatch() {
        val parsed = match("post", "STATUS_FULL_TIME")
        assertFalse(parsed.toString().contains("must-not-be-read"))
        assertEquals(1, EspnParsing.root(event("post", "X"))["competitions"].list().size)
        assertNotNull(EspnParsing.root(event("post", "X"))["competitions"].list().first().obj())
    }
}
