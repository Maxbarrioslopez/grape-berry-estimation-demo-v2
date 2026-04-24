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
# 7. Retrofit / OkHttp / Gson - preservar genéricos y anotaciones
# Crítico: evita ClassCastException Class -> ParameterizedType
-keepattributes Signature
-keepattributes *Annotation*

-keep class retrofit2.** { *; }
-dontwarn retrofit2.**

-keep class okhttp3.** { *; }
-dontwarn okhttp3.**

-keep class okio.** { *; }
-dontwarn okio.**

-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**

# Preservar modelos reales del proyecto
-keep class com.gaiaspa.metrics_detection.data.model.** { *; }
-keep class com.gaiaspa.metrics_detection.data.remote.** { *; }
-keep interface com.gaiaspa.metrics_detection.data.remote.** { *; }
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod
-keep interface com.gaiaspa.metrics_detection.network.ApiService { *; }
-keep class com.gaiaspa.metrics_detection.network.ApiService { *; }
# Retrofit generic signatures
-keepattributes Signature
-keepattributes Exceptions
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault

# Retrofit interfaces
-keep interface retrofit2.** { *; }
-keep class retrofit2.** { *; }

# Tu API
-keep interface com.gaiaspa.metrics_detection.network.ApiService { *; }

# Kotlin coroutines metadata
-keep class kotlin.coroutines.** { *; }

# Response models
-keep class com.gaiaspa.metrics_detection.data.model.response.** { *; }
-keep class com.gaiaspa.metrics_detection.data.model.request.** { *; }
-keepattributes Signature
-keepattributes *Annotation*

-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# 8. Gson TypeToken / Room local history
# Evita crash "Missing type parameter" al abrir historial en release
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses,EnclosingMethod

-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# Preservar entidades, converters y modelos locales usados por Room/Gson
-keep class com.gaiaspa.metrics_detection.data.local.** { *; }
-keep class com.gaiaspa.metrics_detection.data.database.** { *; }
-keep class com.gaiaspa.metrics_detection.data.entity.** { *; }
-keep class com.gaiaspa.metrics_detection.data.dao.** { *; }