package com.kunk.singbox.core

import android.util.Log
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.StringIterator
import java.lang.reflect.Method

/**
 *
 */
@Suppress("TooManyFunctions")
object LibboxCompat {
    private const val TAG = "LibboxCompat"

    private var resetAllConnectionsMethod: Method? = null
    private var resetAllConnectionsChecked = false

    private val hasSelectorMethod by lazy(LazyThreadSafetyMode.NONE) {
        findMethod("hasSelector")
    }
    private val selectOutboundByTagMethod by lazy(LazyThreadSafetyMode.NONE) {
        findMethod("selectOutboundByTag", String::class.java)
    }
    private val getSelectedOutboundMethod by lazy(LazyThreadSafetyMode.NONE) {
        findMethod("getSelectedOutbound")
    }
    private val listOutboundsStringMethod by lazy(LazyThreadSafetyMode.NONE) {
        findMethod("listOutboundsString")
    }
    private val pauseServiceMethod by lazy(LazyThreadSafetyMode.NONE) {
        findMethod("pauseService")
    }
    private val resumeServiceMethod by lazy(LazyThreadSafetyMode.NONE) {
        findMethod("resumeService")
    }
    private val isPausedMethod by lazy(LazyThreadSafetyMode.NONE) {
        findMethod("isPaused")
    }
    private val getTrafficTotalUplinkMethod by lazy(LazyThreadSafetyMode.NONE) {
        findMethod("getTrafficTotalUplink")
    }
    private val getTrafficTotalDownlinkMethod by lazy(LazyThreadSafetyMode.NONE) {
        findMethod("getTrafficTotalDownlink")
    }
    private val resetTrafficStatsMethod by lazy(LazyThreadSafetyMode.NONE) {
        findMethod("resetTrafficStats")
    }
    private val getTrafficByOutboundMethod by lazy(LazyThreadSafetyMode.NONE) {
        findMethod("getTrafficByOutbound")
    }
    private val setAndroidPackageNameMethod by lazy(LazyThreadSafetyMode.NONE) {
        findConnectionOwnerMethod("setAndroidPackageName", String::class.java)
    }
    private val setAndroidPackageNamesMethod by lazy(LazyThreadSafetyMode.NONE) {
        findConnectionOwnerMethod("setAndroidPackageNames", StringIterator::class.java)
    }

    @Volatile
    var hasResetAllConnections: Boolean = false
        private set

    @Volatile
    var hasExtensionApi: Boolean = false
        private set

    init {
        detectAvailableApis()
    }

