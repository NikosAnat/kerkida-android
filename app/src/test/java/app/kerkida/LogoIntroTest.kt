package app.kerkida

import androidx.compose.ui.geometry.Offset
import app.kerkida.ui.intro.LogoMotion
import app.kerkida.ui.intro.LogoParts
import app.kerkida.ui.intro.Scarf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogoIntroTest {
    @Test
    fun everyPointEndsExactlyOnTheLogo() {
        LogoParts.forEach { part ->
            part.commands.forEach { (_, values) ->
                for (index in values.indices step 2) {
                    assertEquals(
                        Offset(values[index], values[index + 1]),
                        LogoMotion.move(part.scarf, values[index], values[index + 1], LogoMotion.ARRIVED, 300f),
                    )
                }
            }
        }
    }

    @Test
    fun scarvesStartAwayFromTheLogo() {
        Scarf.entries.forEach { scarf ->
            val start = LogoMotion.move(scarf, scarf.leadX, scarf.leadY, 0f, 300f)
            assertTrue((start - Offset(scarf.leadX, scarf.leadY)).getDistance() > 200f)
        }
    }

    @Test
    fun theLogoKeepsAllItsShapes() {
        assertEquals(20, LogoParts.size)
        assertEquals(setOf(Scarf.LOWER, Scarf.DIAGONAL, Scarf.UPRIGHT), LogoParts.map { it.scarf }.toSet())
    }
}
