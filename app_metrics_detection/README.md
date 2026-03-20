# Metrics Detection - Native ONNX Pipeline

Esta aplicación Android realiza **Segmentación de Instancias** y **Detección de Métricas** de uvas utilizando un pipeline de alto rendimiento escrito en C++ con ONNX Runtime y OpenCV nativo.

## 🚀 Arquitectura Actual

A diferencia de versiones anteriores basadas en TFLite y Kotlin puro, la arquitectura actual delega toda la lógica pesada de ML al código nativo:

- **Core Engine (C++)**: Ubicado en `app/src/main/cpp/`. Gestiona la inferencia de modelos, el preprocesamiento de imágenes (OpenCV) y la lógica de regresión.
- **Modelos (ONNX)**: Ubicados en `app/src/main/assets/weights/`. 
  - `seg_best.onnx`: Modelo de segmentación YOLOv8.
  - `best_model_5ch_residual.onnx`: Modelo de regresión de 5 canales (RGB + Mask + DT).
- **Bridge (JNI)**: La clase `CppPipelineBridge` actúa como el puente de datos entre la UI de Kotlin y el motor C++.

## 🛠️ Tecnologías Utilizadas

- **Kotlin**: Capa de UI (Fragments, Navigation Component, ViewModels).
- **C++ (STL 17)**: Pipeline de procesamiento nativo.
- **ONNX Runtime Android**: Motor de inferencia para modelos de segmentación y regresión.
- **OpenCV Android SDK**: Preprocesamiento (Letterbox, Normalización) y Postprocesamiento (Overlays de segmentación).
- **Room/WorkManager**: Persistencia local y sincronización de datos con el servidor.

## 📁 Estructura del Proyecto Real

```text
app/src/main/
├── cpp/                    <-- Motor Nativo (C++)
│   ├── c_code/main.cpp      <-- Lógica principal del pipeline ML
│   └── grape_pipeline_jni.cpp <-- Puente JNI
├── java/.../metrics_detection/
│   ├── ml/                 <-- Orquestación del Pipeline (MetricsPipeline.kt)
│   ├── ui/                 <-- Pantallas de la App (Home, Historial, Perfil)
│   └── data/               <-- Base de datos Room y Modelos
├── assets/weights/         <-- Modelos ONNX de producción
└── res/xml/                <-- Configuración de red y FileProviders
```

## ⚠️ Notas de Mantenimiento

- **Variedades**: El orden activo vive en `RuntimeVarietyCatalog.kt`, `pipeline.py` y `main.cpp`. `bundle_kotlin.json` no participa en la inferencia ONNX/JNI actual.
- **Seguridad**: La aplicación requiere permisos de **Cámara** e **Internet**. La configuración de red actual permite tráfico HTTP para depuración, pero debe cambiarse a HTTPS para producción.
- **Build**: Para generar el APK de release, asegúrate de activar `isMinifyEnabled = true` en `build.gradle.kts` y verificar las reglas de ProGuard para evitar que se eliminen los métodos `native`.
