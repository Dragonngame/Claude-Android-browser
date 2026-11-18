plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.remotebrowser.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.remotebrowser.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

    // Fixed signing key, so new builds install over old ones without losing logins.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/9/module-info.class",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.md"
            )
        }
    }
}

dependencies {
    // ngrok built into the app (Android arm64 native library).
    implementation("com.ngrok:ngrok-java:1.1.1")
    implementation("com.ngrok:ngrok-java-native:1.1.1:linux-android-aarch_64")
    implementation("org.slf4j:slf4j-nop:2.0.16")
}
