param(
    [string]$AdbPath = "C:\Users\Maxi Barrios\AppData\Local\Android\Sdk\platform-tools\adb.exe",
    [string]$LocalImagesRoot = "",
    [string]$RemoteInputRoot = "/sdcard/Download/opt_uvas_input",
    [string]$RemoteOutputRoot = "/sdcard/Download/opt_uvas_output",
    [string]$PackageName = "com.gaiaspa.metrics_detection",
    [string]$ActivityName = ".DebugBatchActivity",
    [string]$Provider = "auto",
    [string]$DesktopOutputRoot = "C:\Users\Maxi Barrios\Desktop\resultados_test_uvas",
    [string]$DeviceSerial = "",
    [int]$TimeoutMinutes = 45
)

$ErrorActionPreference = "Stop"

# 1. DETERMINAR RUTAS LOCALES
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
if ([string]::IsNullOrWhiteSpace($LocalImagesRoot)) {
    $LocalImagesRoot = $ScriptDir
}

# Validar existencia de ADB
if (!(Test-Path $AdbPath)) {
    $AdbInPath = Get-Command adb -ErrorAction SilentlyContinue
    if ($AdbInPath) { $AdbPath = $AdbInPath.Source }
    else { throw "ADB no encontrado. Verifica la ruta." }
}

# 2. IDENTIFICAR CARPETAS DE VARIEDADES
$TopLevelDirs = Get-ChildItem -LiteralPath $LocalImagesRoot -Directory | Where-Object {
    (Get-ChildItem $_.FullName -Filter "*.jpg").Count -gt 0
}

if (!(Test-Path $DesktopOutputRoot)) {
    New-Item -ItemType Directory -Force $DesktopOutputRoot | Out-Null
}

# 3. FUNCIONES DE APOYO
function Invoke-Adb {
    param([string[]]$AdbCommandArgs)
    $fullArgs = @()
    if (-not [string]::IsNullOrWhiteSpace($DeviceSerial)) { $fullArgs += "-s"; $fullArgs += $DeviceSerial }
    $fullArgs += $AdbCommandArgs
    & $AdbPath @fullArgs
    if ($LASTEXITCODE -ne 0) { Write-Host "ADB fallo (no critico): $AdbPath $($fullArgs -join ' ')" -ForegroundColor Yellow }
}

function Get-AdbOutput {
    param([string[]]$AdbCommandArgs, [switch]$AllowFailure)
    $fullArgs = @()
    if (-not [string]::IsNullOrWhiteSpace($DeviceSerial)) { $fullArgs += "-s"; $fullArgs += $DeviceSerial }
    $fullArgs += $AdbCommandArgs
    $output = & $AdbPath @fullArgs 2>&1
    if ($LASTEXITCODE -ne 0 -and -not $AllowFailure) { throw "ADB fallo: $output" }
    return ($output | Out-String).Trim()
}

# 4. PREPARACIÓN
Write-Host ""
Write-Host "[1/5] PREPARANDO DISPOSITIVO" -ForegroundColor Magenta
Write-Host "--------------------------------------------------" -ForegroundColor Magenta
Invoke-Adb -AdbCommandArgs @("shell", "rm", "-rf", $RemoteInputRoot)
Invoke-Adb -AdbCommandArgs @("shell", "rm", "-rf", $RemoteOutputRoot)
Invoke-Adb -AdbCommandArgs @("shell", "mkdir", "-p", "$RemoteInputRoot/images")
Invoke-Adb -AdbCommandArgs @("shell", "mkdir", "-p", $RemoteOutputRoot)
Invoke-Adb -AdbCommandArgs @("shell", "appops", "set", $PackageName, "MANAGE_EXTERNAL_STORAGE", "allow")
Write-Host " -> OK" -ForegroundColor Green

