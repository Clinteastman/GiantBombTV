package com.giantbomb.tv

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
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
            enableEdgeToEdge()
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
