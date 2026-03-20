cmake_minimum_required(VERSION 3.22.1)
project(grape_pipeline_jni LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Repo-local third_party defaults (override via -D if needed)
get_filename_component(REPO_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../" ABSOLUTE)
set(THIRD_PARTY_DIR "${REPO_ROOT}/third_party")

set(ONNXRUNTIME_ANDROID_ROOT "${THIRD_PARTY_DIR}/onnxruntime/onnxruntime-android-1.24.3" CACHE PATH "Ruta raiz ONNX Runtime Android (headers + jni/<abi>)")
set(OPENCV_ANDROID_SDK "${THIRD_PARTY_DIR}/opencv/OpenCV-android-sdk" CACHE PATH "Ruta SDK OpenCV Android")

if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "ABI no soportada por defecto: ${ANDROID_ABI}. Inicialmente se soporta arm64-v8a.")
endif()

if(NOT ONNXRUNTIME_ANDROID_ROOT)
    message(FATAL_ERROR "Define ONNXRUNTIME_ANDROID_ROOT para compilar grape_pipeline_jni")
endif()

if(NOT OPENCV_ANDROID_SDK)
    message(FATAL_ERROR "Define OPENCV_ANDROID_SDK para compilar grape_pipeline_jni")
endif()

include_directories(
    ${ONNXRUNTIME_ANDROID_ROOT}/headers
)

set(OpenCV_DIR ${OPENCV_ANDROID_SDK}/sdk/native/jni)
find_package(OpenCV REQUIRED)

add_library(grape_pipeline_jni SHARED
    grape_pipeline_jni.cpp
)

target_include_directories(grape_pipeline_jni PRIVATE
    ${OpenCV_INCLUDE_DIRS}
)

# Expected ORT native lib path: <ONNXRUNTIME_ANDROID_ROOT>/jni/<abi>/libonnxruntime.so
set(ONNXRUNTIME_LIB "${ONNXRUNTIME_ANDROID_ROOT}/jni/${ANDROID_ABI}/libonnxruntime.so")

if(NOT EXISTS "${ONNXRUNTIME_LIB}")
    message(FATAL_ERROR "No se encontro libonnxruntime.so en ${ONNXRUNTIME_ANDROID_ROOT}/jni/${ANDROID_ABI}")
endif()

target_link_libraries(grape_pipeline_jni
    ${OpenCV_LIBS}
    ${ONNXRUNTIME_LIB}
    jnigraphics
    android
    log
)
