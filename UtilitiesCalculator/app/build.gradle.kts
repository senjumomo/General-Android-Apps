plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("app.cash.paparazzi")
}

android {
    namespace = "com.senjumomo.utilities"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.senjumomo.utilities"
        minSdk = 24
        targetSdk = 35
        versionCode = 3
        versionName = "3.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so the APK installs straight away.
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
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.google.android.material:material:1.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // real org.json for JVM tests
}
