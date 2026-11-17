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

add_library(lfm3pocket SHARED native.cpp speculative_decode.cpp)
target_compile_options(lfm3pocket PRIVATE "$<$<CONFIG:Debug>:-O3>" "$<$<CONFIG:Release>:-O3>")
if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_options(lfm3pocket PRIVATE -march=armv8.2-a+dotprod)
    target_compile_definitions(lfm3pocket PRIVATE HANAMI_ARM_DOTPROD=1)
endif()
target_link_libraries(lfm3pocket PRIVATE llama llama-common mtmd android log)
