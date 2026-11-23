package app.kerkida

import app.kerkida.broadcast.NovaTv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NovaTvTest {
    @Test
    fun `the guide's spellings find the right channel`() {
        assertEquals(25, NovaTv.channelNumber("NOVASPORTS 3"))
        assertEquals(25, NovaTv.channelNumber("Novasports3HD"))
        assertEquals(23, NovaTv.channelNumber("Novasports 1 League"))
        assertEquals(89, NovaTv.channelNumber("Novasports Start"))
        assertEquals(177, NovaTv.channelNumber("Novasports Premier League"))
        assertEquals(213, NovaTv.channelNumber("Magenta Sport 3 L"))
        assertEquals(211, NovaTv.channelNumber("Magenta Sport 1 League"))
        assertEquals(3, NovaTv.channelNumber("ΕΡΤ2 ΣΠΟΡ"))
        assertEquals(30, NovaTv.channelNumber("ΣΚΑΪ"))
    }

    @Test
    fun `a channel nova does not list gets no button`() {
        assertNull(NovaTv.channelNumber("Cosmote Sport 1"))
        assertNull(NovaTv.linkFor("DAZN"))
    }

    @Test
    fun `the link is the official channel page`() {
        assertEquals("https://novatv.gr/tv/channel/91", NovaTv.linkFor("Novasports Prime"))
    }

    @Test
    fun `the first carried channel is chosen`() {
        assertEquals("Magenta Sport 4", NovaTv.firstCarried(listOf("DAZN", "Magenta Sport 4", "ΣΚΑΪ")))
        assertNull(NovaTv.firstCarried(listOf("DAZN")))
    }
}
