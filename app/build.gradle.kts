plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "org.w8dsci.watchcat"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.w8dsci.watchcat"
        minSdk = 34
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"

    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Correct placement for NDK debug symbol level
            ndk {
                debugSymbolLevel = "FULL" // Or "SYMBOL_TABLE"
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        viewBinding = true
        compose = true
    }
}

dependencies {

    implementation(libs.play.services.wearable)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.testng)

    implementation(libs.material)
    implementation(libs.appcompat)
    implementation(libs.ui)
    implementation(libs.material3)
    implementation(libs.ui.tooling.preview)
    implementation(libs.compose.material)

    implementation(libs.compose.navigation)
    implementation(libs.wear)

    implementation (libs.runtime)
    implementation (libs.ui)

    implementation (libs.compose.material)
    implementation (libs.compose.foundation)

    implementation(libs.core.splashscreen)

    implementation (libs.ui.tooling.preview)
    debugImplementation (libs.ui.tooling)
}


