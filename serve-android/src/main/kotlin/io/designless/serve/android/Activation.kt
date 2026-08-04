package io.designless.serve.android

import android.app.Activity
import android.app.Application
import android.os.Bundle
import io.designless.serve.Brand

/**
 * Where the "changes land on the next foreground" rule actually happens.
 *
 * The core holds a fetched change until something calls `activate()`. This is
 * that something: it watches the process come to the foreground and activates
 * there.
 *
 * Uses `Application.ActivityLifecycleCallbacks`, which is in the framework,
 * rather than `androidx.lifecycle.ProcessLifecycleOwner`, which would be a
 * dependency. The behaviour that matters is the same — fire when the app goes
 * from no started activities to one — and counting them is six lines.
 *
 * A rotation is not a foreground. During a configuration change the count dips
 * and recovers within the same moment, so the counter is checked on start
 * rather than on stop, and a rotation activates nothing it should not.
 */
public class ForegroundActivation(
    private val brand: Brand,
    /** Called after a held change was promoted, so a caller can recreate. */
    private val onActivated: () -> Unit = {},
) : Application.ActivityLifecycleCallbacks {

    private var startedActivities = 0

    /** Start watching. Call once, from `Application.onCreate`. */
    public fun attach(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    public fun detach(application: Application) {
        application.unregisterActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        val wasBackgrounded = startedActivities == 0
        startedActivities++
        if (!wasBackgrounded) return

        // Coming to the foreground is the moment a change is allowed to land.
        // activate() is a no-op when nothing is held, so this costs nothing on
        // every other resume.
        if (brand.activate()) onActivated()
    }

    override fun onActivityStopped(activity: Activity) {
        if (startedActivities > 0) startedActivities--
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?): Unit = Unit
    override fun onActivityResumed(activity: Activity): Unit = Unit
    override fun onActivityPaused(activity: Activity): Unit = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle): Unit = Unit
    override fun onActivityDestroyed(activity: Activity): Unit = Unit
}
