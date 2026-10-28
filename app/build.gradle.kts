import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localSigning = rootProject.file(".signing/signing.properties")
val signingValues = Properties().apply { if (localSigning.exists()) localSigning.inputStream().use { load(it) } }
val releaseVersion = providers.gradleProperty("releaseVersion").orNull ?: rootProject.file("version.txt").readText().trim()
require(Regex("\\d+\\.\\d+\\.\\d+").matches(releaseVersion)) { "version.txt must contain major.minor.patch" }
val (majorVersion, minorVersion, patchVersion) = releaseVersion.split('.').map(String::toLong)
require(minorVersion < 1000 && patchVersion < 1000) { "Minor and patch versions must be below 1000" }
val releaseVersionCode = majorVersion * 1_000_000 + minorVersion * 1_000 + patchVersion
require(releaseVersionCode in 1..2_100_000_000L) { "Android versionCode is out of range" }
val releaseStorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD") ?: signingValues.getProperty("storePassword")

android {
    namespace = "jp.handwriting.note"
    compileSdk = 35
    defaultConfig {
        applicationId = "net.neomenia.misqnotes"
        minSdk = 29
        targetSdk = 35
        versionCode = releaseVersionCode.toInt()
        versionName = releaseVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    // Both languages must be available for switching without a network download.
    bundle { language { enableSplit = false } }
    signingConfigs {
        if (!releaseStorePassword.isNullOrBlank()) create("localRelease") {
            storeFile = rootProject.file(".signing/handwriting.p12")
            storePassword = releaseStorePassword
            keyAlias = "handwriting"
            keyPassword = releaseStorePassword
            storeType = "PKCS12"
        }
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".probe"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            if (!releaseStorePassword.isNullOrBlank()) signingConfig = signingConfigs.getByName("localRelease")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.ink:ink-authoring:1.0.0")
    implementation("androidx.ink:ink-brush:1.0.0")
    implementation("androidx.ink:ink-strokes:1.0.0")
    implementation("androidx.ink:ink-geometry:1.0.0")
    implementation("androidx.ink:ink-rendering:1.0.0")
    implementation("androidx.ink:ink-storage:1.0.0")
    implementation("androidx.input:input-motionprediction:1.0.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
