package pt.tocai.app.util

import android.animation.ValueAnimator
import android.view.View
import android.view.Window
import androidx.core.content.ContextCompat
import pt.tocai.app.R

/** Fundo azul a pulsar (faz de LED de notificação): azul escuro <-> azul vivo, ~1 s por meio ciclo. */
object BluePulse {

    private const val HALF_CYCLE_MS = 1_000L

    /** Começa a pulsar o fundo de [view] (e as barras de sistema de [window]); devolve o animador. */
    @Suppress("DEPRECATION")   // statusBarColor/navigationBarColor (ignorados em edge-to-edge, API 35)
    fun start(view: View, window: Window?): ValueAnimator {
        val ctx = view.context
        val dark = ContextCompat.getColor(ctx, R.color.tocai_alarm_bg)
        val bright = ContextCompat.getColor(ctx, R.color.tocai_flash_bright)
        return ValueAnimator.ofArgb(dark, bright).apply {
            duration = HALF_CYCLE_MS
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { a ->
                val c = a.animatedValue as Int
                view.setBackgroundColor(c)
                window?.let {
                    it.statusBarColor = c
                    it.navigationBarColor = c
                }
            }
            start()
        }
    }
}
