package com.kunk.singbox.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okio.Timeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.reflect.KClass

class NetworkClientCancellationTest {

    @Test
    fun executeCancellableCancelsCallWhenCoroutineIsCancelled() = runBlocking {
        val started = CountDownLatch(1)
        val cancellation = CompletableDeferred<CancellationException>()
        val call = FakeCall {
            started.countDown()
            awaitCancel()
            throw IOException("Canceled")
        }

        val job = launch(Dispatchers.IO) {
            try {
                NetworkClient.executeCancellable(call) { response ->
                    response.code
                }
            } catch (e: CancellationException) {
                cancellation.complete(e)
                throw e
            }
        }

        assertTrue(started.await(5, TimeUnit.SECONDS))

        job.cancel()
        cancellation.await()
        job.join()

        assertTrue(call.isCanceled())
    }

    @Test
    fun executeCancellableKeepsOriginalIOExceptionWhenCoroutineIsActive() = runBlocking {
        val failure = IOException("Network failed")
        val call = FakeCall {
            throw failure
        }

        val thrown = try {
            NetworkClient.executeCancellable(call) { response ->
                response.code
            }
            null
        } catch (e: IOException) {
            e
        }

        assertEquals(failure.message, thrown?.message)
    }

    @Test
    fun executeCancellableReturnsBlockResultForSuccessfulResponse() = runBlocking {
        val call = FakeCall {
            Response.Builder()
                .request(request())
                .protocol(Protocol.HTTP_1_1)
                .code(204)
                .message("No Content")
                .build()
        }

        val code = NetworkClient.executeCancellable(call) { response ->
            response.code
        }

        assertTrue(call.isExecuted())
        assertEquals(204, code)
    }

    private class FakeCall(
        private val request: Request = Request.Builder().url("https://example.com").build(),
        private val executeBlock: FakeCall.() -> Response
    ) : Call {
        private val executed = AtomicBoolean(false)
        private val canceled = AtomicBoolean(false)
        private val canceledLatch = CountDownLatch(1)

        override fun request(): Request = request

        override fun execute(): Response {
            executed.set(true)
            return executeBlock()
        }

        override fun enqueue(responseCallback: Callback) {
            throw UnsupportedOperationException("enqueue is not used in this test")
        }

        override fun addEventListener(eventListener: EventListener) = Unit

        override fun cancel() {
            canceled.set(true)
            canceledLatch.countDown()
        }

        override fun isExecuted(): Boolean = executed.get()

        override fun isCanceled(): Boolean = canceled.get()

        override fun timeout(): Timeout = Timeout.NONE

        override fun clone(): Call = FakeCall(request, executeBlock)

        override fun <T : Any> tag(type: KClass<T>): T? = null

        override fun <T> tag(type: Class<out T>): T? = null

        override fun <T : Any> tag(type: KClass<T>, computeIfAbsent: () -> T): T = computeIfAbsent()

        override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T = computeIfAbsent()

        fun awaitCancel() {
            assertTrue(canceledLatch.await(5, TimeUnit.SECONDS))
        }
    }
}
