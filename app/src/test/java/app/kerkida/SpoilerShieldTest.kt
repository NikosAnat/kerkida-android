package app.kerkida

import app.kerkida.data.model.LeagueRef
import app.kerkida.data.model.Match
import app.kerkida.data.model.MatchEvent
import app.kerkida.data.model.MatchEventType
import app.kerkida.data.model.MatchStatus
import app.kerkida.data.model.Side
import app.kerkida.data.model.TeamRef
import app.kerkida.shield.DelayBuffer
import app.kerkida.shield.RevealLevel
import app.kerkida.shield.ShieldController
import app.kerkida.shield.ShieldEntry
import app.kerkida.shield.ShieldMode
import app.kerkida.shield.ShieldSettings
import app.kerkida.shield.ShieldStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SpoilerShieldTest {
    private val start = 1_800_000_000_000L
    private var time = start
    private var favourites = emptySet<String>()
    private var enabled = true

    private class MemoryStore : ShieldStore {
        var entries = emptyList<ShieldEntry>()
        var settings = ShieldSettings()
        override fun loadEntries() = entries
        override fun saveEntries(entries: List<ShieldEntry>) { this.entries = entries }
        override fun loadSettings() = settings
        override fun saveSettings(settings: ShieldSettings) { this.settings = settings }
    }

    private val store = MemoryStore()
    private val shield = ShieldController(store, { favourites }, { enabled }, { time })

    private fun match(
        home: Int? = 0,
        away: Int? = 0,
        minute: Int? = 10,
        status: MatchStatus = MatchStatus.LIVE,
        kickoffMs: Long = start - 10 * 60_000,
    ) = Match(
        id = "m1",
        league = LeagueRef("l1", "League"),
        home = TeamRef("h", "Home"),
        away = TeamRef("a", "Away"),
        kickoff = Instant.ofEpochMilli(kickoffMs),
        date = null,
        status = status,
        minute = minute,
        homeScore = home,
        awayScore = away,
    )

    private val goal = MatchEvent(12, MatchEventType.GOAL, Side.HOME, "Scorer")

    private fun advance(seconds: Int) { time += seconds * 1000L }

    @Test
    fun anUnshieldedMatchIsUntouched() {
        val source = match(home = 2, away = 1)
        val view = shield.view(source, listOf(goal))
        assertEquals(source, view.match)
        assertEquals(listOf(goal), view.events)
        assertTrue(view.isFullyOpen)
        assertFalse(view.isShielded)
    }

    @Test
    fun hiddenRemovesScoreClockStatusAndEvents() {
        val source = match(home = 2, away = 1, minute = 67)
        shield.observeDetails(source, listOf(goal))
        shield.toggle(source)
        val view = shield.view(source, listOf(goal))
        assertNull(view.match.homeScore)
        assertNull(view.match.awayScore)
        assertNull(view.match.minute)
        assertEquals(MatchStatus.UNKNOWN, view.match.status)
        assertTrue(view.events.isEmpty())
        assertFalse(view.isFullyOpen)
    }

    @Test
    fun aGoalStaysHiddenUntilTheDelayHasPassed() {
        shield.setDelay(30)
        val before = match(home = 0)
        shield.observeDetails(before, emptyList())
        shield.toggle(before)
        advance(40)

        val after = match(home = 1, minute = 11)
        shield.observeDetails(after, listOf(goal))
        shield.setLevel(after, RevealLevel.FULL)

        advance(29)
        val early = shield.view(after, listOf(goal))
        assertEquals(0, early.match.homeScore)
        assertTrue(early.events.isEmpty())

        advance(1)
        val onTime = shield.view(after, listOf(goal))
        assertEquals(1, onTime.match.homeScore)
        assertEquals(listOf(goal), onTime.events)
    }

    @Test
    fun unlockingWithNothingOldEnoughWaitsInsteadOfShowingTheNewestScore() {
        shield.setDelay(20)
        val source = match(home = 3, away = 2)
        shield.observeDetails(source, listOf(goal))
        shield.setLevel(source, RevealLevel.FULL)
        advance(19)

        val waiting = shield.view(source, listOf(goal))
        assertTrue(waiting.waitingForSync)
        assertNull(waiting.match.homeScore)
        assertTrue(waiting.events.isEmpty())

        advance(1)
        val ready = shield.view(source, listOf(goal))
        assertFalse(ready.waitingForSync)
        assertEquals(3, ready.match.homeScore)
    }

    @Test
    fun repeatedPollsDoNotPushAValueFurtherBack() {
        shield.setDelay(30)
        val source = match(home = 1)
        shield.observe(source)
        shield.setLevel(source, RevealLevel.FULL)
        repeat(4) {
            advance(10)
            shield.observe(source)
        }
        assertEquals(1, shield.view(source).match.homeScore)
    }

    @Test
    fun delayIsKeptBetweenTenAndSixtySeconds() {
        shield.setDelay(5)
        assertEquals(10, shield.settings.value.delaySeconds)
        shield.setDelay(90)
        assertEquals(60, shield.settings.value.delaySeconds)
    }

    @Test
    fun aBufferReturnsNothingBeforeItsFirstValue() {
        val buffer = DelayBuffer<Int>()
        buffer.add(100, 1)
        buffer.add(200, 2)
        assertNull(buffer.atOrBefore(99))
        assertEquals(1, buffer.atOrBefore(199))
        assertEquals(2, buffer.atOrBefore(200))
    }

    @Test
    fun scoreOnlyShowsTheScoreAndNothingElse() {
        shield.setDelay(10)
        val source = match(home = 2, away = 0, minute = 55)
        shield.observeDetails(source, listOf(goal))
        shield.setLevel(source, RevealLevel.SCORE_ONLY)
        advance(10)
        val view = shield.view(source, listOf(goal))
        assertEquals(2, view.match.homeScore)
        assertNull(view.match.minute)
        assertEquals(MatchStatus.UNKNOWN, view.match.status)
        assertTrue(view.events.isEmpty())
    }

    @Test
    fun timeOnlyShowsTheClockAndNoScore() {
        shield.setDelay(10)
        val source = match(home = 2, away = 0, minute = 55)
        shield.observeDetails(source, listOf(goal))
        shield.setLevel(source, RevealLevel.TIME_ONLY)
        advance(10)
        val view = shield.view(source, listOf(goal))
        assertEquals(55, view.match.minute)
        assertEquals(MatchStatus.LIVE, view.match.status)
        assertNull(view.match.homeScore)
        assertTrue(view.events.isEmpty())
    }

    @Test
    fun aRecordedMatchStaysHiddenLongAfterItEndsUntilMarkedWatched() {
        val source = match(home = 4, away = 3, status = MatchStatus.FINISHED, minute = null)
        shield.observeDetails(source, listOf(goal))
        shield.markRecorded(source)
        advance(3 * 24 * 60 * 60)
        assertNull(shield.view(source, listOf(goal)).match.homeScore)

        shield.markWatched(source)
        val watched = shield.view(source, listOf(goal))
        assertEquals(4, watched.match.homeScore)
        assertEquals(listOf(goal), watched.events)
        assertEquals(ShieldMode.RECORDED, watched.entry?.mode)
    }

    @Test
    fun anOldMatchOpensWithoutWaitingForTheDelay() {
        val source = match(home = 1, status = MatchStatus.FINISHED, kickoffMs = start - 5 * 60 * 60_000)
        shield.observe(source)
        shield.setLevel(source, RevealLevel.FULL)
        assertEquals(1, shield.view(source).match.homeScore)
    }

    @Test
    fun autoShieldCoversOnlyFavouriteTeamsAndRecentMatches() {
        val source = match(home = 1)
        assertFalse(shield.view(source).isShielded)
        shield.setAutoForFavourites(true)
        assertFalse(shield.view(source).isShielded)

        favourites = setOf("a")
        assertTrue(shield.view(source).isShielded)
        assertNull(shield.view(source).match.homeScore)

        advance(7 * 60 * 60)
        assertFalse(shield.view(source).isShielded)
    }

    @Test
    fun revealingAnAutoShieldedMatchSticks() {
        favourites = setOf("h")
        shield.setAutoForFavourites(true)
        val source = match(home = 1)
        shield.observe(source)
        shield.toggle(source)
        advance(30)
        assertEquals(1, shield.view(source).match.homeScore)
    }

    @Test
    fun aLiveFilterCannotTellThatAHiddenMatchEnded() {
        val ended = match(home = 1, status = MatchStatus.FINISHED, kickoffMs = start - 100 * 60_000)
        shield.observe(ended)
        shield.toggle(ended)
        assertTrue(shield.looksInPlay(ended))
    }

    @Test
    fun aHiddenStartedMatchHidesItsLeagueTableUntilOpened() {
        val source = match(home = 1)
        assertFalse(shield.hidesTable("l1"))
        shield.observe(source)
        shield.toggle(source)
        assertTrue(shield.hidesTable("l1"))
        assertFalse(shield.hidesTable("other"))
        shield.setLevel(source, RevealLevel.FULL)
        assertFalse(shield.hidesTable("l1"))
    }

    @Test
    fun aMatchThatHasNotKickedOffDoesNotHideTheTable() {
        val source = match(status = MatchStatus.SCHEDULED, kickoffMs = start + 60 * 60_000)
        shield.toggle(source)
        assertFalse(shield.hidesTable("l1"))
    }

    @Test
    fun turningTheFeatureOffRestoresTheOriginalData() {
        val source = match(home = 2)
        shield.toggle(source)
        enabled = false
        assertEquals(source, shield.view(source).match)
        assertFalse(shield.hidesTable("l1"))
    }

    @Test
    fun choicesSurviveARestart() {
        val source = match()
        shield.setDelay(45)
        shield.markRecorded(source)
        val reopened = ShieldController(store, { favourites }, { enabled }, { time })
        assertEquals(45, reopened.settings.value.delaySeconds)
        assertEquals(ShieldMode.RECORDED, reopened.entryFor(source)?.mode)
    }
}
