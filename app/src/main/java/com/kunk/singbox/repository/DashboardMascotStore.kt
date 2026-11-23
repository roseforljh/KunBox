package com.kunk.singbox.repository

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.AtomicFile
import android.util.Log
import androidx.compose.ui.unit.IntSize
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val MAX_MASCOT_EDGE_PX = 768

internal data class MascotPhoto(val bitmap: Bitmap, val transparentBackground: Boolean)

internal object DashboardMascotStore {
    private val lock = Mutex()
    private val _revision = MutableStateFlow(0L)
    val revision = _revision.asStateFlow()

    suspend fun load(context: Context): Bitmap? = withContext(Dispatchers.IO) {
        val bitmap = lock.withLock {
            try {
                imageFile(context).openRead().use { BitmapFactory.decodeStream(it) }
            } catch (_: FileNotFoundException) {
                null
            } catch (e: IOException) {
                Log.w("DashboardMascotStore", "Could not read the custom mascot", e)
                null
            }
        } ?: return@withContext null
        if (bitmap.hasTransparentMascotBackground()) bitmap
        else bitmap.cropMascot(MascotCrop.centered(IntSize(bitmap.width, bitmap.height)))
    }

    suspend fun preview(context: Context, uri: Uri): MascotPhoto? = withContext(Dispatchers.IO) {
        try {
            decodeMascot(context.contentResolver, uri)?.let { MascotPhoto(it, it.hasTransparentMascotBackground()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("DashboardMascotStore", "Could not decode the selected photo", e)
            null
        }
    }

    suspend fun set(
        context: Context,
        photo: MascotPhoto?,
        crop: MascotCrop? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val image = photo?.let { if (crop == null) it.bitmap else it.bitmap.cropMascot(crop) }
            lock.withLock {
                val file = imageFile(context)
                if (image == null) {
                    file.delete()
                    check(!file.baseFile.exists())
                } else {
                    writeImage(file, image)
                }
                _revision.update { it + 1 }
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("DashboardMascotStore", "Could not change the dashboard mascot", e)
            false
        }
    }

    private fun imageFile(context: Context) = AtomicFile(File(context.noBackupFilesDir, "dashboard-mascot.png"))

    private fun writeImage(file: AtomicFile, image: Bitmap) {
        val stream = file.startWrite()
        try {
            check(image.compress(Bitmap.CompressFormat.PNG, 100, stream))
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }
}

private fun decodeMascot(resolver: ContentResolver, uri: Uri): Bitmap? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSampleSize(mascotSampleSize(maxOf(info.size.width, info.size.height)))
        }
    }
    // ponytail: API 24–27 use BitmapFactory; add EXIF rotation here if those devices need it.
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    if (options.outWidth <= 0 || options.outHeight <= 0) return null
    options.inJustDecodeBounds = false
    options.inSampleSize = mascotSampleSize(maxOf(options.outWidth, options.outHeight))
    return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
}

internal fun mascotSampleSize(longestEdge: Int): Int {
    var sampleSize = 1
    while ((longestEdge - 1) / sampleSize >= MAX_MASCOT_EDGE_PX) {
        sampleSize *= 2
    }
    return sampleSize
}
