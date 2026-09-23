plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("androidx.baselineprofile")
}

val releaseSigningValues = mapOf(
    "KEYSTORE_FILE" to System.getenv("KEYSTORE_FILE"),
    "KEYSTORE_PASSWORD" to System.getenv("KEYSTORE_PASSWORD"),
    "KEY_ALIAS" to System.getenv("KEY_ALIAS"),
    "KEY_PASSWORD" to System.getenv("KEY_PASSWORD")
)
val hasReleaseSigning = releaseSigningValues.values.all { !it.isNullOrBlank() }
val verifyReleaseSigning = tasks.register("verifyReleaseSigning") {
    releaseSigningValues.keys.forEach { key ->
        inputs.property(key, providers.environmentVariable(key).orElse(""))
    }
    doLast {
        val missing = inputs.properties
            .filterValues { it.toString().isBlank() }
            .keys
            .joinToString()
        if (missing.isNotEmpty()) {
            throw GradleException("Release signing is required; missing: $missing")
        }
    }
}
tasks.configureEach {
    // Guard only the production release variant. The Baseline Profile plugin
    // creates a separate benchmarkRelease variant that is deliberately
    // test-signed for connected-device performance runs and is never shipped.
    val producesReleaseArtifact = name.startsWith("assembleRelease") ||
        name.startsWith("bundleRelease") ||
        name.startsWith("packageRelease")
    if (producesReleaseArtifact) dependsOn(verifyReleaseSigning)
}

android {
    namespace = "com.giantbomb.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.giantbomb.tv"
        minSdk = 23
        targetSdk = 36
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 9999
        versionName = System.getenv("VERSION_NAME") ?: "0.0.0-dev"

        // Inline YouTube playback using internal API (not compliant with store policies).
        // Set to true if building for sideloading / personal use.
        buildConfigField("boolean", "ENABLE_INLINE_YOUTUBE", "false")

        // Self-update from GitHub releases. Requires REQUEST_INSTALL_PACKAGES permission.
        // Disable for store builds (store handles updates). Enable for sideloaded builds.
        buildConfigField("boolean", "ENABLE_SELF_UPDATE", "false")
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = file(releaseSigningValues.getValue("KEYSTORE_FILE")!!)
                storePassword = releaseSigningValues.getValue("KEYSTORE_PASSWORD")
                keyAlias = releaseSigningValues.getValue("KEY_ALIAS")
                keyPassword = releaseSigningValues.getValue("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // Existing issues are captured in lint-baseline.xml; CI fails only on new ones.
        baseline = file("lint-baseline.xml")
        warningsAsErrors = false
        abortOnError = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.leanback:leanback:1.0.0")
    implementation("androidx.leanback:leanback-preference:1.0.0")

    // Material Components (needed by the mobile bottom-navigation bar)
    implementation("com.google.android.material:material:1.12.0")

    // ExoPlayer
    implementation("androidx.media3:media3-exoplayer:1.10.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.10.1")
    implementation("androidx.media3:media3-ui:1.10.1")
    implementation("androidx.media3:media3-ui-leanback:1.10.1")
    implementation("androidx.media3:media3-session:1.10.1")
    implementation("androidx.media3:media3-cast:1.10.1")

    // Chromecast
    implementation("com.google.android.gms:play-services-cast-framework:22.0.0")
    implementation("androidx.mediarouter:mediarouter:1.7.0")

    // CardView
    implementation("androidx.cardview:cardview:1.0.0")

    // Activity (enableEdgeToEdge)
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    // Image loading
    // Glide 5.0.9 requires compileSdk 37, beyond AGP 9.0's supported SDK.
    // Keep the mature v4 line until the Android 17 toolchain is available.
    implementation("com.github.bumptech.glide:glide:4.16.0")
    implementation("com.github.bumptech.glide:okhttp3-integration:4.16.0")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:5.3.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.3.0")
    testImplementation("org.json:json:20231013")
    baselineProfile(project(":benchmark"))
}
