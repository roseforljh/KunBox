package com.kunk.singbox.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class SingBoxServiceTaskRemovalTest {

    @Test
    fun taskRemovalUsesExplicitStopWhileKeepingSystemCallbackEnabled() {
        val manifest = readManifest()
        val vpnSource = File("src/main/java/com/kunk/singbox/service/SingBoxService.kt")
            .readText(Charsets.UTF_8)
        val proxySource = File("src/main/java/com/kunk/singbox/service/ProxyOnlyService.kt")
            .readText(Charsets.UTF_8)
        val vpnTaskRemoved = vpnSource.functionBody("override fun onTaskRemoved(")
        val proxyTaskRemoved = proxySource.functionBody("override fun onTaskRemoved(")

        // stopWithTask=false 让系统先回调 onTaskRemoved，再走完整 ACTION_STOP 收尾。
        assertTrue(serviceDeclaresStopWithTaskFalse(manifest, ".service.SingBoxService"))
        assertTrue(serviceDeclaresStopWithTaskFalse(manifest, ".service.ProxyOnlyService"))
        assertTrue(vpnTaskRemoved.contains("VpnServiceManager.stopVpn(applicationContext)"))
        assertTrue(proxyTaskRemoved.contains("VpnServiceManager.stopVpn(applicationContext)"))
    }

    @Test
    fun manifestDeclaresLocalNetworkPermission() {
        val manifest = readManifest()

        assertTrue(manifestDeclaresPermission(manifest, "android.permission.ACCESS_LOCAL_NETWORK"))
    }

    @Test
    fun manifestDeclaresLocalNetworkPermissionExactlyOnceWithoutMaxSdk() {
        val permissions = findPermissionElements(readManifest(), "android.permission.ACCESS_LOCAL_NETWORK")

        assertEquals(1, permissions.size)
        assertFalse(permissions.single().hasAttributeNS(ANDROID_NAMESPACE, "maxSdkVersion"))
    }

    @Test
    fun manifestDeclaresSpecialUseForegroundServicePermissions() {
        val manifest = readManifest()

        assertTrue(manifestDeclaresPermission(manifest, "android.permission.FOREGROUND_SERVICE"))
        assertTrue(manifestDeclaresPermission(manifest, "android.permission.FOREGROUND_SERVICE_SPECIAL_USE"))
    }

    @Test
    fun singBoxServiceUsesSpecialUseForegroundService() {
        val manifest = readManifest()

        assertTrue(serviceDeclaresForegroundServiceType(manifest, ".service.SingBoxService", "specialUse"))
        assertTrue(
            serviceDeclaresProperty(
                manifest = manifest,
                serviceName = ".service.SingBoxService",
                propertyName = "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE",
                propertyValue = "vpn"
            )
        )
    }

    @Test
    fun proxyOnlyServiceUsesSpecialUseForegroundService() {
        val manifest = readManifest()

        assertTrue(serviceDeclaresForegroundServiceType(manifest, ".service.ProxyOnlyService", "specialUse"))
        assertTrue(
            serviceDeclaresProperty(
                manifest = manifest,
                serviceName = ".service.ProxyOnlyService",
                propertyName = "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE",
                propertyValue = "proxy_only"
            )
        )
    }

    @Test
    fun taskRemovalStopMarksVpnAsManuallyStopped() {
        val source = File("src/main/java/com/kunk/singbox/service/SingBoxService.kt")
            .readText(Charsets.UTF_8)
        val stopBranch = source
            .substringAfter("SingBoxService.ACTION_STOP ->")
            .substringBefore("SingBoxService.ACTION_SWITCH_NODE ->")

        assertTrue(stopBranch.contains("VpnStateStore.setManuallyStopped(true)"))
        assertTrue(stopBranch.contains("stopVpn(stopService = true)"))
    }

    private fun String.functionBody(startToken: String): String {
        val start = indexOf(startToken)
        require(start >= 0) { "未找到 $startToken" }
        val openingBrace = indexOf('{', start)
        require(openingBrace >= 0) { "$startToken 缺少函数体" }
        var depth = 0
        for (index in openingBrace until length) {
            when (this[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return substring(start, index + 1)
            }
        }
        error("$startToken 函数体未闭合")
    }

    private fun readManifest(): org.w3c.dom.Document =
        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(File("src/main/AndroidManifest.xml"))

    private fun manifestDeclaresPermission(
        manifest: org.w3c.dom.Document,
        permissionName: String
    ): Boolean {
        return findPermissionElements(manifest, permissionName).isNotEmpty()
    }

    private fun findPermissionElements(
        manifest: org.w3c.dom.Document,
        permissionName: String
    ): List<org.w3c.dom.Element> {
        val permissions = manifest.getElementsByTagName("uses-permission")
        val matches = mutableListOf<org.w3c.dom.Element>()
        for (index in 0 until permissions.length) {
            val permission = permissions.item(index) as? org.w3c.dom.Element ?: continue
            if (permission.getAttributeNS(ANDROID_NAMESPACE, "name") == permissionName) {
                matches += permission
            }
        }
        return matches
    }

    private fun serviceDeclaresStopWithTaskFalse(
        manifest: org.w3c.dom.Document,
        serviceName: String
    ): Boolean {
        val services = manifest.getElementsByTagName("service")
        for (index in 0 until services.length) {
            val service = services.item(index) as? org.w3c.dom.Element ?: continue
            if (service.getAttributeNS(ANDROID_NAMESPACE, "name") == serviceName) {
                return service.getAttributeNS(ANDROID_NAMESPACE, "stopWithTask") == "false"
            }
        }
        return false
    }

    private fun serviceDeclaresForegroundServiceType(
        manifest: org.w3c.dom.Document,
        serviceName: String,
        foregroundServiceType: String
    ): Boolean {
        val service = findService(manifest, serviceName) ?: return false
        return service.getAttributeNS(ANDROID_NAMESPACE, "foregroundServiceType") == foregroundServiceType
    }

    private fun serviceDeclaresProperty(
        manifest: org.w3c.dom.Document,
        serviceName: String,
        propertyName: String,
        propertyValue: String
    ): Boolean {
        val service = findService(manifest, serviceName) ?: return false
        val properties = service.getElementsByTagName("property")
        for (index in 0 until properties.length) {
            val property = properties.item(index) as? org.w3c.dom.Element ?: continue
            if (
                property.getAttributeNS(ANDROID_NAMESPACE, "name") == propertyName &&
                property.getAttributeNS(ANDROID_NAMESPACE, "value") == propertyValue
            ) {
                return true
            }
        }
        return false
    }

    private fun findService(
        manifest: org.w3c.dom.Document,
        serviceName: String
    ): org.w3c.dom.Element? {
        val services = manifest.getElementsByTagName("service")
        for (index in 0 until services.length) {
            val service = services.item(index) as? org.w3c.dom.Element ?: continue
            if (service.getAttributeNS(ANDROID_NAMESPACE, "name") == serviceName) {
                return service
            }
        }
        return null
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
