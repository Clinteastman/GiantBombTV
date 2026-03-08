package com.giantbomb.tv.ui

import android.graphics.Bitmap
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import java.security.MessageDigest

/**
 * Glide transformation that blurs images on pre-API 31 devices.
 * Uses downscale + iterative box blur for a smooth result.
 */
class BlurTransformation(private val radius: Int = 25, private val passes: Int = 3) : BitmapTransformation() {

    override fun transform(pool: BitmapPool, toTransform: Bitmap, outWidth: Int, outHeight: Int): Bitmap {
        // Downscale to 1/4 for performance + extra softness
        val scale = 4
        val w = (toTransform.width / scale).coerceAtLeast(1)
        val h = (toTransform.height / scale).coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(toTransform, w, h, true)

        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)

        // Multiple passes of box blur for a smooth gaussian-like result
        repeat(passes) {
            boxBlurHorizontal(pixels, w, h, radius)
            boxBlurVertical(pixels, w, h, radius)
        }

        val result = pool.get(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, w, 0, 0, w, h)
        if (small != toTransform) small.recycle()
        return result
    }

    private fun boxBlurHorizontal(pixels: IntArray, w: Int, h: Int, r: Int) {
        val rr = r.coerceAtMost(w / 2)
        for (y in 0 until h) {
            var rSum = 0; var gSum = 0; var bSum = 0
            val row = y * w
            // Init window
            for (x in -rr..rr) {
                val idx = row + x.coerceIn(0, w - 1)
                val p = pixels[idx]
                rSum += (p shr 16) and 0xFF
                gSum += (p shr 8) and 0xFF
                bSum += p and 0xFF
            }
            val div = 2 * rr + 1
            for (x in 0 until w) {
                pixels[row + x] = (0xFF000000.toInt()) or
                    ((rSum / div) shl 16) or ((gSum / div) shl 8) or (bSum / div)
                // Slide window
                val addIdx = (x + rr + 1).coerceAtMost(w - 1)
                val remIdx = (x - rr).coerceAtLeast(0)
                val addP = pixels[row + addIdx]
                val remP = pixels[row + remIdx]
                rSum += ((addP shr 16) and 0xFF) - ((remP shr 16) and 0xFF)
                gSum += ((addP shr 8) and 0xFF) - ((remP shr 8) and 0xFF)
                bSum += (addP and 0xFF) - (remP and 0xFF)
            }
        }
    }

    private fun boxBlurVertical(pixels: IntArray, w: Int, h: Int, r: Int) {
        val rr = r.coerceAtMost(h / 2)
        val col = IntArray(h)
        for (x in 0 until w) {
            // Copy column
            for (y in 0 until h) col[y] = pixels[y * w + x]

            var rSum = 0; var gSum = 0; var bSum = 0
            for (y in -rr..rr) {
                val p = col[y.coerceIn(0, h - 1)]
                rSum += (p shr 16) and 0xFF
                gSum += (p shr 8) and 0xFF
                bSum += p and 0xFF
            }
            val div = 2 * rr + 1
            for (y in 0 until h) {
                pixels[y * w + x] = (0xFF000000.toInt()) or
                    ((rSum / div) shl 16) or ((gSum / div) shl 8) or (bSum / div)
                val addIdx = (y + rr + 1).coerceAtMost(h - 1)
                val remIdx = (y - rr).coerceAtLeast(0)
                rSum += ((col[addIdx] shr 16) and 0xFF) - ((col[remIdx] shr 16) and 0xFF)
                gSum += ((col[addIdx] shr 8) and 0xFF) - ((col[remIdx] shr 8) and 0xFF)
                bSum += (col[addIdx] and 0xFF) - (col[remIdx] and 0xFF)
            }
        }
    }

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update("blur_${radius}_$passes".toByteArray())
    }

    override fun equals(other: Any?) = other is BlurTransformation && other.radius == radius
    override fun hashCode() = radius
}