# 5. TRANSFERENCIA
Write-Host ""
Write-Host "[2/5] SUBIENDO IMAGENES" -ForegroundColor Cyan
Write-Host "--------------------------------------------------" -ForegroundColor Cyan
foreach ($dir in $TopLevelDirs) {
    Write-Host " -> Enviando: $($dir.Name)..." -NoNewline
    Invoke-Adb -AdbCommandArgs @("push", $dir.FullName, "$RemoteInputRoot/images/")
    Write-Host " OK" -ForegroundColor Green
}

# 6. EJECUCIÓN
Write-Host ""
Write-Host "[3/5] LANZANDO APP EN ANDROID" -ForegroundColor Green
Write-Host "--------------------------------------------------" -ForegroundColor Green
Get-AdbOutput -AdbCommandArgs @("logcat", "-c") -AllowFailure | Out-Null
Invoke-Adb -AdbCommandArgs @("shell", "am", "start", "-S", "-n", "$PackageName/$ActivityName", "--es", "inputDir", $RemoteInputRoot, "--es", "outputDir", $RemoteOutputRoot, "--es", "provider", $Provider)

# 7. MONITOREO
Write-Host ""
Write-Host "[4/5] PROCESANDO (ESTO PUEDE TARDAR)" -ForegroundColor Yellow
Write-Host "--------------------------------------------------" -ForegroundColor Yellow
$runIdFound = ""
$startTime = Get-Date
$lastProg = ""

while (((Get-Date) - $startTime).TotalMinutes -lt $TimeoutMinutes) {
    Start-Sleep -Seconds 3
    $logs = Get-AdbOutput -AdbCommandArgs @("logcat", "-d", "-s", "BATCH:D") -AllowFailure

    if ($logs -match "Batch start runId=(run_\d{8}_\d{6})") {
        $runIdFound = [regex]::Match($logs, "Batch start runId=(run_\d{8}_\d{6})").Groups[1].Value
    }

    if ($logs -match "Forense Processing \[(?<prog>\d+/\d+)\] File: (?<fname>.*)") {
        $m = [regex]::Matches($logs, "Forense Processing \[(?<prog>\d+/\d+)\] File: (?<fname>.*)")
        $last = $m[$m.Count - 1]
        $prog = $last.Groups['prog'].Value
        $file = $last.Groups['fname'].Value
        if ($prog -ne $lastProg) {
            Write-Host "    Progreso: [$prog] -> $file" -ForegroundColor Cyan
            $lastProg = $prog
        }
    }

    if ($runIdFound -ne "" -and $logs -match "Batch end runId=$runIdFound") {
        Write-Host " -> Prueba finalizada exitosamente." -ForegroundColor Green
        break
    }
}

# 8. EXTRACCIÓN
Write-Host ""
Write-Host "[5/5] DESCARGANDO RESULTADOS" -ForegroundColor Blue
Write-Host "--------------------------------------------------" -ForegroundColor Blue
if ($runIdFound -eq "") {
    $ls = Get-AdbOutput -AdbCommandArgs @("shell", "ls", "-1t", $RemoteOutputRoot) -AllowFailure
    $runIdFound = ($ls -split "`n" | Where-Object { $_ -match "run_" })[0].Trim()
}

if ($runIdFound -match "run_") {
    $LocalDest = Join-Path $DesktopOutputRoot $runIdFound
    if (!(Test-Path $LocalDest)) { New-Item -ItemType Directory -Force $LocalDest | Out-Null }
    Write-Host " -> Bajando a: $LocalDest" -ForegroundColor Gray
    Invoke-Adb -AdbCommandArgs @("pull", "$RemoteOutputRoot/$runIdFound/.", $LocalDest)
}

Write-Host ""
Write-Host "==================================================" -ForegroundColor Green
Write-Host " PROCESO TERMINADO. LANZANDO PYTHON..." -ForegroundColor Green
Write-Host "==================================================" -ForegroundColor Green

$finalPyScript = "$ScriptDir\eval_jni_vs_gt.py"
& python $finalPyScript
