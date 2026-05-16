## MacMahon Launcher - Build Script
$javac = "C:\Program Files\Amazon Corretto\jdk25.0.3_9\bin\javac.exe"
$jar = "C:\Program Files\Amazon Corretto\jdk25.0.3_9\bin\jar.exe"
$root = Split-Path $MyInvocation.MyCommand.Path

Write-Host "=== MacMahon Launcher Build ===" -ForegroundColor Cyan

# Clean
if (Test-Path "$root\build") { Remove-Item "$root\build" -Recurse -Force }
New-Item -ItemType Directory -Path "$root\build" -Force | Out-Null

# Step 1: Compile
Write-Host "[1/3] Compiling..." -ForegroundColor Yellow
$sources = Get-ChildItem "$root\src" -Recurse -Filter "*.java" | Select-Object -ExpandProperty FullName
& $javac -encoding UTF-8 --release 8 -d "$root\build" @sources
if ($LASTEXITCODE -ne 0) { Write-Host "COMPILE FAILED" -ForegroundColor Red; exit 1 }
Write-Host "      OK" -ForegroundColor Green

# Step 2: Embed MacMahon JAR
Write-Host "[2/3] Embedding MacMahon JAR..." -ForegroundColor Yellow
$macmahonJar = Get-ChildItem "$root\lib" -Filter "macmahon-*.jar" -ErrorAction SilentlyContinue |
    Select-Object -First 1

$embedded = $false
if ($macmahonJar) {
    New-Item -ItemType Directory -Path "$root\build\embedded" -Force | Out-Null
    Copy-Item $macmahonJar.FullName "$root\build\embedded\macmahon.jar"
    $embSize = [math]::Round($macmahonJar.Length / 1048576, 1)
    Write-Host "      Embedded: $($macmahonJar.Name) ($embSize mb)" -ForegroundColor Green
    $embedded = $true
} else {
    Write-Host "      WARNING: No macmahon JAR found in lib/" -ForegroundColor Red
}

# Step 3: Package JAR (generate manifest inline)
Write-Host "[3/3] Packaging JAR..." -ForegroundColor Yellow
$manifest = "$root\build\MANIFEST.MF"
Set-Content -Path $manifest -Value "Manifest-Version: 1.0`nMain-Class: launcher.MacMahonLauncher`n" -Encoding ASCII
& $jar cfm "$root\macmahon-tesuji.jar" $manifest -C "$root\build" .
if ($LASTEXITCODE -ne 0) { Write-Host "JAR FAILED" -ForegroundColor Red; exit 1 }

# Clean up build dir
Remove-Item "$root\build" -Recurse -Force

$finalSize = [math]::Round((Get-Item "$root\macmahon-tesuji.jar").Length / 1048576, 1)
Write-Host "      OK - macmahon-tesuji.jar ($finalSize mb)" -ForegroundColor Green

Write-Host ""
Write-Host "=== Done! ===" -ForegroundColor Cyan
