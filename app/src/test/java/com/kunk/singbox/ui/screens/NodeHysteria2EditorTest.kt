package com.kunk.singbox.ui.screens

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.kunk.singbox.model.Outbound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeHysteria2EditorTest {
    private val realmJson = """
        {
          "server_url":"https://realm.example.com", "realm_id":"test-realm",
          "token":"example-token", "stun_servers":["stun.example.com:3478"],
          "port_mapping":{"enabled":true,"timeout":"5s","lifetime":"30m"},
          "http_client":{"tls":{"enabled":true,"server_name":"realm.example.com"}}
        }
    """.trimIndent()

    @Test
    fun confirmingRealmClearsOnlyConflictingFieldsAndSurvivesEditorSave() {
        val original = Outbound(
            type = "hysteria2", tag = "node", server = "old.example.com", serverPort = 443,
            password = "password",
            serverPorts = listOf("443:450"),
            hopInterval = "30s",
            hopIntervalMax = "60s",
            bbrProfile = "conservative", disableChromeParrot = false
        )
        val realm = parseHysteria2RealmForEditor(realmJson)
        val edited = applyHysteria2RealmForEditor(original, realm)
        assertNull(edited.server)
        assertNull(edited.serverPort)
        assertNull(edited.serverPorts)
        assertNull(edited.hopInterval)
        assertNull(edited.hopIntervalMax)
        assertEquals("old.example.com", original.server)
        assertNull(original.realm)
        assertEquals("password", edited.password)
        assertEquals("conservative", edited.bbrProfile)
        assertEquals(false, edited.disableChromeParrot)
        assertEquals(JsonParser.parseString(realmJson), edited.realm)
        val gson = Gson()
        assertEquals(edited, gson.fromJson(gson.toJson(edited), Outbound::class.java))
        realm.addProperty("token", "changed")
        assertFalse(edited.realm == realm)
    }

    @Test
    fun disablingRealmPreservesAuthenticationAndRequiresNewServer() {
        val original = Outbound(
            type = "hysteria2", tag = "node", password = "password",
            realm = parseHysteria2RealmForEditor(realmJson)
        )
        val edited = removeHysteria2RealmForEditor(original)
        assertNull(edited.realm)
        assertNull(edited.server)
        assertEquals(443, edited.serverPort)
        assertEquals("password", edited.password)
        assertEquals(JsonParser.parseString(realmJson), original.realm)
    }

    @Test
    fun invalidDraftCannotMutateOriginal() {
        val original = Outbound(
            type = "hysteria2", tag = "node", realm = parseHysteria2RealmForEditor(realmJson)
        )
        val invalidDrafts = listOf(
            "{", "[]", "{}", realmJson.replace("https://", "ftp://"),
            realmJson.replace("test-realm", ""),
            realmJson.replace("[\"stun.example.com:3478\"]", "[]")
        )
        invalidDrafts.forEach { draft ->
            val error = runCatching { parseHysteria2RealmForEditor(draft) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertEquals(JsonParser.parseString(realmJson), original.realm)
        }
    }

    @Test
    fun listableStunStringAndUnknownHttpClientFieldsSurviveEditing() {
        val json = realmJson.replace("[\"stun.example.com:3478\"]", "\"stun.example.com:3478\"")
        val realm = parseHysteria2RealmForEditor(json)
        assertEquals(JsonParser.parseString(json), realm)
    }
}
