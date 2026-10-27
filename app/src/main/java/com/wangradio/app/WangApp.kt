package com.wangradio.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

/** Applies the Light / Dark / Match Android choice before any screen opens. */
class WangApp : Application() {
    override fun onCreate() {
        super.onCreate()
        applyTheme(Prefs.theme(this))
    }

    companion object {
        fun applyTheme(theme: String) {
            AppCompatDelegate.setDefaultNightMode(
                when (theme) {
                    "light" -> AppCompatDelegate.MODE_NIGHT_NO
                    "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            )
        }
    }
}
