param(
    [string]$OnnxVersion = "1.24.3",
    [string]$OpenCvVersion = "4.10.0"
)

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Split-Path -Parent $ScriptDir

$ThirdPartyDir = Join-Path $RepoRoot "third_party"
$OnnxDir = Join-Path $ThirdPartyDir "onnxruntime"
$OpenCvDir = Join-Path $ThirdPartyDir "opencv"

$OnnxDownloadDir = Join-Path $OnnxDir "downloads"
$OpenCvDownloadDir = Join-Path $OpenCvDir "downloads"

New-Item -ItemType Directory -Force $OnnxDir | Out-Null
New-Item -ItemType Directory -Force $OpenCvDir | Out-Null
New-Item -ItemType Directory -Force $OnnxDownloadDir | Out-Null
New-Item -ItemType Directory -Force $OpenCvDownloadDir | Out-Null

$OnnxAarName = "onnxruntime-android-$OnnxVersion.aar"
$OnnxAarUrl = "https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/$OnnxVersion/$OnnxAarName"
$OnnxAarPath = Join-Path $OnnxDownloadDir $OnnxAarName
$OnnxZipPath = Join-Path $OnnxDownloadDir ("onnxruntime-android-$OnnxVersion.zip")
$OnnxExtractDir = Join-Path $OnnxDir ("onnxruntime-android-$OnnxVersion")

$OpenCvZipName = "opencv-$OpenCvVersion-android-sdk.zip"
$OpenCvZipUrl = "https://github.com/opencv/opencv/releases/download/$OpenCvVersion/$OpenCvZipName"
$OpenCvZipPath = Join-Path $OpenCvDownloadDir $OpenCvZipName
$OpenCvExtractRoot = $OpenCvDir
$OpenCvSdkDir = Join-Path $OpenCvDir "OpenCV-android-sdk"

Write-Host "[deps] RepoRoot: $RepoRoot"

if (!(Test-Path $OnnxAarPath)) {
    Write-Host "[deps] Downloading ONNX Runtime AAR: $OnnxAarUrl"
    Invoke-WebRequest -Uri $OnnxAarUrl -OutFile $OnnxAarPath
} else {
    Write-Host "[deps] ONNX Runtime AAR already exists: $OnnxAarPath"
}

if (!(Test-Path $OnnxExtractDir)) {
    Write-Host "[deps] Extracting ONNX Runtime AAR"
    Copy-Item -Force $OnnxAarPath $OnnxZipPath
    Expand-Archive -Path $OnnxZipPath -DestinationPath $OnnxExtractDir -Force
} else {
    Write-Host "[deps] ONNX Runtime already extracted: $OnnxExtractDir"
}

if (!(Test-Path $OpenCvZipPath)) {
    Write-Host "[deps] Downloading OpenCV Android SDK: $OpenCvZipUrl"
    Invoke-WebRequest -Uri $OpenCvZipUrl -OutFile $OpenCvZipPath
} else {
    Write-Host "[deps] OpenCV zip already exists: $OpenCvZipPath"
}

if (!(Test-Path $OpenCvSdkDir)) {
    Write-Host "[deps] Extracting OpenCV Android SDK"
    Expand-Archive -Path $OpenCvZipPath -DestinationPath $OpenCvExtractRoot -Force
} else {
    Write-Host "[deps] OpenCV SDK already extracted: $OpenCvSdkDir"
}

$OnnxHeaders = Join-Path $OnnxExtractDir "headers"
$OnnxSoArm64 = Join-Path $OnnxExtractDir "jni/arm64-v8a/libonnxruntime.so"
$OpenCvJniDir = Join-Path $OpenCvSdkDir "sdk/native/jni"

if (!(Test-Path $OnnxHeaders)) {
    throw "Not found: $OnnxHeaders"
}
if (!(Test-Path $OnnxSoArm64)) {
    throw "Not found: $OnnxSoArm64"
}
if (!(Test-Path $OpenCvJniDir)) {
    throw "Not found: $OpenCvJniDir"
}

Write-Host "[deps] OK"
Write-Host "[deps] ONNX headers: $OnnxHeaders"
Write-Host "[deps] ONNX so arm64: $OnnxSoArm64"
Write-Host "[deps] OpenCV jni: $OpenCvJniDir"
