package com.giantbomb.tv

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import com.giantbomb.tv.mobile.MobileSearchFragment
import com.giantbomb.tv.util.DeviceUtil

class SearchActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val isTv = DeviceUtil.isTv(this)
        if (isTv) {
            setTheme(R.style.Theme_GiantBombTV)
        }
        super.onCreate(savedInstanceState)

        if (!isTv) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        setContentView(R.layout.activity_search)

        if (savedInstanceState == null) {
            val fragment = if (isTv) {
                GiantBombSearchFragment()
            } else {
                MobileSearchFragment()
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.search_fragment_container, fragment)
                .commit()
        }
    }
}
