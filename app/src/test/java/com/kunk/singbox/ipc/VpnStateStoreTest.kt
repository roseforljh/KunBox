package com.kunk.singbox.ipc

import org.junit.Assert.*
import org.junit.Test

class VpnStateStoreTest {

    @Test
    fun testCoreModeEnumValues() {
        assertEquals(3, VpnStateStore.CoreMode.values().size)
        assertEquals("NONE", VpnStateStore.CoreMode.NONE.name)
        assertEquals("VPN", VpnStateStore.CoreMode.VPN.name)
        assertEquals("PROXY", VpnStateStore.CoreMode.PROXY.name)
    }

    @Test
    fun testCoreModeValueOf() {
        assertEquals(VpnStateStore.CoreMode.NONE, VpnStateStore.CoreMode.valueOf("NONE"))
        assertEquals(VpnStateStore.CoreMode.VPN, VpnStateStore.CoreMode.valueOf("VPN"))
        assertEquals(VpnStateStore.CoreMode.PROXY, VpnStateStore.CoreMode.valueOf("PROXY"))
    }

    @Test
    fun testCoreModeOrdinal() {
        assertEquals(0, VpnStateStore.CoreMode.NONE.ordinal)
        assertEquals(1, VpnStateStore.CoreMode.VPN.ordinal)
        assertEquals(2, VpnStateStore.CoreMode.PROXY.ordinal)
    }
}
