import java.util.Base64

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

    signingConfigs {
        create("provito") {
            val b64 = System.getenv("PROVITO_KEYSTORE_B64")
            if (b64 != null) {
                val keystoreFile = layout.buildDirectory.get().asFile.resolve("provito.keystore")
                keystoreFile.parentFile.mkdirs()
                keystoreFile.writeBytes(Base64.getDecoder().decode(b64))
                storeFile = keystoreFile
                storePassword = System.getenv("PROVITO_STORE_PASS")
                keyAlias = System.getenv("PROVITO_KEY_ALIAS")
                keyPassword = System.getenv("PROVITO_KEY_PASS")
            }
        }
    }

    buildTypes {
        release {
            val signed = System.getenv("PROVITO_KEYSTORE_B64") != null
            if (signed) {
                signingConfig = signingConfigs.getByName("provito")
            }
            isMinifyEnabled = signed
            isShrinkResources = signed
            if (signed) {
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
                )
            }
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
