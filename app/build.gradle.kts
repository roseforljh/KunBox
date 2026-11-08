import java.util.Properties
import java.util.zip.ZipFile
import java.security.MessageDigest
import groovy.json.JsonSlurper
import com.android.build.api.dsl.ApplicationExtension

plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
    id("io.gitlab.arturbosch.detekt")
    id("org.jetbrains.kotlin.plugin.compose")
    id("androidx.room")
}

val libboxInputAar = file("libs/raybox.aar")
val libboxStrippedAar = layout.buildDirectory.file("stripped-libs/libbox-stripped.aar")
val rayboxBuildMetadata = file("libs/raybox-build.json")
val rayboxMetadata = if (rayboxBuildMetadata.isFile) {
    JsonSlurper().parse(rayboxBuildMetadata) as? Map<*, *>
        ?: throw GradleException("Invalid RayBox metadata object")
} else throw GradleException("Missing RayBox metadata; install a verified core with tools/sync_raybox.py")
val rayboxCoreCommit = rayboxMetadata["commit"] as? String ?: ""
val rayboxCoreDigest = rayboxMetadata["sourceDigest"] as? String ?: ""
val rayboxCoreAarSha256 = (rayboxMetadata["outputs"] as? List<*>)
    ?.filterIsInstance<Map<*, *>>()?.firstOrNull { it["name"] == "raybox.aar" }?.get("sha256") as? String ?: ""
