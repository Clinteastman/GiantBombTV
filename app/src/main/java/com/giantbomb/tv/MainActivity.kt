package com.giantbomb.tv

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import com.giantbomb.tv.mobile.MobileBrowseFragment
import com.giantbomb.tv.util.DeviceUtil

class MainActivity : FragmentActivity() {

    companion object {
        const val SETUP_REQUEST = 1001
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isSelectKey = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                event.keyCode == KeyEvent.KEYCODE_ENTER ||
                event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
                event.keyCode == KeyEvent.KEYCODE_BUTTON_A
        if (isSelectKey) {
            Log.d("GBKeyEvent", "select action=${event.action} repeat=${event.repeatCount} code=${event.keyCode}")
            if (event.repeatCount > 0) {
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val isTv = DeviceUtil.isTv(this)
        if (isTv) {
            setTheme(R.style.Theme_GiantBombTV)
        }
        super.onCreate(savedInstanceState)

        // Edge-to-edge + display cutout support for phones
        if (!isTv) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        setContentView(R.layout.activity_main)

        if (savedInstanceState == null) {
            val fragment = if (isTv) {
                BrowseFragment()
            } else {
                MobileBrowseFragment()
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.main_fragment_container, fragment)
                .commit()
        }
    }

    @Deprecated("Use Activity Result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SETUP_REQUEST && resultCode == Activity.RESULT_OK) {
            val fragment = supportFragmentManager.findFragmentById(R.id.main_fragment_container)
            when (fragment) {
                is BrowseFragment -> fragment.loadContent()
                is MobileBrowseFragment -> fragment.loadContent()
            }
        }
    }
}
