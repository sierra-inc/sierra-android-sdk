// Copyright Sierra

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("kotlin-parcelize")
    `maven-publish`
}

android {
    namespace = "ai.sierra.sdk.voice"
    compileSdk = 34

    defaultConfig {
        minSdk = 24

        consumerProguardFiles("consumer-rules.pro")
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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

dependencies {
    // The core SDK module is included under different names depending on which settings root is
    // active: `android/settings.gradle.kts` exposes it as `:SierraSDK`, while the standalone
    // `android/SierraSDK/settings.gradle.kts` (used when publishing the SDK from the mirrored
    // repo) exposes it as `:lib`.
    val coreModulePath = if (project.findProject(":SierraSDK") != null) ":SierraSDK" else ":lib"
    val chatKitModulePath = if (project.findProject(":SierraChatKit") != null) ":SierraChatKit" else ":lib-chatkit"
    api(project(coreModulePath))
    api(project(chatKitModulePath))
    implementation("androidx.activity:activity:1.8.2")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "ai.sierra"
            artifactId = "sierra-android-sdk-voice"
            version = "1.0"

            afterEvaluate {
                from(components["release"])
            }
        }
    }
}
