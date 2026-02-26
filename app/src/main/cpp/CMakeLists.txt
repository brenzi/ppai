cmake_minimum_required(VERSION 3.22.1)
project(whisper_jni C CXX)

set(CMAKE_CXX_STANDARD 17)

# whisper.cpp location passed from Gradle
if(NOT DEFINED WHISPER_CPP_DIR)
    message(FATAL_ERROR "WHISPER_CPP_DIR must be set")
endif()

find_library(LOG_LIB log)

# Android 16KB page alignment
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

# Force -O3 for ALL build types including Debug.
# ggml matrix ops are ~200x slower without optimization — unusable for on-device inference.
set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS} -O3")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3")
set(CMAKE_C_FLAGS_DEBUG   "-g" CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS_DEBUG "-g" CACHE STRING "" FORCE)

# Tell ggml-cpu which ARM ISA to target (GGML_NATIVE is OFF when cross-compiling).
# Without this, ggml-cpu falls back to baseline armv8.0 with no fp16 vector ops.
if (${ANDROID_ABI} STREQUAL "arm64-v8a")
    set(GGML_CPU_ARM_ARCH "armv8.2-a+fp16+dotprod" CACHE STRING "" FORCE)
endif()

# Build ggml as STATIC libs — avoids shared-library backend registration issues on Android
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)

include(FetchContent)
FetchContent_Declare(ggml SOURCE_DIR ${WHISPER_CPP_DIR}/ggml)
FetchContent_MakeAvailable(ggml)

# Build whisper as shared lib with our JNI bridge, ggml linked statically
add_library(whisper_jni SHARED
    ${WHISPER_CPP_DIR}/src/whisper.cpp
    whisper_jni.c
)

target_compile_definitions(whisper_jni PUBLIC GGML_USE_CPU)
target_compile_definitions(whisper_jni PRIVATE WHISPER_VERSION="1.8.3")

target_include_directories(whisper_jni PRIVATE
    ${WHISPER_CPP_DIR}/include
    ${WHISPER_CPP_DIR}/src
    ${WHISPER_CPP_DIR}/ggml/include
    ${WHISPER_CPP_DIR}/ggml/src
    ${WHISPER_CPP_DIR}/ggml/src/ggml-cpu
)

# ARM fp16 optimization for whisper.cpp source (ggml-cpu gets it via GGML_CPU_ARM_ARCH above)
if (${ANDROID_ABI} STREQUAL "arm64-v8a")
    target_compile_options(whisper_jni PRIVATE -march=armv8.2-a+fp16+dotprod)
endif()

target_link_libraries(whisper_jni ${LOG_LIB} android ggml)
