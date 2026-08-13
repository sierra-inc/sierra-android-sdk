// Copyright Sierra

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    `maven-publish`
}

android {
    namespace = "ai.sierra.sdk.compose"
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
                "proguard-rules.pro",
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
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }
    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
    testOptions {
        unitTests {
            // Robolectric needs the merged manifest and resources of the variant under test.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // Support both the monorepo build and the standalone mirrored SDK build.
    val coreModulePath = if (project.findProject(":SierraSDK") != null) ":SierraSDK" else ":lib"

    api(project(coreModulePath))
    api(platform("androidx.compose:compose-bom:2023.08.00"))
    api("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.8.2")

    // The host contract tests drive a fake View, so they run under Robolectric on the JVM: no
    // emulator, WebView, backend, or agent token. They live in the test source set together with
    // their host activity, so no test code reaches the published artifact or a consumer's build.
    // Robolectric is pinned to the version lib-voice already uses.
    testImplementation(platform("androidx.compose:compose-bom:2023.08.00"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "ai.sierra"
            artifactId = "sierra-android-sdk-compose"
            version = "1.0"

            afterEvaluate {
                from(components["release"])
            }
        }
    }
}
