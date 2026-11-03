package pt.tocai.app.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingOffsetsTest {

    @Test
    fun escolhaNormalizada() {
        assertEquals(listOf(15, 5), PendingOffsets.choice(listOf(5, 15, 5, 2000, -1)))
        assertTrue(PendingOffsets.choice(null).isEmpty())
    }

    @Test
    fun mergeSobrepoeERemove() {
        val base = mapOf("a" to listOf(10), "b" to listOf(5))
        val pending = mapOf("a" to emptyList<Int>(), "c" to listOf(30))
        assertEquals(mapOf("b" to listOf(5), "c" to listOf(30)), PendingOffsets.merge(base, pending))
        assertSame(base, PendingOffsets.merge(base, emptyMap()))
    }

    @Test
    fun atualTemEmContaPendentes() {
        val pending = mapOf("a" to emptyList<Int>(), "b" to listOf(15))
        assertNull(PendingOffsets.current("a", listOf(10), pending))
        assertEquals(listOf(15), PendingOffsets.current("b", listOf(10), pending))
        assertEquals(listOf(10), PendingOffsets.current("c", listOf(10), pending))
        assertNull(PendingOffsets.current("d", null, pending))
    }

    @Test
    fun codificaEDescodificaParaOBundle() {
        val pending = mapOf("12_1700" to listOf(15, 5), "13_1800" to emptyList<Int>())
        val (keys, values) = PendingOffsets.encode(pending)
        assertEquals(listOf("12_1700", "13_1800"), keys)
        assertEquals(listOf("15,5", ""), values)
        assertEquals(pending, PendingOffsets.decode(keys, values))
        assertTrue(PendingOffsets.decode(null, values).isEmpty())
        assertTrue(PendingOffsets.decode(listOf("x"), emptyList()).isEmpty())
    }
}
