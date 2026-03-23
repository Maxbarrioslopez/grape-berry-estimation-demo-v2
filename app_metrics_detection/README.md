
# 📱 Metrics Detection - Pipeline Nativo ONNX (Android)

Aplicación Android para **segmentación de instancias** y **detección de métricas** en uvas, integrando procesamiento nativo C++ (ONNX Runtime, OpenCV), modelos de deep learning y una interfaz moderna en Kotlin. El pipeline es multiplataforma y combina componentes Python, C++ y Android.

---

## 🚀 Descripción General

El sistema realiza:
- Preprocesamiento de imágenes (OpenCV nativo)
- Inferencia de modelos ONNX (segmentación y regresión)
- Postprocesamiento y visualización en la app
- Persistencia y sincronización de resultados

### Componentes principales

- **pipeline.py**: Orquestador Python para pruebas y entrenamiento.
- **app_metrics_detection/app/**: App Android nativa (UI, lógica, integración JNI).
- **c_code/**: Código C++ para el pipeline nativo y lógica de inferencia.
- **weights/**: Modelos ONNX de producción (`seg_best.onnx`, `best_model_5ch_residual.onnx`).
- **third_party/**: Dependencias nativas (ONNX Runtime, OpenCV Android SDK).
- **data/**: Imágenes y etiquetas para entrenamiento/pruebas.

---

## 🛠️ Tecnologías y Librerías Utilizadas

### Android/Kotlin
- Kotlin (UI, lógica de app)
- AndroidX (core, appcompat, navigation, constraintlayout, exifinterface, activity, fragment, ViewModel, Room, WorkManager)
- Material Components
- Glide (carga de imágenes)
- MPAndroidChart (gráficas)
- OkHttp3, Retrofit2 (red y APIs)
- Gson (serialización JSON)
- uCrop (recorte de imágenes)
- Security Crypto (almacenamiento seguro)

### Nativo (C++)
- C++17 (STL)
- ONNX Runtime Android (inferencia de modelos)
- OpenCV Android SDK (procesamiento de imágenes)

### Python (pipeline y pruebas)
- Python >=3.11
- matplotlib
- numpy
- onnxruntime
- opencv-python
- pandas

---

## 📦 Instalación y Descarga de Dependencias

### 1. Dependencias nativas (ONNX Runtime + OpenCV)

Desde la raíz de `app_metrics_detection`, ejecuta:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/prepare_native_deps.ps1
```

Esto descarga y extrae automáticamente:
- ONNX Runtime Android AAR → `third_party/onnxruntime/onnxruntime-android-1.24.3`
- OpenCV Android SDK → `third_party/opencv/OpenCV-android-sdk`

> Si usas Linux/macOS, deberás crear un script equivalente o descargar manualmente las dependencias y configurar las rutas en `gradle.properties` (`ONNXRUNTIME_ANDROID_ROOT`, `OPENCV_ANDROID_SDK`).

### 2. Dependencias de la app Android

Gradle gestiona todas las librerías. Para compilar:

```powershell
./gradlew.bat :app:assembleDebug
```


---

## 📁 Estructura del Proyecto

```text
optimizacion_uvas/
├── pipeline.py                  # Orquestador Python
├── pyproject.toml               # Configuración Python
├── app_metrics_detection/
│   ├── app/                     # App Android
│   │   ├── src/main/
│   │   │   ├── cpp/             # Motor nativo C++
│   │   │   ├── java/.../        # Lógica y UI Kotlin
│   │   │   ├── assets/weights/  # Modelos ONNX
│   │   │   └── res/xml/         # Configuración
│   │   └── build.gradle.kts     # Configuración Gradle app
│   ├── scripts/                 # Scripts automatización
│   ├── third_party/             # ONNX/OpenCV nativos
│   └── build.gradle.kts         # Configuración Gradle raíz
├── c_code/                      # Código C++ standalone
├── data/                        # Imágenes y etiquetas
├── weights/                     # Modelos ONNX
└── README.md                    # Este archivo
```

---

## ⚙️ Notas de Build y Compatibilidad

- ABI soportada: `arm64-v8a` (puedes agregar más en `ndk.abiFilters` y asegurando los .so correspondientes)
- El build ejecuta automáticamente la descarga de dependencias nativas antes de compilar C++
- Puedes sobreescribir rutas de dependencias nativas en `gradle.properties`
- Para release, revisa reglas de ProGuard y activa `isMinifyEnabled = true` para optimizar el APK

---

## 📝 Notas de Mantenimiento y Seguridad

- El orden de variedades se define en `RuntimeVarietyCatalog.kt`, `pipeline.py` y `main.cpp`
- Permisos requeridos: **Cámara** e **Internet**
- La app permite HTTP solo en debug, usa HTTPS en producción
- `bundle_kotlin.json` no participa en la inferencia ONNX/JNI

---

## 📚 Créditos y Licencias

- ONNX Runtime: https://onnxruntime.ai/
- OpenCV: https://opencv.org/
- MPAndroidChart: https://github.com/PhilJay/MPAndroidChart
- Glide: https://github.com/bumptech/glide

---

Para dudas o contribuciones, contacta al responsable del proyecto.
