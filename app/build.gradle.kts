import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
}

/**
 * Release signing is optional and never committed.
 *
 * Supply a keystore either through `keystore.properties` in the repository root
 * (git-ignored) or through the environment (`ROADGUARD_KEYSTORE_FILE`,
 * `ROADGUARD_KEYSTORE_PASSWORD`, `ROADGUARD_KEY_ALIAS`, `ROADGUARD_KEY_PASSWORD`).
 * When nothing is supplied, `assembleRelease` falls back to the Android debug key so CI
 * still produces an installable, sideloadable APK -- see docs/build.md.
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(propertyKey: String, environmentKey: String): String? =
    keystoreProperties.getProperty(propertyKey) ?: System.getenv(environmentKey)

val releaseKeystorePath = signingValue("storeFile", "ROADGUARD_KEYSTORE_FILE")
val hasReleaseKeystore = releaseKeystorePath != null && file(releaseKeystorePath).exists()

android {
    namespace = "io.github.tunlezah.roadguard"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.tunlezah.roadguard"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // MapLibre ships native renderers. A single universal APK keeps sideloading
        // simple; x86_64 is included so the app can be exercised on an emulator.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = signingValue("storePassword", "ROADGUARD_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "ROADGUARD_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "ROADGUARD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isPseudoLocalesEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                // Documented fallback so CI can publish a sideloadable artifact.
                versionNameSuffix = "-unsigned-release"
                signingConfigs.getByName("debug")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = false
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/INDEX.LIST",
            )
        }
    }

    // Room exports its schema so migrations can be reviewed in code review.
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
        arg("room.generateKotlin", "true")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
        checkDependencies = false
        // LogNotTimber only makes sense in a project that uses Timber; Roadguard logs with
        // android.util.Log deliberately, because a dashcam should not pull in a logging framework
        // whose whole value is routing logs somewhere.
        disable += setOf(
            "GradleDependency",
            "NewerVersionAvailable",
            "ObsoleteLintCustomCheck",
            "LogNotTimber",
        )
        htmlReport = true
        xmlReport = true
        sarifReport = false

        /*
         * The baseline holds four categories, all reviewed, and nothing else. It exists so that a
         * *new* lint error still fails the build rather than being lost in noise -- if you add to
         * it, say why here.
         *
         *  UnsafeOptInUsageError (15)
         *      Kotlin `@file:OptIn` declarations that Android lint cannot see through: the Camera2
         *      interop needed to read a camera's hardware level and focal lengths, and media3's
         *      Compose playback surface. Both opt-ins are explicit in the source, confined to one
         *      file each, and explained there.
         *
         *  OldTargetApi (2)
         *      targetSdk is deliberately 36 (Android 16) against compileSdk 37: Roadguard opts in to
         *      Android 16 behaviour and compiles against 37 so newer APIs can be used behind version
         *      checks, without inheriting API 37's behaviour changes untested.
         *
         *  IconLauncherShape (5)
         *      The legacy per-density launcher PNGs fill their square because the supplied artwork
         *      *is* a squircle badge on black. Every device Roadguard runs on (minSdk 34) uses the
         *      adaptive icon instead, whose foreground is correctly inset into the 72dp safe area.
         *
         *  MonochromeLauncherIcon (2)
         *      No monochrome layer is shipped. The artwork is photographic; an automatically derived
         *      silhouette would misrepresent it and hand-drawing one would mean inventing competing
         *      artwork. Themed icons therefore fall back to the full-colour adaptive icon.
         */
        baseline = file("lint-baseline.xml")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.startup)
    implementation(libs.androidx.concurrent.futures.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.material3.adaptive)
    implementation(libs.androidx.window)

    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.video)
    implementation(libs.camerax.view)
    implementation(libs.camerax.effects)
    implementation(libs.camerax.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui.compose)

    implementation(libs.maplibre)
    implementation(libs.okhttp)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.truth)
    testImplementation(libs.room.testing)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.uiautomator)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.truth)
}
