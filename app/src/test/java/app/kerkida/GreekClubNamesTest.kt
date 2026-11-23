package app.kerkida

import app.kerkida.data.GreekClubNames
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class GreekClubNamesTest {
    private val greek = Locale.forLanguageTag("el")

    @Test
    fun `every spelling of a club gives one name`() {
        listOf("Olympiacos", "ΟΛΥΜΠΙΑΚΟΣ", "Olympiacos Piraeus").forEach {
            assertEquals("Ολυμπιακός", GreekClubNames.greek(it))
        }
        assertEquals("Παναθηναϊκός", GreekClubNames.greek("ΠΑΝΑΘΗΝΑΪΚΟΣ AKTOR"))
    }

    @Test
    fun `a reserve side stays apart from the first team`() {
        assertEquals("Ολυμπιακός Β", GreekClubNames.greek("Olympiacos II"))
    }

    @Test
    fun `a foreign club keeps its own name`() {
        assertEquals("Liverpool", GreekClubNames.greek("Liverpool"))
    }

    @Test
    fun `places are shown in greek only for greek`() {
        assertEquals("Ευρώπη", GreekClubNames.place("Europe", greek))
        assertEquals("Greece", GreekClubNames.place("Greece", Locale.ENGLISH))
    }
}
