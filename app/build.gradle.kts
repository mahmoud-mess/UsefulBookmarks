plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("kotlin-kapt")
}

android {
    namespace = "com.example.ithinkicanchangethislater" // You can change this later
    compileSdk = 35 // Make sure you have SDK 35 installed via Android Studio's SDK Manager

    defaultConfig {
        applicationId = "com.example.ithinkicanchangethislater"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        javaCompileOptions {
            annotationProcessorOptions {
                arguments["room.schemaLocation"] = "$projectDir/schemas"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

val roomVersion = "2.6.1"
val lifecycleVersion = "2.7.0"
val coroutinesVersion = "1.7.3"
val pdfViewerVersion = "3.2.0-beta.3" // Corrected PDF viewer version
val activityKtxVersion = "1.8.2"

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    implementation("com.github.mhiew:android-pdf-viewer:$pdfViewerVersion")
    implementation("androidx.recyclerview:recyclerview:1.3.2") // Use the latest stable version
    implementation("androidx.room:room-runtime:$roomVersion")
    kapt("androidx.room:room-compiler:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:$lifecycleVersion")
    implementation("androidx.activity:activity-ktx:$activityKtxVersion")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutinesVersion")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
