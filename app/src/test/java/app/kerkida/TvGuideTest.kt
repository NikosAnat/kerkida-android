package app.kerkida

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import app.kerkida.broadcast.AccessType
import app.kerkida.data.espn.EspnProvider
import app.kerkida.data.model.LeagueRef
import app.kerkida.data.model.LineupPlayer
import app.kerkida.data.model.Match
import app.kerkida.data.model.MatchStatus
import app.kerkida.data.model.TeamRef
import app.kerkida.data.net.CachedHttpClient
import app.kerkida.data.net.RateLimiter
import app.kerkida.data.net.ResponseCache
import app.kerkida.fantasy.Position
import app.kerkida.tv.SportFmSource
import app.kerkida.tv.TvItem
import app.kerkida.tv.TvLogic
import app.kerkida.tv.TvSport
import app.kerkida.tv.TvStatus
import app.kerkida.tv.XmltvSource
import app.kerkida.ui.match.FormationLayout
import app.kerkida.ui.match.PitchPlayer
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TvGuideTest {
    private val today = LocalDate.parse("2026-11-22")

    private fun at(time: String) = LocalDate.parse("2026-11-22").atTime(
        time.substringBefore(':').toInt(), time.substringAfter(':').toInt(),
    ).atZone(TvLogic.Athens).toInstant().toEpochMilli()

    private fun item(title: String, competition: String, channel: String = "Magenta Sport 1", time: String = "16:00") = TvItem(
        "2026-11-22", time, at(time), channel, title, competition, TvLogic.classify(competition, title), sourceId = "test",
    )

    private fun match(home: String, away: String, league: String, time: String = "16:00") = Match(
        id = "$league~$home",
        league = LeagueRef(league, league),
        home = TeamRef(home, home),
        away = TeamRef(away, away),
        kickoff = Instant.ofEpochMilli(at(time)),
        date = today,
        status = MatchStatus.SCHEDULED,
    )

    @Test
    fun readsTheListingsPageAndIgnoresNews() {
        val html = """
            <div class="tv-day active"><h2 class="section-heading">Σήμερα, Κυριακή 22/11</h2>
            <div class="archive-row-tile"><div class="tv_logos"></div><h3>Γιουβέντους-Βενέτσια</h3><p>Ιταλικό Πρωτάθλημα</p>
            <span class="post-category">Magenta Sport 1</span><time>13:30</time></div>
            <div class="archive-row-tile"><h3>Μια είδηση</h3><p>Νέα</p><time>14:00</time></div>
            </div>
            <div class="tv-day"><h2>Αύριο, Δευτέρα 23/11</h2>
            <div class="archive-row-tile"><div class="tv_logos"></div><h3>Προμηθέας-ΑΕΚ</h3><p>Stoiximan Greek Basketball League</p>
            <span class="post-category">ΣΠΟΡ FM TV 1</span><time>9:00</time></div></div>
        """
        val items = SportFmSource.parse(html, today)
        assertEquals(2, items.size)
        assertEquals("2026-11-22", items[0].date)
        assertEquals(TvSport.FOOTBALL, items[0].sport)
        assertEquals(at("13:30"), items[0].startMs)
        assertEquals("09:00", items[1].time)
        assertEquals(TvSport.BASKETBALL, items[1].sport)
    }

    @Test
    fun readsOnlyLiveSportsFromXmltvWithCorrectTimes() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?><tv>
            <channel id="a"><display-name>Novasports Start</display-name></channel>
            <channel id="b"><display-name>ΣΚΑΪ ΗD</display-name></channel>
            <channel id="c"><display-name>BBC News</display-name></channel>
            <programme start="20261122083000 -0600" stop="20261122103000 -0600" channel="a"><title>Άουγκσμπουργκ - Μάιντς (Ζ) : Bundesliga 2026/27</title><category>Ποδόσφαιρο</category></programme>
            <programme start="20261122140000 -0600" stop="20261122160000 -0600" channel="a"><title>Άλκμααρ - Χρόνινγκεν : Eredivisie</title><category>Ποδόσφαιρο</category></programme>
            <programme start="20261122153000 +0000" stop="20261122173000 +0000" channel="b"><title>Live: Ποδόσφαιρο: Παναθηναϊκός - ΟΦΗ</title><category>Ποδόσφαιρο</category></programme>
            <programme start="20261122153000 +0000" stop="20261122173000 +0000" channel="c"><title>Match (Z)</title><category>Ποδόσφαιρο</category></programme>
            <programme start="20261122153000 +0000" stop="20261122173000 +0000" channel="b"><title>Ειδήσεις (Z)</title><category>Ειδήσεις</category></programme>
        </tv>"""
        val items = XmltvSource.parse(xml.byteInputStream(), today)
        assertEquals(listOf("Άουγκσμπουργκ - Μάιντς", "Παναθηναϊκός - ΟΦΗ"), items.map { it.title })
        assertEquals("16:30", items[0].time)
        assertEquals("Bundesliga 2026/27", items[0].competition)
        assertEquals("17:30", items[1].time)
        assertEquals("ΣΚΑΪ", items[1].channel)
    }

    @Test
    fun knowsEveryNetworkAndHowItIsWatched() {
        val cases = mapOf(
            "NOVASPORTS 5" to "nova", "Magenta Sport 3 League" to "magenta", "ΕΡΤ2 ΣΠΟΡ" to "ert", "ΕPΤ1 HD" to "ert",
            "MEGA HD" to "mega", "ALPHA HD" to "alpha", "ANT1 HD" to "ant1", "ΑΝΤ1+" to "ant1plus", "ΣΚΑΪ ΗD" to "skai",
            "STAR HD" to "star", "OPEN BEYOND HD" to "open", "ΣΠΟΡ FM TV 2" to "sportfm", "Eurosport 1 HD" to "eurosport",
        )
        cases.forEach { (channel, id) -> assertEquals(channel, id, TvLogic.channelIdOf(channel)) }
        assertEquals(AccessType.SUBSCRIPTION, TvLogic.accessOf("nova"))
        assertEquals(AccessType.FREE, TvLogic.accessOf("alpha"))
        assertEquals(AccessType.SUBSCRIPTION, TvLogic.accessOf("ant1plus"))
    }

    @Test
    fun everySportTabMapsToItsTvSport() {
        assertEquals(TvSport.FOOTBALL, TvLogic.tvSportOf(app.kerkida.data.model.Sport.FOOTBALL))
        assertEquals(TvSport.BASKETBALL, TvLogic.tvSportOf(app.kerkida.data.model.Sport.BASKETBALL))
        assertEquals(TvSport.VOLLEYBALL, TvLogic.tvSportOf(app.kerkida.data.model.Sport.VOLLEYBALL))
        assertEquals(TvSport.OTHER, TvLogic.tvSportOf(app.kerkida.data.model.Sport.LACROSSE))
    }

    @Test
    fun magentaLeagueSpellingsAreOneChannel() {
        (1..4).forEach { n ->
            val full = "Magenta Sport $n League"
            listOf("Magenta Sport $n L", "Magenta Sport ${n}L", "MAGENTA SPORT $n L HD", full).forEach { spelling ->
                assertEquals(full, TvLogic.channelName(spelling))
                assertEquals(TvLogic.channelKey(full), TvLogic.channelKey(spelling))
            }
            assertTrue(TvLogic.channelKey("Magenta Sport $n") != TvLogic.channelKey(full))
        }
        assertEquals("Magenta Sport HL", TvLogic.channelName("Magenta Sport HL"))
        assertEquals("1|magentasport3league|a b", TvLogic.currentItemKey("1|magentasport3l|a b"))

        val merged = TvLogic.merge(
            listOf(
                listOf(item("Α-Β", "Serie A", "Magenta Sport 3 League")),
                listOf(item("Α-Β", "Serie A", "Magenta Sport 3 L")),
            ),
        )
        assertEquals(listOf("Magenta Sport 3 League"), merged.map { it.channel })
    }

    @Test
    fun matchesGreekListingsToLatinTeamNames() {
        listOf(
            Triple("Γιουβέντους-Βενέτσια", "Ιταλικό Πρωτάθλημα", match("Juventus", "Venezia", "ita.1")),
            Triple("Μπαρτσελόνα-Βιγιαρεάλ", "Ισπανικό Πρωτάθλημα", match("Barcelona", "Villarreal", "esp.1")),
            Triple("Λίβερπουλ - Μάντσεστερ Γιουνάιτεντ", "Premier League", match("Liverpool", "Manchester United", "eng.1")),
            Triple("Παναθηναϊκός - ΟΦΗ", "Stoiximan Super League", match("Panathinaikos", "OFI Crete", "gre.1")),
            Triple("Ολυμπιακός-Ηρακλής", "Stoiximan Super League", match("Olympiacos", "Iraklis 1908", "gre.1")),
            Triple("Χαλ-Μπράιτον", "Αγγλικό Πρωτάθλημα", match("Hull City", "Brighton & Hove Albion", "eng.1")),
            Triple("Μίλγουολ-ΚΠΡ", "Championship Αγγλίας", match("Millwall", "Queens Park Rangers", "eng.2")),
            Triple("Αταλάντα-Ίντερ", "Ιταλικό Πρωτάθλημα", match("Atalanta", "Internazionale", "ita.1")),
            Triple("Asteras AKTOR- Άρης", "Stoiximan Super League", match("Asteras Tripoli", "Aris", "gre.1")),
            Triple("Μπόντο Γκλιμτ - Λίντσερ", "Champions League", match("Bodo/Glimt", "LASK Linz", "uefa.champions")),
        ).forEach { (title, competition, game) ->
            assertNotNull(title, TvLogic.matchScore(item(title, competition), game))
        }
    }

    @Test
    fun doesNotConfuseDifferentMatches() {
        val listing = item("Λίβερπουλ - Μάντσεστερ Γιουνάιτεντ", "Premier League")
        assertNull(TvLogic.matchScore(listing, match("Chelsea", "Arsenal", "eng.1")))
        assertNull(TvLogic.matchScore(listing, match("Liverpool", "Manchester United", "ita.1")))
        assertNull(TvLogic.matchScore(listing, match("Liverpool", "Manchester United", "eng.1", "19:00")))
        assertNull(TvLogic.matchScore(item("Προμηθέας-ΑΕΚ", "Stoiximan Greek Basketball League"), match("Promitheas", "AEK Athens", "gre.1")))
        assertNull(TvLogic.matchScore(item("Κότμπους-Χέρτα Βερολίνου", "Δεύτερη κατηγορία Γερμανίας"), match("Cottbus", "Hertha", "ger.1")))
    }

    @Test
    fun theSameBroadcastFromTwoSourcesAppearsOnce() {
        val first = item("Γιουβέντους-Βενέτσια", "Ιταλικό Πρωτάθλημα", "NOVASPORTS 5")
        val second = item("Γιουβέντους - Βενέτσια", "Serie A", "Novasports5 HD").copy(startMs = first.startMs + 5 * 60_000, sourceId = "xmltv")
        val otherChannel = second.copy(channel = "ΣΚΑΪ")
        val merged = TvLogic.merge(listOf(listOf(first), listOf(second, otherChannel)))
        assertEquals(2, merged.size)
        assertEquals("test", merged.first { it.channel == "NOVASPORTS 5" }.sourceId)
    }

    @Test
    fun repeatedListingsFoldIntoOneEventWithAllChannels() {
        val events = TvLogic.groupEvents(
            listOf(
                item("Αναγέννηση Καρδίτσας - Athens Kallithea FC", "Ποδόσφαιρο", "ΕΡΤ2", "12:30"),
                item("Αναγέννηση Καρδίτσας-Athens Kallithea", "Superbet League 2", "ΕΡΤ2 ΣΠΟΡ", "12:30"),
                item("Αναγέννηση Καρδίτσας - Athens Kallithea FC", "Ποδόσφαιρο", "ERT WORLD", "12:45"),
                item("Μπιλμπάο-Μπασκόνια", "Ισπανικό πρωτάθλημα μπάσκετ", "Magenta Sport 4", "13:00"),
            ),
        )
        assertEquals(2, events.size)
        assertEquals(3, events[0].channels.size)
        assertEquals("Superbet League 2", events[0].item.competition)
    }

    @Test
    fun aListingKnowsWhetherItIsLive() {
        val listing = item("Α - Β", "Premier League")
        assertEquals(TvStatus.UPCOMING, TvLogic.statusOf(listing, listing.startMs - 1))
        assertEquals(TvStatus.LIVE, TvLogic.statusOf(listing, listing.startMs + 60_000))
        assertEquals(TvStatus.PAST, TvLogic.statusOf(listing, listing.startMs + 116 * 60_000))
    }

    private fun player(code: String, name: String = code) =
        PitchPlayer(name, "1", code, app.kerkida.fantasy.LineupRatings.positionOf(code))

    @Test
    fun placesAnElevenIntoItsFormation() {
        val players = listOf(
            player("F"), player("AM-L"), player("AM"), player("AM-R"), player("DM"), player("DM"),
            player("RB"), player("CD-R"), player("CD-L"), player("LB"), player("G"),
        )
        val lines = FormationLayout.lines(players, "4-2-3-1")
        assertEquals(listOf(1, 4, 2, 3, 1), lines.map { it.size })
        assertEquals("G", lines[0][0].positionCode)
        assertEquals(listOf("LB", "CD-L", "CD-R", "RB"), lines[1].map { it.positionCode })
        assertEquals(listOf("AM-L", "AM", "AM-R"), lines[3].map { it.positionCode })
        assertEquals("F", lines[4][0].positionCode)
    }

    @Test
    fun anUnknownFormationStillGroupsPlayersByRole() {
        val players = listOf(player("G"), player("LB"), player("RB"), player("CM-L"), player("F"))
        val lines = FormationLayout.lines(players, null)
        assertEquals(listOf(1, 2, 1, 1), lines.map { it.size })
        assertEquals(Position.GKP, lines[0][0].position)
    }

    @Test
    fun ratingsFollowTheStarterOnThePitch() {
        val lineup = listOf(
            LineupPlayer("1", "Joannis Gelios", "1", isStarter = true, positionCode = "G"),
            LineupPlayer("2", "On Bench", "20", isStarter = false, positionCode = "SUB"),
        )
        val pitch = FormationLayout.fromLineup(lineup, emptyList())
        assertEquals(1, pitch.size)
        assertFalse(pitch.any { it.name == "On Bench" })
    }

    /** Run with KERKIDA_LIVE_CHECK=1 to measure how many real matches find a channel. */
    @Test
    fun liveMatchRate() = runBlocking {
        assumeTrue(System.getenv("KERKIDA_LIVE_CHECK") == "1")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", "Kerkida/0.1 (Android)").build())
        }.build()
        val day = LocalDate.now(TvLogic.Athens)
        val sport = SportFmSource(http).fetch(day)
        val guide = XmltvSource(http, File.createTempFile("guide", ".gz").also { it.delete() }).fetch(day)
        val merged = TvLogic.merge(listOf(sport, guide))
        println("LIVE listings sportfm=${sport.size} xmltv=${guide.size} merged=${merged.size} networks=${merged.groupingBy { TvLogic.channelIdOf(it.channel) }.eachCount()}")
        val espn = EspnProvider(
            context,
            CachedHttpClient(http, ResponseCache(File(context.cacheDir, "live-espn")), RateLimiter(minGapMs = 150, maxPerMinute = 120)),
        )
        for (date in listOf(day.minusDays(1), day, day.plusDays(1), day.plusDays(2))) {
            val matches = espn.matchDay(date).lastOrNull()?.matches.orEmpty()
            val found = matches.filter { game -> merged.any { TvLogic.matchScore(it, game) != null } }
            println("LIVE $date matches=${matches.size} withChannel=${found.size}")
            matches.filter { it !in found }.take(14).forEach { game ->
                val slot = merged.filter { it.sport == TvSport.FOOTBALL && kotlin.math.abs(it.startMs - (game.kickoff?.toEpochMilli() ?: 0)) <= 20 * 60_000 }
                    .filter { TvLogic.leagueHint(it.competition).let { hint -> hint == null || hint == game.league.id } }
                println("LIVE   none: ${game.league.id} ${game.home.name} - ${game.away.name} | slot: " + slot.take(3).joinToString { "${it.title} (${it.competition})" })
            }
            found.take(4).forEach { game ->
                println("LIVE   ok: ${game.home.name} - ${game.away.name} -> " + merged.filter { TvLogic.matchScore(it, game) != null }.joinToString { "${it.channel} [${it.title}]" })
            }
        }
        assertTrue(merged.isNotEmpty())
    }
}
