package com.senjumomo.cards

import android.app.Application
import com.google.android.material.color.DynamicColors

class CardsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Material You: follow the wallpaper colours on Android 12+.
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
