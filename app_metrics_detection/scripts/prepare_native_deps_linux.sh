#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

ONNX_VERSION="${1:-1.24.3}"
OPENCV_VERSION="${2:-4.10.0}"

THIRD_PARTY_DIR="$REPO_ROOT/third_party"
ONNX_DIR="$THIRD_PARTY_DIR/onnxruntime"
OPENCV_DIR="$THIRD_PARTY_DIR/opencv"

ONNX_DOWNLOAD_DIR="$ONNX_DIR/downloads"
OPENCV_DOWNLOAD_DIR="$OPENCV_DIR/downloads"

mkdir -p "$ONNX_DIR" "$OPENCV_DIR" "$ONNX_DOWNLOAD_DIR" "$OPENCV_DOWNLOAD_DIR"

ONNX_AAR_NAME="onnxruntime-android-$ONNX_VERSION.aar"
ONNX_AAR_URL="https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/$ONNX_VERSION/$ONNX_AAR_NAME"
ONNX_AAR_PATH="$ONNX_DOWNLOAD_DIR/$ONNX_AAR_NAME"
ONNX_ZIP_PATH="$ONNX_DOWNLOAD_DIR/onnxruntime-android-$ONNX_VERSION.zip"
ONNX_EXTRACT_DIR="$ONNX_DIR/onnxruntime-android-$ONNX_VERSION"

OPENCV_ZIP_NAME="opencv-$OPENCV_VERSION-android-sdk.zip"
OPENCV_ZIP_URL="https://github.com/opencv/opencv/releases/download/$OPENCV_VERSION/$OPENCV_ZIP_NAME"
OPENCV_ZIP_PATH="$OPENCV_DOWNLOAD_DIR/$OPENCV_ZIP_NAME"
OPENCV_SDK_DIR="$OPENCV_DIR/OpenCV-android-sdk"

download_file() {
    local url="$1"
    local output="$2"

    if [[ -f "$output" ]]; then
        echo "[deps] already exists: $output"
        return 0
    fi

    echo "[deps] downloading: $url"
    curl -fL --retry 3 --retry-delay 2 --output "$output" "$url"
}

extract_aar() {
    if [[ -d "$ONNX_EXTRACT_DIR" ]]; then
        echo "[deps] already extracted: $ONNX_EXTRACT_DIR"
        return 0
    fi

    echo "[deps] extracting ONNX Runtime AAR"
    rm -f "$ONNX_ZIP_PATH"
    cp "$ONNX_AAR_PATH" "$ONNX_ZIP_PATH"
    unzip -q "$ONNX_ZIP_PATH" -d "$ONNX_EXTRACT_DIR"
}

extract_opencv() {
    if [[ -d "$OPENCV_SDK_DIR" ]]; then
        echo "[deps] already extracted: $OPENCV_SDK_DIR"
        return 0
    fi

    echo "[deps] extracting OpenCV Android SDK"
    unzip -q "$OPENCV_ZIP_PATH" -d "$OPENCV_DIR"
}

download_file "$ONNX_AAR_URL" "$ONNX_AAR_PATH"
extract_aar

download_file "$OPENCV_ZIP_URL" "$OPENCV_ZIP_PATH"
extract_opencv

ONNX_HEADERS="$ONNX_EXTRACT_DIR/headers"
ONNX_SO_ARM64="$ONNX_EXTRACT_DIR/jni/arm64-v8a/libonnxruntime.so"
OPENCV_JNI_DIR="$OPENCV_SDK_DIR/sdk/native/jni"

[[ -d "$ONNX_HEADERS" ]] || { echo "[deps] missing: $ONNX_HEADERS"; exit 1; }
[[ -f "$ONNX_SO_ARM64" ]] || { echo "[deps] missing: $ONNX_SO_ARM64"; exit 1; }
[[ -d "$OPENCV_JNI_DIR" ]] || { echo "[deps] missing: $OPENCV_JNI_DIR"; exit 1; }

echo "[deps] OK"
echo "[deps] ONNX headers: $ONNX_HEADERS"
echo "[deps] ONNX so arm64: $ONNX_SO_ARM64"
echo "[deps] OpenCV jni: $OPENCV_JNI_DIR"