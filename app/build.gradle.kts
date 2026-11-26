plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ovh.adan.allstar"
    compileSdk = 36

    defaultConfig {
        applicationId = "ovh.adan.allstar"
        minSdk = 24
        targetSdk = 36
        versionCode = 9
        versionName = "1.0.3"
    }

    // Release signing: keystore and passwords live outside the repo
    // (~/allstarmobile-release.jks and ~/.gradle/gradle.properties).
    // Without them the release build falls back to the debug key.
    val ks = file(System.getProperty("user.home") + "/allstarmobile-release.jks")
    signingConfigs {
        if (ks.exists()) create("release") {
            storeFile = ks
            storePassword = project.findProperty("allstarStorePw") as String? ?: ""
            keyAlias = "allstarmobile"
            keyPassword = project.findProperty("allstarKeyPw") as String? ?: ""
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (ks.exists()) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2025.09.01")
    implementation(bom)
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
}
