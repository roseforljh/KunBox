import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("io.gitlab.arturbosch.detekt")
}

val libboxInputAar = file("libs/libbox.aar")
val libboxStrippedAarId = providers.gradleProperty("libboxStrippedAarId").orNull
    ?: System.currentTimeMillis().toString()
val libboxStrippedAar = layout.buildDirectory.file("stripped-libs/libbox-stripped-$libboxStrippedAarId.aar")

val enableSfaLibboxReplacement = (project.findProperty("enableSfaLibboxReplacement") as String?)
    ?.toBoolean()
    ?: false

val isBundleBuild = gradle.startParameter.taskNames.any { it.contains("bundle", ignoreCase = true) }

val abiOnly = (project.findProperty("abiOnly") as String?)
    ?.trim()
    ?.takeIf { it.isNotBlank() }

val defaultAbis = listOf("arm64-v8a")
val apkAbis = abiOnly?.let { listOf(it) } ?: defaultAbis

val autoSfaUniversalDir = rootProject.projectDir
    .listFiles()
    ?.filter { it.isDirectory && it.name.startsWith("SFA-") && it.name.endsWith("-universal") }
    ?.sortedByDescending { it.name }
    ?.firstOrNull()

val stripLibboxAar = tasks.register("stripLibboxAar") {
    inputs.file(libboxInputAar)
    inputs.property("stripLibboxAarVersion", "2")
    inputs.property("libboxStrippedAarId", libboxStrippedAarId)
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

        val enableReplacement = (project.findProperty("enableSfaLibboxReplacement") as String?)
            ?.toBoolean()
            ?: false

        val sfaApkArm64Prop = (project.findProperty("sfaApkArm64") as String?)
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it) }
        val sfaApkArmProp = (project.findProperty("sfaApkArm") as String?)
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it) }

        val sfaArm64Source = if (enableReplacement) (sfaApkArm64Prop ?: autoSfaUniversalDir) else null
        val sfaArmSource = if (enableReplacement) (sfaApkArmProp ?: autoSfaUniversalDir) else null

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
                .filter { it.isFile && it.name == "libbox.so" }
                .forEach { so ->
                    exec {
                        commandLine(stripExe.absolutePath, "--strip-unneeded", so.absolutePath)
                    }
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

android {
    namespace = "com.kunk.singbox"
    compileSdk = 36

    ndkVersion = (project.findProperty("ndkVersion") as String?) ?: "29.0.14206865"

    // NekoBox 风格：优先压缩 APK 体积 (useLegacyPackaging = true)
    val preferCompressedApk = (project.findProperty("preferCompressedApk") as String?)?.toBoolean() ?: true

    defaultConfig {
        applicationId = "com.kunk.singbox"
        minSdk = 24
        targetSdk = 36
        
        // Dynamic versioning
        val cmd = "git rev-list --count HEAD"
        val process = Runtime.getRuntime().exec(cmd)
        val gitCommitCount = process.inputStream.bufferedReader().readText().trim().toIntOrNull() ?: 1
        // Offset to ensure versionCode > previous hardcoded value (5946)
        val gitVersionCode = 6000 + gitCommitCount

        val gitVersionName = System.getenv("VERSION_NAME") ?: run {
             val p = Runtime.getRuntime().exec("git describe --tags --always")
             p.inputStream.bufferedReader().readText().trim()
        }

        versionCode = gitVersionCode
        versionName = gitVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        resConfigs("zh", "en") // 仅保留中文和英文资源，减少体积
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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        aidl = true
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.4"
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
        // useLegacyPackaging = true 会压缩 APK 中的 .so，使下载体积最小（类似 NekoBox）
        // 但安装后会解压到 lib 目录，增加安装后占用。
        jniLibs {
            useLegacyPackaging = preferCompressedApk
        }
    }
    
    // 避免压缩规则集文件，提高读取效率
    aaptOptions {
        noCompress("srs")
    }
    
    // 单元测试配置：返回 Android API 默认值，避免 android.util.* 抛异常
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// 如果 libbox.aar 已经是精简版（只包含目标架构），可以跳过 strip 任务
val skipLibboxStrip = (project.findProperty("skipLibboxStrip") as String?)?.toBoolean() ?: true

if (!skipLibboxStrip) {
    tasks.named("preBuild") {
        dependsOn(stripLibboxAar)
    }
}

dependencies {
    // Sing-box 核心库 (libbox) - 本地 AAR 文件
    // 请从 sing-box 官方仓库下载或编译 libbox AAR 并放置在 app/libs 目录
    // 参见: https://github.com/SagerNet/sing-box
    if (skipLibboxStrip) {
        implementation(files(libboxInputAar))
    } else {
        implementation(files(libboxStrippedAar))
    }
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))

    // 优化: 更新核心依赖版本
    implementation("androidx.core:core-ktx:1.13.1")  // 从 1.12.0 更新
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")  // 从 2.7.0 更新
    implementation("androidx.appcompat:appcompat:1.7.0")  // 从 1.6.1 更新
    implementation("androidx.activity:activity-compose:1.9.3")  // 从 1.9.0 更新
    implementation(platform("androidx.compose:compose-bom:2024.11.00"))  // 从 2024.06.00 更新（修正：2024.12.00不存在）
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.8.0")
    
    // Icons
    implementation("androidx.compose.material:material-icons-extended")

    // DataStore for settings persistence
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    
    // ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")  // 从 2.7.0 更新

    // ProcessLifecycleOwner - 用于检测应用前后台状态
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")

    // Network - OkHttp
    implementation("com.squareup.okhttp3:okhttp:4.12.0")  // 已是最新版本

    // JSON parsing - Gson
    implementation("com.google.code.gson:gson:2.11.0")  // 从 2.10.1 更新

    // YAML parsing
    implementation("org.yaml:snakeyaml:2.2")

    // MMKV - 高性能跨进程 KV 存储
    implementation("com.tencent:mmkv:1.3.2")

    // QR Code scanning - ZXing
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Room Database
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2023.08.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    
    detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:1.23.7")
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    baseline = file("$rootDir/config/detekt/baseline.xml")
    autoCorrect = true
}