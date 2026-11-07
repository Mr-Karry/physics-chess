plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.whale.crazychess"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "dev.whale.crazychess"
        minSdk = 24
        targetSdk = 35
        versionCode = 8
        versionName = "1.3"
    }

    // Both halves of a Compose build must agree on the bytecode target. Without
    // these, AGP falls back to its own default (Java 8) while the Kotlin plugin
    // derives its target from whichever JDK runs Gradle, and any JDK newer than
    // the AGP release makes the two disagree:
    //
    //   'compileDebugJavaWithJavac' and 'compileDebugKotlin' have different JVM
    //   target compatibility: 1.8 and 22
    //
    // Pinning both to the same version is what makes a freshly generated project
    // build on a machine whose only JDK is newer than the template's.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // 签名材料不写进仓库。设置了 CRAZYCHESS_KEYSTORE 时，debug 与 release 共用同一把
    // 自签证书（这样 `adb install -r` 可以在两种构建之间来回覆盖）；没设置就退回
    // Gradle 默认行为（debug 用 ~/.android/debug.keystore，release 不签名）。
    val keystorePath: String? = System.getenv("CRAZYCHESS_KEYSTORE")
    val keystoreFile = keystorePath?.let { file(it) }
    val hasKeystore = keystoreFile != null && keystoreFile.exists()
    val storePass = System.getenv("CRAZYCHESS_KEYSTORE_PASSWORD") ?: "crazychess"
    val keyAliasName = System.getenv("CRAZYCHESS_KEY_ALIAS") ?: "crazychess"

    signingConfigs {
        if (hasKeystore) {
            create("shared") {
                storeFile = keystoreFile
                storePassword = storePass
                keyAlias = keyAliasName
                keyPassword = System.getenv("CRAZYCHESS_KEY_PASSWORD") ?: storePass
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (hasKeystore) signingConfig = signingConfigs.getByName("shared")
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasKeystore) signingConfig = signingConfigs.getByName("shared")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    // 只用到 ThumbUp 一个图标；R8 会把没用到的图标全部裁掉
    implementation(libs.androidx.compose.material.icons.extended)

    testImplementation(libs.junit)
}

