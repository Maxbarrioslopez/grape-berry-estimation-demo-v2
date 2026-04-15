# REGLAS DE PRODUCCIÓN - MÉTRICAS DE UVA
# Prioridad: Estabilidad sobre tamaño.

# 1. Preservar JNI (Crítico para ONNX/C++)
-keepclasseswithmembernames class * {
    native <methods>;
}

# 2. Preservar modelos de datos (GSON)
# Evita que el renombrado de campos rompa la comunicación con el servidor y el pipeline
-keep class com.gaiaspa.metrics_detection.data.model.** { *; }
-keep class com.gaiaspa.metrics_detection.data.model.request.** { *; }
-keep class com.gaiaspa.metrics_detection.data.model.response.** { *; }

# 3. Preservar Google Tink / Protobuf
# Evita el error 'InvalidProtocolBufferException: invalid tag zero' en Release
-keep class com.google.crypto.tink.** { *; }
-keep class com.google.crypto.tink.shaded.protobuf.** { *; }

# 4. Preservar Room
-keep class androidx.room.RoomDatabase { *; }
-keep class * extends androidx.room.RoomDatabase { *; }

# 5. MPAndroidChart (si se usa en release)
-keep class com.github.mikephil.charting.** { *; }

# 6. ONNX Runtime (específico para evitar stripping de métodos nativos)
-keep class ai.onnxruntime.** { *; }
