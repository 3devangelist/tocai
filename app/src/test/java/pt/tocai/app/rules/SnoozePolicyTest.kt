package pt.tocai.app.rules

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnoozePolicyTest {

    private val min = 60_000L
    private val now = 1_700_000_000_000L

    @Test
    fun antecedenciaGrandeEAindaHaTempoPermiteAdiar() {
        assertTrue(SnoozePolicy.canSnooze(15, now, now + 15 * min, 5))
        assertTrue(SnoozePolicy.canSnooze(10, now, now + 10 * min, 5))
        assertTrue(SnoozePolicy.canSnooze(60, now, now + 60 * min, 15))
    }

    @Test
    fun antecedenciaMenorQue10NaoPermiteAdiar() {
        assertFalse(SnoozePolicy.canSnooze(5, now, now + 60 * min, 5))
        assertFalse(SnoozePolicy.canSnooze(9, now, now + 60 * min, 5))
        assertFalse(SnoozePolicy.canSnooze(1, now, now + 60 * min, 5))
        assertFalse(SnoozePolicy.canSnooze(0, now, now + 60 * min, 5))
    }

    @Test
    fun adiamentoDepoisDoInicioNaoPermiteAdiar() {
        // 10 min antes, adiar 10 min cairia exatamente no início
        assertFalse(SnoozePolicy.canSnooze(10, now, now + 10 * min, 10))
        assertFalse(SnoozePolicy.canSnooze(15, now, now + 10 * min, 15))
        // evento já começou
        assertFalse(SnoozePolicy.canSnooze(15, now, now - min, 5))
    }

    @Test
    fun valoresInvalidosNaoPermitemAdiar() {
        assertFalse(SnoozePolicy.canSnooze(15, now, 0L, 5))
        assertFalse(SnoozePolicy.canSnooze(15, now, now + 60 * min, 0))
    }

    @Test
    fun alarmeDeTesteNaoTemAdiar() {
        // teste: offset 0 e início = hora do toque
        assertFalse(SnoozePolicy.canSnooze(0, now, now, 5))
    }
}
