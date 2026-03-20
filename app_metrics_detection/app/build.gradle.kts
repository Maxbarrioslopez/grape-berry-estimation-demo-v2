import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    id("androidx.navigation.safeargs.kotlin")

    id("kotlin-kapt")
}

// Carga de keystore.properties desde el root del proyecto
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

val prepareNativeDeps by tasks.registering(Exec::class) {
    group = "native"
    description = "Descarga y extrae ONNX Runtime Android + OpenCV Android SDK en third_party"
    workingDir = rootProject.projectDir

    if (org.gradle.internal.os.OperatingSystem.current().isWindows) {
        commandLine(
            "powershell",
            "-ExecutionPolicy", "Bypass",
            "-File", "scripts/prepare_native_deps.ps1"
        )
    } else {
        doFirst {
            throw GradleException("Este proyecto incluye automatizacion Windows via scripts/prepare_native_deps.ps1. En Linux/macOS agrega un script equivalente.")
        }
    }
}

android {
    namespace = "com.gaiaspa.metrics_detection"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.gaiaspa.metrics_detection"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                val ortRoot = (project.findProperty("ONNXRUNTIME_ANDROID_ROOT") as String?)
                val opencvRoot = (project.findProperty("OPENCV_ANDROID_SDK") as String?)

                if (!ortRoot.isNullOrBlank()) {
                    arguments += "-DONNXRUNTIME_ANDROID_ROOT=$ortRoot"
                }
                if (!opencvRoot.isNullOrBlank()) {
                    arguments += "-DOPENCV_ANDROID_SDK=$opencvRoot"
                }
            }
        }
    }

    signingConfigs {
        create("release") {
            if (keystoreProperties.isNotEmpty()) {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir("../third_party/onnxruntime/onnxruntime-android-1.24.3/jni")
        }
    }
}

tasks.matching {
    it.name == "preBuild" ||
        it.name.startsWith("configureCMake") ||
        it.name.startsWith("buildCMake") ||
        it.name.startsWith("externalNativeBuild")
}.configureEach {
    dependsOn(prepareNativeDeps)
}

dependencies {

    // Dependencias principales de Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.ui.android)

    // Dependencias para pruebas
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // Navigation
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)

    // TensorFlow Lite y otras librerías
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
    implementation("org.tensorflow:tensorflow-lite-metadata:0.4.4")
    implementation("com.github.haifengl:smile-core:2.6.0")
    implementation("org.boofcv:boofcv-android:0.40")

    //
    implementation ("com.google.code.gson:gson:2.9.0")

    // Room
    implementation ("androidx.room:room-runtime:2.5.2")
    kapt ("androidx.room:room-compiler:2.5.2")

    // Opcional: Kotlin Extensions y Coroutines
    implementation ("androidx.room:room-ktx:2.5.2")
    implementation ("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    //
    implementation (libs.mpandroidchart)

    //
    implementation ("com.github.bumptech.glide:glide:4.15.1")
    annotationProcessor ("com.github.bumptech.glide:compiler:4.15.1")
    //implementation (libs.mpandroidchart)

    //
    implementation("com.squareup.okhttp3:okhttp:4.9.1")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")

    //
    //implementation ("androidx.security:security-crypto:1.0.0")
    //implementation ("androidx.security:security-crypto:1.1.0")
    implementation ("androidx.security:security-crypto-ktx:1.1.0-alpha03")

    //
    implementation ("com.github.yalantis:ucrop:2.2.8")

    //
    implementation ("androidx.work:work-runtime-ktx:2.7.1")
}
