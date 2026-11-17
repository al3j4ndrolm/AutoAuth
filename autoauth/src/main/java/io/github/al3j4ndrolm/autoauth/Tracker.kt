package io.github.al3j4ndrolm.autoauth

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.startup.Initializer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Keeps the app context and the screen on top, so AutoAuth needs no `Context` or `Activity`. */
internal object Tracker : Application.ActivityLifecycleCallbacks {

    private var appContextOrNull: Context? = null
    private val resumed = MutableStateFlow<Activity?>(null)

    val appContext: Context
        get() = appContextOrNull ?: error(
            "AutoAuth isn't initialized. Make sure androidx.startup's InitializationProvider " +
                "isn't removed from your merged manifest."
        )

    fun install(context: Context) {
        if (appContextOrNull != null) return
        val app = context.applicationContext as Application
        appContextOrNull = app
        app.registerActivityLifecycleCallbacks(this)
    }

    /** The screen on top, waiting a little if none is showing yet (e.g. called from onCreate). */
    suspend fun activity(): Activity? = withTimeoutOrNull(10_000) {
        resumed.filterNotNull().first { !it.isFinishing && !it.isDestroyed }
    }

    override fun onActivityResumed(activity: Activity) {
        resumed.value = activity
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumed.value === activity) resumed.value = null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

class AutoAuthInitializer : Initializer<AutoAuth> {
    override fun create(context: Context): AutoAuth {
        Tracker.install(context)
        AutoAuth.start()
        return AutoAuth
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
