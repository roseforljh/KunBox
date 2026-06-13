package com.kunk.singbox.service.manager

object NetworkAutoSwitchPolicy {

    data class Config(
        val enabled: Boolean,
        val trustedWifiSsids: String
    )

    data class NetworkSnapshot(
        val type: NetworkType,
        val ssid: String?
    )

    data class VpnSnapshot(
        val isRunning: Boolean,
        val isStarting: Boolean,
        val manuallyStopped: Boolean,
        val stoppedByTrustedWifi: Boolean
    )

    enum class NetworkType {
        WIFI,
        CELLULAR,
        OTHER
    }

    enum class Action {
        None,
        StopForTrustedWifi,
        StartForCellular
    }

    fun evaluate(
        config: Config,
        network: NetworkSnapshot,
        vpn: VpnSnapshot
    ): Action {
        if (!config.enabled) return Action.None

        return when (network.type) {
            NetworkType.WIFI -> {
                val currentSsid = normalizeSsid(network.ssid) ?: return Action.None
                val trustedSsids = parseTrustedSsids(config.trustedWifiSsids)
                if (currentSsid in trustedSsids && (vpn.isRunning || vpn.isStarting)) {
                    Action.StopForTrustedWifi
                } else {
                    Action.None
                }
            }
            NetworkType.CELLULAR -> {
                if (!vpn.isRunning && !vpn.isStarting && vpn.stoppedByTrustedWifi) {
                    Action.StartForCellular
                } else {
                    Action.None
                }
            }
            NetworkType.OTHER -> Action.None
        }
    }

    fun normalizeSsid(value: String?): String? {
        val normalized = value
            ?.trim()
            ?.removeSurrounding("\"")
            ?.trim()
            .orEmpty()

        val isUnknown = normalized.equals("<unknown ssid>", ignoreCase = true) ||
            normalized.equals("unknown ssid", ignoreCase = true)

        return normalized.takeUnless { it.isEmpty() || isUnknown }
    }

    fun parseTrustedSsids(value: String): Set<String> {
        return value
            .split('\n', ',', ';')
            .mapNotNull { normalizeSsid(it) }
            .toSet()
    }
}
