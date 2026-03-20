cmake_minimum_required(VERSION 3.16)
project(grape_pipeline_cpp LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Debe apuntar a la carpeta raíz de ONNX Runtime descomprimido.
# Ejemplo Windows: C:/libs/onnxruntime-win-x64-1.20.1
set(ONNXRUNTIME_ROOT "" CACHE PATH "Ruta raíz de ONNX Runtime")

if(NOT ONNXRUNTIME_ROOT)
    message(FATAL_ERROR "Define ONNXRUNTIME_ROOT. Ejemplo: -DONNXRUNTIME_ROOT=C:/libs/onnxruntime-win-x64-1.20.1")
endif()

find_package(OpenCV REQUIRED)

find_library(ONNXRUNTIME_LIB
    NAMES onnxruntime
    PATHS "${ONNXRUNTIME_ROOT}/lib"
    NO_DEFAULT_PATH
)

if(NOT ONNXRUNTIME_LIB)
    message(FATAL_ERROR "No se encontró la librería onnxruntime en ${ONNXRUNTIME_ROOT}/lib")
endif()

add_executable(grape_pipeline main.cpp)

target_include_directories(grape_pipeline PRIVATE
    ${OpenCV_INCLUDE_DIRS}
    "${ONNXRUNTIME_ROOT}/include"
)

target_link_libraries(grape_pipeline PRIVATE
    ${OpenCV_LIBS}
    ${ONNXRUNTIME_LIB}
)

if(WIN32)
    add_custom_command(TARGET grape_pipeline POST_BUILD
        COMMAND ${CMAKE_COMMAND} -E copy_if_different
            "${ONNXRUNTIME_ROOT}/lib/onnxruntime.dll"
            "$<TARGET_FILE_DIR:grape_pipeline>/onnxruntime.dll"
    )
endif()
