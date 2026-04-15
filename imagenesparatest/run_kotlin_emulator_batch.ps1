param(
    [string]$AdbPath = "C:\Users\Maxi Barrios\AppData\Local\Android\Sdk\platform-tools\adb.exe",
    [string]$LocalImagesRoot = "",
    [string]$RemoteInputRoot = "/sdcard/Download/opt_uvas_input",
    [string]$RemoteOutputRoot = "/sdcard/Download/opt_uvas_output",
    [string]$PackageName = "com.gaiaspa.metrics_detection",
    [string]$ActivityName = ".DebugBatchActivity",
    [string]$Provider = "auto",
    [string]$DesktopOutputRoot = "",
    [string]$DeviceSerial = "",
    [int]$TimeoutMinutes = 20
)

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = Split-Path -Parent (Split-Path -Parent $ScriptDir)

if ([string]::IsNullOrWhiteSpace($LocalImagesRoot)) {
    $LocalImagesRoot = Join-Path $ProjectRoot "imagenesparatest"
}

if ([string]::IsNullOrWhiteSpace($DesktopOutputRoot)) {
    $DesktopOutputRoot = Join-Path ([Environment]::GetFolderPath("Desktop")) "resultados_kotlin_emulador"
}

$LocalImagesRoot = (Resolve-Path $LocalImagesRoot).Path
if (!(Test-Path $AdbPath)) {
    throw "ADB no encontrado en: $AdbPath"
}

$ResolvedImagesRoot = $LocalImagesRoot
$NestedImagesDir = Join-Path $LocalImagesRoot "images"
if ((Split-Path $LocalImagesRoot -Leaf).ToLower() -ne "images" -and (Test-Path $NestedImagesDir)) {
    $ResolvedImagesRoot = (Resolve-Path $NestedImagesDir).Path
}

$TopLevelDirs = Get-ChildItem -LiteralPath $ResolvedImagesRoot -Directory | Sort-Object Name
if ($TopLevelDirs.Count -eq 0) {
    throw "No se encontraron carpetas de variedades en: $ResolvedImagesRoot"
}

$DesktopOutputRoot = [System.IO.Path]::GetFullPath($DesktopOutputRoot)
New-Item -ItemType Directory -Force $DesktopOutputRoot | Out-Null

$env:ANDROID_SDK_HOME = $ScriptDir
$env:HOME = $ScriptDir
$env:USERPROFILE = $ScriptDir
New-Item -ItemType Directory -Force $env:ANDROID_SDK_HOME | Out-Null

function Invoke-Adb {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Args
    )

    $fullArgs = @()
    if (-not [string]::IsNullOrWhiteSpace($DeviceSerial)) {
        $fullArgs += "-s"
        $fullArgs += $DeviceSerial
    }
    $fullArgs += $Args

    & $AdbPath @fullArgs
    if ($LASTEXITCODE -ne 0) {
        throw "ADB fallo: $AdbPath $($fullArgs -join ' ')"
    }
}

function Get-AdbOutput {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Args,
        [switch]$AllowFailure
    )

    $fullArgs = @()
    if (-not [string]::IsNullOrWhiteSpace($DeviceSerial)) {
        $fullArgs += "-s"
        $fullArgs += $DeviceSerial
    }
    $fullArgs += $Args

    $output = & $AdbPath @fullArgs 2>&1
    if ($LASTEXITCODE -ne 0 -and -not $AllowFailure) {
        throw "ADB fallo: $AdbPath $($fullArgs -join ' ')`n$output"
    }
    return ($output | Out-String).Trim()
}

Write-Host "[android-batch] Verificando dispositivo..."
$devicesText = Get-AdbOutput -Args @("devices")
$deviceLines = $devicesText -split "`r?`n" | Where-Object { $_ -match "device$" -and $_ -notmatch "^List of devices" }
if ($deviceLines.Count -eq 0) {
    throw "No hay dispositivos/emuladores conectados en adb."
}

Write-Host "[android-batch] Limpiando logcat..."
Get-AdbOutput -Args @("logcat", "-c") -AllowFailure | Out-Null

Write-Host "[android-batch] Preparando carpetas remotas..."
Invoke-Adb -Args @("shell", "rm", "-rf", $RemoteInputRoot)
Invoke-Adb -Args @("shell", "rm", "-rf", $RemoteOutputRoot)
Invoke-Adb -Args @("shell", "mkdir", "-p", "$RemoteInputRoot/images")
Invoke-Adb -Args @("shell", "mkdir", "-p", $RemoteOutputRoot)

Write-Host "[android-batch] Otorgando acceso de almacenamiento a la app..."
Invoke-Adb -Args @("shell", "appops", "set", $PackageName, "MANAGE_EXTERNAL_STORAGE", "allow")

Write-Host "[android-batch] Subiendo imagenes al emulador..."
foreach ($dir in $TopLevelDirs) {
    Invoke-Adb -Args @("push", $dir.FullName, "$RemoteInputRoot/images")
}

Write-Host "[android-batch] Lanzando DebugBatchActivity..."
Invoke-Adb -Args @(
    "shell", "am", "start",
    "-n", "$PackageName/$ActivityName",
    "--es", "inputDir", $RemoteInputRoot,
    "--es", "outputDir", $RemoteOutputRoot,
    "--es", "provider", $Provider
)

$deadline = (Get-Date).AddMinutes($TimeoutMinutes)
$runId = $null
$manifestRemote = $null

Write-Host "[android-batch] Esperando fin del batch..."
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 10
    $logs = Get-AdbOutput -Args @("logcat", "-d", "-s", "BATCH:D", "*:S") -AllowFailure

    if ($logs -match "Manifest written:\s+([^\r\n]+manifest\.json)") {
        $manifestRemote = $matches[1].Trim()
    }
    if ($logs -match "Batch end runId=(run_\d{8}_\d{6})") {
        $runId = $matches[1]
        break
    }
}

if (-not $runId) {
    throw "Timeout esperando 'Batch end' en logcat."
}

if (-not $manifestRemote) {
    $manifestRemote = "$RemoteOutputRoot/$runId/manifest.json"
}

$LocalRunRoot = Join-Path $DesktopOutputRoot $runId
New-Item -ItemType Directory -Force $LocalRunRoot | Out-Null

Write-Host "[android-batch] Descargando resultados al escritorio..."
Invoke-Adb -Args @("pull", "$RemoteOutputRoot/$runId", $DesktopOutputRoot)

$PulledRunDir = Join-Path $DesktopOutputRoot $runId
if (!(Test-Path $PulledRunDir)) {
    throw "No se encontro la carpeta descargada: $PulledRunDir"
}

Write-Host ""
Write-Host "[android-batch] Batch completado"
Write-Host "[android-batch] run_id: $runId"
Write-Host "[android-batch] carpeta: $PulledRunDir"
Write-Host "[android-batch] manifest: $PulledRunDir\manifest.json"
