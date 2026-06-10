package com.kunk.singbox.core

import android.net.DnsResolver
import android.os.Build
import android.os.CancellationSignal
import android.system.ErrnoException
import com.kunk.singbox.utils.DefaultNetworkListener
import io.nekohasekai.libbox.ExchangeContext
import io.nekohasekai.libbox.LocalDNSTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

object LocalResolverImpl : LocalDNSTransport {
    private const val RCODE_SERVFAIL = 2
    private const val RCODE_NXDOMAIN = 3

    override fun raw(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }

    override fun lookup(ctx: ExchangeContext, network: String, domain: String) {
        val defaultNetwork = DefaultNetworkListener.underlyingNetwork
        if (defaultNetwork == null) {
            ctx.errorCode(RCODE_SERVFAIL)
            return
        }

        runBlocking {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                lookupWithDnsResolver(ctx, defaultNetwork, network, domain)
            } else {
                lookupWithNetworkGetAllByName(ctx, defaultNetwork, domain)
            }
        }
    }

    override fun exchange(ctx: ExchangeContext, message: ByteArray) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            ctx.errorCode(RCODE_SERVFAIL)
            return
        }
        val defaultNetwork = DefaultNetworkListener.underlyingNetwork
        if (defaultNetwork == null) {
            ctx.errorCode(RCODE_SERVFAIL)
            return
        }

        runBlocking {
            exchangeWithDnsResolver(ctx, defaultNetwork, message)
        }
    }

    private suspend fun lookupWithDnsResolver(
        ctx: ExchangeContext,
        defaultNetwork: android.net.Network,
        network: String,
        domain: String
    ) {
        suspendCoroutine { continuation ->
            val signal = CancellationSignal()
            ctx.onCancel(signal::cancel)
            val callback = object : DnsResolver.Callback<Collection<InetAddress>> {
                override fun onAnswer(answer: Collection<InetAddress>, rcode: Int) {
                    if (rcode == 0) {
                        ctx.success(answer.mapNotNull { it.hostAddress }.joinToString("\n"))
                    } else {
                        ctx.errorCode(rcode)
                    }
                    continuation.resume(Unit)
                }

                override fun onError(error: DnsResolver.DnsException) {
                    handleDnsError(ctx, error)
                    continuation.resume(Unit)
                }
            }
            val queryType = when {
                network.endsWith("4") -> DnsResolver.TYPE_A
                network.endsWith("6") -> DnsResolver.TYPE_AAAA
                else -> null
            }
            if (queryType != null) {
                DnsResolver.getInstance().query(
                    defaultNetwork,
                    domain,
                    queryType,
                    DnsResolver.FLAG_NO_RETRY,
                    Dispatchers.IO.asExecutor(),
                    signal,
                    callback
                )
            } else {
                DnsResolver.getInstance().query(
                    defaultNetwork,
                    domain,
                    DnsResolver.FLAG_NO_RETRY,
                    Dispatchers.IO.asExecutor(),
                    signal,
                    callback
                )
            }
        }
    }

    private fun lookupWithNetworkGetAllByName(
        ctx: ExchangeContext,
        defaultNetwork: android.net.Network,
        domain: String
    ) {
        val answer = try {
            defaultNetwork.getAllByName(domain)
        } catch (_: UnknownHostException) {
            ctx.errorCode(RCODE_NXDOMAIN)
            return
        }
        ctx.success(answer.mapNotNull { it.hostAddress }.joinToString("\n"))
    }

    private suspend fun exchangeWithDnsResolver(
        ctx: ExchangeContext,
        defaultNetwork: android.net.Network,
        message: ByteArray
    ) {
        suspendCoroutine { continuation ->
            val signal = CancellationSignal()
            ctx.onCancel(signal::cancel)
            val callback = object : DnsResolver.Callback<ByteArray> {
                override fun onAnswer(answer: ByteArray, rcode: Int) {
                    if (rcode == 0) {
                        ctx.rawSuccess(answer)
                    } else {
                        ctx.errorCode(rcode)
                    }
                    continuation.resume(Unit)
                }

                override fun onError(error: DnsResolver.DnsException) {
                    handleDnsError(ctx, error)
                    continuation.resume(Unit)
                }
            }
            DnsResolver.getInstance().rawQuery(
                defaultNetwork,
                message,
                DnsResolver.FLAG_NO_RETRY,
                Dispatchers.IO.asExecutor(),
                signal,
                callback
            )
        }
    }

    private fun handleDnsError(ctx: ExchangeContext, error: DnsResolver.DnsException) {
        when (val cause = error.cause) {
            is ErrnoException -> ctx.errnoCode(cause.errno)
            else -> ctx.errorCode(RCODE_SERVFAIL)
        }
    }
}
