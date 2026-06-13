package com.kunk.singbox.service.manager

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkAutoSwitchPolicyTest {

    @Test
    fun trustedWifiStopsRunningVpn() {
        val action = NetworkAutoSwitchPolicy.evaluate(
            config = NetworkAutoSwitchPolicy.Config(
                enabled = true,
                trustedWifiSsids = "Home WiFi\nOffice"
            ),
            network = NetworkAutoSwitchPolicy.NetworkSnapshot(
                type = NetworkAutoSwitchPolicy.NetworkType.WIFI,
                ssid = "\"Home WiFi\""
            ),
            vpn = NetworkAutoSwitchPolicy.VpnSnapshot(
                isRunning = true,
                isStarting = false,
                manuallyStopped = false,
                stoppedByTrustedWifi = false
            )
        )

        assertEquals(NetworkAutoSwitchPolicy.Action.StopForTrustedWifi, action)
    }

    @Test
    fun cellularStartsAfterTrustedWifiStopEvenIfServiceMarkedManualStop() {
        val action = NetworkAutoSwitchPolicy.evaluate(
            config = NetworkAutoSwitchPolicy.Config(
                enabled = true,
                trustedWifiSsids = "Home WiFi"
            ),
            network = NetworkAutoSwitchPolicy.NetworkSnapshot(
                type = NetworkAutoSwitchPolicy.NetworkType.CELLULAR,
                ssid = null
            ),
            vpn = NetworkAutoSwitchPolicy.VpnSnapshot(
                isRunning = false,
                isStarting = false,
                manuallyStopped = true,
                stoppedByTrustedWifi = true
            )
        )

        assertEquals(NetworkAutoSwitchPolicy.Action.StartForCellular, action)
    }

    @Test
    fun cellularDoesNotStartAfterManualStop() {
        val action = NetworkAutoSwitchPolicy.evaluate(
            config = NetworkAutoSwitchPolicy.Config(
                enabled = true,
                trustedWifiSsids = "Home WiFi"
            ),
            network = NetworkAutoSwitchPolicy.NetworkSnapshot(
                type = NetworkAutoSwitchPolicy.NetworkType.CELLULAR,
                ssid = null
            ),
            vpn = NetworkAutoSwitchPolicy.VpnSnapshot(
                isRunning = false,
                isStarting = false,
                manuallyStopped = true,
                stoppedByTrustedWifi = false
            )
        )

        assertEquals(NetworkAutoSwitchPolicy.Action.None, action)
    }

    @Test
    fun disabledOrUnknownSsidDoesNothing() {
        val disabled = NetworkAutoSwitchPolicy.evaluate(
            config = NetworkAutoSwitchPolicy.Config(
                enabled = false,
                trustedWifiSsids = "Home WiFi"
            ),
            network = NetworkAutoSwitchPolicy.NetworkSnapshot(
                type = NetworkAutoSwitchPolicy.NetworkType.WIFI,
                ssid = "Home WiFi"
            ),
            vpn = NetworkAutoSwitchPolicy.VpnSnapshot(
                isRunning = true,
                isStarting = false,
                manuallyStopped = false,
                stoppedByTrustedWifi = false
            )
        )
        val unknownSsid = NetworkAutoSwitchPolicy.evaluate(
            config = NetworkAutoSwitchPolicy.Config(
                enabled = true,
                trustedWifiSsids = "Home WiFi"
            ),
            network = NetworkAutoSwitchPolicy.NetworkSnapshot(
                type = NetworkAutoSwitchPolicy.NetworkType.WIFI,
                ssid = "<unknown ssid>"
            ),
            vpn = NetworkAutoSwitchPolicy.VpnSnapshot(
                isRunning = true,
                isStarting = false,
                manuallyStopped = false,
                stoppedByTrustedWifi = false
            )
        )

        assertEquals(NetworkAutoSwitchPolicy.Action.None, disabled)
        assertEquals(NetworkAutoSwitchPolicy.Action.None, unknownSsid)
    }
}
