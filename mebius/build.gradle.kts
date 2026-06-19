import com.vanniktech.maven.publish.SonatypeHost

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.dokka)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    alias(libs.plugins.maven.publish)
}

android {
    namespace = "io.mebius.sdk"
    // minSdk 24 (Android 7.0): required by libwebrtc prebuilts and chosen as the
    // baseline for WebRTC + media3 support. Documented in README.
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            // libwebrtc ships native libraries; restrict to the supported ABIs.
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }

        buildConfigField(
            "String",
            "MEBIUS_VERSION",
            "\"${project.findProperty("VERSION_NAME") ?: "0.0.0"}\"",
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
    // NOTE: the release publishing variant (with sources + javadoc) is
    // configured automatically by the vanniktech maven-publish plugin below.
    // Declaring `publishing { singleVariant("release") }` here too is a hard
    // error ("singleVariant ... multiple times"), so it is intentionally omitted.
}

// Enforce explicit visibility on the PUBLIC API (production sources only).
// Using the Kotlin DSL instead of a raw -Xexplicit-api compiler arg so it does
// NOT apply to test sources (tests don't need explicit visibility).
kotlin {
    explicitApi()
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.annotation)

    // Internal transport — never exposed on the public API surface.
    implementation(libs.webrtc.android)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
}

ktlint {
    version.set("1.3.1")
    android.set(true)
    ignoreFailures.set(false)
    reporters {
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = false)
    // Sign ONLY when a signing key is configured (required for Maven Central,
    // not for local installs / CI dry-runs). Provide a key via
    // ORG_GRADLE_PROJECT_signingInMemoryKey or signing.* gradle properties.
    val hasSigningKey =
        providers.gradleProperty("signingInMemoryKey").isPresent ||
            providers.gradleProperty("signing.keyId").isPresent
    if (hasSigningKey) {
        signAllPublications()
    }
    coordinates(
        groupId = "io.mebius",
        artifactId = "mebius-android-sdk",
        version = project.findProperty("VERSION_NAME") as String,
    )
}
