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
        versionCode = 1
        versionName = "1.0"
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

    // The sandbox forbids writing to C:\Users\<user>\.android, so the default
    // debug keystore location is unusable here. Both variants are signed with
    // the same workspace keystore so `adb install -r` can hop between a debug
    // and a release build without uninstalling first.
    signingConfigs {
        create("workspaceDebug") {
            storeFile = file("D:/whaletest/work/release.keystore")
            storePassword = "crazychess"
            keyAlias = "crazychess"
            keyPassword = "crazychess"
        }
        // release 包也放在工作区里，避免碰 C:\Users\<user>\.android
        create("release") {
            storeFile = file("D:/whaletest/work/release.keystore")
            storePassword = "crazychess"
            keyAlias = "crazychess"
            keyPassword = "crazychess"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("workspaceDebug")
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
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

