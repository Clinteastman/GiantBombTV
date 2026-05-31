package com.giantbomb.tv

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.giantbomb.tv.util.DeviceUtil

/**
 * Thin host for [DownloadsFragment]. Used as the entry point from the TV
 * settings row (and any non-tab caller). On mobile the same fragment is hosted
 * directly as a bottom-nav tab by [MainActivity].
 */
class DownloadsActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!DeviceUtil.isTv(this)) enableEdgeToEdge()
        // The fragment's root view fills the screen and carries the gradient
        // background, so hosting it in android.R.id.content needs no layout.
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, DownloadsFragment())
                .commit()
        }
    }
}
