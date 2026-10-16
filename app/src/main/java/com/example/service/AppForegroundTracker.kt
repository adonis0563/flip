package com.example.service

import android.app.Activity
import android.app.Application
import android.os.Bundle

object AppForegroundTracker {
    @Volatile
    var isAppInForeground: Boolean = false
        private set

    private var startedActivities = 0
    private var isInitialized = false

    fun init(application: Application) {
        if (isInitialized) return
        isInitialized = true
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                isAppInForeground = startedActivities > 0
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities = maxOf(0, startedActivities - 1)
                isAppInForeground = startedActivities > 0
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {
                isAppInForeground = true
            }
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
