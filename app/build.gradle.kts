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
        // Build number comes from GitHub Actions (-PbuildNumber=N), so every APK has its own version.
        val buildNumber = (project.findProperty("buildNumber") as String?)?.toIntOrNull() ?: 1
        versionCode = buildNumber
        versionName = "1.$buildNumber"
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
    // ngrok-java 1.1.1's Android library returns JNI_VERSION_1_8 from JNI_OnLoad, which Android rejects
    // (it only accepts 1.2, 1.4 and 1.6). Patch "mov w0, #8" to "mov w0, #6" so it returns JNI_VERSION_1_6.
    doLast {
        val f = destinationDir.resolve("libngrok_java.so")
        val b = f.readBytes()
        val find = listOf(0x00, 0x01, 0x80, 0x52, 0x20, 0x00, 0xa0, 0x72, 0xfe, 0x07, 0x41, 0xf8, 0xc0, 0x03, 0x5f, 0xd6)
            .map { it.toByte() }.toByteArray()
        var at = -1
        var count = 0
        for (i in 0..b.size - find.size) {
            var ok = true
            for (j in find.indices) {
                if (b[i + j] != find[j]) {
                    ok = false
                    break
                }
            }
            if (ok) {
                at = i
                count++
            }
        }
        if (count != 1) throw GradleException("JNI version patch: expected 1 match, found $count")
        b[at] = 0xc0.toByte()
        b[at + 1] = 0x00
        f.writeBytes(b)
        println("Patched JNI_OnLoad to return JNI_VERSION_1_6")
    }
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
