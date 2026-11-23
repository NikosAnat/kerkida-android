package app.kerkida

import app.kerkida.data.model.LineupPlayer
import app.kerkida.data.photos.PlayerPhotoLogic
import app.kerkida.fantasy.FplPlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerPhotosTest {
    private fun lineup(id: String, name: String, photo: String? = null) = LineupPlayer(id, name, isStarter = true, photoUrl = photo)

    private fun fpl(id: Int, short: String, first: String, second: String) =
        FplPlayer(id, short, first, second, team = 1, elementType = 3, code = 1000 + id)

    @Test
    fun matchesFullShortAndUniqueSurnames() {
        val squad = listOf(fpl(1, "Salah", "Mohamed", "Salah"), fpl(2, "Gakpo", "Cody", "Gakpo"), fpl(3, "Szoboszlai", "Dominik", "Szoboszlai"))
        val found = PlayerPhotoLogic.matchSquad(
            listOf(lineup("a", "Mohamed Salah"), lineup("b", "Gakpo"), lineup("c", "D. Szoboszlai")),
            squad,
        )
        assertEquals(1, found["a"]?.id)
        assertEquals(2, found["b"]?.id)
        assertEquals(3, found["c"]?.id)
    }

    @Test
    fun aDifferentPersonWithTheSameSurnameGetsNoFace() {
        val found = PlayerPhotoLogic.matchSquad(listOf(lineup("a", "Thomas Silva")), listOf(fpl(1, "Silva", "Bernardo", "Silva")))
        assertNull(found["a"])
    }

    @Test
    fun twoTeammatesWithOneSurnameAreNotGuessed() {
        val squad = listOf(fpl(1, "Timber", "Jurrien", "Timber"), fpl(2, "Timber", "Jurgen", "Timber"))
        assertFalse("a" in PlayerPhotoLogic.matchSquad(listOf(lineup("a", "J Timber")), squad))
    }

    @Test
    fun prefersTheOfficialPhotoThenEspnThenTheOpenOne() {
        val espn = lineup("a", "A", "https://espn/a.png")
        assertEquals(PlayerPhotoLogic.fplUrl(1001), PlayerPhotoLogic.choose(espn, fpl(1, "A", "A", "A"), "x.jpg"))
        assertEquals("https://espn/a.png", PlayerPhotoLogic.choose(espn, null, "x.jpg"))
        assertEquals(
            "https://commons.wikimedia.org/wiki/Special:FilePath/Jos%C3%A9_Doe.jpg?width=160",
            PlayerPhotoLogic.choose(lineup("b", "B"), null, "José Doe.jpg"),
        )
        assertNull(PlayerPhotoLogic.choose(lineup("c", "C"), null, null))
    }

    @Test
    fun shrinksEspnHeadshots() {
        assertEquals(
            "https://a.espncdn.com/combiner/i?img=/i/headshots/soccer/players/full/1.png&w=160&h=160&scale=crop",
            PlayerPhotoLogic.smallEspn("https://a.espncdn.com/i/headshots/soccer/players/full/1.png"),
        )
    }
}
