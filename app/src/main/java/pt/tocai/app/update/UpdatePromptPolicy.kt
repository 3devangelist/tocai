package pt.tocai.app.update

/**
 * Regras da pergunta "Nova versão do Tocaí" ao abrir a app (Kotlin puro, testado na JVM).
 */
object UpdatePromptPolicy {

    /** No máximo uma consulta à API do GitHub por cada 10 minutos. */
    const val CHECK_INTERVAL_MS = 10L * 60L * 1000L

    /**
     * Consultar o GitHub agora? Sim se nunca se consultou, se já passou o intervalo ou se o relógio
     * andou para trás (o timestamp guardado está no futuro).
     */
    fun shouldCheck(now: Long, lastCheckAt: Long, intervalMs: Long = CHECK_INTERVAL_MS): Boolean {
        if (lastCheckAt <= 0L) return true
        if (now < lastCheckAt) return true
        return now - lastCheckAt >= intervalMs
    }

    /**
     * Mostrar o diálogo agora?
     * @param remoteCode versionCode da versão publicada (null = nenhuma conhecida)
     * @param answeredCode versão a que o utilizador já respondeu neste ecrã (0 = nenhuma)
     * @param enabled preferência "Verificar atualizações automaticamente"
     * @param busy já há outro diálogo aberto, ou o ecrã não está utilizável
     */
    fun shouldPrompt(
        remoteCode: Int?,
        currentCode: Int,
        answeredCode: Int,
        enabled: Boolean,
        busy: Boolean,
    ): Boolean {
        if (!enabled || busy || remoteCode == null) return false
        if (!UpdateChecker.isNewer(remoteCode, currentCode)) return false
        return remoteCode > answeredCode
    }

    /**
     * Versão a que o utilizador já respondeu e que ainda conta: só na mesma abertura da app
     * ([answeredOpen] == [currentOpen]); numa nova abertura volta a perguntar (devolve 0).
     */
    fun answeredThisOpen(answeredCode: Int, answeredOpen: Int, currentOpen: Int): Int =
        if (answeredCode > 0 && answeredOpen == currentOpen) answeredCode else 0

    /**
     * Resume as notas da release (markdown) em poucas linhas de texto simples: tira títulos, ênfases,
     * código, imagens, ligações e HTML; as listas ficam com "•". Devolve "" se não sobrar nada.
     */
    fun summarizeNotes(body: String?, maxLines: Int = 3, maxChars: Int = 280): String {
        if (body.isNullOrBlank() || maxLines <= 0) return ""
        val lines = ArrayList<String>()
        var inCode = false
        var more = false
        for (raw in body.replace("\r", "").split('\n')) {
            val t = raw.trim()
            if (t.startsWith("```") || t.startsWith("~~~")) {
                inCode = !inCode
                continue
            }
            if (inCode || t.isEmpty()) continue
            if (t.startsWith("#")) continue                       // títulos (a versão já está no título)
            if (HR.matches(t)) continue                           // --- *** ___
            val clean = cleanLine(t)
            if (clean.isBlank()) continue
            if (lines.size >= maxLines) {
                more = true
                break
            }
            lines.add(clean)
        }
        var text = lines.joinToString("\n")
        if (text.length > maxChars) {
            text = text.take(maxChars).trimEnd()
            more = true
        }
        return if (more && text.isNotEmpty()) "$text…" else text
    }

    private val HR = Regex("^([-*_]\\s*){3,}$")
    private val IMAGE = Regex("!\\[[^\\]]*]\\([^)]*\\)")
    private val LINK = Regex("\\[([^\\]]*)]\\([^)]*\\)")
    private val HTML = Regex("<[^>]+>")
    private val BULLET = Regex("^[-*+]\\s+")
    private val NUMBERED = Regex("^(\\d+)[.)]\\s+")
    private val QUOTE = Regex("^>+\\s?")
    private val EMPHASIS = Regex("(\\*\\*|__|\\*|_|~~|`)")
    private val SPACES = Regex("\\s{2,}")

    private fun cleanLine(line: String): String {
        var s = line.replace(QUOTE, "")
        var prefix = ""
        val numbered = NUMBERED.find(s)
        when {
            BULLET.containsMatchIn(s) -> {
                prefix = "• "
                s = s.replace(BULLET, "")
            }
            numbered != null -> {
                prefix = "${numbered.groupValues[1]}. "
                s = s.substring(numbered.range.last + 1)
            }
        }
        s = s.replace(IMAGE, "")
            .replace(LINK, "$1")
            .replace(HTML, "")
            .replace(EMPHASIS, "")
            .replace(SPACES, " ")
            .trim()
        return if (s.isEmpty()) "" else prefix + s
    }
}
