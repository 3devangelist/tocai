package pt.tocai.app.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Formatação de horas e dias em português de Portugal. */
object TimeFormat {

    private val PT: Locale = Locale.forLanguageTag("pt-PT")

    // SimpleDateFormat não é thread-safe: cria-se um por chamada
    fun hm(t: Long): String = SimpleDateFormat("HH:mm", PT).format(Date(t))

    /** "hoje", "amanhã", "ontem" ou "quinta-feira, 5 de novembro". */
    fun relativeDay(t: Long, now: Long = System.currentTimeMillis()): String {
        val diff = daysBetween(now, t)
        return when (diff) {
            0 -> "hoje"
            1 -> "amanhã"
            -1 -> "ontem"
            else -> {
                val sameYear = year(t) == year(now)
                val pattern = if (sameYear) "EEEE, d 'de' MMMM" else "EEEE, d 'de' MMMM 'de' yyyy"
                SimpleDateFormat(pattern, PT).format(Date(t)).lowercase(PT)
            }
        }
    }

    /** Chave única por dia civil (no fuso local). */
    fun dayKey(t: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = t }
        return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
    }

    /** "5 min", "1 h", "1 h 30 min". */
    fun minutesLabel(m: Int): String {
        val v = if (m < 0) 0 else m
        val h = v / 60
        val min = v % 60
        return when {
            h == 0 -> "$min min"
            min == 0 -> "$h h"
            else -> "$h h $min min"
        }
    }

    private fun year(t: Long): Int =
        Calendar.getInstance().apply { timeInMillis = t }.get(Calendar.YEAR)

    // Diferença em dias civis (resistente a mudanças de hora)
    private fun daysBetween(from: Long, to: Long): Int {
        val a = startOfDay(from)
        val b = startOfDay(to)
        val c = Calendar.getInstance().apply { timeInMillis = a }
        if (a == b) return 0
        var n = 0
        if (b > a) {
            while (c.timeInMillis < b && n < 400) { c.add(Calendar.DAY_OF_YEAR, 1); n++ }
            return if (n >= 400) ((b - a) / 86_400_000L).toInt() else n
        } else {
            while (c.timeInMillis > b && n < 400) { c.add(Calendar.DAY_OF_YEAR, -1); n++ }
            return if (n >= 400) -((a - b) / 86_400_000L).toInt() else -n
        }
    }

    private fun startOfDay(t: Long): Long = Calendar.getInstance().apply {
        timeInMillis = t
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
