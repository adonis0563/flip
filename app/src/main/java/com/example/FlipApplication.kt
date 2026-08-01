package com.example

import android.app.Application
import android.util.Log

class FlipApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Install Global Uncaught Exception Logger for process death diagnostics
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e("FATAL_CRASH", "==================== FATAL UNCAUGHT EXCEPTION ====================")
                Log.e("FATAL_CRASH", "Thread: ${thread.name} (id: ${thread.id})")
                Log.e("FATAL_CRASH", "Exception Class: ${throwable.javaClass.name}")
                Log.e("FATAL_CRASH", "Message: ${throwable.message}")
                Log.e("FATAL_CRASH", "Cause: ${throwable.cause}")
                Log.e("FATAL_CRASH", "Stack trace:", throwable)
                Log.e("FATAL_CRASH", "==================================================================")
            } catch (e: Exception) {
                Log.e("FATAL_CRASH", "Error logging fatal crash: ${e.message}")
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
        
        Log.d("FlipApplication", "FlipApplication initialized with Global Uncaught Exception Handler")
    }
}
