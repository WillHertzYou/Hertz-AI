plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.hertz.ai"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hertz.ai"
        minSdk = 31
        targetSdk = 35
        versionCode = 6
        versionName = "0.4.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // Every build is signed with the same key, so each new APK installs as an update. The key is not
    // in the repo: the build workflow writes it from the repo's secrets. Without it (e.g. a local
    // build) the standard debug key is used, which can't update the installed app.
    val hertzKey = file("hertz.keystore")
    val hertzPassword: String? = System.getenv("HERTZ_KEYSTORE_PASSWORD")
    signingConfigs {
        if (hertzKey.exists() && hertzPassword != null) {
            create("hertz") {
                storeFile = hertzKey
                storePassword = hertzPassword
                keyAlias = "hertz"
                keyPassword = hertzPassword
            }
        }
    }

    buildTypes {
        debug {
            signingConfigs.findByName("hertz")?.let { signingConfig = it }
            // Tools are discovered by reflection, so code shrinking stays off.
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("hertz")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            // The voice and wake-word libraries both ship these; keep one copy.
            pickFirsts += listOf("**/libc++_shared.so", "**/libonnxruntime.so")
        }
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    // On-device LLM runtime (Gemma 4 .litertlm models)
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.17.1")
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.3.0")
    implementation("com.google.code.gson:gson:2.11.0")

    // Offline "Hey Hertz" wake word
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")

    // Free on-device neural voice (sherpa-onnx; the build downloads the library into app/libs)
    implementation(files("libs/sherpa-onnx.aar"))
    implementation("org.apache.commons:commons-compress:1.27.1")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
