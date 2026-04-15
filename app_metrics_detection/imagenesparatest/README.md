# Pipeline de Pruebas Batch (Imágenes de Test)

Este directorio permite automatizar pruebas masivas del motor de procesamiento de uvas directamente en el emulador o dispositivo físico, generando resultados en JSON con un formato estandarizado y descargándolos automáticamente a tu PC.

## 🚀 Guía de Ejecución Rápida (Sin Errores)

Sigue estos pasos exactamente desde la terminal de **Android Studio**:

1.  **Instalar la App**: Dale al botón **Run ▶️** en Android Studio para instalar la versión más reciente en tu emulador.
2.  **Navegar**: Ejecuta `cd imagenesparatest` en la terminal.
3.  **Permisos de Script**: Si es la primera vez, ejecuta:
    `Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope CurrentUser`
4.  **Correr el Test**: Ejecuta:
    `.\run_kotlin_emulator_batch.ps1`

---

## 📊 Formato de Salida (JSON 1:1)

El sistema genera un archivo `.json` por cada imagen procesada con la siguiente estructura:
- **`source`**: Detalles del archivo original (ruta, tamaño, extensión).
- **`timing_ms`**: Desglose de tiempos (decodificación, pipeline, total).
- **`result`**: Conteos finales (`qty_total`) y variedad inferida.
- **`histogram`**: Distribución de calibres (bins 7-32) según el modelo de regresión.
- **`segmentation`**: Metadatos de la detección (dimensiones de imagen, umbrales y conteo de instancias).
- **`model_debug`**: Información sobre los modelos ONNX utilizados y el provider de ejecución (NNAPI/CPU).

---

## 🛠️ Requisitos Técnicos y Soluciones (Para que no se rompa nada)

### 1. Manejo de Modelos Pesados (.onnx + .data)
El modelo de regresión requiere un archivo de pesos externo llamado `.onnx.data`. 
*   **Solución**: La App detecta automáticamente este archivo en los Assets y lo copia a la memoria interna. **No es necesario mover archivos manualmente**.

### 2. Ahorro de Memoria (Batch vs UI)
Procesar cientos de imágenes seguidas agota la RAM.
*   **Solución**: El `BatchProcessor` libera (recicla) los Bitmaps inmediatamente después de generar el JSON.
*   **Seguridad**: Esto **NO afecta** a la pantalla principal de la App, donde las fotos se siguen viendo normalmente.

### 3. Permisos de Almacenamiento (Debug Only)
*   **Solución**: El permiso `MANAGE_EXTERNAL_STORAGE` se otorga automáticamente vía ADB y solo existe en la versión de **Debug**. Tu App de producción permanece limpia y segura.

### 4. Monitoreo en Tiempo Real
El script muestra el progreso imagen por imagen:
- `Processing [X/Y]`: Progreso actual.
- `[OK]`: Procesamiento exitoso.
- `[FAIL]`: Error detallado (ej. variedad no encontrada o falta de memoria).

---

## 🛡️ ¿Es seguro realizar estos cambios?
**SÍ.** Los cambios realizados:
- No alteran la lógica algorítmica del C++.
- No afectan la experiencia del usuario final (Release).
- Garantizan que el test sea replicable y estable en cualquier equipo con el SDK de Android.
