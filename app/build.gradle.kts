plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.familycontrol.lab"
    compileSdk = 36

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "com.familycontrol.lab"
        minSdk = 26
        targetSdk = 36
        versionCode = 41
        versionName = "1.4.6"
    }

    signingConfigs {
        create("release") {
            storeFile = file("${rootDir}/famorbit-release.jks")
            storePassword = "FamOrbit2026SecureReleaseKey"
            keyAlias = "famorbit"
            keyPassword = "FamOrbit2026SecureReleaseKey"
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
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.3")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation("androidx.compose.ui:ui:1.9.1")
    implementation("androidx.compose.ui:ui-tooling-preview:1.9.1")
    implementation("androidx.compose.material3:material3:1.3.2")
    implementation("androidx.compose.foundation:foundation:1.9.1")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    
    // Firebase Cloud Messaging (FCM)
    implementation(platform("com.google.firebase:firebase-bom:33.10.0"))
    implementation("com.google.firebase:firebase-messaging-ktx")
    
    debugImplementation("androidx.compose.ui:ui-tooling:1.9.1")
}
