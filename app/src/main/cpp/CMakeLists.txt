cmake_minimum_required(VERSION 3.22)
project(lfm3pocket LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# CI distributes debug APKs for phone testing. Keep -g while optimizing CPU
# kernels; unoptimized ggml makes real-device inference misleadingly slow.
if(CMAKE_CXX_COMPILER_ID MATCHES "Clang|GNU")
    add_compile_options("$<$<CONFIG:Debug>:-O2>")
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
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

get_filename_component(LLAMA_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp" ABSOLUTE)
if(NOT EXISTS "${LLAMA_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "Missing llama.cpp in third_party/llama.cpp: see README")
endif()
add_subdirectory("${LLAMA_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/llama-build" EXCLUDE_FROM_ALL)

add_library(lfm3pocket SHARED native.cpp)
target_link_libraries(lfm3pocket PRIVATE llama mtmd android log)

