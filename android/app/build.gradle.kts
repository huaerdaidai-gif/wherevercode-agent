import java.io.ByteArrayOutputStream
import java.time.Instant

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
    id("com.chaquo.python")
}

// V5 C1：真实版本信息——提交 SHA 与 UTC 构建时间，烘进 BuildConfig。
// `git rev-parse HEAD` 在 CI 的浅克隆里也能工作（HEAD 提交本身存在）；
// 不在 git 仓库里（例如纯源码导出）或 git 不可用时回退为 "unknown"，构建不失败。
// 注意：不能写 java.io.ByteArrayOutputStream / java.time.Instant——
// Kotlin DSL 脚本里 `java` 会被 Project.java 扩展遮住，必须用 import。
val commitSha: String = try {
    val gitOut = ByteArrayOutputStream()
    exec {
        commandLine("git", "rev-parse", "HEAD")
        standardOutput = gitOut
        isIgnoreExitValue = true
    }
    gitOut.toString(Charsets.UTF_8).trim()
        .takeIf { it.matches(Regex("[0-9a-f]{40}")) } ?: "unknown"
} catch (_: Exception) {
    "unknown"
}
val buildTimeUtc: String = Instant.now().toString()
fun releaseConfigValue(property: String, environment: String): String =
    providers.gradleProperty(property).orElse(providers.environmentVariable(environment)).getOrElse("")
fun buildConfigString(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

// Public builds default to no platform host, channel IDs, or certificate pin.
// Maintainers may provide these through local Gradle properties or environment
// variables; never commit private deployment values to this repository.
val platformHost = releaseConfigValue("platformHost", "WHEREVERCODE_PLATFORM_HOST")
val platformDefaultChannel = releaseConfigValue("platformDefaultChannel", "WHEREVERCODE_PLATFORM_DEFAULT_CHANNEL")
val platformV5Channel = releaseConfigValue("platformV5Channel", "WHEREVERCODE_PLATFORM_V5_CHANNEL")
val pinnedPlatformHost = releaseConfigValue("pinnedPlatformHost", "WHEREVERCODE_PINNED_PLATFORM_HOST")
val pinnedPlatformSha256 = releaseConfigValue("pinnedPlatformSha256", "WHEREVERCODE_PINNED_PLATFORM_SHA256")
val updateDriveFileId = releaseConfigValue("updateDriveFileId", "WHEREVERCODE_UPDATE_DRIVE_FILE_ID")

android {
    namespace = "wherevercode.agent"
    compileSdk = 34

    defaultConfig {
        applicationId = "wherevercode.agent"
        // K70 runs Android 14; minSdk 26 keeps the spike simple.
        minSdk = 26
        // Deliberate: targetSdk 28 (see android/README.md).
        // Android 10 (API 29+) forbids execve() on files in the app's writable
        // home directory (W^X). Termux kept targetSdk 28 for exactly this reason:
        // npm-installed agent CLIs must stay executable in the sandbox.
        // Cost: no Google Play (Play requires targetSdk 35 since 2025-08-31).
        // This spike ships as a sideloaded APK, per the task.
        targetSdk = 28
        versionCode = 2
        versionName = "0.2.0-ui2"

        // V5 C1：真实版本信息（commitSha / buildTimeUtc 见文件顶部）。
        buildConfigField("String", "COMMIT_SHA", "\"$commitSha\"")
        buildConfigField("String", "BUILD_TIME", "\"$buildTimeUtc\"")
        buildConfigField("String", "PLATFORM_HOST", buildConfigString(platformHost))
        buildConfigField("String", "PLATFORM_DEFAULT_CHANNEL", buildConfigString(platformDefaultChannel))
        buildConfigField("String", "PLATFORM_V5_CHANNEL", buildConfigString(platformV5Channel))
        buildConfigField("String", "PINNED_PLATFORM_HOST", buildConfigString(pinnedPlatformHost))
        buildConfigField("String", "PINNED_PLATFORM_SHA256", buildConfigString(pinnedPlatformSha256))
        buildConfigField("String", "UPDATE_DRIVE_FILE_ID", buildConfigString(updateDriveFileId))

        ndk {
            // K70 is arm64; one ABI keeps the APK small and the spike focused.
            abiFilters += "arm64-v8a"
        }
    }

    buildFeatures {
        // V5 C1：新版 AGP 默认不生成 BuildConfig；打开它才能读 COMMIT_SHA / BUILD_TIME。
        buildConfig = true
    }

    // Open-source builds use Android Gradle Plugin's standard local debug signing.
    // No shared private key or signing password is stored in this repository.
    // APKs signed with a new key cannot update installations signed by the
    // historical pinned debug key; users may need to uninstall first.
    
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            // UI V2 Robolectric tests inflate real views/drawables; this
            // makes AGP package the app resources for unit tests.
            isIncludeAndroidResources = true
        }
    }
}

chaquopy {
    defaultConfig {
        // Match the Chaquopy plugin version pinned in android/build.gradle.kts.
        version = "3.11"
        // Keep the build interpreter aligned with the embedded Python minor
        // version. GitHub's ubuntu-latest default `python3` may be 3.12, which
        // prevents Chaquopy from compiling the app's Python sources to bytecode.
        buildPython("python3.11")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    // M3 (SIWC OAuth): Custom Tabs opens the ChatGPT sign-in page in the
    // user's browser for the SIWC authorization flow. No other new deps:
    // token storage reuses security-crypto, HTTP reuses HttpsURLConnection.
    implementation("androidx.browser:browser:1.8.0")
    // Stage 2 (Brain Engine + core): coroutines for the engine's Flow-based
    // chat loop, Room for the task store (kapt, see below), security-crypto
    // for Keystore-backed EncryptedSharedPreferences (brain keys).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    // UI V2: ChatViewModel survives Activity recreation (theme switch /
    // rotation) so an in-flight engine turn and its transcript are not lost.
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.5.1")
    implementation("androidx.lifecycle:lifecycle-livedata-core:2.5.1")
    // V5 按主流改（maintainer 2026-11-11）：“+”菜单附件拷贝用 lifecycleScope
    // （Activity 销毁时自动取消；lifecycleScope 在 lifecycle-runtime-ktx 里，
    // 之前没引这个包，CI 编译曾因此报 Unresolved reference）。
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.5.1")
    implementation("androidx.room:room-runtime:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // V5 一键安装（SSH 一键装 Hand）：mwiede JSch 0.2.21（JSch 0.1.55 的
    // 维护分支，支持 curve25519/chacha20/rsa-sha2；POM 无运行时依赖、
    // 自包含，不带 BouncyCastle）。只用于安装流程，密码/私钥只放内存。
    implementation("com.github.mwiede:jsch:0.2.21")
    // NOTE: no BouncyCastle dependency. DeviceKeys uses a pure-Kotlin
    // RFC 7748 X25519 ladder instead (minSdk 26; JCA "XDH" needs API 31+),
    // so the crypto works identically in JVM unit tests and on the phone.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("org.robolectric:robolectric:4.11.1")
    // Real org.json for unit tests: the android.jar on the unit-test
    // classpath only has stubs that throw RuntimeException ("Stub!").
    // testImplementation only — the APK keeps using the framework copy.
    testImplementation("org.json:json:20240303")
}
