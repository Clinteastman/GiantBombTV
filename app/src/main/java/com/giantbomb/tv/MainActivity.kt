package com.giantbomb.tv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity

class MainActivity : FragmentActivity() {

    companion object {
        const val SETUP_REQUEST = 1001
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
