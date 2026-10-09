plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.backer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.backer"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        resourceConfigurations += "en"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        // Env vars are read at configuration time, so export them before invoking Gradle.
        val releaseKsPass = System.getenv("BACKER_KS_PASS")
        if (releaseKsPass != null) {
            create("release") {
                val ksPath = System.getenv("BACKER_KS_PATH")
                    ?: "${System.getProperty("user.home")}/.config/backer-release.jks"
                storeFile = file(ksPath)
                storePassword = releaseKsPass
                keyAlias = System.getenv("BACKER_KEY_ALIAS") ?: "backer"
                keyPassword = System.getenv("BACKER_KEY_PASS") ?: releaseKsPass
            }
        } else {
            logger.warn("BACKER_KS_PASS not set; release APK will be unsigned.")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (System.getenv("BACKER_KS_PASS") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt"
            )
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-tooling-preview")
}
