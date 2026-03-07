package com.giantbomb.tv

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.widget.ImageView
import androidx.fragment.app.FragmentActivity
import com.bumptech.glide.Glide
import com.giantbomb.tv.model.Show

class ShowActivity : FragmentActivity() {

    companion object {
        const val EXTRA_SHOW = "extra_show"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_show)

        @Suppress("DEPRECATION")
        val show = intent.getSerializableExtra(EXTRA_SHOW) as? Show ?: run {
            finish()
            return
        }

        val backdrop = findViewById<ImageView>(R.id.show_backdrop)
        val imageUrl = show.posterUrl ?: show.logoUrl
        if (!imageUrl.isNullOrEmpty()) {
            Glide.with(this).load(imageUrl).override(480, 270).centerCrop().into(backdrop)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            backdrop.setRenderEffect(
                RenderEffect.createBlurEffect(50f, 50f, Shader.TileMode.CLAMP)
            )
        }
    }
}
