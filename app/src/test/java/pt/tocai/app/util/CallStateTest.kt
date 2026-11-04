package pt.tocai.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallStateTest {

    private val api26 = 26
    private val api30 = 30
    private val api33 = 33
    private val api35 = 35

    @Test
    fun modoNormalNaoEChamada() {
        assertFalse(CallState.isCallMode(CallState.MODE_NORMAL, api35))
        assertFalse(CallState.isCallMode(-1, api35))   // MODE_INVALID / MODE_CURRENT
        assertFalse(CallState.isCallMode(99, api35))
    }

    @Test
    fun chamadaTelefonicaEReuniaoSaoChamada() {
        for (sdk in listOf(api26, api30, api33, api35)) {
            assertTrue(CallState.isCallMode(CallState.MODE_IN_CALL, sdk))
            assertTrue(CallState.isCallMode(CallState.MODE_IN_COMMUNICATION, sdk))
            assertTrue(CallState.isCallMode(CallState.MODE_RINGTONE, sdk))
        }
    }

    @Test
    fun callScreeningSoDesdeApi30() {
        assertFalse(CallState.isCallMode(CallState.MODE_CALL_SCREENING, api26))
        assertFalse(CallState.isCallMode(CallState.MODE_CALL_SCREENING, 29))
        assertTrue(CallState.isCallMode(CallState.MODE_CALL_SCREENING, api30))
        assertTrue(CallState.isCallMode(CallState.MODE_CALL_SCREENING, api35))
    }

    @Test
    fun redirecionamentosSoDesdeApi33() {
        assertFalse(CallState.isCallMode(CallState.MODE_CALL_REDIRECT, 32))
        assertFalse(CallState.isCallMode(CallState.MODE_COMMUNICATION_REDIRECT, 32))
        assertTrue(CallState.isCallMode(CallState.MODE_CALL_REDIRECT, api33))
        assertTrue(CallState.isCallMode(CallState.MODE_COMMUNICATION_REDIRECT, api35))
    }
}
