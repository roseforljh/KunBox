package com.kunk.singbox.ipc

import com.kunk.singbox.service.ServiceState
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class SingBoxRemoteStateTest {

    @Test
    fun `pending starting returns STARTING`() {
        val result = SingBoxRemote.resolvePersistedStateFromValues(
            pending = "starting",
            isActive = false,
            mode = VpnStateStore.CoreMode.NONE,
            hasVpnTransport = false
        )
        assertEquals(ServiceState.STARTING, result)
    }

    @Test
    fun `pending stopping returns STOPPING`() {
        val result = SingBoxRemote.resolvePersistedStateFromValues(
            pending = "stopping",
            isActive = false,
            mode = VpnStateStore.CoreMode.NONE,
            hasVpnTransport = false
        )
        assertEquals(ServiceState.STOPPING, result)
    }

    @Test
    fun `isActive true with hasVpnTransport false returns STOPPED`() {
        val result = SingBoxRemote.resolvePersistedStateFromValues(
            pending = "",
            isActive = true,
            mode = VpnStateStore.CoreMode.VPN,
            hasVpnTransport = false
        )
        assertEquals(ServiceState.STOPPED, result)
    }

    @Test
    fun `isActive true with hasVpnTransport true returns RUNNING`() {
        val result = SingBoxRemote.resolvePersistedStateFromValues(
            pending = "",
            isActive = true,
            mode = VpnStateStore.CoreMode.VPN,
            hasVpnTransport = true
        )
        assertEquals(ServiceState.RUNNING, result)
    }

    @Test
    fun `mode PROXY with hasVpnTransport true returns RUNNING`() {
        val result = SingBoxRemote.resolvePersistedStateFromValues(
            pending = "",
            isActive = false,
            mode = VpnStateStore.CoreMode.PROXY,
            hasVpnTransport = true
        )
        assertEquals(ServiceState.RUNNING, result)
    }

    @Test
    fun `mode PROXY with hasVpnTransport false returns RUNNING`() {
        val result = SingBoxRemote.resolvePersistedStateFromValues(
            pending = "",
            isActive = false,
            mode = VpnStateStore.CoreMode.PROXY,
            hasVpnTransport = false
        )
        assertEquals(ServiceState.RUNNING, result)
    }

    @Test
    fun `stale vpn mode without pending or transport returns STOPPED`() {
        val result = SingBoxRemote.resolvePersistedStateFromValues(
            pending = "",
            isActive = false,
            mode = VpnStateStore.CoreMode.VPN,
            hasVpnTransport = false
        )
        assertEquals(ServiceState.STOPPED, result)
    }

    @Test
    fun `all false returns STOPPED`() {
        val result = SingBoxRemote.resolvePersistedStateFromValues(
            pending = "",
            isActive = false,
            mode = VpnStateStore.CoreMode.NONE,
            hasVpnTransport = false
        )
        assertEquals(ServiceState.STOPPED, result)
    }

    @Test
    fun `pending takes precedence over isActive`() {
        val result = SingBoxRemote.resolvePersistedStateFromValues(
            pending = "stopping",
            isActive = true,
            mode = VpnStateStore.CoreMode.VPN,
            hasVpnTransport = true
        )
        assertEquals(ServiceState.STOPPING, result)
    }

    @Test
    fun `isActive takes precedence over mode PROXY`() {
        val result = SingBoxRemote.resolvePersistedStateFromValues(
            pending = "",
            isActive = true,
            mode = VpnStateStore.CoreMode.PROXY,
            hasVpnTransport = true
        )
        assertEquals(ServiceState.RUNNING, result)
    }

    @Test
    fun `disconnected stop state preserves revoke terminal error`() {
        val result = SingBoxRemote.resolveDisconnectedStopState(
            storedLastError = "VPN revoked by system (another VPN may have started)",
            storedManuallyStopped = true
        )

        assertTrue(result.preserveLastError)
        assertEquals("VPN revoked by system (another VPN may have started)", result.lastError)
        assertTrue(result.manuallyStopped)
    }

    @Test
    fun `disconnected stop state clears transient error for non terminal stop`() {
        val result = SingBoxRemote.resolveDisconnectedStopState(
            storedLastError = "temporary failure",
            storedManuallyStopped = false
        )

        assertFalse(result.preserveLastError)
        assertEquals("", result.lastError)
        assertFalse(result.manuallyStopped)
    }

    @Test
    fun `service loss reconnects when vpn still exists or proxy mode is active`() {
        assertTrue(
            SingBoxRemote.shouldReconnectAfterServiceLoss(
                systemVpn = true,
                storedManuallyStopped = false,
                storedMode = VpnStateStore.CoreMode.VPN
            )
        )
        assertFalse(
            SingBoxRemote.shouldReconnectAfterServiceLoss(
                systemVpn = true,
                storedManuallyStopped = true,
                storedMode = VpnStateStore.CoreMode.VPN
            )
        )
        assertTrue(
            SingBoxRemote.shouldReconnectAfterServiceLoss(
                systemVpn = false,
                storedManuallyStopped = false,
                storedMode = VpnStateStore.CoreMode.PROXY
            )
        )
        assertFalse(
            SingBoxRemote.shouldReconnectAfterServiceLoss(
                systemVpn = false,
                storedManuallyStopped = false,
                storedMode = VpnStateStore.CoreMode.VPN
            )
        )
        assertFalse(
            SingBoxRemote.shouldReconnectAfterServiceLoss(
                systemVpn = false,
                storedManuallyStopped = false,
                storedMode = VpnStateStore.CoreMode.NONE
            )
        )
    }

    @Test
    fun `temporary ipc loss keeps a live data plane in recovering state`() {
        val source = File("src/main/java/com/kunk/singbox/ipc/SingBoxRemote.kt").readText(Charsets.UTF_8)
        val disconnectedBody = source
            .substringAfter("override fun onServiceDisconnected")
            .substringBefore("private fun unregisterCallback")

        assertTrue(disconnectedBody.contains("markReadinessRecovering"))
        assertFalse(disconnectedBody.contains("markReadinessUnavailable(\"service_disconnected\")"))

        val callbackBody = source
            .substringAfter("override fun onStateChanged(")
            .substringBefore("override fun onUrlTestNodeDelayResult")
        assertTrue(callbackBody.contains("markReadinessRecovering(\"callback_snapshot_failed\")"))
        assertTrue(callbackBody.contains("let(::rebind)"))
    }

    @Test
    fun `normal ipc disconnect resets readiness without terminal failure`() {
        val source = File("src/main/java/com/kunk/singbox/ipc/SingBoxRemote.kt")
            .readText(Charsets.UTF_8)
        val disconnectedBody = source
            .substringAfter("private fun syncStoppedStateAfterDisconnect()")
            .substringBefore("internal fun resolveLocalStateSnapshot")

        assertTrue(disconnectedBody.contains("markReadinessStopped"))
        assertFalse(disconnectedBody.contains("markReadinessUnavailable"))
    }

    @Test
    fun `new start clears readiness failure state`() {
        val source = File("src/main/java/com/kunk/singbox/ipc/SingBoxRemote.kt")
            .readText(Charsets.UTF_8)
        val body = source
            .substringAfter("fun clearLastErrorForNewStart()")
            .substringBefore("private fun syncStateFromStore()")

        assertTrue(body.contains("DataPlaneStatus.STARTING"))
        assertTrue(body.contains("lastReadinessReason = \"new_start\""))
    }

    @Test
    fun `ensure bound rebinds stale live reference`() {
        val result = resolveSingBoxEnsureBoundAction(
            connectionActive = true,
            bound = true,
            servicePresent = true,
            serviceAlive = false,
            bindingInProgress = false
        )

        assertEquals(EnsureBoundAction.REBIND, result)
    }

    @Test
    fun `ensure bound skips healthy connection and waits for active bind`() {
        assertEquals(
            EnsureBoundAction.NONE,
            resolveSingBoxEnsureBoundAction(
                connectionActive = true,
                bound = true,
                servicePresent = true,
                serviceAlive = true,
                bindingInProgress = false
            )
        )
        assertEquals(
            EnsureBoundAction.WAIT_FOR_BIND,
            resolveSingBoxEnsureBoundAction(
                connectionActive = true,
                bound = false,
                servicePresent = false,
                serviceAlive = false,
                bindingInProgress = true
            )
        )
    }

    @Test
    fun `ensure bound syncs live service state before returning healthy connection`() {
        val source = File("src/main/java/com/kunk/singbox/ipc/SingBoxRemote.kt").readText()
        val start = source.indexOf("fun ensureBound(context: Context)")
        val body = source.substring(
            start,
            source.indexOf("fun queryAndSyncState(context: Context)", start)
        )

        assertTrue(body.contains("EnsureBoundAction.NONE -> {"))
        assertTrue(body.contains("if (!syncStateFromService(currentService))"))
        assertTrue(body.contains("rebind(context)"))
    }

    @Test
    fun `service state sync failure is observable and triggers rebind`() {
        val source = File("src/main/java/com/kunk/singbox/ipc/SingBoxRemote.kt").readText()
        val syncStart = source.indexOf("private fun syncStateFromService")
        val syncBody = source.substring(syncStart, source.indexOf("private fun hasSystemVpn", syncStart))
        val queryStart = source.indexOf("fun queryAndSyncState(context: Context)")
        val queryBody = source.substring(queryStart, source.indexOf("fun rebind(context: Context)", queryStart))
        val recoveryStart = source.indexOf("fun instantRecovery(")
        val recoveryBody = source.substring(
            recoveryStart,
            source.indexOf("@Deprecated", recoveryStart)
        )

        assertTrue(syncBody.contains("ISingBoxService?): Boolean"))
        assertTrue(syncBody.contains("}.isSuccess"))
        assertTrue(queryBody.contains("val synced = syncStateFromService(s)"))
        assertTrue(queryBody.contains("rebind(context)"))
        assertTrue(recoveryBody.contains("val ok = syncStateFromService(s)"))
        assertTrue(recoveryBody.contains("return recovering"))
        assertFalse(recoveryBody.contains("callback?.invoke(RecoveryResult.AlreadyConnected)"))
    }

    @Test
    fun `state generation rejects stale callback`() {
        assertTrue(SingBoxRemote.shouldAcceptStateGeneration(incoming = 12L, accepted = 11L))
        assertTrue(SingBoxRemote.shouldAcceptStateGeneration(incoming = 12L, accepted = 12L))
        assertFalse(SingBoxRemote.shouldAcceptStateGeneration(incoming = 11L, accepted = 12L))
        assertFalse(SingBoxRemote.shouldAcceptStateGeneration(incoming = 0L, accepted = 12L))
    }

    @Test
    fun `locally resolved state does not become authoritative`() {
        val persisted = VpnStateStore.RuntimeStateSnapshot(
            generation = 42L,
            stateOrdinal = ServiceState.RUNNING.ordinal,
            activeLabel = "节点 A",
            lastError = "temporary error",
            manuallyStopped = false
        )

        val resolved = SingBoxRemote.resolveLocalStateSnapshot(
            persisted = persisted,
            state = ServiceState.STOPPED,
            clearTransientState = true
        )

        assertEquals(0L, resolved.generation)
        assertEquals(ServiceState.STOPPED.ordinal, resolved.stateOrdinal)
        assertEquals("", resolved.activeLabel)
        assertEquals("", resolved.lastError)
        assertEquals(ServiceState.RUNNING.ordinal, persisted.stateOrdinal)
        assertEquals(42L, persisted.generation)
    }

    @Test
    fun `newer state commit cannot be overwritten by an older in flight commit`() {
        val gate = StateGenerationGate()
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val appliedState = AtomicReference("")
        val executor = Executors.newFixedThreadPool(2)

        try {
            val older = executor.submit<Boolean> {
                gate.tryCommit(10L) {
                    firstEntered.countDown()
                    assertTrue(releaseFirst.await(5, TimeUnit.SECONDS))
                    appliedState.set("generation-10")
                }
            }
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS))

            val newer = executor.submit<Boolean> {
                gate.tryCommit(11L) { appliedState.set("generation-11") }
            }
            releaseFirst.countDown()

            assertTrue(older.get(5, TimeUnit.SECONDS))
            assertTrue(newer.get(5, TimeUnit.SECONDS))
            assertEquals("generation-11", appliedState.get())
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `concurrent recovery requests keep every final callback`() {
        val source = File("src/main/java/com/kunk/singbox/ipc/SingBoxRemote.kt").readText(Charsets.UTF_8)
        val start = source.indexOf("private val pendingRecoveryCallbacks")
        val body = source.substring(
            start,
            source.indexOf("private fun clearPendingUrlTestRequests", start)
        )

        assertTrue(body.contains("ConcurrentLinkedQueue<(RecoveryResult) -> Unit>()"))
        assertTrue(body.contains("callback?.let(pendingRecoveryCallbacks::add)"))
        assertTrue(body.contains("pendingRecoveryCallbacks.poll()"))
        assertFalse(body.contains("pendingRecoveryCallback = callback"))
    }
}
