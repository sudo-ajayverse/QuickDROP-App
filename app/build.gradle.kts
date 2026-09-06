plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

import java.util.Properties

/**
 * Read secrets from (in order): local.properties -> Gradle properties -> environment variables.
 */
val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) {
        f.inputStream().use { load(it) }
    }
}

fun readProp(name: String, defaultValue: String = ""): String {
    val fromLocal = localProperties.getProperty(name)
    val fromGradle = (project.findProperty(name) as String?)
    val fromEnv = System.getenv(name)
    return (fromLocal ?: fromGradle ?: fromEnv ?: defaultValue).trim()
}

fun asBuildConfigString(value: String): String {
    val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
    return "\"$escaped\""
}

// Read version info from gradle.properties
val verCode = (project.findProperty("VERSION_CODE") as? String)?.toIntOrNull() ?: 1
val verName = (project.findProperty("VERSION_NAME") as? String) ?: "1.0.0"

// Read Repo info from gradle.properties
val repoOwner = project.findProperty("GITHUB_REPO_OWNER") as? String ?: "sudo-ajayverse"
val repoName = project.findProperty("GITHUB_REPO_NAME") as? String ?: "QuickDROP-App"

android {
    namespace = "com.quickdrop.sharetarget"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.quickdrop.sharetarget"
        minSdk = 24
        targetSdk = 35
        versionCode = verCode
        versionName = verName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "SUPABASE_URL", asBuildConfigString(readProp("SUPABASE_URL")))
        buildConfigField("String", "SUPABASE_ANON_KEY", asBuildConfigString(readProp("SUPABASE_ANON_KEY")))
        buildConfigField("String", "SUPABASE_BUCKET", asBuildConfigString(readProp("SUPABASE_BUCKET", "files")))
        buildConfigField("String", "SUPABASE_FILES_TABLE", asBuildConfigString(readProp("SUPABASE_FILES_TABLE", "files")))

        // Repo info for AutoUpdater
        buildConfigField("String", "REPO_OWNER", asBuildConfigString(repoOwner))
        buildConfigField("String", "REPO_NAME", asBuildConfigString(repoName))
    }

    signingConfigs {
        create("release") {
            val storeFile = System.getenv("SIGNING_STORE_FILE")
            val storePassword = System.getenv("SIGNING_STORE_PASSWORD")
            val keyAlias = System.getenv("SIGNING_KEY_ALIAS")
            val keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            if (storeFile != null) {
                this.storeFile = file(storeFile)
                this.storePassword = storePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val cfg = signingConfigs.getByName("release")
            if (cfg.storeFile != null) {
                signingConfig = cfg
            }
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

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
