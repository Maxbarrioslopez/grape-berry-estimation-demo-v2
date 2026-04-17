param(
    [string]$AdbPath = "C:\Users\Maxi Barrios\AppData\Local\Android\Sdk\platform-tools\adb.exe",
    [string]$LocalImagesRoot = "",
    [string]$RemoteInputRoot = "/sdcard/Download/opt_uvas_input",
    [string]$RemoteOutputRoot = "/sdcard/Download/opt_uvas_output",
    [string]$PackageName = "com.gaiaspa.metrics_detection",
    [string]$ActivityName = ".DebugBatchActivity",
    [string]$Provider = "auto",
    [string]$DesktopOutputRoot = "C:\Users\Maxi Barrios\Desktop\resultados_test_uvas",
    [int]$TimeoutMinutes = 45
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

# Validar existencia de ADB
if (!(Test-Path $AdbPath)) { $AdbPath = "adb" }

# Funciones de apoyo
function Invoke-Adb { param([string[]]$cmdArgs) & $AdbPath @cmdArgs }
function Get-AdbOutput { param([string[]]$cmdArgs) & $AdbPath @cmdArgs 2>&1 | Out-String }

Write-Host "`n[1/5] PREPARANDO DISPOSITIVO..." -ForegroundColor Magenta
Invoke-Adb @("shell", "rm", "-rf", $RemoteInputRoot)
Invoke-Adb @("shell", "rm", "-rf", $RemoteOutputRoot)
Invoke-Adb @("shell", "mkdir", "-p", "$RemoteInputRoot/images")
Invoke-Adb @("shell", "mkdir", "-p", $RemoteOutputRoot)
Invoke-Adb @("shell", "appops", "set", $PackageName, "MANAGE_EXTERNAL_STORAGE", "allow")

Write-Host "[2/5] SUBIENDO DATA..." -ForegroundColor Cyan
if (Test-Path "$ScriptDir\manifest_subsample.csv") {
    Invoke-Adb @("push", "$ScriptDir\manifest_subsample.csv", $RemoteInputRoot)
}
Get-ChildItem -LiteralPath $ScriptDir -Directory | ForEach-Object {
    if ((Get-ChildItem $_.FullName -Filter "*.jpg").Count -gt 0) {
        Write-Host " -> $($_.Name)" -ForegroundColor Gray
        Invoke-Adb @("push", $_.FullName, "$RemoteInputRoot/images/")
    }
}

Write-Host "[3/5] LANZANDO TEST EN ANDROID..." -ForegroundColor Green
Invoke-Adb @("logcat", "-c")
Invoke-Adb @("shell", "am", "start", "-S", "-n", "$PackageName/$ActivityName", "--es", "inputDir", $RemoteInputRoot, "--es", "outputDir", $RemoteOutputRoot)

Write-Host "[4/5] PROCESANDO..." -ForegroundColor Yellow
$runId = ""
$startTime = Get-Date
$lastFile = ""
while (((Get-Date) - $startTime).TotalMinutes -lt $TimeoutMinutes) {
    Start-Sleep -Seconds 2
    $logs = Get-AdbOutput @("logcat", "-d", "-t", "50", "BATCH:D")

    if ($logs -match "Batch start runId=(run_\d{8}_\d{6})") {
        $runId = $Matches[1]
    }

    if ($logs -match "Forense Processing \[(?<p>\d+)/(?<t>\d+)\] File: (?<f>.*)") {
        $prog = $Matches['p']; $total = $Matches['t']; $file = $Matches['f']
        if ($file -ne $lastFile) {
            Write-Host "`r    Avance: [$prog/$total] -> Analizando: $file                    " -NoNewline -ForegroundColor Cyan
            $lastFile = $file
        }
        if ([int]$prog -eq [int]$total) { Start-Sleep -Seconds 5; break }
    }
    if ($runId -and $logs -match "Batch end runId=$runId") { break }
}

Write-Host "`n[5/5] DESCARGANDO RESULTADOS..." -ForegroundColor Blue
if ([string]::IsNullOrWhiteSpace($runId)) {
    $runId = (Get-AdbOutput @("shell", "ls", "-1t", $RemoteOutputRoot)).Trim().Split("`n")[0].Trim()
}

if ($runId -match "run_") {
    $LocalDest = Join-Path $DesktopOutputRoot $runId
    if (!(Test-Path $LocalDest)) { New-Item -ItemType Directory -Force $LocalDest | Out-Null }
    Invoke-Adb @("pull", "$RemoteOutputRoot/$runId/.", $LocalDest)
    Write-Host " -> OK: Resultados en su escritorio." -ForegroundColor Green
}

Write-Host "`nIniciando Evaluacion Python..." -ForegroundColor White
$py = Join-Path $ScriptDir "eval_jni_vs_gt.py"
& python "$py"
