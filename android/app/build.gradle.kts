plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.fastwake.app"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.fastwake.app"
        minSdk = 28
        targetSdk = 35
        versionCode = 4
        versionName = "0.4"
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DGGML_NATIVE=OFF",
                    "-DWHISPER_BUILD_TESTS=OFF",
                    "-DWHISPER_BUILD_EXAMPLES=OFF",
                    "-DBUILD_SHARED_LIBS=OFF"
                )
                cppFlags += "-std=c++17"
            }
        }
    }

    signingConfigs {
        // Committed debug key (public, password "android") so every build —
        // local or GitHub Actions — has the same signature and installs as an
        // update over the previous one. NOT for Play Store publishing.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            // Signed with the debug key so the APK installs directly for testing.
            signingConfig = signingConfigs.getByName("debug")
            // R8 shrinking: the Claude SDK contains classes for the whole API
            // (~42 MB of dex unshrunk). Its jars ship R8 rules
            // (META-INF/proguard/*.pro) that keep what Jackson needs at runtime.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        // Jackson / OkHttp ship duplicate license and module files.
        resources.excludes += listOf(
            "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
            "META-INF/INDEX.LIST", "META-INF/*.kotlin_module", "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            // GraalVM native-image metadata: not used on Android.
            "META-INF/native-image/**"
        )
    }
}

dependencies {
    // Official Claude SDK (Kotlin uses the Java SDK).
    implementation("com.anthropic:anthropic-java:3.6.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Used directly by the Gemini client and live-data helpers (also SDK deps).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.19.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

tasks.withType<Test>().configureEach {
    // Lets LiveDataTest's network test run locally with FASTWAKE_LIVE=1.
    environment("FASTWAKE_LIVE", System.getenv("FASTWAKE_LIVE") ?: "0")
    testLogging { showStandardStreams = true }
}
