# Native Android C++ Setup (ONNX Runtime + OpenCV)

Este proyecto automatiza la preparacion de dependencias nativas en `third_party/` para compilar el pipeline C++ en Android sin rutas locales personales.

## 1) Descargar dependencias

Desde la raiz de `app_metrics_detection` ejecuta:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/prepare_native_deps.ps1
```

Esto descarga y extrae:

- ONNX Runtime Android AAR -> `third_party/onnxruntime/onnxruntime-android-1.24.3`
- OpenCV Android SDK -> `third_party/opencv/OpenCV-android-sdk`

## 2) Compilar

```powershell
.\gradlew.bat :app:assembleDebug
```

Gradle ejecuta automaticamente `prepare_native_deps` antes del native build.

## 3) ABI soportada inicialmente

- `arm64-v8a`

La configuracion esta fijada asi en:

- `app/build.gradle.kts` (`ndk.abiFilters`)
- `app/src/main/cpp/CMakeLists.txt` (validacion de ABI)

## 4) Agregar mas ABIs despues

1. Agrega ABI en `app/build.gradle.kts` dentro de `ndk.abiFilters`.
2. Asegura que ONNX Runtime tenga `jni/<abi>/libonnxruntime.so` para esas ABIs.
3. Ajusta/elimina la validacion estricta de ABI en `app/src/main/cpp/CMakeLists.txt`.
4. Recompila.

## 5) Fallback documentado

- Si no hay PowerShell (Linux/macOS), el task Gradle falla con mensaje claro.
- Puedes sobreescribir rutas con `ONNXRUNTIME_ANDROID_ROOT` y `OPENCV_ANDROID_SDK` en `gradle.properties` si necesitas usar dependencias preinstaladas.
