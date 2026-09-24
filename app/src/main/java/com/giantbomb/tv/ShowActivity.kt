package com.giantbomb.tv

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.widget.ImageView
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.bumptech.glide.Glide
import com.giantbomb.tv.model.Show
import com.giantbomb.tv.util.DeviceUtil
import com.giantbomb.tv.ui.NoBackdropRefraction

class ShowActivity : FragmentActivity(), NoBackdropRefraction {

    companion object {
        const val EXTRA_SHOW = "extra_show"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val isTv = DeviceUtil.isTv(this)
        if (isTv) {
            setTheme(R.style.Theme_GiantBombTV)
        }
        super.onCreate(savedInstanceState)
        if (!isTv) {
            enableEdgeToEdge()
        }
        setContentView(R.layout.activity_show)

        @Suppress("DEPRECATION")
        val show = intent.getSerializableExtra(EXTRA_SHOW) as? Show ?: run {
            finish()
            return
        }

        val backdrop = findViewById<ImageView>(R.id.show_backdrop)
        val imageUrl = show.posterUrl ?: show.logoUrl
        if (!imageUrl.isNullOrEmpty()) {
            Glide.with(this).load(imageUrl).override(960, 540).centerCrop().into(backdrop)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            backdrop.setRenderEffect(
                RenderEffect.createBlurEffect(4f, 4f, Shader.TileMode.CLAMP)
            )
        }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.show_fragment_container, ShowBrowseFragment())
                .commit()
        }
    }
}
