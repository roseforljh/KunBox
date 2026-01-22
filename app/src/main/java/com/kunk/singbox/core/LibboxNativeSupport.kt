package com.kunk.singbox.core

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object LibboxNativeSupport {
    private const val LIB_NAME = "libbox.so"
    private val cache = ConcurrentHashMap<String, Boolean>()
    @Volatile private var libBytes: ByteArray? = null

    fun hasSymbol(context: Context, symbol: String): Boolean {
        return cache[symbol] ?: run {
            val bytes = libBytes ?: loadLibBytes(context).also { libBytes = it }
            val found = bytes != null && bytes.containsAscii(symbol)
            cache[symbol] = found
            found
        }
    }

    private fun loadLibBytes(context: Context): ByteArray? {
        val dir = context.applicationInfo.nativeLibraryDir ?: return null
        val file = File(dir, LIB_NAME)
        if (!file.exists() || !file.canRead()) return null
        return try {
            file.readBytes()
        } catch (_: Exception) {
            null
        }
    }

    private fun ByteArray.containsAscii(needle: String): Boolean {
        if (needle.isEmpty() || needle.length > size) return false
        val target = needle.toByteArray(Charsets.US_ASCII)
        outer@ for (i in 0..(size - target.size)) {
            for (j in target.indices) {
                if (this[i + j] != target[j]) continue@outer
            }
            return true
        }
        return false
    }
}
