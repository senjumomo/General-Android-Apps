package com.senjumomo.utilities

import android.app.Application
import com.google.android.material.color.DynamicColors

class UtilitiesApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Material You: follow the wallpaper colours on Android 12+.
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
