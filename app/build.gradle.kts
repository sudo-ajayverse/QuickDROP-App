plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

import java.util.Properties

android {
    namespace = "com.quickdrop.sharetarget"
    compileSdk = 35

    // Read versions from gradle.properties
    val vCode = (project.findProperty("VERSION_CODE") as? String)?.toIntOrNull() ?: 7
    val vName = (project.findProperty("VERSION_NAME") as? String) ?: "0.7.3"
    val signingStoreFile = System.getenv("SIGNING_STORE_FILE")
    val signingStorePassword = System.getenv("SIGNING_STORE_PASSWORD")
    val signingKeyAlias = System.getenv("SIGNING_KEY_ALIAS")
    val signingKeyPassword = System.getenv("SIGNING_KEY_PASSWORD")
    val hasReleaseSigning = !signingStoreFile.isNullOrBlank() &&
        !signingStorePassword.isNullOrBlank() &&
        !signingKeyAlias.isNullOrBlank() &&
        !signingKeyPassword.isNullOrBlank() &&
        file(signingStoreFile).exists()

    defaultConfig {
        applicationId = "com.quickdrop.sharetarget"
        minSdk = 24
        targetSdk = 35
        versionCode = vCode
        versionName = vName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Hardcoded Supabase Credentials
        buildConfigField("String", "SUPABASE_URL", "\"https://llxmpzutwtuytcgtlskc.supabase.co\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImxseG1wenV0d3R1eXRjZ3Rsc2tjIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzE0Mjk2ODEsImV4cCI6MjA4NzAwNTY4MX0.a-x8bi5uDtkUGR-d7NBsfwMOEYftju7b91IzgDIcPCA\"")
        buildConfigField("String", "SUPABASE_BUCKET", "\"files\"")
        buildConfigField("String", "SUPABASE_FILES_TABLE", "\"files\"")

        // Dynamic Repo Info
        val rOwner = (project.findProperty("GITHUB_REPO_OWNER") as? String) ?: "sudo-ajayverse"
        val rName = (project.findProperty("GITHUB_REPO_NAME") as? String) ?: "QuickDROP-App"
        buildConfigField("String", "REPO_OWNER", "\"$rOwner\"")
        buildConfigField("String", "REPO_NAME", "\"$rName\"")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(signingStoreFile!!)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