val verifyRayboxCore = tasks.register("verifyRayboxCore") {
    inputs.file(libboxInputAar)
    if (rayboxBuildMetadata.isFile) inputs.file(rayboxBuildMetadata)
    doLast {
        if (providers.gradleProperty("enableSfaLibboxReplacement").orNull?.toBoolean() == true) {
            throw GradleException("RayBox cannot use unverified SFA native replacements")
        }
        if (rayboxMetadata["repository"] != "roseforljh/RayBox-rx" ||
            !Regex("[0-9a-f]{40}").matches(rayboxCoreCommit) ||
            !Regex("[0-9a-f]{64}").matches(rayboxCoreDigest) ||
            !Regex("[0-9a-f]{64}").matches(rayboxCoreAarSha256)
        ) throw GradleException("Invalid RayBox source identity")
        val dirty = rayboxMetadata["dirty"] as? Boolean
            ?: throw GradleException("RayBox metadata must declare dirty explicitly")
        val expectedTag = if (dirty) null else "android-$rayboxCoreCommit"
        val output = (rayboxMetadata["outputs"] as? List<*>)?.filterIsInstance<Map<*, *>>()
            ?.singleOrNull { it["name"] == "raybox.aar" }
        val phoneCandidate = dirty &&
            providers.gradleProperty("abiOnly").orNull == "arm64-v8a" &&
            (rayboxMetadata["architectures"] as? List<*>) == listOf("arm64-v8a")
        val expectedAbis = if (phoneCandidate) setOf("arm64-v8a") else {
            setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
        if (rayboxMetadata["schemaVersion"] != 1 || !rayboxMetadata.containsKey("tag") ||
            rayboxMetadata["tag"] != expectedTag ||
            (rayboxMetadata["architectures"] as? List<*>)?.toSet() != expectedAbis ||
            (output?.get("size") as? Number)?.toLong() != libboxInputAar.length() ||
            rayboxMetadata["sourceDigestAlgorithm"] != "sha256-git-path-kind-content-v1" ||
            (rayboxMetadata["source"] as? Map<*, *>)?.get("url") !=
                "https://github.com/roseforljh/RayBox-rx/tree/$rayboxCoreCommit" ||
            (rayboxMetadata["source"] as? Map<*, *>)?.get("runtimeLicense") != "docs/reference/runtime/LICENSE" ||
            (rayboxMetadata["source"] as? Map<*, *>)?.get("protocolLicense") != "docs/reference/protocol/LICENSE" ||
            (rayboxMetadata["buildTags"] as? Map<*, *>)?.get("raybox.aar") !is List<*> ||
            ((rayboxMetadata["buildTags"] as? Map<*, *>)?.get("raybox.aar") as? List<*>)
                ?.contains("with_tailscale") == true ||
            !(rayboxMetadata["go"] as? String).orEmpty().startsWith("go version go1.27.3 ") ||
            rayboxMetadata["ndk"] != "29.0.14206865" || rayboxMetadata["gomobile"] != "v0.1.13" ||
            availableLibboxAbis != expectedAbis ||
            rayboxMetadata["rxDefault"] != false || rayboxMetadata["oobDefault"] != false
        ) throw GradleException("RayBox metadata tag, architecture, size or defaults are invalid")
        if (dirty && gradle.taskGraph.allTasks.any { it.name.contains("release", ignoreCase = true) }
        ) throw GradleException("Dirty RayBox candidates are allowed only in local debug builds")
        val digest = MessageDigest.getInstance("SHA-256")
        libboxInputAar.inputStream().use { stream ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != rayboxCoreAarSha256) throw GradleException("RayBox AAR SHA-256 differs from metadata")
    }
}
val composeBomVersion = "2026.06.00"

fun gitOutput(vararg args: String): String? = runCatching {
    providers.exec {
        commandLine("git", *args)
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().takeIf { it.isNotBlank() }
}.getOrNull()

val enableSfaLibboxReplacement = providers.gradleProperty("enableSfaLibboxReplacement")
    .orNull
    ?.toBoolean()
    ?: false

val isBundleBuild = gradle.startParameter.taskNames.any { it.contains("bundle", ignoreCase = true) }

val abiOnly = providers.gradleProperty("abiOnly").orNull
    ?.trim()
    ?.takeIf { it.isNotBlank() }

fun detectLibboxAbis(libboxAar: File): Set<String> {
    if (!libboxAar.isFile) {
        return emptySet()
    }
    val abiRegex = Regex("^jni/([^/]+)/libraybox\\.so$")
    ZipFile(libboxAar).use { zip ->
        return zip.entries().asSequence()
            .mapNotNull { entry -> abiRegex.matchEntire(entry.name)?.groupValues?.get(1) }
            .toSet()
    }
}

val availableLibboxAbis = detectLibboxAbis(libboxInputAar)
if (!abiOnly.isNullOrBlank() && abiOnly !in availableLibboxAbis) {
    throw GradleException(
        "Requested abiOnly=$abiOnly, but app/libs/raybox.aar does not contain it. " +
            "Available: ${availableLibboxAbis.sorted()}. " +
            "Rebuild libbox.aar with android/arm platform support first."
    )
}

val preferredDefaultAbis = listOf("arm64-v8a", "armeabi-v7a")
val missingDefaultAbis = preferredDefaultAbis.filterNot { it in availableLibboxAbis }
if (abiOnly.isNullOrBlank() && missingDefaultAbis.isNotEmpty()) {
    throw GradleException(
        "app/libs/raybox.aar is missing required default ABIs: $missingDefaultAbis. " +
            "Available: ${availableLibboxAbis.sorted()}. Run the pinned kernel sync first."
    )
}
val defaultAbis = preferredDefaultAbis
val apkAbis = abiOnly?.let { listOf(it) } ?: defaultAbis

val sfaApkArm64Path = providers.gradleProperty("sfaApkArm64").orNull?.takeIf { it.isNotBlank() }
val sfaApkArmPath = providers.gradleProperty("sfaApkArm").orNull?.takeIf { it.isNotBlank() }

val autoSfaUniversalDir = rootProject.projectDir
    .listFiles()
    ?.filter { it.isDirectory && it.name.startsWith("SFA-") && it.name.endsWith("-universal") }
    ?.sortedByDescending { it.name }
    ?.firstOrNull()

val stripLibboxAar = tasks.register("stripLibboxAar") {
    inputs.file(libboxInputAar)
    inputs.property("stripLibboxAarVersion", "3")
    inputs.property("enableSfaLibboxReplacement", enableSfaLibboxReplacement.toString())
    inputs.property("abiOnly", abiOnly ?: "")
    inputs.property("sfaApkArm64", providers.gradleProperty("sfaApkArm64").orNull ?: "")
    inputs.property("sfaApkArm", providers.gradleProperty("sfaApkArm").orNull ?: "")
    inputs.property("autoSfaUniversalDir", autoSfaUniversalDir?.absolutePath ?: "")
    outputs.file(libboxStrippedAar)

    doLast {
        if (!libboxInputAar.exists()) {
            throw GradleException("Missing libbox AAR: ${libboxInputAar.absolutePath}")
        }

        val props = Properties()
        val localPropsFile = rootProject.file("local.properties")
        if (localPropsFile.exists()) {
            props.load(localPropsFile.inputStream())
        }

        fun findNdkDir(): File? {
            val envNdk = System.getenv("ANDROID_NDK_HOME")
                ?: System.getenv("ANDROID_NDK_ROOT")
                ?: System.getenv("NDK_HOME")
            if (!envNdk.isNullOrBlank()) {
                val f = File(envNdk)
                if (f.isDirectory) return f
            }

            val ndkDirProp = props.getProperty("ndk.dir")
            if (!ndkDirProp.isNullOrBlank()) {
                val f = File(ndkDirProp)
                if (f.isDirectory) return f
            }

            val sdkDirProp = props.getProperty("sdk.dir")
            if (!sdkDirProp.isNullOrBlank()) {
                val ndkBundle = File(sdkDirProp, "ndk-bundle")
                if (ndkBundle.isDirectory) return ndkBundle

                val ndkRoot = File(sdkDirProp, "ndk")
                if (ndkRoot.isDirectory) {
                    val candidates = ndkRoot.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name }
                    return candidates?.firstOrNull()
                }
            }
            return null
        }

        val ndkDir = findNdkDir() ?: throw GradleException(
            "NDK not found. Set ANDROID_NDK_HOME (or ndk.dir in local.properties)."
        )

        fun findLlvmStripExe(): File {
            val prebuiltRoot = File(ndkDir, "toolchains/llvm/prebuilt")
            if (prebuiltRoot.isDirectory) {
                val candidates = prebuiltRoot
                    .listFiles()
                    ?.asSequence()
                    ?.filter { it.isDirectory }
                    ?.flatMap { prebuiltDir ->
                        sequenceOf(
                            File(prebuiltDir, "bin/llvm-strip.exe"),
                            File(prebuiltDir, "bin/llvm-strip")
                        )
                    }
                    ?.toList()
                    .orEmpty()
                candidates.firstOrNull { it.isFile }?.let { return it }
            }

            val recursive = ndkDir.walkTopDown()
                .firstOrNull { it.isFile && (it.name == "llvm-strip.exe" || it.name == "llvm-strip") }
            return recursive ?: throw GradleException(
                "llvm-strip not found under NDK: ${ndkDir.absolutePath}"
            )
        }

        val stripExe = findLlvmStripExe()

        val workDir = layout.buildDirectory.dir("stripped-libs/tmp/libbox").get().asFile
        workDir.deleteRecursively()
        workDir.mkdirs()

        copy {
            from(zipTree(libboxInputAar))
            into(workDir)
        }

        fun replaceLibboxSoFromSfaSource(source: File, abi: String) {
            val dstDir = File(workDir, "jni/$abi")
            dstDir.mkdirs()

            if (source.isDirectory) {
                val srcSo = File(source, "lib/$abi/libbox.so")
                if (!srcSo.isFile) {
                    throw GradleException("libbox.so not found in SFA directory for abi=$abi: ${srcSo.absolutePath}")
                }
                copy {
                    from(srcSo)
                    into(dstDir)
                    includeEmptyDirs = false
                }
            } else {
                if (!source.isFile) {
                    throw GradleException("SFA source not found: ${source.absolutePath}")
                }
                copy {
                    from(zipTree(source)) {
                        include("lib/$abi/libbox.so")
                    }
                    into(dstDir)
                    includeEmptyDirs = false
                    eachFile {
                        path = name
                    }
                }
            }

            val replaced = File(dstDir, "libbox.so")
            if (!replaced.isFile) {
                throw GradleException("libbox.so replacement failed for abi=$abi from: ${source.absolutePath}")
            }
        }

        val sfaArm64Source = if (enableSfaLibboxReplacement) (sfaApkArm64Path?.let(::File) ?: autoSfaUniversalDir) else null
        val sfaArmSource = if (enableSfaLibboxReplacement) (sfaApkArmPath?.let(::File) ?: autoSfaUniversalDir) else null

        val keepAbis = mutableSetOf<String>()

        if (sfaArm64Source != null) {
            replaceLibboxSoFromSfaSource(sfaArm64Source, "arm64-v8a")
            keepAbis.add("arm64-v8a")
        }
        if (sfaArmSource != null) {
            val v7aSo = if (sfaArmSource.isDirectory) File(sfaArmSource, "lib/armeabi-v7a/libbox.so") else null
            if (v7aSo == null || v7aSo.isFile) {
                replaceLibboxSoFromSfaSource(sfaArmSource, "armeabi-v7a")
                keepAbis.add("armeabi-v7a")
            }
        }

        val targetAbis = when {
            !abiOnly.isNullOrBlank() -> setOf(abiOnly)
            keepAbis.isNotEmpty() -> keepAbis
            else -> defaultAbis.toSet()
        }

        val jniDir = File(workDir, "jni")
        if (jniDir.isDirectory) {
            jniDir.walkTopDown()
                .filter { it.isFile && it.name == "libraybox.so" }
                .forEach { so ->
                    providers.exec {
                        commandLine(stripExe.absolutePath, "--strip-unneeded", so.absolutePath)
                    }.result.get()
                }

            jniDir.listFiles()
                ?.filter { it.isDirectory && it.name !in targetAbis }
                ?.forEach { it.deleteRecursively() }
        }

        val outAarFile = libboxStrippedAar.get().asFile
        outAarFile.parentFile.mkdirs()
        if (outAarFile.exists()) outAarFile.delete()
        ant.invokeMethod(
            "zip",
            mapOf(
                "destfile" to outAarFile.absolutePath,
                "basedir" to workDir.absolutePath
            )
        )
    }
}

configure<ApplicationExtension> {
    namespace = "com.kunk.singbox"
    compileSdk = 37

    ndkVersion = providers.gradleProperty("ndkVersion").orNull ?: "29.0.14206865"

    // 体积优先：优先压缩 APK 体积 (useLegacyPackaging = true)
    val preferCompressedApk = providers.gradleProperty("preferCompressedApk").orNull?.toBoolean() ?: true

    defaultConfig {
        applicationId = "com.kunk.singbox"
        minSdk = 24
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testBuildType = "release"
        buildConfigField("String", "RAYBOX_COMMIT", "\"$rayboxCoreCommit\"")
        buildConfigField("String", "RAYBOX_SOURCE_DIGEST", "\"$rayboxCoreDigest\"")
        buildConfigField("String", "RAYBOX_AAR_SHA256", "\"$rayboxCoreAarSha256\"")
        buildConfigField("boolean", "RAYBOX_DIRTY", (rayboxMetadata["dirty"] == true).toString())
        val coreTags = (rayboxMetadata["buildTags"] as? Map<*, *>)?.get("raybox.aar") as? List<*>
        buildConfigField(
            "boolean",
            "RAYBOX_NAIVE_QUIC_SUPPORTED",
            (coreTags?.contains("with_naive_outbound") == true).toString()
        )

        // Dynamic versioning
        val envVersionCode = System.getenv("VERSION_CODE")?.toIntOrNull()
        val gitCommitCount = gitOutput("rev-list", "--count", "HEAD")?.toIntOrNull()

        // Offset to ensure versionCode > previous hardcoded value (5946)
        val gitVersionCode = gitCommitCount?.let { 6000 + it } ?: 6001

        val gitVersionName = System.getenv("VERSION_NAME")
            ?: gitOutput("describe", "--tags", "--always")
            ?: "0.0.0-dev"

        versionCode = envVersionCode ?: gitVersionCode
        versionName = gitVersionName

        vectorDrawables {
            useSupportLibrary = true
        }

        androidResources {
            localeFilters += listOf("zh", "en") // 仅保留中文和英文资源，减少体积
        }
    }

    signingConfigs {
        create("release") {
            val props = Properties()
            val propsFile = rootProject.file("signing.properties")
            if (propsFile.exists()) {
                // 本地开发：从 signing.properties 文件读取
                props.load(propsFile.inputStream())
                storeFile = rootProject.file(props.getProperty("STORE_FILE"))
                storePassword = props.getProperty("KEYSTORE_PASSWORD")
                keyAlias = props.getProperty("KEY_ALIAS")
                keyPassword = props.getProperty("KEY_PASSWORD")
            } else {
                // CI 环境：从环境变量读取签名配置
                val keystorePath = System.getenv("KEYSTORE_PATH")
                val keystorePassword = System.getenv("KEYSTORE_PASSWORD")
                val keyAliasEnv = System.getenv("KEY_ALIAS")
                val keyPasswordEnv = System.getenv("KEY_PASSWORD")
                
                if (keystorePath != null) {
                    storeFile = File(keystorePath)
                    storePassword = keystorePassword
                    keyAlias = keyAliasEnv
                    keyPassword = keyPasswordEnv
                }
            }

        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // 在 debug 模式下也可以开启简单的分包优化，或者减小 ABI 范围
            // 如果仅用于本地调试，建议在 local.properties 中配置仅编译当前设备的架构
        }
    }
    
    splits {
        abi {
            // AAB 构建时不能启用多 APK 输出（否则 buildReleasePreBundle 会报 multiple shrunk-resources）
            isEnable = !isBundleBuild
            reset()
            isUniversalApk = false
            include(*apkAbis.toTypedArray())
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        aidl = true
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "**/kotlin/**"
            excludes += "**/*.kotlin_*"
            excludes += "**/META-INF/*.version"
            excludes += "DebugProbesKt.bin"
            excludes += "META-INF/*.kotlin_module"
            excludes += "META-INF/proguard/*"
        }
        // 优化 JNI 库打包方式
        // useLegacyPackaging = true 会压缩 APK 中的 .so，使下载体积最小（体积优先策略）
        // 但安装后会解压到 lib 目录，增加安装后占用。
        jniLibs {
            useLegacyPackaging = preferCompressedApk
        }
    }
    
    // 避免压缩规则集文件，提高读取效率
    androidResources {
        noCompress += "srs"
    }
    
    // 单元测试配置：返回 Android API 默认值，避免 android.util.* 抛异常
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// JVM Target Configuration for Kotlin
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

// 如果 libbox.aar 已经是精简版（只包含目标架构），可以跳过 strip 任务
val skipLibboxStrip = providers.gradleProperty("skipLibboxStrip").orNull?.toBoolean() ?: true

tasks.named("preBuild") { dependsOn(verifyRayboxCore) }

if (!skipLibboxStrip) {
    tasks.named("preBuild") {
        dependsOn(stripLibboxAar)
    }
}

dependencies {
    // 核心库 (libbox) - 本地 AAR 文件
    if (skipLibboxStrip) {
        implementation(files(libboxInputAar))
    } else {
        implementation(files(libboxStrippedAar))
    }
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:$composeBomVersion"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("dev.chrisbanes.haze:haze:1.7.2")
    implementation("io.github.kyant0:backdrop:2.0.0")
    
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-process:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("org.yaml:snakeyaml:2.6")
    implementation("com.tencent:mmkv:2.4.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.github.topjohnwu.libsu:core:6.0.0")
    implementation("com.github.topjohnwu.libsu:service:6.0.0")
    implementation("androidx.work:work-runtime-ktx:2.11.2")

    val roomVersion = "2.8.4"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:5.4.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:$composeBomVersion"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    
    detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:1.23.8")
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    baseline = file("$rootDir/config/detekt/baseline.xml")
    autoCorrect = true
}
