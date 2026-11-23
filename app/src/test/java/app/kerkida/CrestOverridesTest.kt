package app.kerkida

import app.kerkida.ui.common.CrestOverrides
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrestOverridesTest {
    @Test
    fun `both espn olympiacos crests become the white one`() {
        val light = CrestOverrides.resolve("https://a.espncdn.com/i/teamlogos/soccer/500/435.png")
        val dark = CrestOverrides.resolve("https://a.espncdn.com/i/teamlogos/soccer/500-dark/435.png")
        assertTrue(light!!.contains("thesportsdb"))
        assertEquals(light, dark)
    }

    @Test
    fun `other crests stay as they are`() {
        val other = "https://a.espncdn.com/i/teamlogos/soccer/500/4350.png"
        assertEquals(other, CrestOverrides.resolve(other))
        assertNull(CrestOverrides.resolve(null))
    }
}
