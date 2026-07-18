package com.kunk.singbox.utils.dns

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

/**
 * DnsResolver 单元测试
 */
class DnsResolverTest {

    // ==================== IP 地址检测 ====================

    @Test
    fun testIsIpAddress_ipv4() {
        assertTrue(DnsResolver.isIpAddress("1.2.3.4"))
        assertTrue(DnsResolver.isIpAddress("192.168.1.1"))
        assertTrue(DnsResolver.isIpAddress("255.255.255.255"))
        assertTrue(DnsResolver.isIpAddress("0.0.0.0"))
    }

    @Test
    fun testIsIpAddress_ipv6() {
        assertTrue(DnsResolver.isIpAddress("::1"))
        assertTrue(DnsResolver.isIpAddress("2001:db8::1"))
        assertTrue(DnsResolver.isIpAddress("fe80::1"))
        assertTrue(DnsResolver.isIpAddress("2001:0db8:85a3:0000:0000:8a2e:0370:7334"))
    }

    @Test
    fun testIsIpAddress_domain() {
        assertFalse(DnsResolver.isIpAddress("example.com"))
        assertFalse(DnsResolver.isIpAddress("www.google.com"))
        assertFalse(DnsResolver.isIpAddress("sub.domain.example.org"))
        assertFalse(DnsResolver.isIpAddress("localhost"))
    }

    @Test
    fun testIsIpAddress_edgeCases() {
        assertFalse(DnsResolver.isIpAddress(""))
        assertFalse(DnsResolver.isIpAddress("1.2.3"))
        assertFalse(DnsResolver.isIpAddress("1.2.3.4.5"))
        assertFalse(DnsResolver.isIpAddress("abc.def.ghi.jkl"))
    }

    // ==================== DoH 服务器常量 ====================

    @Test
    fun testDohServerConstants() {
        assertEquals("https://1.1.1.1/dns-query", DnsResolver.DOH_CLOUDFLARE)
        assertEquals("https://8.8.8.8/dns-query", DnsResolver.DOH_GOOGLE)
        assertEquals("https://223.5.5.5/dns-query", DnsResolver.DOH_ALIDNS)
    }

    // ==================== DnsResolveResult ====================

    @Test
    fun testDnsResolveResult_success() {
        val result = DnsResolveResult(ip = "1.2.3.4", source = "doh")
        assertTrue(result.isSuccess)
        assertEquals("1.2.3.4", result.ip)
        assertEquals("doh", result.source)
        assertNull(result.error)
    }

    @Test
    fun testDnsResolveResult_failure() {
        val result = DnsResolveResult(ip = null, source = "doh", error = "Timeout")
        assertFalse(result.isSuccess)
        assertNull(result.ip)
        assertEquals("Timeout", result.error)
    }

    @Test
    fun testDnsResolveResult_directIp() {
        val result = DnsResolveResult(ip = "192.168.1.1", source = "direct")
        assertTrue(result.isSuccess)
        assertEquals("direct", result.source)
    }

    @Test
    fun testParseDnsResponseReturnsIpv6WhenOnlyAaaaExists() {
        val response = byteArrayOf(
            0x12, 0x34,
            0x81.toByte(), 0x80.toByte(),
            0x00, 0x01,
            0x00, 0x01,
            0x00, 0x00,
            0x00, 0x00,
            0x07, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(),
            'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
            0x03, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
            0x00,
            0x00, 0x1c,
            0x00, 0x01,
            0xc0.toByte(), 0x0c,
            0x00, 0x1c,
            0x00, 0x01,
            0x00, 0x00, 0x00, 0x3c,
            0x00, 0x10,
            0x20, 0x01, 0x0d, 0xb8.toByte(),
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x01
        )

        assertEquals("2001:db8:0:0:0:0:0:1", DnsResolver().parseDnsResponseForTest(response))
    }

    @Test
    fun testResolveBatchReturnsEmptyMapWhenInputsAreOnlyIps() = runBlocking {
        val resolver = DnsResolver()

        val results = resolver.resolveBatch(
            domains = listOf("1.1.1.1", "8.8.8.8", "::1", "1.1.1.1")
        )

        assertTrue(results.isEmpty())
    }
}
