cmake_minimum_required(VERSION 3.22)
project(lfm3pocket LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# PocketPal / llama.rn always compile CPU kernels with -O3 -DNDEBUG, including
# Debug APKs. CI ships phone Debug builds, so leaving NDK Debug at -O0/-O2 made
# on-device decode look ~2× slower than Release peers.
set(CMAKE_C_FLAGS_DEBUG "-O3 -DNDEBUG -g" CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS_DEBUG "-O3 -DNDEBUG -g" CACHE STRING "" FORCE)
set(CMAKE_C_FLAGS_RELEASE "-O3 -DNDEBUG" CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG" CACHE STRING "" FORCE)
set(CMAKE_C_FLAGS_RELWITHDEBINFO "-O3 -DNDEBUG -g" CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS_RELWITHDEBINFO "-O3 -DNDEBUG -g" CACHE STRING "" FORCE)

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
# KleidiAI pulls FetchContent at configure time; keep OFF for offline / F-Droid
# reproducible builds. PocketPal gets similar kernels via its multi-.so ladder.
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# Cross-compile must not use -march=native (GGML_NATIVE=OFF). Without an explicit
# GGML_CPU_ARM_ARCH, ggml leaves ARCH_FLAGS empty and ships baseline NEON only —
# no DOTPROD — which is the main tok/s gap vs PocketPal/ChatterUI's
# rnllama_v8_2_dotprod*. Single arm64-v8a .so stays F-Droid friendly; i8mm is
# omitted so Snapdragon 720G-class phones (dotprod yes, i8mm no) still run.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod" CACHE STRING "" FORCE)
    message(STATUS "Hanami: GGML_CPU_ARM_ARCH=${GGML_CPU_ARM_ARCH} (PocketPal/llama.rn parity)")
endif()

get_filename_component(LLAMA_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp" ABSOLUTE)
if(NOT EXISTS "${LLAMA_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "Missing llama.cpp in third_party/llama.cpp: see README")
endif()
add_subdirectory("${LLAMA_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/llama-build" EXCLUDE_FROM_ALL)

add_library(lfm3pocket SHARED native.cpp)
target_compile_options(lfm3pocket PRIVATE -O3 -DNDEBUG)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    # Match ggml-cpu so JNI sees the same feature macros as the kernels.
    target_compile_options(lfm3pocket PRIVATE -march=armv8.2-a+dotprod)
    target_compile_definitions(lfm3pocket PRIVATE HANAMI_ARM_DOTPROD=1)
endif()
target_link_libraries(lfm3pocket PRIVATE llama mtmd android log)
