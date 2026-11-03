package pt.tocai.app.util

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

/** Sabe se a app está em primeiro plano e qual a activity visível (registado em TocaiApp). */
object AppForeground : Application.ActivityLifecycleCallbacks {

    @Volatile
    private var started = 0

    @Volatile
    private var resumed: WeakReference<Activity>? = null

    private var registered = false

    val isInForeground: Boolean get() = started > 0

    /** A activity em primeiro plano (pode já estar a terminar). Só usar na thread principal. */
    val currentActivity: Activity? get() = resumed?.get()

    fun register(app: Application) {
        if (registered) return
        registered = true
        app.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        started++
    }

    override fun onActivityStopped(activity: Activity) {
        started = (started - 1).coerceAtLeast(0)
    }

    override fun onActivityResumed(activity: Activity) {
        resumed = WeakReference(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumed?.get() === activity) resumed = null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
