package com.giantbomb.tv

import android.app.Application
import com.giantbomb.tv.data.PrefsManager
import com.giantbomb.tv.ui.GlassSurface

/**
 * Loads the saved visual theme before any activity draws. Setup, Detail and
 * Playback can each be the first screen of a cold process (deep link,
 * notification, task restore), so this can't live in MainActivity alone.
 */
class GiantBombApp : Application() {
    override fun onCreate() {
        super.onCreate()
        GlassSurface.configure(PrefsManager(this).visualTheme)
    }
}
