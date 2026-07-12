package com.kunk.singbox.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class PhysicalNetworkSelectionPolicyTest {

    @Test
    fun activePhysicalNetworkWinsBeforeNonActiveValidatedNetwork() {
        val selected = selectPreferredPhysicalNetwork(
            listOf(
                candidate("wifi", id = 1, active = true, validated = false, transport = 2),
                candidate("cellular", id = 2, active = false, validated = true, transport = 1)
            )
        )

        assertEquals("wifi", selected)
    }

    @Test
    fun validatedNetworkWinsWhenNoPhysicalCandidateIsActive() {
        val selected = selectPreferredPhysicalNetwork(
            listOf(
                candidate("wifi", id = 1, active = false, validated = false, transport = 2),
                candidate("cellular", id = 2, active = false, validated = true, transport = 1)
            )
        )

        assertEquals("cellular", selected)
    }

    @Test
    fun activeValidatedNetworkWinsOverHigherPriorityNonActiveTransport() {
        val selected = selectPreferredPhysicalNetwork(
            listOf(
                candidate("wifi", id = 1, active = false, validated = true, transport = 2),
                candidate("cellular", id = 2, active = true, validated = true, transport = 1)
            )
        )

        assertEquals("cellular", selected)
    }

    @Test
    fun transportPriorityAndCurrentNetworkBreakRemainingTies() {
        assertEquals(
            "ethernet",
            selectPreferredPhysicalNetwork(
                listOf(
                    candidate("wifi", id = 1, active = false, validated = true, transport = 2),
                    candidate("ethernet", id = 2, active = false, validated = true, transport = 3)
                )
            )
        )
        assertEquals(
            "current",
            selectPreferredPhysicalNetwork(
                listOf(
                    candidate("other", id = 3, active = false, validated = true, transport = 1),
                    candidate("current", id = 4, active = false, validated = true, transport = 1, current = true)
                )
            )
        )
    }

    private fun candidate(
        name: String,
        id: Long,
        active: Boolean,
        validated: Boolean,
        transport: Int,
        current: Boolean = false
    ): PhysicalNetworkCandidate<String> {
        return PhysicalNetworkCandidate(name, id, active, validated, transport, current)
    }
}
