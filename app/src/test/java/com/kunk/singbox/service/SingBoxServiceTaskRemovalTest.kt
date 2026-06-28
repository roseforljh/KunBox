package com.kunk.singbox.service

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class SingBoxServiceTaskRemovalTest {

    @Test
    fun vpnCoreServicesStayRunningWhenTaskIsRemoved() {
        val manifest = readManifest()

        assertTrue(serviceDeclaresStopWithTaskFalse(manifest, ".service.SingBoxService"))
        assertTrue(serviceDeclaresStopWithTaskFalse(manifest, ".service.ProxyOnlyService"))
    }

    @Test
    fun manifestDeclaresLocalNetworkPermission() {
        val manifest = readManifest()

        assertTrue(manifestDeclaresPermission(manifest, "android.permission.ACCESS_LOCAL_NETWORK"))
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

    private fun readManifest(): org.w3c.dom.Document =
        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(File("src/main/AndroidManifest.xml"))

    private fun manifestDeclaresPermission(
        manifest: org.w3c.dom.Document,
        permissionName: String
    ): Boolean {
        val androidNamespace = "http://schemas.android.com/apk/res/android"
        val permissions = manifest.getElementsByTagName("uses-permission")
        for (index in 0 until permissions.length) {
            val permission = permissions.item(index) as? org.w3c.dom.Element ?: continue
            if (permission.getAttributeNS(androidNamespace, "name") == permissionName) {
                return true
            }
        }
        return false
    }

    private fun serviceDeclaresStopWithTaskFalse(
        manifest: org.w3c.dom.Document,
        serviceName: String
    ): Boolean {
        val androidNamespace = "http://schemas.android.com/apk/res/android"
        val services = manifest.getElementsByTagName("service")
        for (index in 0 until services.length) {
            val service = services.item(index) as? org.w3c.dom.Element ?: continue
            if (service.getAttributeNS(androidNamespace, "name") == serviceName) {
                return service.getAttributeNS(androidNamespace, "stopWithTask") == "false"
            }
        }
        return false
    }

    private fun serviceDeclaresForegroundServiceType(
        manifest: org.w3c.dom.Document,
        serviceName: String,
        foregroundServiceType: String
    ): Boolean {
        val androidNamespace = "http://schemas.android.com/apk/res/android"
        val service = findService(manifest, serviceName) ?: return false
        return service.getAttributeNS(androidNamespace, "foregroundServiceType") == foregroundServiceType
    }

    private fun serviceDeclaresProperty(
        manifest: org.w3c.dom.Document,
        serviceName: String,
        propertyName: String,
        propertyValue: String
    ): Boolean {
        val androidNamespace = "http://schemas.android.com/apk/res/android"
        val service = findService(manifest, serviceName) ?: return false
        val properties = service.getElementsByTagName("property")
        for (index in 0 until properties.length) {
            val property = properties.item(index) as? org.w3c.dom.Element ?: continue
            if (
                property.getAttributeNS(androidNamespace, "name") == propertyName &&
                property.getAttributeNS(androidNamespace, "value") == propertyValue
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
        val androidNamespace = "http://schemas.android.com/apk/res/android"
        val services = manifest.getElementsByTagName("service")
        for (index in 0 until services.length) {
            val service = services.item(index) as? org.w3c.dom.Element ?: continue
            if (service.getAttributeNS(androidNamespace, "name") == serviceName) {
                return service
            }
        }
        return null
    }
}
