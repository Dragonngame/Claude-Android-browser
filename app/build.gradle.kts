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

// ngrok built into the app.
// The native jar keeps its .so as a Java resource, which Android drops, so it is split here:
// classes go into a jar (without com.ngrok.Runtime, replaced by app/src/main/java/com/ngrok/Runtime.java)
// and the .so goes into lib/arm64-v8a.
val ngrokNative: Configuration by configurations.creating

val ngrokNativeJar by tasks.registering(Jar::class) {
    archiveFileName.set("ngrok-native-classes.jar")
    destinationDirectory.set(layout.buildDirectory.dir("ngrok"))
    from({ ngrokNative.map { zipTree(it) } }) {
        exclude("com/ngrok/Runtime.class", "com/ngrok/Runtime$*.class", "*.so", "META-INF/MANIFEST.MF")
    }
}

val ngrokJniLibs by tasks.registering(Copy::class) {
    from({ ngrokNative.map { zipTree(it) } }) {
        include("*.so")
    }
    into(layout.buildDirectory.dir("ngrok/jniLibs/arm64-v8a"))
}

android.sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("ngrok/jniLibs"))

tasks.named("preBuild") {
    dependsOn(ngrokJniLibs)
}

dependencies {
    implementation("com.ngrok:ngrok-java:1.1.1")
    ngrokNative("com.ngrok:ngrok-java-native:1.1.1:linux-android-aarch_64") {
        isTransitive = false
    }
    implementation(files(ngrokNativeJar))
    implementation("org.slf4j:slf4j-nop:2.0.16")
}
