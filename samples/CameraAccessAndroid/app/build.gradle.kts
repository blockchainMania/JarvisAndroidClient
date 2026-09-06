/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

/**
 * The commit this APK was actually built from.
 *
 * Read at configuration time and surfaced in Settings because "is the running app built from the
 * code I think it is?" has been unanswerable from the phone, and guessing it wrong has cost more
 * than one debugging session: a stale Gradle build reports BUILD SUCCESSFUL while leaving a
 * week-old APK in place, and there is no way to notice from the device.
 */
fun gitDescribe(): String = try {
    val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
    val text = process.inputStream.bufferedReader().readText().trim()
    process.waitFor()
    text.ifEmpty { "unknown" }
} catch (e: Exception) {
    "unknown"
}

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
  alias(libs.plugins.compose.compiler)
  id("com.google.gms.google-services")
}

android {
  namespace = "com.meta.wearable.dat.externalsampleapps.cameraaccess"
  compileSdk = 35

  buildFeatures { buildConfig = true }

  defaultConfig {
    applicationId = "com.meta.wearable.dat.externalsampleapps.cameraaccess"
    minSdk = 31
    targetSdk = 34
    versionCode = 1
    versionName = "1.0"

    buildConfigField("String", "GIT_SHA", "\"${gitDescribe()}\"")
    buildConfigField(
        "String",
        "BUILD_TIME",
        "\"${
            SimpleDateFormat("yyyy-MM-dd HH:mm").apply {
                timeZone = TimeZone.getTimeZone("Asia/Seoul")
            }.format(Date())
        }\"",
    )

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    vectorDrawables { useSupportLibrary = true }
    ndk {
      abiFilters += listOf("arm64-v8a", "armeabi-v7a")
    }
    externalNativeBuild {
      cmake {
        arguments += listOf("-DANDROID_STL=c++_shared")
        // Android's 16KB page-size requirement needs every .so's ELF LOAD segments 4KB-aligned
        // at minimum -> 16KB; our own CMake targets (jarvis_whisper*, and ggml pulled in via
        // FetchContent under the same CMake project) were linking at the NDK's old 4KB default.
        // Must be CMAKE_*_LINKER_FLAGS specifically, not cFlags/cppFlags -- those apply to every
        // compile invocation too, where a bare -Wl,... passthrough is a no-op linker flag on a
        // non-link step (clang warns "linker input unused" on each one).
        arguments += listOf(
          "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384",
          "-DCMAKE_EXE_LINKER_FLAGS=-Wl,-z,max-page-size=16384",
        )
      }
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("debug")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
  }
  kotlinOptions { jvmTarget = "1.8" }
  buildFeatures { compose = true }
  composeOptions { kotlinCompilerExtensionVersion = "1.5.1" }
  externalNativeBuild {
    cmake {
      path = file("src/main/cpp/CMakeLists.txt")
    }
  }
  packaging {
    resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    jniLibs { pickFirsts += "**/libc++_shared.so" }
  }
  signingConfigs {
    getByName("debug") {
      storeFile = file("sample.keystore")
      storePassword = "sample"
      keyAlias = "sample"
      keyPassword = "sample"
    }
  }
}

dependencies {
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform("com.google.firebase:firebase-bom:34.15.0"))
  implementation(libs.androidx.exifinterface)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.material.icons.extended)
  implementation(libs.androidx.material3)
  implementation(libs.kotlinx.collections.immutable)
  implementation(libs.mwdat.core)
  implementation(libs.mwdat.camera)
  implementation(libs.mwdat.display)
  implementation(libs.mwdat.mockdevice)
  // VisionClaw additions
  implementation(libs.okhttp)
  implementation(libs.webrtc)
  implementation(libs.camerax.core)
  implementation(libs.camerax.camera2)
  implementation(libs.camerax.lifecycle)
  implementation(libs.camerax.view)
  implementation(libs.datastore.preferences)
  implementation(libs.gson)
  implementation(libs.lifecycle.process)
  androidTestImplementation(libs.androidx.ui.test.junit4)
  androidTestImplementation(libs.androidx.test.uiautomator)
  androidTestImplementation(libs.androidx.test.rules)
}
