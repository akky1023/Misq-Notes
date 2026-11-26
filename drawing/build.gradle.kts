plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "net.neomenia.misqnotes.drawing"
    compileSdk = 35

    defaultConfig { minSdk = 29 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    api("androidx.ink:ink-authoring:1.0.0")
    api("androidx.ink:ink-brush:1.0.0")
    api("androidx.ink:ink-strokes:1.0.0")
    api("androidx.ink:ink-geometry:1.0.0")
    api("androidx.ink:ink-rendering:1.0.0")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.input:input-motionprediction:1.0.0")
    testImplementation("junit:junit:4.13.2")
}
