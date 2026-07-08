package com.kunk.singbox.utils.parser

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SnakeYamlProguardRulesTest {

    @Test
    fun proguardKeepsSnakeYamlPackageNames() {
        val rules = File("proguard-rules.pro").readText()

        assertTrue(rules.contains("-keeppackagenames org.yaml.snakeyaml.**"))
    }
}
