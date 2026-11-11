import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
}

// One version for the host tool and the agent: host/adbw.py is the source of truth.
val adbwVersion: String = Regex("""__version__ = "([^"]+)"""")
    .find(rootProject.file("../host/adbw.py").readText())!!.groupValues[1]
val (vMajor, vMinor, vPatch) = adbwVersion.split(".").map { it.toInt() }

// Release signing key from the environment (CI secrets, or a local keystore). Updates only
// install over an existing copy when the key matches, so releases must all use the same one.
// Without it the build falls back to the machine's debug key; `adbw setup` copes by reinstalling.
val keystorePath: String? = System.getenv("ADBW_KEYSTORE")

android {
    namespace = "dev.adbw.agent"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.adbw.agent"
        minSdk = 30 // Android 11: Wireless debugging (TLS adbd) and Wear OS 3+
        targetSdk = 36
        versionCode = vMajor * 10000 + vMinor * 100 + vPatch
        versionName = adbwVersion
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("ADBW_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ADBW_KEY_ALIAS") ?: "adbw"
                keyPassword = System.getenv("ADBW_KEY_PASSWORD") ?: System.getenv("ADBW_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // A sideloaded developer tool: these checks target Play-distributed apps.
        disable += setOf("ProtectedPermissions", "ForegroundServicePermission")
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}
// No dependencies on purpose: platform APIs only, so it builds and installs anywhere.
