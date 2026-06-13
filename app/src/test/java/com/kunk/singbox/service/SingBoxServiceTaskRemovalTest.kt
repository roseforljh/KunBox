package com.kunk.singbox.service

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class SingBoxServiceTaskRemovalTest {

    @Test
    fun vpnCoreServicesStayRunningWhenTaskIsRemoved() {
        val manifest = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(File("src/main/AndroidManifest.xml"))

        assertTrue(serviceDeclaresStopWithTaskFalse(manifest, ".service.SingBoxService"))
        assertTrue(serviceDeclaresStopWithTaskFalse(manifest, ".service.ProxyOnlyService"))
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
}
