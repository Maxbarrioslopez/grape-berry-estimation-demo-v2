
# 📱 Metrics Detection - Pipeline Nativo ONNX (Android)

Aplicación Android para **segmentación de instancias** y **detección de métricas** en uvas, integrando procesamiento nativo C++ (ONNX Runtime, OpenCV), modelos de deep learning y una interfaz moderna en Kotlin. El sistema está alineado con un backend multi-tenant y un motor de auditoría forense.

---

## 🚀 Descripción General

El sistema realiza:
- Preprocesamiento de imágenes (OpenCV nativo)
- Inferencia de modelos ONNX (segmentación y regresión)
- Postprocesamiento y visualización en la app
- **Auditoría técnica**: Generación de máscaras binarias raw y detecciones para validación externa.
- **Sincronización**: Gestión de lotes (Batch) con persistencia offline/online.

---

## 🔐 Seguridad y Autenticación (Multi-tenant)

La aplicación ha sido adaptada para entornos corporativos con las siguientes reglas:

- **Multi-tenant Invisible**: El `companyId` (tenant real) lo gestiona el backend mediante el JWT. El campo `company` en los lotes se mantiene solo por compatibilidad de contrato Multipart.
- **Registro por Invitación**: El alta de usuarios finales requiere un `inviteCode` corporativo válido (`POST /auth/company-registration`).
- **Gestión de Roles**: Soporte para roles `superadmin`, `client_admin` y `user` para el control de acceso en la UI.
- **Recuperación de Cuenta**: Flujo estricto de recuperación mediante Email + RUT.
- **Persistencia Segura**: Uso de `EncryptedSharedPreferences` (vía `TokenProvider`) para almacenar tokens y roles de usuario.

---

## 🧪 Validación y Auditoría Forense

El proyecto incluye un motor de validación para asegurar la paridad de resultados entre el pipeline de investigación (Python) y el de producción (Android/JNI).

### Scripts de Validación (`/app_metrics_detection/imagenesparatest/`)
- **`run_kotlin_emulator_batch.ps1`**: Automatiza la ejecución de tests masivos en el emulador, descarga resultados y lanza la evaluación.
- **`eval_jni_vs_gt.py`**: Genera reportes de precisión detallados (`reporte_forense_maxi.md`) comparando predicciones contra Ground Truth (CSV).

### Evidencia Técnica Generada
Por cada imagen, el sistema exporta:
- **`.json`**: Datos técnicos, tiempos de inferencia y parámetros bimodales.
- **`_raw.png`**: Máscara binaria pura (0/255) resultante de la segmentación.
- **`_pro.jpg`**: Overlay con cajas de detección (BBoxes) y scores.
- **`_seg.jpg`**: Visualización coloreada de la máscara de uvas.

---

## 🛠️ Tecnologías y Librerías Utilizadas

### Android/Kotlin
- **UI**: ViewBinding, Fragment, Navigation, MPAndroidChart.
- **Sesión**: Security Crypto (EncryptedSharedPreferences).
- **Red**: Retrofit2 (con DTOs seguros), OkHttp3, Gson.
- **Background**: WorkManager (SyncWorker para subida offline).
- **Imágenes**: Glide, uCrop, ExifInterface (Telemetría ISO/Exposure).

### Nativo (C++)
- **Engine**: C++17, ONNX Runtime Android (Inferencia), OpenCV Android SDK.
- **Bridge**: JNI para comunicación eficiente entre Kotlin y el motor de procesamiento.

---

## 📁 Estructura del Proyecto

```text
optimizacion_uvas/
├── pipeline.py                  # Orquestador Python (Entrenamiento)
├── app_metrics_detection/
│   ├── app/src/main/
│   │   ├── cpp/                 # Motor JNI y lógica C++ (main.cpp)
│   │   ├── java/.../auth/       # Login, Registro (Invite), Recovery
│   │   ├── java/.../network/    # ApiService (v1), TokenProvider
│   │   ├── java/.../data/       # Repositorios y DTOs (AuthRequests)
│   │   ├── java/.../worker/     # SyncWorker (Multi-tenant aware)
│   │   └── assets/weights/      # Modelos ONNX (seg_best, regression)
│   ├── imagenesparatest/        # Motor de pruebas forenses (PS1, Python)
│   └── third_party/             # Librerías nativas (ONNX/OpenCV)
└── README.md                    # Este archivo
```

---

## ⚙️ Notas de Build

- **Actualización de Motor**: Al modificar el código C++ (`main.cpp` o `jni`), es **obligatorio** realizar un `Build > Rebuild Project` en Android Studio para recompilar el binario `.so`.
- **ABI**: Soporte optimizado para `arm64-v8a`.
- **Preparación**: Ejecutar `scripts/prepare_native_deps.ps1` para descargar dependencias nativas.

---

## 📚 Créditos y Licencias

- **Gaia Robotics Team**
- ONNX Runtime: https://onnxruntime.ai/
- OpenCV: https://opencv.org/
