package pt.tocai.app.rules

/** Quando faz sentido oferecer "Adiar". Kotlin puro (sem Android). */
object SnoozePolicy {

    /** Antecedência mínima do despertador para haver "Adiar" (com menos, adiar não faz sentido). */
    const val MIN_OFFSET_MINUTES = 10

    private const val MINUTE_MS = 60_000L

    /**
     * true se o despertador foi marcado com pelo menos [MIN_OFFSET_MINUTES] de antecedência
     * e o adiamento ([snoozeMinutes] a partir de [now]) ainda toca antes do início do evento ([begin]).
     */
    fun canSnooze(offsetMinutes: Int, now: Long, begin: Long, snoozeMinutes: Int): Boolean {
        if (offsetMinutes < MIN_OFFSET_MINUTES) return false
        if (snoozeMinutes <= 0 || begin <= 0L) return false
        return now + snoozeMinutes * MINUTE_MS < begin
    }
}
