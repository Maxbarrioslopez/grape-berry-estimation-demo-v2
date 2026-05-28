# PRODUCTION RULES - GRAPE METRICS
# Priority: Stability over size.

# 1. Preserve JNI (Critical for ONNX/C++)
-keepclasseswithmembernames class * {
    native <methods>;
}

# 2. Preserve data models (GSON)
# Prevents field renaming from breaking communication with server and pipeline
-keep class com.gaiaspa.metrics_detection.data.model.** { *; }
-keep class com.gaiaspa.metrics_detection.data.model.request.** { *; }
-keep class com.gaiaspa.metrics_detection.data.model.response.** { *; }

# 3. Preserve Google Tink / Protobuf
# Prevents 'InvalidProtocolBufferException: invalid tag zero' error in Release
-keep class com.google.crypto.tink.** { *; }
-keep class com.google.crypto.tink.shaded.protobuf.** { *; }

# 4. Preserve Room
-keep class androidx.room.RoomDatabase { *; }
-keep class * extends androidx.room.RoomDatabase { *; }

# 5. MPAndroidChart (if used in release)
-keep class com.github.mikephil.charting.** { *; }

# 6. ONNX Runtime (specific to prevent native method stripping)
-keep class ai.onnxruntime.** { *; }
# 7. Retrofit / OkHttp / Gson - preserve generics and annotations
# Critical: prevents ClassCastException Class -> ParameterizedType
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

# Preserve real project models
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

# Your API
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
# Prevents "Missing type parameter" crash when opening history in release
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses,EnclosingMethod

-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# Preserve entities, converters, and local models used by Room/Gson
-keep class com.gaiaspa.metrics_detection.data.local.** { *; }
-keep class com.gaiaspa.metrics_detection.data.database.** { *; }
-keep class com.gaiaspa.metrics_detection.data.entity.** { *; }
-keep class com.gaiaspa.metrics_detection.data.dao.** { *; }
