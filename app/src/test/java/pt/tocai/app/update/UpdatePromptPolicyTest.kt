package pt.tocai.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePromptPolicyTest {

    private val min = 60_000L
    private val t0 = 1_700_000_000_000L

    @Test
    fun shouldCheck_primeiraVez() {
        assertTrue(UpdatePromptPolicy.shouldCheck(t0, 0L))
        assertTrue(UpdatePromptPolicy.shouldCheck(t0, -5L))
    }

    @Test
    fun shouldCheck_noMaximoUmaVezPor10Minutos() {
        assertFalse(UpdatePromptPolicy.shouldCheck(t0, t0))
        assertFalse(UpdatePromptPolicy.shouldCheck(t0 + 9 * min, t0))
        assertFalse(UpdatePromptPolicy.shouldCheck(t0 + 10 * min - 1, t0))
        assertTrue(UpdatePromptPolicy.shouldCheck(t0 + 10 * min, t0))
        assertTrue(UpdatePromptPolicy.shouldCheck(t0 + 3 * 60 * min, t0))
    }

    @Test
    fun shouldCheck_relogioParaTras() {
        assertTrue(UpdatePromptPolicy.shouldCheck(t0 - min, t0))
    }

    @Test
    fun shouldPrompt_versaoNova() {
        assertTrue(UpdatePromptPolicy.shouldPrompt(16, 15, 0, enabled = true, busy = false))
    }

    @Test
    fun shouldPrompt_naoSeNaoHaNova() {
        assertFalse(UpdatePromptPolicy.shouldPrompt(null, 15, 0, enabled = true, busy = false))
        assertFalse(UpdatePromptPolicy.shouldPrompt(15, 15, 0, enabled = true, busy = false))
        assertFalse(UpdatePromptPolicy.shouldPrompt(14, 15, 0, enabled = true, busy = false))
    }

    @Test
    fun shouldPrompt_respeitaPreferenciaEOutrosDialogos() {
        assertFalse(UpdatePromptPolicy.shouldPrompt(16, 15, 0, enabled = false, busy = false))
        assertFalse(UpdatePromptPolicy.shouldPrompt(16, 15, 0, enabled = true, busy = true))
    }

    @Test
    fun shouldPrompt_maisTardeSoNestaAbertura() {
        // respondeu à 16 nesta abertura: não volta a perguntar
        assertFalse(UpdatePromptPolicy.shouldPrompt(16, 15, 16, enabled = true, busy = false))
        // mas pergunta se entretanto saiu outra mais nova
        assertTrue(UpdatePromptPolicy.shouldPrompt(17, 15, 16, enabled = true, busy = false))
    }

    @Test
    fun answeredThisOpen_novaAberturaVoltaAPerguntar() {
        assertEquals(16, UpdatePromptPolicy.answeredThisOpen(16, 3, 3))
        assertEquals(0, UpdatePromptPolicy.answeredThisOpen(16, 3, 4))
        assertEquals(0, UpdatePromptPolicy.answeredThisOpen(0, 3, 3))
        val answered = UpdatePromptPolicy.answeredThisOpen(16, 3, 4)
        assertTrue(UpdatePromptPolicy.shouldPrompt(16, 15, answered, enabled = true, busy = false))
    }

    @Test
    fun summarizeNotes_notasDoCi() {
        val body = "## Tocaí 1.0.15\n\n" +
            "1. No telemóvel, descarrega o ficheiro **Tocai.apk** que está mais abaixo, em *Assets*.\n" +
            "2. Abre-o e carrega em **Instalar**.\n" +
            "3. Se já tinhas o Tocaí, a atualização instala-se por cima.\n"
        assertEquals(
            "1. No telemóvel, descarrega o ficheiro Tocai.apk que está mais abaixo, em Assets.\n" +
                "2. Abre-o e carrega em Instalar.\n" +
                "3. Se já tinhas o Tocaí, a atualização instala-se por cima.",
            UpdatePromptPolicy.summarizeNotes(body),
        )
    }

    @Test
    fun summarizeNotes_semMarkdownCru() {
        val body = "# Novidades\r\n" +
            "- **Despertador** mais `alto`\n" +
            "* Ver [as notas](https://example.com) ![img](https://x/y.png)\n" +
            "> <b>Importante</b>\n" +
            "---\n" +
            "```\ncodigo()\n```\n" +
            "+ quarta linha\n"
        assertEquals(
            "• Despertador mais alto\n• Ver as notas\nImportante…",
            UpdatePromptPolicy.summarizeNotes(body),
        )
    }

    @Test
    fun summarizeNotes_vazioELimites() {
        assertEquals("", UpdatePromptPolicy.summarizeNotes(null))
        assertEquals("", UpdatePromptPolicy.summarizeNotes("  \n## Só título\n---\n"))
        assertEquals("abcde…", UpdatePromptPolicy.summarizeNotes("abcdefghij", maxChars = 5))
        assertEquals("a\nb…", UpdatePromptPolicy.summarizeNotes("a\nb\nc", maxLines = 2))
    }
}
