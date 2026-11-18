cmake_minimum_required(VERSION 3.22)
project(lfm3pocket LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Append -O3 for Debug and Release so ggml-cpu (and other llama.cpp targets)
# get it too — not only lfm3pocket. Clang's last -O wins over NDK Debug -O0.
# No CACHE FORCE (keeps other NDK flags) and no Debug -DNDEBUG (keeps asserts).
# See docs/adr/0001-arm64-dotprod-and-kv-residency.md.
if(CMAKE_CXX_COMPILER_ID MATCHES "Clang|GNU")
    add_compile_options(
        "$<$<CONFIG:Debug>:-O3>"
        "$<$<CONFIG:Release>:-O3>"
        "$<$<CONFIG:RelWithDebInfo>:-O3>")
endif()

# Experimental spike (#164): opt-in Vulkan backend for llama.cpp/ggml.
# Default OFF so CI and universal builds remain CPU-safe. Requires a Vulkan-capable
# Android/NDK toolchain environment and shader generation deps (glslc, etc.) when ON.
option(HANAMI_GGML_VULKAN "Enable experimental ggml Vulkan backend (spike #164, off by default)" OFF)
if(HANAMI_GGML_VULKAN)
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)
else()
    # Preserve explicit GGML_VULKAN=ON from caller if provided; otherwise force OFF.
    if(NOT GGML_VULKAN)
        set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
    endif()
endif()

set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD ON CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# Cross-compile must not use -march=native. Phone arm64 uses DOTPROD (ADR 0001);
# Java refuses load on CPUs without it so the app stays usable without SIGILL.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod" CACHE STRING "" FORCE)
    message(STATUS "Hanami: GGML_CPU_ARM_ARCH=${GGML_CPU_ARM_ARCH} (see docs/adr/0001)")
endif()

get_filename_component(LLAMA_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp" ABSOLUTE)
if(NOT EXISTS "${LLAMA_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "Missing llama.cpp in third_party/llama.cpp: see README")
endif()
add_subdirectory("${LLAMA_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/llama-build" EXCLUDE_FROM_ALL)

add_library(lfm3pocket SHARED native.cpp speculative_decode.cpp cactus_whistle_jni.cpp)
target_compile_options(lfm3pocket PRIVATE "$<$<CONFIG:Debug>:-O3>" "$<$<CONFIG:Release>:-O3>")
if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_options(lfm3pocket PRIVATE -march=armv8.2-a+dotprod)
    target_compile_definitions(lfm3pocket PRIVATE HANAMI_ARM_DOTPROD=1)
endif()
if(GGML_VULKAN)
    target_compile_definitions(lfm3pocket PRIVATE HANAMI_ENABLE_VULKAN_OFFLOAD=1)
    message(STATUS "Hanami: experimental GGML_VULKAN enabled (spike #164); runtime will attempt GPU offload with CPU fallback")
endif()
# NDK Gradle does not always propagate llama-common INTERFACE includes to JNI sources.
target_include_directories(lfm3pocket PRIVATE
    "${LLAMA_DIR}/common"
    "${LLAMA_DIR}/include"
    "${LLAMA_DIR}/vendor"
)

# Optional: Cactus Whistle STT runtime (Android native artifacts from Cactus release).
# Provide headers/lib at third_party/cactus/<abi>/cactus.h and libcactus.a (or set CACTUS_WHISTLE_DIR).
set(CACTUS_WHISTLE_DIR "" CACHE PATH "Directory containing cactus.h and libcactus.a for the target ABI")
if(CACTUS_WHISTLE_DIR STREQUAL "" AND DEFINED ANDROID_ABI)
    if(EXISTS "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/cactus/${ANDROID_ABI}/cactus.h")
        get_filename_component(CACTUS_WHISTLE_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/cactus/${ANDROID_ABI}" ABSOLUTE)
    elseif(EXISTS "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/cactus/android-${ANDROID_ABI}/cactus.h")
        get_filename_component(CACTUS_WHISTLE_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/cactus/android-${ANDROID_ABI}" ABSOLUTE)
    endif()
endif()
if(NOT CACTUS_WHISTLE_DIR STREQUAL "" AND EXISTS "${CACTUS_WHISTLE_DIR}/cactus.h" AND EXISTS "${CACTUS_WHISTLE_DIR}/libcactus.a")
    message(STATUS "Hanami: enabling Cactus Whistle STT runtime from ${CACTUS_WHISTLE_DIR}")
    target_compile_definitions(lfm3pocket PRIVATE HANAMI_CACTUS_WHISTLE=1)
    target_include_directories(lfm3pocket PRIVATE "${CACTUS_WHISTLE_DIR}" "${CMAKE_CURRENT_LIST_DIR}/cactus_whistle")
    target_link_libraries(lfm3pocket PRIVATE "${CACTUS_WHISTLE_DIR}/libcactus.a")
endif()

target_link_libraries(lfm3pocket PRIVATE llama llama-common mtmd android log)
