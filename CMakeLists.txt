cmake_minimum_required(VERSION 3.16)
project(fast_wake_stt CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()

# --- IMPROVEMENT #1: GPU backend toggles ---------------------------------
# Desktop GPU : -DWAKE_ENABLE_VULKAN=ON   (Vulkan SDK installed)
# Android GPU : build whisper.cpp with -DGGML_VULKAN=ON via the NDK toolchain
option(WAKE_ENABLE_VULKAN "Build ggml with the Vulkan GPU backend" OFF)
if(WAKE_ENABLE_VULKAN)
  set(GGML_VULKAN ON CACHE BOOL "" FORCE)
endif()

set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
add_subdirectory(vendor/whisper.cpp)

add_executable(wake_stt src/main.cpp src/wake_engine.cpp src/text_utils.cpp)
target_include_directories(wake_stt PRIVATE src vendor/whisper.cpp/include)
target_link_libraries(wake_stt PRIVATE whisper)

# Unit tests for the pure text helpers (no models needed): `ctest` or
# ./build/text_utils_test
enable_testing()
add_executable(text_utils_test tests/text_utils_test.cpp src/text_utils.cpp)
target_include_directories(text_utils_test PRIVATE src)
add_test(NAME text_utils COMMAND text_utils_test)
