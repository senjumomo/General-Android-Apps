package com.senjumomo.cards

import android.app.Application
import com.google.android.material.color.DynamicColors

class CardsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Material You: follow the wallpaper colours on Android 12+.
        DynamicColors.applyToActivitiesIfAvailable(this)
        CrashLog.install(this)
    }
}

/**
 * Keeps the last crash (stack trace) so the app can show it on the next launch: a crash otherwise
 * just looks like the screen closing, with nothing to report.
 */
object CrashLog {
    private const val FILE = "last_crash.txt"

    fun install(context: android.content.Context) {
        val file = java.io.File(context.filesDir, FILE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val trace = java.io.StringWriter().also { e.printStackTrace(java.io.PrintWriter(it)) }.toString()
                val version = runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                }.getOrNull()
                file.writeText("Card Binder $version · ${java.util.Date()}\n${trace.take(6000)}")
            }
            previous?.uncaughtException(thread, e)
        }
    }

    /** The last crash report, once (it's deleted when read). */
    fun take(context: android.content.Context): String? {
        val file = java.io.File(context.filesDir, FILE)
        if (!file.exists()) return null
        return runCatching { file.readText() }.getOrNull().also { file.delete() }
    }
}
