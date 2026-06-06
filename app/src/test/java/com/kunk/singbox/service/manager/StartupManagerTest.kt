package com.kunk.singbox.service.manager

import com.kunk.singbox.model.DomainResolveConfig
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.SingBoxConfig
import com.kunk.singbox.model.TlsConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class StartupManagerTest {

    @Test
    fun resolveRuntimeLogLevelOnlyEnablesVerboseLogsInDebugMode() {
        assertEquals("error", StartupManager.resolveRuntimeLogLevel(debugLoggingEnabled = false))
        assertEquals("debug", StartupManager.resolveRuntimeLogLevel(debugLoggingEnabled = true))
    }

    @Test
    fun applyPrewarmedDomainIpsReplacesMatchingOutboundServers() {
        val config = SingBoxConfig(
            outbounds = listOf(
                Outbound(
                    type = "hysteria2",
                    tag = "hy2",
                    server = "hy2.example.com",
                    serverPort = 443,
                    tls = TlsConfig(enabled = true, serverName = "hy2.example.com")
                ),
                Outbound(type = "vless", tag = "vl", server = "vl.example.com", serverPort = 443)
            )
        )

        val patched = StartupManager.applyPrewarmedDomainIps(
            config,
            mapOf("hy2.example.com" to "1.2.3.4")
        )

        assertEquals("1.2.3.4", patched.outbounds?.get(0)?.server)
        assertEquals("vl.example.com", patched.outbounds?.get(1)?.server)
    }

    @Test
    fun applyPrewarmedDomainIpsSkipsIpLiteralsAndMissingEntries() {
        val config = SingBoxConfig(
            outbounds = listOf(
                Outbound(type = "hysteria2", tag = "hy2", server = "1.2.3.4", serverPort = 443),
                Outbound(type = "vless", tag = "vl", server = "vl.example.com", serverPort = 443)
            )
        )

        val patched = StartupManager.applyPrewarmedDomainIps(
            config,
            mapOf("other.example.com" to "5.6.7.8")
        )

        assertEquals("1.2.3.4", patched.outbounds?.get(0)?.server)
        assertEquals("vl.example.com", patched.outbounds?.get(1)?.server)
    }

    @Test
    fun applyPrewarmedDomainIpsSkipsTlsOutboundsWithoutExplicitRemoteIdentity() {
        val config = SingBoxConfig(
            outbounds = listOf(
                Outbound(
                    type = "hysteria2",
                    tag = "hy2",
                    server = "hy2.example.com",
                    serverPort = 443,
                    tls = TlsConfig(enabled = true)
                )
            )
        )

        val patched = StartupManager.applyPrewarmedDomainIps(
            config,
            mapOf("hy2.example.com" to "1.2.3.4")
        )

        assertEquals("hy2.example.com", patched.outbounds?.firstOrNull()?.server)
    }

    @Test
    fun applyPrewarmedDomainIpsSkipsOutboundsWithDomainResolver() {
        val config = SingBoxConfig(
            outbounds = listOf(
                Outbound(
                    type = "vless",
                    tag = "airport-node",
                    server = "fly-nnca.bestvmr.com",
                    serverPort = 443,
                    tls = TlsConfig(enabled = true, serverName = "fly-nnca.bestvmr.com"),
                    domainResolver = DomainResolveConfig(server = "airport-dns")
                )
            )
        )

        val patched = StartupManager.applyPrewarmedDomainIps(
            config,
            mapOf("fly-nnca.bestvmr.com" to "1.2.3.4")
        )

        assertEquals("fly-nnca.bestvmr.com", patched.outbounds?.firstOrNull()?.server)
    }
}
