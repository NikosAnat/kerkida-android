package app.kerkida

import app.kerkida.data.model.LeagueRef
import app.kerkida.data.model.Match
import app.kerkida.data.model.MatchStatus
import app.kerkida.data.model.Sport
import app.kerkida.data.model.TeamRef
import app.kerkida.highlights.HighlightLogic
import app.kerkida.highlights.HighlightVideo
import app.kerkida.highlights.OfficialChannels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HighlightsTest {
    private val kickoff = Instant.parse("2026-11-21T17:30:00Z")

    private fun match(home: String, away: String) = Match(
        id = "$home-$away",
        league = LeagueRef("l", "L"),
        home = TeamRef(home, home),
        away = TeamRef(away, away),
        kickoff = kickoff,
        date = null,
        status = MatchStatus.FINISHED,
        homeScore = 1,
        awayScore = 1,
    )

    private fun video(title: String, hoursAfter: Long = 3) = HighlightVideo(
        "v", title, "c", "C", kickoff.plusSeconds(hoursAfter * 3600).toEpochMilli(),
    )

    @Test
    fun keepsMatchHighlightsAndDropsEverythingElse() {
        assertTrue(HighlightLogic.isHighlight("SEVILLA FC 1 - 1 REAL BETIS | RESUMEN LALIGA EA SPORTS"))
        assertTrue(HighlightLogic.isHighlight("Scott Fa Sorridere Allegri | NAPOLI-TORINO | HIGHLIGHTS"))
        assertTrue(HighlightLogic.isHighlight("Super League K15: ΑΕΚ-Καλαμάτα 5-0 | AEK F.C."))
        assertFalse(HighlightLogic.isHighlight("Συνέντευξη Τύπου κ. Αλγουαθίλ (Ολυμπιακός - ΠΑΟΚ)"))
        assertFalse(HighlightLogic.isHighlight("Η παρακάμερα του Ρεν - Ολυμπιακός!"))
        assertFalse(HighlightLogic.isHighlight("The best bicycle kicks in Ligue 1 history #shorts"))
    }

    @Test
    fun linksAVideoToItsMatchInEitherAlphabet() {
        val games = listOf(match("Sevilla", "Real Betis"), match("AEK Athens", "Kalamata"), match("Napoli", "Torino"))
        assertEquals("Sevilla-Real Betis", HighlightLogic.matchFor(video("SEVILLA FC 1 - 1 REAL BETIS | RESUMEN"), games)?.id)
        assertEquals("AEK Athens-Kalamata", HighlightLogic.matchFor(video("Super League K15: ΑΕΚ-Καλαμάτα 5-0 | AEK F.C."), games)?.id)
    }

    @Test
    fun doesNotLinkTheWrongMatchOrAnOldOne() {
        val games = listOf(match("Sevilla", "Real Betis"))
        assertNull(HighlightLogic.matchFor(video("Sevilla 2 - 0 Getafe | RESUMEN"), games))
        assertNull(HighlightLogic.matchFor(video("Sevilla 1 - 1 Real Betis | RESUMEN", hoursAfter = -2), games))
        assertNull(HighlightLogic.matchFor(video("Sevilla 1 - 1 Real Betis | RESUMEN", hoursAfter = 100), games))
    }

    @Test
    fun aSeasonInATitleIsNotAScore() {
        assertFalse(HighlightLogic.isHighlight("Official Jersey Reveal 2026-27 ARIS FC x Intersport-Nike"))
        assertTrue(HighlightLogic.isHighlight("Λίβερπουλ - Μάντσεστερ Γιουνάιτεντ: 4-0 (MD 11, 22/11/2026)"))
        assertTrue(HighlightLogic.isHighlight("Παναθηναϊκός - Ολυμπιακός 101 - 98"))
    }

    @Test
    fun aMatchGetsOnlyVideosOfItsOwnSportWithBroadcastersFirst() {
        val football = match("Olympiacos", "Panathinaikos")
        val basketball = football.copy(id = "basket", league = LeagueRef("b", "B", sport = Sport.BASKETBALL))
        val euroleague = HighlightVideo("e", "Olympiacos - Panathinaikos | Highlights", "UCGr3nR_XH9r6E5b09ZJAT9w", "EuroLeague", kickoff.plusSeconds(7200).toEpochMilli())
        val nova = HighlightVideo("n", "Ολυμπιακός - Παναθηναϊκός: 2-1", "UCEIbXco8hU9oDHXGo1kwIlA", "Novasports", kickoff.plusSeconds(10800).toEpochMilli())
        val league = HighlightVideo("l", "Olympiacos - Panathinaikos 2-1 | Highlights", "UCG5qGWdu8nIRZqJ_GgDwQ-w", "League", kickoff.plusSeconds(14400).toEpochMilli())
        assertEquals(listOf("n", "l"), HighlightLogic.forMatch(listOf(euroleague, league, nova), football).map { it.videoId })
        assertEquals(listOf("e"), HighlightLogic.forMatch(listOf(euroleague, league, nova), basketball).map { it.videoId })
    }

    @Test
    fun readsAnOfficialFeed() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns:yt="http://www.youtube.com/xml/schemas/2015" xmlns:media="http://search.yahoo.com/mrss/" xmlns="http://www.w3.org/2005/Atom">
            <title>Channel</title>
            <entry><yt:videoId>abc</yt:videoId><title>Napoli 2-1 Torino | HIGHLIGHTS</title><published>2026-11-21T20:00:00+00:00</published>
            <media:group><media:thumbnail url="https://i.ytimg.com/vi/abc/hqdefault.jpg" width="480" height="360"/></media:group></entry>
            <entry><yt:videoId>def</yt:videoId><title>Press conference</title><published>2026-11-21T20:00:00+00:00</published></entry>
            </feed>"""
        val videos = HighlightLogic.parseFeed(xml.byteInputStream(), OfficialChannels.first())
        assertEquals(listOf("abc"), videos.map { it.videoId })
        assertEquals("https://www.youtube.com/watch?v=abc", videos[0].url)
        assertEquals("https://i.ytimg.com/vi/abc/hqdefault.jpg", videos[0].thumbnail)
    }

    @Test
    fun onlyOfficialChannelsAreListedAndNoSocialNetworks() {
        assertTrue(OfficialChannels.all { it.id.startsWith("UC") && it.id.length == 24 })
        assertEquals(OfficialChannels.size, OfficialChannels.map { it.id }.distinct().size)
    }
}
