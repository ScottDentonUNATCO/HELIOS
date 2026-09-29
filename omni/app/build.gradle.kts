plugins {
    alias(libs.plugins.agp)
    alias(libs.plugins.kotlin)
    // Compose compiler plugin — version taken from the catalog's kotlin version;
    // no plugin alias for it was listed in versions.toml, so this avoids inventing one.
    id("org.jetbrains.kotlin.plugin.compose") version libs.versions.kotlin.get()
}

android {
    namespace = "com.omni.app"
    // Platform android-36 is installed by the toolchain; app builds against it.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.omni.app"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-phase0"
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

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":gateway"))

    // Compose BOM manages versions for androidx.compose artifacts.
    implementation(platform(libs.compose.bom))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")

    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.security.crypto)

    implementation(libs.coroutines) // kotlinx-coroutines-android
    implementation(libs.okhttp)
}
