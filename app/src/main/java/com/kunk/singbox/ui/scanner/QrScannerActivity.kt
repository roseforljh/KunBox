package com.kunk.singbox.ui.scanner

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.ImageButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.journeyapps.barcodescanner.CaptureManager
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.kunk.singbox.R
import com.kunk.singbox.ui.components.AppNotificationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 */
class QrScannerActivity : AppCompatActivity() {

    private lateinit var capture: CaptureManager
    private lateinit var barcodeScannerView: DecoratedBarcodeView
    private var isFlashOn = false

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            parseQrCodeFromUri(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        overridePendingTransition(R.anim.fade_in, R.anim.hold)
        setContentView(R.layout.activity_qr_scanner)

        barcodeScannerView = findViewById(R.id.barcode_scanner)

        capture = CaptureManager(this, barcodeScannerView)
        capture.initializeFromIntent(intent, savedInstanceState)
        capture.decode()

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }

        findViewById<ImageButton>(R.id.btn_gallery).setOnClickListener {
            galleryLauncher.launch(arrayOf("image/*"))
        }

        findViewById<ImageButton>(R.id.btn_flash).setOnClickListener {
            toggleFlash()
        }

        barcodeScannerView.setStatusText("")
    }

    private fun parseQrCodeFromUri(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bitmap = decodeQrBitmapSafely(uri)

                if (bitmap != null) {
                    val result = try {
                        decodeQRCode(bitmap)
                    } finally {
                        bitmap.recycle()
                    }
                    withContext(Dispatchers.Main) {
                        if (result != null) {
                            val intent = Intent()

                            intent.putExtra("SCAN_RESULT", result)
                            setResult(Activity.RESULT_OK, intent)
                            finish()
                        } else {
                            AppNotificationManager.showMessage(
                                this@QrScannerActivity,
                                getString(R.string.qr_scanner_no_qr_found)
                            )
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        AppNotificationManager.showMessage(
                            this@QrScannerActivity,
                            getString(R.string.qr_scanner_cannot_read_image)
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse QR code from image", e)
                withContext(Dispatchers.Main) {
                    AppNotificationManager.showMessage(
                        context = this@QrScannerActivity,
                        message = getString(R.string.profiles_import_failed) + ": ${e.message}"
                    )
                }
            }
        }
    }

    private fun decodeQrBitmapSafely(uri: Uri): Bitmap? {
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { inputStream ->
            BitmapFactory.decodeStream(inputStream, null, boundsOptions)
        } ?: return null

        val sampleSize = calculateQrImageSampleSize(boundsOptions.outWidth, boundsOptions.outHeight)
        if (sampleSize <= 0) return null

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
        }
        return contentResolver.openInputStream(uri)?.use { inputStream ->
            BitmapFactory.decodeStream(inputStream, null, decodeOptions)
        }
    }

    private fun decodeQRCode(bitmap: Bitmap): String? {
        return try {
            val width = bitmap.width
            val height = bitmap.height
            val pixelCount = width.toLong() * height.toLong()
            if (pixelCount > MAX_QR_IMAGE_PIXELS) return null
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            val source = RGBLuminanceSource(width, height, pixels)
            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
            val reader = MultiFormatReader()
            val result = reader.decode(binaryBitmap)
            result.text
        } catch (e: Exception) {
            null
        }
    }

    private fun calculateQrImageSampleSize(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return 0
        var sampleSize = 1
        while ((width / sampleSize).toLong() * (height / sampleSize).toLong() > MAX_QR_IMAGE_PIXELS) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun toggleFlash() {
        isFlashOn = !isFlashOn
        if (isFlashOn) {
            barcodeScannerView.setTorchOn()
            AppNotificationManager.showMessage(this, getString(R.string.qr_scanner_flash_on))
        } else {
            barcodeScannerView.setTorchOff()
            AppNotificationManager.showMessage(this, getString(R.string.qr_scanner_flash_off))
        }
    }

    override fun onResume() {
        super.onResume()
        capture.onResume()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, R.anim.fade_out)
    }

    override fun onPause() {
        super.onPause()
        capture.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        capture.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        capture.onSaveInstanceState(outState)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return barcodeScannerView.onKeyDown(keyCode, event) || super.onKeyDown(keyCode, event)
    }

    companion object {
        private const val TAG = "QrScannerActivity"
        private const val MAX_QR_IMAGE_PIXELS = 12_000_000L
        const val EXTRA_RESULT = "scan_result"

        fun createIntent(activity: Activity): Intent {
            return Intent(activity, QrScannerActivity::class.java)
        }
    }
}
