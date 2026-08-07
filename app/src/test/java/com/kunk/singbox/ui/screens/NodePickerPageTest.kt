package com.kunk.singbox.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NodePickerPageTest {

    @Test
    fun currentProfileIsKeptWhileItRemainsAvailable() {
        val result = resolveNodePickerProfileId(
            availableProfileIds = listOf("profile-a", "profile-b"),
            currentProfileId = "profile-b",
            selectedNodeProfileId = "profile-a",
            activeProfileId = "profile-a"
        )

        assertEquals("profile-b", result)
    }

    @Test
    fun selectedNodeProfilePrecedesActiveProfile() {
        val result = resolveNodePickerProfileId(
            availableProfileIds = listOf("profile-a", "profile-b"),
            currentProfileId = null,
            selectedNodeProfileId = "profile-b",
            activeProfileId = "profile-a"
        )

        assertEquals("profile-b", result)
    }

    @Test
    fun firstAvailableProfileIsTheFinalFallback() {
        assertEquals(
            "profile-a",
            resolveNodePickerProfileId(
                availableProfileIds = listOf("profile-a", "profile-b"),
                currentProfileId = "missing",
                selectedNodeProfileId = "missing",
                activeProfileId = null
            )
        )
        assertNull(resolveNodePickerProfileId(emptyList(), null, null, null))
    }
}
