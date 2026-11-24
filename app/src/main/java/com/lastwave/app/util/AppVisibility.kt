package com.lastwave.app.util

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-wide "is any LastWave screen on display" flag.
 *
 * Lets background work (the playback progress ticker in particular) relax its
 * cadence while nothing is drawn: a 60ms clock only matters for an on-screen
 * seek bar or lyrics, and running it with the screen off costs battery for
 * no visible benefit. Started-activity counting survives configuration
 * changes (the new instance starts before the old one stops).
 */
object AppVisibility {
    private val startedActivities = AtomicInteger(0)
    private val installed = AtomicBoolean(false)
    private val resumedActivityRef = AtomicReference<WeakReference<Activity>?>(null)

    /** True while at least one activity is started (visible to the user). */
    val isVisible: Boolean
        get() = startedActivities.get() > 0

    /** Currently resumed foreground activity, if any (safe for foreground-only launches). */
    val resumedActivity: Activity?
        get() = resumedActivityRef.get()?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }

    fun install(application: Application) {
        if (!installed.compareAndSet(false, true)) return
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities.incrementAndGet()
                com.lastwave.app.diagnostics.SessionMarker.noteActivity(application, uiVisible = true)
            }

            override fun onActivityStopped(activity: Activity) {
                // Never go negative if a callback is ever missed.
                val left = startedActivities.updateAndGet { (it - 1).coerceAtLeast(0) }
                if (left == 0) {
                    com.lastwave.app.diagnostics.SessionMarker.noteActivity(application, uiVisible = false)
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) {
                resumedActivityRef.set(WeakReference(activity))
            }
            override fun onActivityPaused(activity: Activity) {
                val current = resumedActivityRef.get()?.get()
                if (current === activity) {
                    resumedActivityRef.set(null)
                }
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) {
                val current = resumedActivityRef.get()?.get()
                if (current === activity) {
                    resumedActivityRef.set(null)
                }
            }
        })
    }
}
