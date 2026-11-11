import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Rust core (calzone-ffi) built by `make core` in the sibling repo:
//   <czCoreDir>/android/jniLibs/<abi>/libcalzone_ffi.so, <czCoreDir>/android/kotlin/...,
//   <czCoreDir>/host/libcalzone_ffi.so
val czCoreDir: File = rootProject.file(providers.gradleProperty("czCoreDir").get())
val czCoreKotlin = czCoreDir.resolve("android/kotlin")
val czCoreJniLibs = czCoreDir.resolve("android/jniLibs")
val czCoreHostLib = czCoreDir.resolve("host/libcalzone_ffi.so")

// Release signing from a git-ignored keystore.properties or env vars; unsigned otherwise.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(propKey: String, envKey: String): String? =
    (keystoreProps.getProperty(propKey) ?: System.getenv(envKey))?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("storeFile", "RELEASE_STORE_FILE")
val releaseStorePassword = signingValue("storePassword", "RELEASE_STORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "RELEASE_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "RELEASE_KEY_PASSWORD")
val hasReleaseSigning = releaseStoreFile != null && releaseStorePassword != null &&
    releaseKeyAlias != null && releaseKeyPassword != null

val appId = "com.xblackbytesx.calzone"

android {
    namespace = appId
    compileSdk = 37
    // The build image installs 37.0.0; without this AGP 9.4 fetches its default
    // (36) into the throwaway container on every build.
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = appId
        // Both phones run GrapheneOS on the newest Android (decision 44 in
        // calzone-server): nothing older is supported.
        minSdk = 35
        targetSdk = 37
        versionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("appVersionName") as String?) ?: "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        // The Android account type is the application id, so a debug build
        // and a release build each have their own account and sync adapters.
        resValue("string", "account_type", appId)
    }

    signingConfigs {
        getByName("debug") {
            val ks = rootProject.file("keystore/debug.keystore")
            if (ks.exists()) {
                storeFile = ks
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            resValue("string", "account_type", "$appId.debug")
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    sourceSets {
        getByName("main") {
            kotlin.directories.add(czCoreKotlin.path)
            jniLibs.directories.add(czCoreJniLibs.path)
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    testOptions {
        // Robolectric renders real Compose UI for the screenshot tests.
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // JVM tests load the HOST build of the same Rust core through JNA.
            it.systemProperty("uniffi.component.calzone_ffi.libraryOverride", czCoreHostLib.absolutePath)
            // The server's /sync/v1 golden files: the app and the core must parse them.
            it.systemProperty("cz.golden", czCoreDir.resolve("../crates/server/tests/golden").absolutePath)
            // Roborazzi without its Gradle plugin only writes in record mode.
            it.systemProperty("roborazzi.test.record", "true")
            // Screenshots land outside the repo unless a path is given.
            it.systemProperty(
                "cz.screenshots",
                (project.findProperty("czScreenshots") as String?)
                    ?: layout.buildDirectory.dir("screenshots").get().asFile.absolutePath,
            )
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

// Fail early and clearly instead of with a wall of unresolved `uniffi.calzone_ffi` imports.
// The native libs are only needed in an APK; JVM tests use the host lib.
val checkCzCore = tasks.register("checkCzCore") {
    val bindings = czCoreKotlin.resolve("uniffi/calzone_ffi/calzone_ffi.kt")
    val native = czCoreJniLibs.resolve("arm64-v8a/libcalzone_ffi.so")
    val needsNative = gradle.startParameter.taskNames.any { "assemble" in it || "bundle" in it || "install" in it }
    doLast {
        check(bindings.exists() && (!needsNative || native.exists())) {
            "Rust core not built: run `make core` (builds ../calzone-server android-core into $czCoreDir)"
        }
    }
}
tasks.named("preBuild") { dependsOn(checkCzCore) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)

    implementation(libs.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    // The home screen widgets (phase 4b step 6).
    implementation(libs.androidx.glance.appwidget)

    implementation(libs.okhttp)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.zxing.core)
    implementation(libs.unifiedpush.connector)
    testImplementation(libs.okhttp.mockwebserver)

    // JNA: the AAR carries Android's libjnidispatch.so; JVM tests need the plain jar.
    implementation(libs.jna) { artifact { type = "aar" } }
    testImplementation(libs.jna)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.kotlinx.coroutines.test)
}
