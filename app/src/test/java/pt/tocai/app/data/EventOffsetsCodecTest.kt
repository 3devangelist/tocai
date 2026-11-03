package pt.tocai.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventOffsetsCodecTest {

    @Test
    fun codificaEDescodifica() {
        val map = mapOf("12_1700000000000" to listOf(15, 5), "13_1700000360000" to listOf(0))
        val raw = SettingsStore.encodeEventOffsets(map)
        assertEquals("12_1700000000000:15,5;13_1700000360000:0", raw)
        assertEquals(map, SettingsStore.decodeEventOffsets(raw))
    }

    @Test
    fun ignoraPartesInvalidas() {
        val decoded = SettingsStore.decodeEventOffsets("12_1:15;;semminutos;:5;13_2:x;14_3: 10 , 20")
        assertEquals(mapOf("12_1" to listOf(15), "14_3" to listOf(10, 20)), decoded)
        assertTrue(SettingsStore.decodeEventOffsets(null).isEmpty())
        assertTrue(SettingsStore.decodeEventOffsets("").isEmpty())
    }

    @Test
    fun naoCodificaChavesOuListasInvalidas() {
        val raw = SettingsStore.encodeEventOffsets(
            mapOf("a:b" to listOf(5), "" to listOf(5), "12_1" to emptyList(), "13_2" to listOf(10)),
        )
        assertEquals("13_2:10", raw)
    }
}
