package com.kunk.singbox.repository

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kunk.singbox.R
import com.kunk.singbox.model.ProfileType
import com.kunk.singbox.model.SubscriptionUpdateResult
import com.kunk.singbox.utils.dns.DnsResolveStore
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ConfigRepositoryEntryRegressionTest {

    private lateinit var application: Application
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        MMKV.initialize(application)
        resetRepositoryEnvironment(application)
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) {
            server.shutdown()
        }
        resetRepositoryEnvironment(application)
    }

    @Test
    fun testImportFromSubscriptionEntryReportsPreResolveProgressAndStoresProfile() = runBlocking {
        server.dispatcher = fixedDispatcher(
            mapOf(
                "/import" to listOf(subscriptionYaml(name = "import-ip", server = "1.1.1.1"))
            )
        )
        val repository = freshRepository()
        val progressMessages = mutableListOf<String>()

        val result = repository.importFromSubscription(
            name = "Imported Profile",
            url = server.url("/import").toString(),
            dnsPreResolve = true,
            onProgress = progressMessages::add
        )

        assertTrue(result.isSuccess)
        val imported = result.getOrNull()
        assertNotNull(imported)
        assertEquals(ProfileType.Subscription, imported?.type)
        assertTrue(imported?.dnsPreResolve == true)
        assertEquals(1, repository.profiles.value.size)
        assertEquals(imported?.id, repository.profiles.value.single().id)
        assertEquals(imported?.id, repository.activeProfileId.value)
        assertTrue(progressMessages.contains(application.getString(R.string.subscription_import_fetching)))
        assertTrue(progressMessages.any { it.contains("User-Agent") })
        assertTrue(progressMessages.contains(application.getString(R.string.subscription_import_parsing)))
        assertTrue(
            progressMessages.any {
                it.contains(application.getString(R.string.profiles_import_success, "1"))
            }
        )
    }

    @Test
    fun testUpdateAllProfilesEntryAggregatesResultsAndUsesRuntimeDnsSignal() = runBlocking {
        server.dispatcher = fixedDispatcher(
            mapOf(
                "/changed" to listOf(
                    subscriptionYaml(name = "changed-old", server = "1.1.1.1"),
                    subscriptionYaml(name = "changed-new", server = "1.1.1.1")
                ),
                "/same" to listOf(
                    subscriptionYaml(name = "same-node", server = "8.8.8.8"),
                    subscriptionYaml(name = "same-node", server = "8.8.8.8")
                )
            )
        )
        val repository = freshRepository()
        repository.setAllNodesUiActive(true)

        val changedProfile = repository.importFromSubscription(
            name = "Changed Profile",
            url = server.url("/changed").toString(),
            dnsPreResolve = true
        ).getOrThrow()
        val sameProfile = repository.importFromSubscription(
            name = "Same Profile",
            url = server.url("/same").toString(),
            dnsPreResolve = false
        ).getOrThrow()
        repository.importFromContent(
            name = "Imported Local",
            content = subscriptionYaml(name = "local-only", server = "9.9.9.9")
        ).getOrThrow().also { localProfile ->
            val changedBefore = repository.profiles.value.first { it.id == changedProfile.id }.lastUpdated
            val sameBefore = repository.profiles.value.first { it.id == sameProfile.id }.lastUpdated
            val localBefore = repository.profiles.value.first { it.id == localProfile.id }.lastUpdated

            awaitClockAdvance(maxOf(changedBefore, sameBefore, localBefore))

            val batchResult = repository.updateAllProfiles()

            assertEquals(1, batchResult.successWithChanges)
            assertEquals(1, batchResult.successNoChanges)
            assertEquals(0, batchResult.failed)
            assertEquals(2, batchResult.details.size)

            val detailsByProfile = batchResult.details.associateBy {
                when (it) {
                    is SubscriptionUpdateResult.SuccessWithChanges -> it.profileName
                    is SubscriptionUpdateResult.SuccessNoChanges -> it.profileName
                    is SubscriptionUpdateResult.Failed -> it.profileName
                }
            }
            val changedResult = detailsByProfile.getValue(changedProfile.name)
            val sameResult = detailsByProfile.getValue(sameProfile.name)
            val profilesById = repository.profiles.value.associateBy { it.id }
            val activeNodeNames = repository.nodes.value.map { it.name }
            val allNodeNames = repository.allNodes.value.map { it.name }

            assertTrue(changedResult is SubscriptionUpdateResult.SuccessWithChanges)
            assertTrue(sameResult is SubscriptionUpdateResult.SuccessNoChanges)
            assertEquals(changedProfile.id, repository.activeProfileId.value)
            assertEquals(3, profilesById.size)
            assertTrue(profilesById.containsKey(changedProfile.id))
            assertTrue(profilesById.containsKey(sameProfile.id))
            assertTrue(profilesById.containsKey(localProfile.id))
            assertTrue(profilesById.getValue(changedProfile.id).lastUpdated > changedBefore)
            assertTrue(profilesById.getValue(sameProfile.id).lastUpdated > sameBefore)
            assertEquals(localBefore, profilesById.getValue(localProfile.id).lastUpdated)
            assertEquals(listOf("changed-new"), activeNodeNames)
            assertFalse(activeNodeNames.contains("changed-old"))
            assertEquals(setOf("changed-new", "same-node", "local-only"), allNodeNames.toSet())
            assertFalse(allNodeNames.contains("changed-old"))
        }
    }

    private fun freshRepository(): ConfigRepository {
        resetRepositoryEnvironment(application)
        MMKV.initialize(application)
        return ConfigRepository.getInstance(application)
    }

    private fun resetRepositoryEnvironment(context: Application) {
        cancelRepositoryScopeIfPresent()
        closeAppDatabaseIfPresent()
        clearSingleton(ConfigRepository::class.java, "instance")
        clearSingleton(com.kunk.singbox.repository.store.SettingsStore::class.java, "INSTANCE")
        clearSingleton(SettingsRepository::class.java, "INSTANCE")
        clearSingleton(com.kunk.singbox.core.SingBoxCore::class.java, "instance")
        clearSingleton(DnsResolveStore::class.java, "instance")
        context.deleteDatabase("singbox.db")
        clearPath(File(context.filesDir, "configs"))
        clearPath(File(context.filesDir, "profiles.json"))
        clearPath(File(context.filesDir, "singbox_work"))
        clearPath(File(context.cacheDir, "singbox_temp"))
    }

    private fun cancelRepositoryScopeIfPresent() {
        val instance = getStaticField(ConfigRepository::class.java, "instance") as? ConfigRepository ?: return
        val scopeField = ConfigRepository::class.java.getDeclaredField("scope").apply { isAccessible = true }
        val schedulerField = ConfigRepository::class.java
            .getDeclaredField("cacheCleanupScheduler")
            .apply { isAccessible = true }
        (scopeField.get(instance) as? CoroutineScope)?.cancel()
        (schedulerField.get(instance) as? java.util.concurrent.ScheduledExecutorService)?.shutdownNow()
    }

    private fun closeAppDatabaseIfPresent() {
        val dbClass = com.kunk.singbox.database.AppDatabase::class.java
        val database = getStaticField(dbClass, "INSTANCE") as? androidx.room.RoomDatabase ?: return
        database.close()
        clearSingleton(dbClass, "INSTANCE")
    }

    private fun clearSingleton(clazz: Class<*>, fieldName: String) {
        clazz.getDeclaredField(fieldName).apply {
            isAccessible = true
            set(null, null)
        }
    }

    private fun getStaticField(clazz: Class<*>, fieldName: String): Any? {
        return clazz.getDeclaredField(fieldName).apply {
            isAccessible = true
        }.get(null)
    }

    private fun clearPath(file: File) {
        if (file.exists()) {
            file.deleteRecursively()
        }
    }

    private fun subscriptionYaml(name: String, server: String): String {
        return """
            proxies:
              - name: "$name"
                type: ss
                server: $server
                port: 443
                cipher: aes-128-gcm
                password: pass-a
        """.trimIndent()
    }

    private fun awaitClockAdvance(afterMs: Long) {
        while (System.currentTimeMillis() <= afterMs) {
            Thread.yield()
        }
    }

    private fun fixedDispatcher(responsesByPath: Map<String, List<String>>): Dispatcher {
        val counters = ConcurrentHashMap<String, AtomicInteger>()
        return object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path?.substringBefore('?') ?: return notFoundResponse()
                val responses = responsesByPath[path] ?: return notFoundResponse()
                val index = counters.getOrPut(path) { AtomicInteger(0) }.getAndIncrement()
                if (index >= responses.size) {
                    return MockResponse().setResponseCode(500).setBody("No queued response")
                }
                return MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/plain; charset=utf-8")
                    .setBody(responses[index])
            }
        }
    }

    private fun notFoundResponse(): MockResponse {
        return MockResponse().setResponseCode(404).setBody("Not Found")
    }
}
