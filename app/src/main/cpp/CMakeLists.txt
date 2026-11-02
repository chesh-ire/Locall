cmake_minimum_required(VERSION 3.22.1)
project(locall)

set(CMAKE_CXX_STANDARD 17)

# --- Vendored inference engines ------------------------------------------------
# Check this out (git clone) before building:
#   app/src/main/cpp/llama.cpp     (https://github.com/ggml-org/llama.cpp)
#
# whisper.cpp (https://github.com/ggml-org/whisper.cpp) used to be vendored here
# too, for STT — but STT moved to Android's built-in SpeechRecognizer a while ago
# (see SpeechRecognitionBridge.kt) and nothing ever instantiated WhisperBridge, so
# it was 100% dead code at runtime. It's been dropped from the native build
# entirely: whisper.cpp's vendored ggml is older than llama.cpp's and lacks ops
# llama.cpp's current model-loading code needs (e.g. `ggml_top_k_mask`), and since
# both projects guard their own `add_subdirectory(ggml)` with `if (NOT TARGET
# ggml)`, whichever got added FIRST silently "won" and the other was stuck on a
# too-old ggml copy — removing whisper.cpp lets llama.cpp own ggml outright with
# its own (current) vendored copy.

# Perf knobs that dwarf thread tuning: enable dotprod/i8mm on arm64.
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)   # NATIVE = host arch; wrong when cross-compiling for the phone
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)   # NDK ships no OpenMP runtime; we pin our own threads anyway
# The single biggest throughput lever: enable ARM dotprod + i8mm + fp16 for quantized
# matmul (2-4x). Requires ARMv8.2+ (phones ~2018+); will SIGILL on older CPUs.
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+i8mm+fp16" CACHE STRING "" FORCE)

# llama.cpp: local LLM for the intent fallback + reply-drafting features. Only
# build the model-loading/inference library (`llama`), none of its tools/examples/
# server/tests — those default OFF anyway when vendored via add_subdirectory
# (LLAMA_STANDALONE becomes OFF), but set explicitly for clarity.
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)  # llm_jni.cpp uses only core llama.h, not common/ helpers
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP      OFF CACHE BOOL "" FORCE)
add_subdirectory(llama.cpp)

# --- sherpa-onnx (Piper TTS) ----------------------------------------------------
# On-device neural text-to-speech, fully ours: no API, no network, no Google/
# Samsung TTS engine. Produces its OWN independent libsherpa-onnx-jni.so, loaded
# directly by the vendored com.k2fsa.sherpa.onnx.Tts.kt (unrelated to `locall`
# below) — so it needs no target_link_libraries entry here.
set(SHERPA_ONNX_ENABLE_TTS ON CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION OFF CACHE BOOL "" FORCE) # unused, cuts build time
set(SHERPA_ONNX_ENABLE_BINARY OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_C_API OFF CACHE BOOL "" FORCE)       # JNI only
set(SHERPA_ONNX_ENABLE_JNI ON CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_PYTHON OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_TESTS OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_CHECK OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_PORTAUDIO OFF CACHE BOOL "" FORCE)   # no portaudio on Android
# sherpa-onnx auto-fetches a matching prebuilt ONNX Runtime for Android via
# FetchContent at configure time — nothing to vendor ourselves here. A matching
# copy of libonnxruntime.so for RUNTIME packaging lives in app/src/main/jniLibs/
# (Gradle's own native-lib build output isn't where a prebuilt .so belongs).

add_subdirectory(sherpa-onnx)

# --- Our JNI + thread-control code ---------------------------------------------
add_library(locall SHARED
        llm_jni.cpp
        cpu_topology.cpp)

target_link_libraries(locall
        llama
        android
        log)
