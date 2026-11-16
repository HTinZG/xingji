import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
}

// 高德 Key 不进版本库、不进任何项目文件、也不进命令行历史。
// 取值顺序：-PAMAP_KEY=xxx  →  环境变量 AMAP_KEY  →  空串（地图会白屏但不影响编译）
val amapKey: String =
    (project.findProperty("AMAP_KEY") as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv("AMAP_KEY")?.takeIf { it.isNotBlank() }
        ?: ""

// ---------------------------------------------------------------------------
// release 签名：密码只能来自 local.properties 或环境变量，绝不写进本文件。
// local.properties 已在 .gitignore 里，不会被提交。
//
// 需要的四个值（前两个是路径/别名，后两个是密码）：
//   xingji.release.storeFile       例子：D:\\AndroidKeys\\xingji-release.jks
//   xingji.release.keyAlias        例子：xingji
//   xingji.release.storePassword   ← 你自己填，我不接触
//   xingji.release.keyPassword     ← 你自己填，我不接触
//
// 也可以用环境变量：XINGJI_STORE_FILE / XINGJI_KEY_ALIAS /
//                   XINGJI_STORE_PASSWORD / XINGJI_KEY_PASSWORD
// ---------------------------------------------------------------------------
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun secret(propKey: String, envKey: String): String? =
    (localProps.getProperty(propKey) ?: System.getenv(envKey))
        ?.trim()
        // CHANGE_ME 是模板占位符，等同于「没填」
        ?.takeIf { it.isNotBlank() && it != "CHANGE_ME" }

val releaseStoreFile = secret("xingji.release.storeFile", "XINGJI_STORE_FILE")
val releaseKeyAlias = secret("xingji.release.keyAlias", "XINGJI_KEY_ALIAS")
val releaseStorePassword = secret("xingji.release.storePassword", "XINGJI_STORE_PASSWORD")

// PKCS12 keystore 只有一个密码。keyPassword 没填时直接复用 storePassword，
// 避免"两个密码写成不一样"这种小白最容易踩的坑。
val releaseKeyPassword = secret("xingji.release.keyPassword", "XINGJI_KEY_PASSWORD")
    ?: releaseStorePassword

val hasReleaseSigning = listOf(
    releaseStoreFile, releaseKeyAlias, releaseStorePassword, releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "com.llyytt.path"
    compileSdk = 36
    // 本机已装 36.0.0；不写这行 AGP 会去找它默认的 35.0.0 然后失败
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.llyytt.path"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.2.0"

        manifestPlaceholders["amapKey"] = amapKey

        // K80 是 arm64；只打一个 ABI，16KB 检查结果才唯一
        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    // 调试签名固定在工程内，避免换电脑/重装 Android Studio 后 SHA1 变化导致高德 Key 失效。
    // 密码 "android" 是 Android 调试证书的公开约定，不是机密；正式包用 release keystore。
    //
    // 注意：signingConfigs 必须写在 buildTypes 之前——buildTypes 里要引用它，
    // 顺序反了会报 "SigningConfig with name 'release' not found"。
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/xingji-debug.jks")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }

        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // R8 开着：缩小体积 + 混淆。地图 SDK 和 Room 的 keep 规则在 proguard-rules.pro
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 没配密码就不签名，产出 app-release-unsigned.apk，绝不退化成调试签名
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    // 本地 aar 没有 POM，关掉 metadata 检查省得 Sync 报警
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

kapt {
    correctErrorTypes = true
    arguments {
        // 导出 Room schema，做数据库迁移时要用
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

dependencies {
    // 版本刻意停在「AGP 8.x / compileSdk 36」这一代：
    // 2026 年的最新 AndroidX 要求 AGP 9.1 + compileSdk 37，会把工具链顶到未验证的组合上。
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.activity:activity-compose:1.10.1")

    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    // Room。用 KAPT 而不是 KSP：KSP 的版本号要跟 Kotlin 严格配对，
    // 当前 Kotlin 2.4.21 没有对应的 KSP 发布，KAPT 是稳妥选择。
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")

    // AI 聊天：HTTP + 加密设置存储
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    kapt("androidx.room:room-compiler:2.8.5")
    // Room 的处理器自带的 kotlin-metadata-jvm 只认到 Kotlin 2.3 的元数据，
    // 而本项目用 Kotlin 2.4.21（元数据 2.4.0）。这里把读取库顶到同版本。
    kapt("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.21")

    testImplementation("junit:junit:4.13.2")

    // 高德 3D 地图 SDK（11.3.100）。官方自 11.x 起只发「classes.jar + 独立 .so」，
    // 不再提供单文件 AAR，所以 classes 放 libs/、native 放 src/main/jniLibs/。
    implementation(files("libs/amap-sdk-11.3.100.jar"))
}

