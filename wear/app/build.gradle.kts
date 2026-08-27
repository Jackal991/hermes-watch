plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.hermes.watch"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hermes.watch"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // Build-time config. Users bake their own backend URL + token into
        // their APK with:
        //   ./gradlew :app:assembleDebug -PbackendUrl=https://... -PbackendToken=...
        // Falls back to blank — the in-app setup page then collects them.
        // These are NEVER committed; each user's values stay in their own build.
        val cfgUrl = (project.findProperty("backendUrl") as String?) ?: ""
        val cfgToken = (project.findProperty("backendToken") as String?) ?: ""
        buildConfigField("String", "BACKEND_URL", "\"$cfgUrl\"")
        buildConfigField("String", "BACKEND_TOKEN", "\"$cfgToken\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.wear.compose:compose-material:1.4.0")
    implementation("androidx.wear.compose:compose-foundation:1.4.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.gms:play-services-wearable:18.1.0")
}
