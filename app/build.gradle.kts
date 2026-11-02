plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.locall"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.locall"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 64-bit only: whisper.cpp/llama.cpp NEON + the affinity code assume arm64.
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                // -O3 matters far more than thread count for throughput.
                // NOTE: do NOT add -fno-exceptions/-fno-rtti here — ggml uses try/catch.
                cppFlags += "-O3"
                arguments += "-DANDROID_ARM_NEON=TRUE"
            }
        }
    }

    // Requires: SDK Manager > NDK + CMake installed, and whisper.cpp/llama.cpp
    // checked out under app/src/main/cpp/ (see CMakeLists.txt).
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        // The bundled GGUF is already quantized (near-incompressible); skip wasting
        // build time trying to recompress it into the APK.
        noCompress += "gguf"
    }
    testOptions {
        unitTests {
            // Let JVM unit tests call android.util.Log etc. without the stub
            // android.jar throwing "not mocked" — the music/memory parsers log in
            // their fallback paths. Returns defaults; real org.json still shadows
            // the stub so JSON parsing works for real.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    // Room: local on-device storage for conversational memories (feature 2). Fully
    // offline — no network, no backend. KSP generates the DAO/database impls.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    // Email feature (feature 4): Gmail REST over java.net.HttpURLConnection + the
    // org.json already on the classpath. The ONLY new dependency is play-services-auth,
    // used solely to obtain an OAuth access token for the Gmail scope via the Identity
    // Authorization API. This is the app's first network-touching feature — the local
    // LLM still does all summarization/intent work offline; only mailbox fetch/modify
    // crosses the network. See voice/email/ and EMAIL_SETUP.md.
    implementation(libs.play.services.auth)
    // Wake-word inference runs on ONNX Runtime, not TFLite: the openWakeWord
    // melspectrogram model has a fully-dynamic input whose STFT CONV_2D the TFLite
    // interpreter (2.16.1 and 2.17.0) can't allocate ("BytesRequired overflowed").
    // ONNX Runtime handles the same models' dynamic shapes correctly. See WakeWordDetector.
    //
    // We vendor ONNX Runtime 1.28.2 (ai.onnxruntime Java classes) from csukuangfj's
    // onnxruntime-libs rather than Microsoft's Maven AAR, because sherpa-onnx already
    // bundles libonnxruntime.so at 1.28.2 and there can be only ONE in the APK. The
    // JNI bridge is version-locked by symbol version node (OrtGetApiBase@VERS_1.28.2),
    // so Microsoft's AAR (only 1.28.0 published) would fail to link against sherpa's
    // core. The matching 1.28.2 libonnxruntime4j_jni.so lives in jniLibs/.
    implementation(files("libs/onnxruntime-1.28.2.jar"))
    // Text-to-phoneme frontend required by sherpa-onnx v2.0+ for VITS/Piper voices.
    implementation("com.github.csukuangfj.piper-phonemize:piper-phonemize:v1.5.9")
    testImplementation(libs.junit)
    // Real org.json on the unit-test classpath — android.jar's stub throws
    // "not mocked", so this lets the music/memory JSON parsers be tested on the JVM.
    testImplementation("org.json:json:20240303")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}