package com.kunk.singbox.service.manager

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class ServiceStateHolderRecoveryIntentTest {
    @Before
    fun setUp() {
        resetRecoveryIntent()
    }

    @After
    fun tearDown() {
        resetRecoveryIntent()
    }

    private fun resetRecoveryIntent() {
        val lease = ServiceStateHolder.setRecoveryIntentOnFailure(false)
        checkNotNull(ServiceStateHolder.consumeRecoveryIntentOnFailure(lease))
    }

    private fun createBaseline(): RecoveryIntentLease {
        val startLease = ServiceStateHolder.setRecoveryIntentOnFailure(false)
        return checkNotNull(ServiceStateHolder.completeRecoveryIntentOnSuccess(startLease))
    }

    @Test
    fun `resource recovery cannot claim without running baseline`() {
        assertNull(ServiceStateHolder.claimResourceRecoveryIntent(Any(), attemptId = 7L))
    }

    @Test
    fun `resource recovery owner can clear its preserve marker`() {
        val owner = Any()
        createBaseline()
        val lease = checkNotNull(ServiceStateHolder.claimResourceRecoveryIntent(owner, attemptId = 7L))

        assertTrue(ServiceStateHolder.preserveRecoveryIntentOnFailure)
        assertTrue(ServiceStateHolder.clearResourceRecoveryIntent(owner, attemptId = 7L, lease = lease))
        assertFalse(ServiceStateHolder.preserveRecoveryIntentOnFailure)
    }

    @Test
    fun `different resource owner cannot clear preserve marker`() {
        val owner = Any()
        createBaseline()
        val lease = checkNotNull(ServiceStateHolder.claimResourceRecoveryIntent(owner, attemptId = 7L))

        assertFalse(ServiceStateHolder.clearResourceRecoveryIntent(Any(), attemptId = 7L, lease = lease))
        assertTrue(ServiceStateHolder.preserveRecoveryIntentOnFailure)
    }

    @Test
    fun `external recovery intent supersedes resource ownership`() {
        val resourceOwner = Any()
        createBaseline()
        val resourceLease = checkNotNull(
            ServiceStateHolder.claimResourceRecoveryIntent(resourceOwner, attemptId = 7L)
        )

        val externalLease = ServiceStateHolder.setRecoveryIntentOnFailure(true)

        assertFalse(
            ServiceStateHolder.clearResourceRecoveryIntent(
                resourceOwner,
                attemptId = 7L,
                lease = resourceLease
            )
        )
        assertNull(ServiceStateHolder.claimResourceRecoveryIntent(resourceOwner, attemptId = 8L))
        assertTrue(ServiceStateHolder.preserveRecoveryIntentOnFailure)
        assertTrue(checkNotNull(ServiceStateHolder.consumeRecoveryIntentOnFailure(externalLease)))
    }

    @Test
    fun `old resource attempt cannot consume newer resource intent`() {
        val owner = Any()
        createBaseline()
        val oldLease = checkNotNull(ServiceStateHolder.claimResourceRecoveryIntent(owner, attemptId = 7L))
        val newLease = checkNotNull(ServiceStateHolder.claimResourceRecoveryIntent(owner, attemptId = 8L))

        assertNull(ServiceStateHolder.consumeRecoveryIntentOnFailure(oldLease))
        assertTrue(ServiceStateHolder.preserveRecoveryIntentOnFailure)
        assertTrue(checkNotNull(ServiceStateHolder.consumeRecoveryIntentOnFailure(newLease)))
    }

    @Test
    fun `old resource attempt cannot consume external recovery intent`() {
        val owner = Any()
        createBaseline()
        val resourceLease = checkNotNull(ServiceStateHolder.claimResourceRecoveryIntent(owner, attemptId = 7L))
        val externalLease = ServiceStateHolder.setRecoveryIntentOnFailure(true)

        assertNull(ServiceStateHolder.consumeRecoveryIntentOnFailure(resourceLease))
        assertTrue(checkNotNull(ServiceStateHolder.consumeRecoveryIntentOnFailure(externalLease)))
    }

    @Test
    fun `old resource success cannot clear newer external intent`() {
        val owner = Any()
        createBaseline()
        val resourceLease = checkNotNull(ServiceStateHolder.claimResourceRecoveryIntent(owner, attemptId = 7L))
        val externalLease = ServiceStateHolder.setRecoveryIntentOnFailure(true)

        assertNull(ServiceStateHolder.completeRecoveryIntentOnSuccess(resourceLease))
        assertTrue(ServiceStateHolder.isRecoveryIntentCurrent(externalLease))
        assertTrue(checkNotNull(ServiceStateHolder.consumeRecoveryIntentOnFailure(externalLease)))
    }

    @Test
    fun `same owner wrong attempt cannot clear exact lease`() {
        val owner = Any()
        createBaseline()
        val lease = checkNotNull(ServiceStateHolder.claimResourceRecoveryIntent(owner, attemptId = 7L))

        assertFalse(ServiceStateHolder.clearResourceRecoveryIntent(owner, attemptId = 8L, lease = lease))
        assertTrue(ServiceStateHolder.isRecoveryIntentCurrent(lease))
        assertTrue(checkNotNull(ServiceStateHolder.consumeRecoveryIntentOnFailure(lease)))
    }

    @Test
    fun `resource recovery cannot supersede explicit non resource intent`() {
        val owner = Any()
        val nonResourceLease = ServiceStateHolder.setRecoveryIntentOnFailure(false)

        assertNull(ServiceStateHolder.claimResourceRecoveryIntent(owner, attemptId = 7L))
        assertTrue(ServiceStateHolder.isRecoveryIntentCurrent(nonResourceLease))
        assertFalse(checkNotNull(ServiceStateHolder.consumeRecoveryIntentOnFailure(nonResourceLease)))
    }

    @Test
    fun `resource recovery can claim successful baseline`() {
        val startLease = ServiceStateHolder.setRecoveryIntentOnFailure(false)
        val baseline = checkNotNull(ServiceStateHolder.completeRecoveryIntentOnSuccess(startLease))

        val resourceLease = checkNotNull(ServiceStateHolder.claimResourceRecoveryIntent(Any(), attemptId = 7L))

        assertNull(ServiceStateHolder.consumeRecoveryIntentOnFailure(baseline))
        assertTrue(checkNotNull(ServiceStateHolder.consumeRecoveryIntentOnFailure(resourceLease)))
    }

    @Test
    fun `concurrent manual stop always supersedes resource claim`() {
        repeat(100) { attempt ->
            createBaseline()
            val start = CountDownLatch(1)
            var manualLease: RecoveryIntentLease? = null
            val manual = thread {
                start.await()
                manualLease = ServiceStateHolder.setRecoveryIntentOnFailure(false)
            }
            val recovery = thread {
                start.await()
                ServiceStateHolder.claimResourceRecoveryIntent(Any(), attempt.toLong())
            }

            start.countDown()
            manual.join()
            recovery.join()

            assertTrue(ServiceStateHolder.isRecoveryIntentCurrent(checkNotNull(manualLease)))
        }
    }

    @Test
    fun `older non resource start cannot consume newer intent`() {
        val oldLease = ServiceStateHolder.setRecoveryIntentOnFailure(false)
        val newLease = ServiceStateHolder.setRecoveryIntentOnFailure(true)

        assertNull(ServiceStateHolder.consumeRecoveryIntentOnFailure(oldLease))
        assertTrue(checkNotNull(ServiceStateHolder.consumeRecoveryIntentOnFailure(newLease)))
    }

    @Test
    fun `older successful start cannot clear newer intent`() {
        val oldLease = ServiceStateHolder.setRecoveryIntentOnFailure(true)
        val newLease = ServiceStateHolder.setRecoveryIntentOnFailure(false)

        assertNull(ServiceStateHolder.completeRecoveryIntentOnSuccess(oldLease))
        assertFalse(checkNotNull(ServiceStateHolder.consumeRecoveryIntentOnFailure(newLease)))
    }

    @Test
    fun `successful start replaces exact lease with neutral baseline`() {
        val startLease = ServiceStateHolder.setRecoveryIntentOnFailure(true)

        val baseline = checkNotNull(ServiceStateHolder.completeRecoveryIntentOnSuccess(startLease))

        assertNull(ServiceStateHolder.consumeRecoveryIntentOnFailure(startLease))
        assertFalse(checkNotNull(ServiceStateHolder.consumeRecoveryIntentOnFailure(baseline)))
    }
}
