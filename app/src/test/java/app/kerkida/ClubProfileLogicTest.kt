package app.kerkida

import app.kerkida.data.club.ClubMoment
import app.kerkida.data.club.ClubProfileLogic
import app.kerkida.data.club.MomentKind
import app.kerkida.data.model.Sport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClubProfileLogicTest {
    private fun entity(id: String, label: String, kind: String, country: String = "Q41", sport: String? = null): JsonObject {
        fun claim(value: String) = """{"mainsnak":{"datavalue":{"value":{"id":"$value"}}}}"""
        val played = sport?.let { ""","P641":[${claim(it)}]""" }.orEmpty()
        return Json.parseToJsonElement(
            """{"id":"$id","labels":{"en":{"value":"$label"}},"claims":{"P31":[${claim(kind)}],"P17":[${claim(country)}]$played}}""",
        ).jsonObject
    }

    @Test
    fun `the football club is chosen over a town with the same name`() {
        val town = entity("Q1", "Panachaiki", "Q515")
        val club = entity("Q2", "Panachaiki FC", "Q476028")
        assertEquals(club, ClubProfileLogic.pick(listOf(town, club), "Panachaiki", Sport.FOOTBALL, true))
    }

    @Test
    fun `a club of another sport or country is refused`() {
        val basket = entity("Q3", "Panathinaikos BC", "Q13393265", sport = "Q5372")
        assertNull(ClubProfileLogic.pick(listOf(basket), "Panathinaikos", Sport.FOOTBALL, true))
        val abroad = entity("Q4", "Olympiacos FC", "Q476028", country = "Q229")
        assertNull(ClubProfileLogic.pick(listOf(abroad), "Olympiacos", Sport.FOOTBALL, true))
    }

    @Test
    fun `honours skip the contents list and keep real titles`() {
        val html = """
            <ul><li class="toclevel-1"><a>1 Τίτλοι</a></li>
            <li>Β´ Κατηγορία Πρωταθλήτρια (6): 1963–64, 1968–69<sup>[1]</sup></li>
            <li>Χωρίς χρονιά</li></ul>
        """
        assertEquals(listOf("Β´ Κατηγορία Πρωταθλήτρια (6): 1963–64, 1968–69"), ClubProfileLogic.honours(html))
    }

    @Test
    fun `first and latest title come from one line`() {
        val moments = ClubProfileLogic.titleMoments(listOf("Κύπελλο (3): 1990, 2001, 2015"))
        assertEquals(listOf(1990, 2015), moments.map { it.year })
        assertEquals(listOf(MomentKind.FIRST_TITLE, MomentKind.LATEST_TITLE), moments.map { it.kind })
        assertEquals("Κύπελλο", moments[0].text)
    }

    @Test
    fun `the timeline starts at the founding and runs down in date order`() {
        val line = ClubProfileLogic.timeline(
            founded = 1891,
            groundName = "Αγυιά",
            groundOpened = 1902,
            titles = listOf(ClubMoment(1964, MomentKind.FIRST_TITLE, "Β´ Κατηγορία"), ClubMoment(1800, MomentKind.FIRST_TITLE, "Λάθος")),
            story = listOf(ClubMoment(1973, MomentKind.STORY, "Ανέβηκε στην Α´ Εθνική.")),
        )
        assertEquals(listOf(1891, 1902, 1964, 1973), line.map { it.year })
        assertEquals(MomentKind.FOUNDED, line.first().kind)
    }

    @Test
    fun `the timeline never starts before the founding`() {
        val line = ClubProfileLogic.timeline(1925, "Καραϊσκάκης", 1896, emptyList(), emptyList())
        assertEquals(listOf(1925), line.map { it.year })
    }

    @Test
    fun `a link trail is removed from a title name`() {
        val moments = ClubProfileLogic.titleMoments(listOf("↑ Αρχείο > Πρωτάθλημα Ελλάδας (2): 1931, 1933"))
        assertEquals("Πρωτάθλημα Ελλάδας", moments[0].text)
    }

    @Test
    fun `the founding year is read from the article`() {
        assertEquals(1891, ClubProfileLogic.foundedIn("Είναι σωματείο στην Πάτρα. Ιδρύθηκε στις 14 Ιουνίου 1891 με την επωνυμία."))
        assertNull(ClubProfileLogic.foundedIn("Σωματείο της Πάτρας."))
    }

    @Test
    fun `the gallery keeps wide photos and drops maps and the main photo`() {
        fun page(index: Int, title: String, width: Int, height: Int, mime: String = "image/jpeg") =
            """{"index":$index,"title":"File:$title","imageinfo":[{"thumburl":"https://x/$index.jpg","width":$width,"height":$height,"mime":"$mime"}]}"""
        val root = Json.parseToJsonElement(
            """{"query":{"pages":[${page(4, "Port.jpg", 1200, 800)},${page(1, "Castle.jpg", 1600, 900)},${page(2, "Map of Patras.jpg", 1200, 800)},${page(3, "Person.jpg", 600, 900)},${page(5, "Main photo.jpg", 1200, 800)},${page(6, "Crest.png", 1200, 800, "image/png")}]}}""",
        ).jsonObject
        assertEquals(listOf("https://x/1.jpg", "https://x/4.jpg"), ClubProfileLogic.gallery(root, "Main_photo.jpg"))
    }

    @Test
    fun `a grid of town photos is recognised`() {
        assertTrue(ClubProfileLogic.isCollage("Patras Collage 2020.jpg"))
        assertTrue(!ClubProfileLogic.isCollage("Rio Antirrio bridge.jpg"))
    }

    @Test
    fun `a title won by another club stays out of the story`() {
        val html = "<p>Το 1931 το πρωτάθλημα κατέκτησε ο Άρης Θεσσαλονίκης. Το 1933 ο Ολυμπιακός κατέκτησε το πρωτάθλημα.</p>"
        val story = ClubProfileLogic.storyMoments(html, ClubProfileLogic.clubStems("Ολυμπιακός"))
        assertEquals(listOf(1933), story.map { it.year })
    }

    @Test
    fun `national titles are kept before local ones`() {
        val local = (1..14).map { ClubMoment(1920 + it, MomentKind.FIRST_TITLE, "Κύπελλο Πατρών $it") }
        val line = ClubProfileLogic.timeline(1900, null, null, local + ClubMoment(1990, MomentKind.FIRST_TITLE, "Β´ Κατηγορία"), emptyList())
        assertTrue(line.any { it.year == 1990 })
    }

    @Test
    fun `story keeps dated milestones only`() {
        val html = "<p>Το 1973 ανέβηκε στην Α´ Εθνική για πρώτη φορά. Ο καιρός ήταν καλός το 1974 στην πόλη της Πάτρας.</p>"
        val story = ClubProfileLogic.storyMoments(html)
        assertEquals(listOf(1973), story.map { it.year })
        assertTrue(story[0].text.contains("ανέβηκε"))
    }
}
