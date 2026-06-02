package com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream

object VisualMemoryFrameStore {
    private const val JPEG_QUALITY = 70

    @Volatile
    private var latestJpeg: ByteArray? = null

    @Volatile
    private var latestCapturedAtMs: Long = 0L

    fun update(bitmap: Bitmap) {
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
        latestJpeg = output.toByteArray()
        latestCapturedAtMs = System.currentTimeMillis()
    }

    fun latestBase64(): String? {
        val jpeg = latestJpeg ?: return null
        return Base64.encodeToString(jpeg, Base64.NO_WRAP)
    }

    fun latestCapturedAtMs(): Long = latestCapturedAtMs
}
