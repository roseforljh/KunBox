// Top-level build file where you can add configuration options common to all sub-projects/modules.
import java.util.Properties

plugins {
    id("com.android.application") version "9.2.1" apply false
    id("org.jetbrains.kotlin.android") version "2.4.0" apply false
    id("com.google.devtools.ksp") version "2.3.9" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.8" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0" apply false
    id("androidx.room") version "2.8.4" apply false
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use(::load)
    }
}

localProperties.getProperty("kunbox.buildDir")
    ?.takeIf { it.isNotBlank() }
    ?.let { configuredBuildDir ->
        val externalBuildRoot = File(configuredBuildDir)
        layout.buildDirectory.set(externalBuildRoot.resolve("root"))

        subprojects {
            layout.buildDirectory.set(
                externalBuildRoot.resolve(project.path.removePrefix(":").replace(':', '/').ifBlank { name })
            )
        }
    }
