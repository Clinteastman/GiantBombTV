package com.giantbomb.tv.util

import android.content.Context
import android.content.pm.PackageManager

object DeviceUtil {
    fun isTv(context: Context): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }
}
