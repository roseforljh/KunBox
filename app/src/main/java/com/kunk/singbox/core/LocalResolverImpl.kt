package com.kunk.singbox.core

import io.nekohasekai.libbox.ExchangeContext
import io.nekohasekai.libbox.LocalDNSTransport

object LocalResolverImpl : LocalDNSTransport {
    override fun raw(): Boolean {
        return false
    }

    override fun lookup(ctx: ExchangeContext, network: String, domain: String) {
        ctx.errorCode(2)
    }

    override fun exchange(ctx: ExchangeContext, message: ByteArray) {
    }
}