    private fun detectAvailableApis() {
        resetAllConnectionsMethod = try {
            Libbox::class.java.getMethod("resetAllConnections", Boolean::class.javaPrimitiveType).also {
                hasResetAllConnections = true
                Log.i(TAG, "Detected Libbox.resetAllConnections(boolean)")
            }
        } catch (e: NoSuchMethodException) {
            try {
                Libbox::class.java.getMethod("ResetAllConnections", Boolean::class.javaPrimitiveType).also {
                    hasResetAllConnections = true
                    Log.i(TAG, "Detected Libbox.ResetAllConnections(boolean)")
                }
            } catch (e2: NoSuchMethodException) {
                Log.d(TAG, "Libbox.resetAllConnections not available")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error detecting resetAllConnections: ${e.message}")
            null
        }
        resetAllConnectionsChecked = true
        hasExtensionApi = try {
            Libbox::class.java.getMethod("getKunBoxVersion")
            Log.i(TAG, "Detected extension API")
            true
        } catch (e: Exception) {
            Log.d(TAG, "Extension API not available")
            false
        }
    }

    private fun findMethod(name: String, vararg parameterTypes: Class<*>): Method? {
        return runCatching {
            Libbox::class.java.getMethod(name, *parameterTypes)
        }.getOrNull()
    }

    private fun findConnectionOwnerMethod(name: String, vararg parameterTypes: Class<*>): Method? {
        return runCatching {
            ConnectionOwner::class.java.getMethod(name, *parameterTypes)
        }.getOrNull()
    }

    private fun invokeBoolean(method: Method?, vararg args: Any?): Boolean? {
        if (method == null) return null
        return runCatching {
            method.invoke(null, *args) as? Boolean
        }.onFailure {
            Log.w(TAG, "Failed to invoke ${method.name}: ${it.message}")
        }.getOrNull()
    }

    private fun invokeLong(method: Method?, vararg args: Any?): Long? {
        if (method == null) return null
        return runCatching {
            (method.invoke(null, *args) as? Number)?.toLong()
        }.onFailure {
            Log.w(TAG, "Failed to invoke ${method.name}: ${it.message}")
        }.getOrNull()
    }

    private fun invokeString(method: Method?, vararg args: Any?): String? {
        if (method == null) return null
        return runCatching {
            method.invoke(null, *args) as? String
        }.onFailure {
            Log.w(TAG, "Failed to invoke ${method.name}: ${it.message}")
        }.getOrNull()
    }

    /**
     */
    fun resetAllConnections(system: Boolean = true): Boolean {
        if (BoxWrapperManager.isAvailable()) {
            return BoxWrapperManager.resetAllConnections(system)
        }

        val method = resetAllConnectionsMethod ?: return false

        return try {
            method.invoke(null, system)
            Log.i(TAG, "Called Libbox.resetAllConnections($system)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to call resetAllConnections: ${e.message}")
            false
        }
    }

    fun getVersion(): String {
        return try {
            Libbox.version()
        } catch (e: Exception) {
            "unknown"
        }
    }

    /**
     * 鑾峰彇鎵╁睍鐗堟湰
     */
    fun getExtensionVersion(): String {
        return try {
            Libbox.getKunBoxVersion()
        } catch (e: Exception) {
            "N/A"
        }
    }

    fun hasExtendedLibbox(): Boolean = hasResetAllConnections

    fun hasKunBoxExtension(): Boolean = hasExtensionApi

    fun hasSelector(): Boolean {
        return invokeBoolean(hasSelectorMethod) ?: false
    }

    fun selectOutboundByTag(nodeTag: String): Boolean {
        return invokeBoolean(selectOutboundByTagMethod, nodeTag) ?: false
    }

    fun getSelectedOutbound(): String? {
        return invokeString(getSelectedOutboundMethod)?.takeIf { it.isNotBlank() }
    }

    fun listOutboundsString(): String? {
        return invokeString(listOutboundsStringMethod)
    }

    fun pauseService(): Boolean {
        val method = pauseServiceMethod ?: return false
        return runCatching {
            method.invoke(null)
            true
        }.onFailure {
            Log.w(TAG, "Failed to invoke ${method.name}: ${it.message}")
        }.getOrDefault(false)
    }

    fun resumeService(): Boolean {
        val method = resumeServiceMethod ?: return false
        return runCatching {
            method.invoke(null)
            true
        }.onFailure {
            Log.w(TAG, "Failed to invoke ${method.name}: ${it.message}")
        }.getOrDefault(false)
    }

    fun isPaused(): Boolean {
        return invokeBoolean(isPausedMethod) ?: false
    }

    fun getTrafficTotalUplink(): Long {
        return invokeLong(getTrafficTotalUplinkMethod) ?: -1L
    }

    fun getTrafficTotalDownlink(): Long {
        return invokeLong(getTrafficTotalDownlinkMethod) ?: -1L
    }

    fun resetTrafficStats(): Boolean {
        return invokeBoolean(resetTrafficStatsMethod) ?: false
    }

    fun getTrafficByOutbound(): Map<String, Pair<Long, Long>> {
        val method = getTrafficByOutboundMethod ?: return emptyMap()
        return runCatching {
            val iterator = method.invoke(null) ?: return emptyMap()
            val iteratorClass = iterator.javaClass
            val hasNextMethod = iteratorClass.getMethod("hasNext")
            val nextMethod = iteratorClass.getMethod("next")
            buildMap {
                while ((hasNextMethod.invoke(iterator) as? Boolean) == true) {
                    val item = nextMethod.invoke(iterator) ?: continue
                    val itemClass = item.javaClass
                    val tag = itemClass.getMethod("getTag").invoke(item) as? String
                    val upload = (itemClass.getMethod("getUpload").invoke(item) as? Number)?.toLong()
                    val download = (itemClass.getMethod("getDownload").invoke(item) as? Number)?.toLong()
                    if (!tag.isNullOrBlank() && upload != null && download != null) {
                        put(tag, upload to download)
                    }
                }
            }
        }.onFailure {
            Log.w(TAG, "Failed to read outbound traffic: ${it.message}")
        }.getOrDefault(emptyMap())
    }

    fun setConnectionOwnerPackageName(owner: ConnectionOwner, packageName: String) {
        if (packageName.isBlank()) return
        val stringSetter = setAndroidPackageNameMethod
        if (stringSetter != null) {
            runCatching {
                stringSetter.invoke(owner, packageName)
            }.onFailure {
                Log.w(TAG, "Failed to set androidPackageName: ${it.message}")
            }
            return
        }

        val iteratorSetter = setAndroidPackageNamesMethod ?: return
        runCatching {
            iteratorSetter.invoke(owner, singleValueIterator(packageName))
        }.onFailure {
            Log.w(TAG, "Failed to set androidPackageNames: ${it.message}")
        }
    }

    private fun singleValueIterator(value: String): StringIterator {
        return object : StringIterator {
            private var consumed = false

            override fun hasNext(): Boolean = !consumed

            override fun len(): Int = 1

            override fun next(): String {
                consumed = true
                return value
            }
        }
    }

    fun isNaiveQuicSupported(): Boolean {
        val versionText = getVersion()
        val normalized = versionText.removePrefix("v")
        val head = normalized.substringBefore('-')
        val parts = head.split(".")

        val major = parts.getOrNull(0)?.toIntOrNull()
        val minor = parts.getOrNull(1)?.toIntOrNull()

        return parts.size < 3 || major == null || minor == null ||
            major > 1 || (major == 1 && minor >= 13)
    }

    fun printDiagnostics() {
        Log.i(TAG, "LibboxCompat: version=${getVersion()}, extensionVersion=${getExtensionVersion()}, hasResetAllConnections=$hasResetAllConnections, hasExtensionApi=$hasExtensionApi")
    }
}
