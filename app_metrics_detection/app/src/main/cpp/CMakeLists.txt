cmake_minimum_required(VERSION 3.22.1)
project(grape_pipeline_jni LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

if(NOT ANDROID)
    message(FATAL_ERROR "This CMake module is intended for Android Studio.")
endif()

if(NOT ANDROID_ABI STREQUAL "arm64-v8a" AND
   NOT ANDROID_ABI STREQUAL "armeabi-v7a" AND
   NOT ANDROID_ABI STREQUAL "x86_64")
    message(FATAL_ERROR "Unsupported ABI: ${ANDROID_ABI}. Allowed: arm64-v8a, armeabi-v7a, x86_64.")
endif()

get_filename_component(MODULE_ROOT "${CMAKE_CURRENT_LIST_DIR}" ABSOLUTE)
get_filename_component(REPO_ROOT "${MODULE_ROOT}/../../../.." ABSOLUTE)

set(ONNXRUNTIME_ANDROID_ROOT
    "${REPO_ROOT}/third_party/onnxruntime/onnxruntime-android-1.24.3"
    CACHE PATH "ONNX Runtime Android root path")
set(OPENCV_ANDROID_SDK
    "${REPO_ROOT}/third_party/opencv/OpenCV-android-sdk"
    CACHE PATH "OpenCV Android SDK path")

set(OpenCV_DIR "${OPENCV_ANDROID_SDK}/sdk/native/jni")
set(ONNXRUNTIME_INCLUDE_DIR "${ONNXRUNTIME_ANDROID_ROOT}/headers")
set(ONNXRUNTIME_LIB "${ONNXRUNTIME_ANDROID_ROOT}/jni/${ANDROID_ABI}/libonnxruntime.so")

if(NOT EXISTS "${OpenCV_DIR}")
    message(FATAL_ERROR "OpenCV Android SDK not found at: ${OpenCV_DIR}")
endif()

if(NOT EXISTS "${ONNXRUNTIME_INCLUDE_DIR}")
    message(FATAL_ERROR "ONNX Runtime headers not found at: ${ONNXRUNTIME_INCLUDE_DIR}")
endif()

if(NOT EXISTS "${ONNXRUNTIME_LIB}")
    message(FATAL_ERROR "ONNX Runtime library not found at: ${ONNXRUNTIME_LIB}")
endif()

find_package(OpenCV REQUIRED)

add_library(onnxruntime SHARED IMPORTED)
set_target_properties(onnxruntime PROPERTIES IMPORTED_LOCATION "${ONNXRUNTIME_LIB}")

add_library(grape_pipeline_jni SHARED
    jni/grape_pipeline_jni.cpp
    src/grape_pipeline_onnx.cpp
    src/grape_pipeline_preprocess.cpp
    src/grape_pipeline_postprocess.cpp
    src/grape_pipeline_core.cpp
)

target_include_directories(grape_pipeline_jni PRIVATE
    "${MODULE_ROOT}/include"
    "${ONNXRUNTIME_INCLUDE_DIR}"
    ${OpenCV_INCLUDE_DIRS}
)

target_link_libraries(grape_pipeline_jni
    ${OpenCV_LIBS}
    onnxruntime
    jnigraphics
    android
    log
)
