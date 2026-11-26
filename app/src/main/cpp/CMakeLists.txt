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
        -ffunction-sections
        -fdata-sections
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

set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD ON CACHE BOOL "" FORCE)
set(LLAMA_SUBPROCESS OFF CACHE BOOL "" FORCE)
set(MTMD_VIDEO OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(GGML_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
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

# Compile only llama.cpp's self-contained 65-line ngram-mod table directly rather than
# building all ~55+ translation units in llama-common (Jinja, parsers, httplib, CLI args).
add_library(lfm3pocket SHARED
    native.cpp
    speculative_decode.cpp
    cactus_whistle_jni.cpp
    "${LLAMA_DIR}/common/ngram-mod.cpp"
)
target_compile_options(lfm3pocket PRIVATE "$<$<CONFIG:Debug>:-O3>" "$<$<CONFIG:Release>:-O3>")
if(CMAKE_CXX_COMPILER_ID MATCHES "Clang|GNU")
    target_link_options(lfm3pocket PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
endif()
if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_options(lfm3pocket PRIVATE -march=armv8.2-a+dotprod)
    target_compile_definitions(lfm3pocket PRIVATE HANAMI_ARM_DOTPROD=1)
endif()
if(GGML_VULKAN)
    target_compile_definitions(lfm3pocket PRIVATE HANAMI_ENABLE_VULKAN_OFFLOAD=1)
    message(STATUS "Hanami: experimental GGML_VULKAN enabled (spike #164); runtime will attempt GPU offload with CPU fallback")
endif()
target_include_directories(lfm3pocket PRIVATE
    "${LLAMA_DIR}/common"
    "${LLAMA_DIR}/include"
)

# Optional: Cactus Whistle STT runtime (Android native artifacts from Cactus release).
# Provide headers/lib at third_party/cactus/<abi>/cactus.h and libcactus.a (or set CACTUS_WHISTLE_DIR).
# Upstream HF layout uses android-arm64 for ANDROID_ABI=arm64-v8a; scripts/fetch-cactus.sh
# installs into third_party/cactus/arm64-v8a/. Phone release / ARM64 CI require the artifacts.
option(HANAMI_REQUIRE_CACTUS "Fail the build when arm64-v8a Cactus runtime artifacts are missing" OFF)
set(CACTUS_WHISTLE_DIR "" CACHE PATH "Directory containing cactus.h and libcactus.a for the target ABI")
if(CACTUS_WHISTLE_DIR STREQUAL "" AND DEFINED ANDROID_ABI)
    set(_hanami_cactus_candidates
        "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/cactus/${ANDROID_ABI}"
        "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/cactus/android-${ANDROID_ABI}")
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        list(APPEND _hanami_cactus_candidates
            "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/cactus/android-arm64")
    endif()
    foreach(_hanami_cactus_dir IN LISTS _hanami_cactus_candidates)
        if(EXISTS "${_hanami_cactus_dir}/cactus.h" AND EXISTS "${_hanami_cactus_dir}/libcactus.a")
            get_filename_component(CACTUS_WHISTLE_DIR "${_hanami_cactus_dir}" ABSOLUTE)
            break()
        endif()
    endforeach()
endif()
set(_hanami_cactus_enabled OFF)
if(NOT CACTUS_WHISTLE_DIR STREQUAL "" AND EXISTS "${CACTUS_WHISTLE_DIR}/cactus.h" AND EXISTS "${CACTUS_WHISTLE_DIR}/libcactus.a")
    message(STATUS "Hanami: enabling Cactus Whistle STT runtime from ${CACTUS_WHISTLE_DIR}")
    target_compile_definitions(lfm3pocket PRIVATE HANAMI_CACTUS_WHISTLE=1)
    # Prefer the fetched ABI header (cactus.h) over the local stub under cactus_whistle/.
    target_include_directories(lfm3pocket PRIVATE "${CACTUS_WHISTLE_DIR}")
    target_link_libraries(lfm3pocket PRIVATE "${CACTUS_WHISTLE_DIR}/libcactus.a" m)
    set(_hanami_cactus_enabled ON)
endif()
if(HANAMI_REQUIRE_CACTUS AND ANDROID_ABI STREQUAL "arm64-v8a" AND NOT _hanami_cactus_enabled)
    message(FATAL_ERROR
        "HANAMI_REQUIRE_CACTUS=ON but cactus.h/libcactus.a were not found for arm64-v8a. "
        "Run scripts/fetch-cactus.sh (pinned in vendor/CACTUS_RELEASE) before assembling the phone APK.")
endif()

target_link_libraries(lfm3pocket PRIVATE llama mtmd android log)
