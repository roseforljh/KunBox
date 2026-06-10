package com.kunk.singbox.ipc

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class SingBoxIpcServiceSourceTest {

    @Test
    fun serviceDoesNotLinkDeathOnItsOwnLocalBinder() {
        val source = File("src/main/java/com/kunk/singbox/ipc/SingBoxIpcService.kt").readText()

        assertFalse(source.contains("linkToDeath"))
        assertFalse(source.contains("unlinkToDeath"))
        assertFalse(source.contains("onServiceBinderDied()"))
    }
}
