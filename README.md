# Pipeline optimizado de uvas (Python + C++)

Este repositorio contiene:
- pipeline.py: pipeline completo en Python (segmentación + regresor ONNX + comparación con GT).
- c_code/main.cpp: versión C++ del pipeline para inferencia ONNX Runtime + OpenCV.

## 1) Requisitos en Windows

- Python 3.11+
- CMake 3.16+
- Visual Studio 2022 (Desktop development with C++)
- OpenCV (instalado y con OpenCV_DIR configurado)
- ONNX Runtime C/C++ package (zip oficial)

Para runtime de MSVC:
- https://learn.microsoft.com/en-us/cpp/windows/latest-supported-vc-redist?view=msvc-170#latest-supported-redistributable-version

## 2) Ejecutar pipeline en Python

Desde la raíz del repo:

```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -U pip
pip install -e .
python pipeline.py
```

Variables opcionales para sobreescribir rutas:

```powershell
$env:SEG_ONNX_PATH="C:/ruta/seg_best.onnx"
$env:REG_ONNX_PATH="C:/ruta/best_model_5ch_residual.onnx"
$env:CSV_PATH="C:/ruta/labels_v4.csv"
$env:IMAGE_ROOT="C:/ruta/images"
$env:IMAGE_PATH="C:/ruta/imagen.jpg"
python pipeline.py
```

## 3) Compilar y ejecutar C++

### 3.1 Preparar dependencias

1. Descarga ONNX Runtime para Windows (x64) y descomprime, por ejemplo en:
   - C:/libs/onnxruntime-win-x64-1.20.1
2. Asegura OpenCV_DIR apuntando al OpenCVConfig.cmake, por ejemplo:
   - C:/libs/opencv/build

### 3.2 Configurar CMake

Desde la raíz del repo:

```powershell
cmake -S c_code -B c_code/build -G "Visual Studio 17 2022" -A x64 -DONNXRUNTIME_ROOT="C:/libs/onnxruntime-win-x64-1.20.1" -DOpenCV_DIR="C:/libs/opencv/build"
```

### 3.3 Build y run

```powershell
cmake --build c_code/build --config Release
.\c_code\build\Release\grape_pipeline.exe
```

El ejecutable genera imágenes de salida en el directorio de ejecución:
- seg_overlay.jpg
- rgb_letterbox.jpg
- grapes_mask.jpg
- grapes_dt.jpg

## 4) Notas de rutas

- En C++, los modelos e imagen están hardcodeados en c_code/main.cpp como rutas relativas (../weights/..., ../data/images/...).
- Si quieres evaluar otra imagen/modelo, cambia esas constantes o conviértelas a argumentos CLI/env vars.
