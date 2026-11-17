plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.provito.browser"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.provito.browser"
        minSdk = 26
        targetSdk = 35
        val vName = (project.findProperty("PROVITO_VERSION_NAME") as String?) ?: "1.0.0"
        versionName = vName
        versionCode = vName.split('.').fold(0) { acc, part ->
            (acc * 100) + (part.filter { it.isDigit() }.toIntOrNull() ?: 0)
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
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
