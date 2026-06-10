package com.kunk.singbox.ui.scanner

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class QrScannerActivitySourceTest {
    @Test
    fun galleryImageInputStreamIsClosedAfterDecode() {
        val source = File("src/main/java/com/kunk/singbox/ui/scanner/QrScannerActivity.kt").readText()

        assertTrue(source.contains("contentResolver.openInputStream(uri)?.use { inputStream ->"))
        assertTrue(source.contains("BitmapFactory.decodeStream(inputStream, null, boundsOptions)"))
        assertTrue(source.contains("BitmapFactory.decodeStream(inputStream, null, decodeOptions)"))
        assertFalse(source.contains("val inputStream: InputStream? = contentResolver.openInputStream(uri)"))
    }

    @Test
    fun galleryQrImageIsBoundsCheckedAndDownsampledBeforeDecode() {
        val source = File("src/main/java/com/kunk/singbox/ui/scanner/QrScannerActivity.kt").readText()

        assertTrue(source.contains("MAX_QR_IMAGE_PIXELS"))
        assertTrue(source.contains("BitmapFactory.Options().apply { inJustDecodeBounds = true }"))
        assertTrue(source.contains("calculateQrImageSampleSize(boundsOptions.outWidth, boundsOptions.outHeight)"))
        assertTrue(source.contains("inSampleSize = sampleSize"))
        assertTrue(source.contains("if (pixelCount > MAX_QR_IMAGE_PIXELS) return null"))
    }

    @Test
    fun galleryQrBitmapIsRecycledAfterDecodeAttempt() {
        val source = File("src/main/java/com/kunk/singbox/ui/scanner/QrScannerActivity.kt").readText()
        val body = source.substring(
            source.indexOf("if (bitmap != null)"),
            source.indexOf("} else {", source.indexOf("if (bitmap != null)"))
        )

        assertTrue(body.contains("try {"))
        assertTrue(body.contains("decodeQRCode(bitmap)"))
        assertTrue(body.contains("finally {"))
        assertTrue(body.contains("bitmap.recycle()"))
    }
}
