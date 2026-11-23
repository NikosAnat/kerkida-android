package app.kerkida

import app.kerkida.alerts.AlertKind
import app.kerkida.alerts.SmartAlertEngine
import app.kerkida.alerts.TeamAlertRules
import app.kerkida.broadcast.AccessType
import app.kerkida.broadcast.Broadcast
import app.kerkida.broadcast.BroadcastScope
import app.kerkida.broadcast.BroadcastStore
import app.kerkida.broadcast.ManualBroadcastProvider
import app.kerkida.broadcast.WatchRank
import app.kerkida.broadcast.WhatIHave
import app.kerkida.data.model.LeagueRef
import app.kerkida.data.model.Match
import app.kerkida.data.model.MatchEvent
import app.kerkida.data.model.MatchEventType
import app.kerkida.data.model.MatchStatus
import app.kerkida.data.model.Side
import app.kerkida.data.model.TeamRef
import app.kerkida.fantasy.FplTeam
import app.kerkida.fantasy.FplTeamMatcher
import app.kerkida.fantasy.PlayerMatchStats
import app.kerkida.fantasy.Position
import app.kerkida.fantasy.RatingAction
import app.kerkida.fantasy.RatingCalculator
import app.kerkida.fantasy.SquadPlayer
import app.kerkida.preview.FormResult
import app.kerkida.preview.InsightKind
import app.kerkida.preview.PreviewBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class StageOneLogicTest {
    private fun broadcast(channel: String, access: AccessType) = Broadcast(
        scope = BroadcastScope.MATCH,
        targetId = "x",
        channelId = channel,
        channelName = channel,
        access = access,
        updatedAtMs = 1,
    )

    private fun game(
        id: String,
        homeId: String,
        awayId: String,
        home: Int?,
        away: Int?,
        status: MatchStatus = MatchStatus.FINISHED,
        minute: Int? = null,
        kickoffSeconds: Long = 1_000,
        season: String? = "2026-2027",
    ) = Match(
        id = id,
        league = LeagueRef("l1", "League"),
        home = TeamRef(homeId, homeId),
        away = TeamRef(awayId, awayId),
        kickoff = Instant.ofEpochSecond(kickoffSeconds),
        date = null,
        status = status,
        minute = minute,
        homeScore = home,
        awayScore = away,
        season = season,
    )

    @Test
    fun ranksWhatIOwnThenFreeThenPayPerViewThenTheRest() {
        val owned = setOf("nova")
        assertEquals(WatchRank.OWNED, WhatIHave.rankOf(broadcast("nova", AccessType.SUBSCRIPTION), owned))
        assertEquals(WatchRank.FREE, WhatIHave.rankOf(broadcast("ert", AccessType.FREE), owned))
        assertEquals(WatchRank.PAY_PER_VIEW, WhatIHave.rankOf(broadcast("nova", AccessType.PAY_PER_VIEW), owned))
        assertEquals(WatchRank.NOT_OWNED, WhatIHave.rankOf(broadcast("magenta", AccessType.SUBSCRIPTION), owned))
        assertEquals(WatchRank.UNKNOWN, WhatIHave.rankOf(emptyList(), owned))
    }

    @Test
    fun aMatchTakesItsBestOption() {
        val options = listOf(broadcast("magenta", AccessType.SUBSCRIPTION), broadcast("ert", AccessType.FREE))
        assertEquals(WatchRank.FREE, WhatIHave.rankOf(options, setOf("nova")))
        assertEquals("ert", WhatIHave.bestFirst(options, setOf("nova")).first().channelId)
    }

    @Test
    fun sortsMatchesByWhatIHaveThenKickoff() {
        data class Row(val name: String, val options: List<Broadcast>, val kickoff: Long?)
        val rows = listOf(
            Row("unknown", emptyList(), 1),
            Row("other-sub", listOf(broadcast("magenta", AccessType.SUBSCRIPTION)), 2),
            Row("ppv", listOf(broadcast("nova", AccessType.PAY_PER_VIEW)), 3),
            Row("free", listOf(broadcast("ert", AccessType.FREE)), 4),
            Row("mine-late", listOf(broadcast("nova", AccessType.SUBSCRIPTION)), 9),
            Row("mine-early", listOf(broadcast("nova", AccessType.SUBSCRIPTION)), 5),
        )
        val sorted = WhatIHave.sort(rows, setOf("nova"), { it.options }, { it.kickoff }).map { it.name }
        assertEquals(listOf("mine-early", "mine-late", "free", "ppv", "other-sub", "unknown"), sorted)
    }

    @Test
    fun changingMySubscriptionsChangesTheOrder() {
        val a = "a" to listOf(broadcast("nova", AccessType.SUBSCRIPTION))
        val b = "b" to listOf(broadcast("magenta", AccessType.SUBSCRIPTION))
        val rows = listOf(a, b)
        assertEquals("a", WhatIHave.sort(rows, setOf("nova"), { it.second }, { 1L }).first().first)
        assertEquals("b", WhatIHave.sort(rows, setOf("magenta"), { it.second }, { 1L }).first().first)
    }

    private class MemoryBroadcasts : BroadcastStore {
        var items = emptyList<Broadcast>()
        var subscriptions = emptySet<String>()
        override fun load() = items
        override fun save(items: List<Broadcast>) { this.items = items }
        override fun loadSubscriptions() = subscriptions
        override fun saveSubscriptions(ids: Set<String>) { subscriptions = ids }
    }

    @Test
    fun aMatchEntryOverridesItsLeagueAndLeagueEntriesStayInTheirSeason() {
        var time = 100L
        val manual = ManualBroadcastProvider(MemoryBroadcasts()) { time }
        val thisSeason = game("m1", "h", "a", null, null, MatchStatus.SCHEDULED)
        manual.add(thisSeason, BroadcastScope.LEAGUE, "nova", "Nova", AccessType.SUBSCRIPTION)
        assertEquals("nova", manual.matching(thisSeason).single().channelId)
        assertEquals(100L, manual.matching(thisSeason).single().updatedAtMs)

        val nextSeason = thisSeason.copy(id = "m2", season = "2027-2028")
        assertTrue(manual.matching(nextSeason).isEmpty())

        time = 200L
        manual.add(thisSeason, BroadcastScope.MATCH, "ert", "ΕΡΤ", AccessType.FREE)
        assertEquals("ert", manual.matching(thisSeason).single().channelId)
    }

    @Test
    fun editingTheSameChannelUpdatesItsLastUpdatedTime() {
        var time = 100L
        val manual = ManualBroadcastProvider(MemoryBroadcasts()) { time }
        val match = game("m1", "h", "a", null, null, MatchStatus.SCHEDULED)
        manual.add(match, BroadcastScope.MATCH, "nova", "Nova", AccessType.SUBSCRIPTION)
        time = 300L
        manual.add(match, BroadcastScope.MATCH, "nova", "Nova", AccessType.PAY_PER_VIEW)
        val only = manual.matching(match).single()
        assertEquals(300L, only.updatedAtMs)
        assertEquals(AccessType.PAY_PER_VIEW, only.access)
    }

    private val rules = TeamAlertRules()
    private fun live(home: Int, away: Int, minute: Int) =
        game("m", "h", "a", home, away, MatchStatus.LIVE, minute)

    @Test
    fun theFirstLookNeverNotifies() {
        val red = MatchEvent(30, MatchEventType.RED_CARD, Side.HOME, "P")
        assertTrue(SmartAlertEngine.detect(null, live(1, 0, 85), listOf(red), rules).alerts.isEmpty())
    }

    @Test
    fun aRedCardNotifiesOnce() {
        val first = SmartAlertEngine.detect(null, live(0, 0, 20), emptyList(), rules)
        val red = MatchEvent(30, MatchEventType.RED_CARD, Side.AWAY, "P")
        val second = SmartAlertEngine.detect(first.memory, live(0, 0, 31), listOf(red), rules)
        assertEquals(AlertKind.RED_CARD, second.alerts.single().kind)
        assertTrue(SmartAlertEngine.detect(second.memory, live(0, 0, 32), listOf(red), rules).alerts.isEmpty())
    }

    @Test
    fun onlyLateGoalsNotify() {
        val start = SmartAlertEngine.detect(null, live(0, 0, 40), emptyList(), rules)
        val early = MatchEvent(41, MatchEventType.GOAL, Side.HOME, "P")
        val afterEarly = SmartAlertEngine.detect(start.memory, live(1, 0, 42), listOf(early), rules)
        assertTrue(afterEarly.alerts.isEmpty())
        val late = MatchEvent(84, MatchEventType.GOAL, Side.HOME, "Q")
        val afterLate = SmartAlertEngine.detect(afterEarly.memory, live(2, 0, 85), listOf(early, late), rules)
        assertEquals(AlertKind.LATE_GOAL, afterLate.alerts.single().kind)
        assertEquals("Q", afterLate.alerts.single().player)
    }

    @Test
    fun aLateScoreChangeWithoutAnEventStillNotifies() {
        val start = SmartAlertEngine.detect(null, live(1, 1, 80), emptyList(), rules)
        val result = SmartAlertEngine.detect(start.memory, live(2, 1, 88), emptyList(), rules)
        assertEquals(AlertKind.LATE_GOAL, result.alerts.single().kind)
    }

    @Test
    fun aComebackNotifiesOnceForTheTeamThatTurnedItAround() {
        val a = SmartAlertEngine.detect(null, live(0, 1, 20), emptyList(), rules)
        val b = SmartAlertEngine.detect(a.memory, live(1, 1, 50), emptyList(), rules)
        assertTrue(b.alerts.isEmpty())
        val c = SmartAlertEngine.detect(b.memory, live(2, 1, 60), emptyList(), rules)
        assertEquals(AlertKind.COMEBACK, c.alerts.single().kind)
        assertEquals(Side.HOME, c.alerts.single().side)
        assertTrue(SmartAlertEngine.detect(c.memory, live(3, 1, 65), emptyList(), rules).alerts.isEmpty())
    }

    @Test
    fun rulesCanBeSwitchedOffPerTeam() {
        val quiet = TeamAlertRules(redCards = false, lateGoals = false, comebacks = false)
        val first = SmartAlertEngine.detect(null, live(0, 1, 70), emptyList(), quiet)
        val red = MatchEvent(82, MatchEventType.RED_CARD, Side.AWAY, "P")
        val goal = MatchEvent(85, MatchEventType.GOAL, Side.HOME, "Q")
        val result = SmartAlertEngine.detect(first.memory, live(2, 1, 86), listOf(red, goal), quiet)
        assertTrue(result.alerts.isEmpty())
    }

    @Test
    fun fantasyPlayersNotifyOnlyForTheirOwnEvents() {
        val first = SmartAlertEngine.detect(null, live(0, 0, 10), emptyList(), rules)
        val mine = MatchEvent(20, MatchEventType.GOAL, Side.HOME, "Mine")
        val other = MatchEvent(21, MatchEventType.YELLOW_CARD, Side.AWAY, "Other")
        val result = SmartAlertEngine.detect(first.memory, live(1, 0, 22), listOf(mine, other), rules, setOf("mine"))
        assertEquals(AlertKind.FANTASY_PLAYER, result.alerts.single().kind)
        assertEquals("Mine", result.alerts.single().player)
    }

    @Test
    fun aRatingExplainsEveryActionThatMovedIt() {
        val result = RatingCalculator.calculate(
            PlayerMatchStats(minutes = 90, goals = 1, assists = 1, tackles = 3, yellowCards = 1),
            Position.MID,
        )
        assertEquals(6.0 + 1.2 + 0.8 + 0.3 - 0.3, result.rating, 0.051)
        assertEquals(RatingAction.GOALS, result.contributions.first().action)
        assertEquals(result.rating, result.base + result.contributions.sumOf { it.delta }, 0.051)
        assertFalse(result.isSmallSample)
    }

    @Test
    fun ratingsStayInRangeAndFlagShortAppearances() {
        val bad = RatingCalculator.calculate(
            PlayerMatchStats(minutes = 12, redCards = 1, ownGoals = 3, penaltiesMissed = 2, goalsConceded = 9),
            Position.DEF,
        )
        assertEquals(3.0, bad.rating, 0.0)
        assertTrue(bad.isSmallSample)
        val great = RatingCalculator.calculate(PlayerMatchStats(minutes = 90, goals = 9), Position.FWD)
        assertEquals(10.0, great.rating, 0.0)
    }

    @Test
    fun everyPremierLeagueNameFindsItsFantasyTeam() {
        val teams = listOf(
            "Arsenal", "Aston Villa", "Bournemouth", "Brentford", "Brighton", "Chelsea", "Coventry City",
            "Crystal Palace", "Everton", "Fulham", "Hull City", "Ipswich Town", "Leeds", "Liverpool",
            "Man City", "Man Utd", "Newcastle", "Nott'm Forest", "Spurs", "Sunderland",
        ).mapIndexed { index, name -> FplTeam(index + 1, name) }
        val sourceNames = listOf(
            "Arsenal", "Aston Villa", "AFC Bournemouth", "Bournemouth", "Brentford", "Brighton and Hove Albion",
            "Chelsea", "Coventry City", "Crystal Palace", "Everton", "Fulham", "Hull City", "Ipswich Town",
            "Leeds United", "Liverpool", "Manchester City", "Manchester United", "Newcastle United",
            "Nottingham Forest", "Tottenham Hotspur", "Sunderland",
        )
        sourceNames.forEach { assertNotNull(it, FplTeamMatcher.find(it, teams)) }
        assertEquals("Man Utd", FplTeamMatcher.find("Manchester United", teams)?.name)
    }

    private fun squadPlayer(id: Int, position: Position, starts: Int, available: Boolean = true, news: String = "") =
        SquadPlayer(id, "P$id", position, starts, starts * 90, available, if (available) null else 0, news)

    @Test
    fun aProbableElevenHasOneKeeperAndSkipsAbsentPlayers() {
        val squad = buildList {
            add(squadPlayer(1, Position.GKP, 11))
            add(squadPlayer(2, Position.GKP, 1))
            (3..8).forEach { add(squadPlayer(it, Position.DEF, 12 - it)) }
            (9..14).forEach { add(squadPlayer(it, Position.MID, 20 - it)) }
            (15..17).forEach { add(squadPlayer(it, Position.FWD, 25 - it)) }
            add(squadPlayer(18, Position.FWD, 30, available = false, news = "Knee injury"))
        }
        val eleven = PreviewBuilder.probableEleven(squad)
        assertEquals(11, eleven.size)
        assertEquals(1, eleven.count { it.position == Position.GKP })
        assertTrue(eleven.count { it.position == Position.DEF } >= 3)
        assertFalse(eleven.any { it.id == 18 })
    }

    @Test
    fun previewUsesOnlyFinishedEarlierGamesAndGivesAtMostThreePoints() {
        val match = game("next", "h", "a", null, null, MatchStatus.SCHEDULED, kickoffSeconds = 10_000)
        val homeGames = (1..5).map { game("h$it", "h", "x$it", 3, 0, kickoffSeconds = 1_000L + it) } +
            game("later", "h", "z", 9, 0, kickoffSeconds = 20_000) +
            game("running", "h", "y", 0, 5, MatchStatus.LIVE, kickoffSeconds = 5_000)
        val awayGames = (1..5).map { game("a$it", "x$it", "a", 2, 0, kickoffSeconds = 1_000L + it) }
        val preview = PreviewBuilder.build(match, homeGames, awayGames, null, null)
        assertEquals(List(5) { FormResult.WIN }, preview.home.form)
        assertEquals(List(5) { FormResult.LOSS }, preview.away.form)
        assertTrue(preview.insights.size <= 3)
        assertEquals(InsightKind.WIN_STREAK, preview.insights.first().kind)
        assertFalse(preview.hasSquadData)
    }
}
