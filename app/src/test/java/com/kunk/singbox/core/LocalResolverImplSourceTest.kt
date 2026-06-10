package com.kunk.singbox.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocalResolverImplSourceTest {

    @Test
    fun localResolverPerformsPlatformDnsLookupInsteadOfImmediateServfail() {
        val source = File("src/main/java/com/kunk/singbox/core/LocalResolverImpl.kt").readText()

        assertTrue(source.contains("ctx.success("))
        assertTrue(source.contains("DnsResolver.getInstance()") || source.contains("getAllByName(domain)"))
        assertFalse(
            source.contains(
                "override fun lookup(ctx: ExchangeContext, network: String, domain: String) {\n" +
                    "        ctx.errorCode(2)\n" +
                    "    }"
            )
        )
    }
}
