package com.kunk.singbox.ui.scanner

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.widget.ImageButton
import android.widget.Toast
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * 自定义二维码扫描 Activity
 * 使用正方形扫描框，更符合二维码的形状
 */
class QrScannerActivity : AppCompatActivity() {

    private lateinit var capture: CaptureManager
    private lateinit var barcodeScannerView: DecoratedBarcodeView
    private var isFlashOn = false

    // 相册选择器
    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            parseQrCodeFromUri(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 使用淡入淡出动画，避免相机加载时的黑屏造成的视觉卡顿
        overridePendingTransition(R.anim.fade_in, R.anim.hold)
        setContentView(R.layout.activity_qr_scanner)

        barcodeScannerView = findViewById(R.id.barcode_scanner)

        // 初始化扫描管理器
        capture = CaptureManager(this, barcodeScannerView)
        capture.initializeFromIntent(intent, savedInstanceState)
        capture.decode()

        // 设置返回按钮
        findViewById<ImageButton>(R.id.btn_back).setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }

        // 设置相册按钮
        findViewById<ImageButton>(R.id.btn_gallery).setOnClickListener {
            galleryLauncher.launch(arrayOf("image/*"))
        }

        // 设置闪光灯按钮
        findViewById<ImageButton>(R.id.btn_flash).setOnClickListener {
            toggleFlash()
        }

        // 设置提示文字
        barcodeScannerView.setStatusText("")
    }

    private fun parseQrCodeFromUri(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val inputStream: InputStream? = contentResolver.openInputStream(uri)
                val bitmap = BitmapFactory.decodeStream(inputStream)

                if (bitmap != null) {
                    val result = decodeQRCode(bitmap)
                    withContext(Dispatchers.Main) {
                        if (result != null) {
                            val intent = Intent()
                            // ZXing 的 ScanIntentResult 使用 "SCAN_RESULT" 作为 key
                            intent.putExtra("SCAN_RESULT", result)
                            setResult(Activity.RESULT_OK, intent)
                            finish()
                        } else {
                            Toast.makeText(this@QrScannerActivity, getString(R.string.qr_scanner_no_qr_found), Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@QrScannerActivity, getString(R.string.qr_scanner_cannot_read_image), Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@QrScannerActivity, getString(R.string.profiles_import_failed) + ": ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun decodeQRCode(bitmap: Bitmap): String? {
        return try {
            val width = bitmap.width
            val height = bitmap.height
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

    private fun toggleFlash() {
        isFlashOn = !isFlashOn
        if (isFlashOn) {
            barcodeScannerView.setTorchOn()
            Toast.makeText(this, getString(R.string.qr_scanner_flash_on), Toast.LENGTH_SHORT).show()
        } else {
            barcodeScannerView.setTorchOff()
            Toast.makeText(this, getString(R.string.qr_scanner_flash_off), Toast.LENGTH_SHORT).show()
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
        const val EXTRA_RESULT = "scan_result"

        fun createIntent(activity: Activity): Intent {
            return Intent(activity, QrScannerActivity::class.java)
        }
    }
}
