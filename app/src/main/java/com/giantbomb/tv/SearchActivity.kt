package com.giantbomb.tv

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.giantbomb.tv.util.DeviceUtil

class SearchActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        if (DeviceUtil.isTv(this)) {
            setTheme(R.style.Theme_GiantBombTV)
        }
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.search_fragment_container, GiantBombSearchFragment())
                .commit()
        }
    }
}
