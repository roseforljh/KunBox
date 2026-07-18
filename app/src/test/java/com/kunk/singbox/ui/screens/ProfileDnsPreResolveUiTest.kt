package com.kunk.singbox.ui.screens

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProfileDnsPreResolveUiTest {

    @Test
    fun subscriptionDialogAndEditorExposeDnsPreResolve() {
        val dialogSource = File("src/main/java/com/kunk/singbox/ui/screens/ProfilesScreenDialogs.kt")
            .readText(Charsets.UTF_8)
        val screenSource = File("src/main/java/com/kunk/singbox/ui/screens/ProfilesScreen.kt")
            .readText(Charsets.UTF_8)
        val viewModelSource = File("src/main/java/com/kunk/singbox/viewmodel/ProfilesViewModel.kt")
            .readText(Charsets.UTF_8)

        assertTrue(dialogSource.contains("initialDnsPreResolve: Boolean = false"))
        assertTrue(dialogSource.contains("R.string.profiles_dns_preresolve"))
        assertTrue(dialogSource.contains("DnsResolver.DOH_CLOUDFLARE"))
        assertTrue(dialogSource.contains("DnsResolver.DOH_GOOGLE"))
        assertTrue(dialogSource.contains("DnsResolver.DOH_ALIDNS"))
        assertTrue(screenSource.contains("initialDnsPreResolve = profile.dnsPreResolve"))
        assertTrue(screenSource.contains("initialDnsServer = profile.dnsServer"))
        assertTrue(viewModelSource.contains("dnsPreResolve = dnsPreResolve"))
        assertTrue(viewModelSource.contains("dnsServer = dnsServer"))
    }
}
