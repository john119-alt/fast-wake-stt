cmake_minimum_required(VERSION 3.22)
project(fastwake CXX C)

# Repo root is five levels up: cpp -> main -> src -> app -> android -> root
set(ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../../..)

add_subdirectory(${ROOT}/vendor/whisper.cpp ${CMAKE_BINARY_DIR}/whisper)

add_library(fastwake SHARED
    jni.cpp
    ${ROOT}/src/wake_engine.cpp
    ${ROOT}/src/text_utils.cpp)
target_include_directories(fastwake PRIVATE ${ROOT}/src ${ROOT}/vendor/whisper.cpp/include)
target_compile_options(fastwake PRIVATE -O3)
target_link_libraries(fastwake PRIVATE whisper log android)
