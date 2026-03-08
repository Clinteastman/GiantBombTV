package com.giantbomb.tv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.fragment.app.FragmentActivity

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
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }

    @Deprecated("Use Activity Result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SETUP_REQUEST && resultCode == Activity.RESULT_OK) {
            val fragment = supportFragmentManager.findFragmentById(R.id.main_browse_fragment)
            if (fragment is BrowseFragment) {
                fragment.loadContent()
            }
        }
    }
}
