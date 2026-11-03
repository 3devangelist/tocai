package pt.tocai.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun parseVersionCode_tagDoCi() {
        assertEquals(42, UpdateChecker.parseVersionCode("v1.0.42"))
        assertEquals(7, UpdateChecker.parseVersionCode("1.0.7"))
        assertEquals(123, UpdateChecker.parseVersionCode(" V1.0.123 "))
        assertEquals(5, UpdateChecker.parseVersionCode("v5"))
    }

    @Test
    fun parseVersionCode_invalidas() {
        assertNull(UpdateChecker.parseVersionCode(null))
        assertNull(UpdateChecker.parseVersionCode(""))
        assertNull(UpdateChecker.parseVersionCode("v"))
        assertNull(UpdateChecker.parseVersionCode("v1.0.x"))
        assertNull(UpdateChecker.parseVersionCode("v1.0."))
        assertNull(UpdateChecker.parseVersionCode("v1.0.0"))
        assertNull(UpdateChecker.parseVersionCode("nightly"))
        assertNull(UpdateChecker.parseVersionCode("v1.0.42-beta"))
    }

    @Test
    fun isNewer_comparaPeloVersionCode() {
        assertTrue(UpdateChecker.isNewer("v1.0.43", 42))
        assertFalse(UpdateChecker.isNewer("v1.0.42", 42))
        assertFalse(UpdateChecker.isNewer("v1.0.41", 42))
        // numérico, não lexicográfico
        assertTrue(UpdateChecker.isNewer("v1.0.100", 99))
        assertFalse(UpdateChecker.isNewer("v1.0.9", 10))
        assertFalse(UpdateChecker.isNewer("lixo", 1))
        assertTrue(UpdateChecker.isNewer(2, 1))
        assertFalse(UpdateChecker.isNewer(1, 1))
    }
}
