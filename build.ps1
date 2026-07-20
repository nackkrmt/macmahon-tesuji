## MacMahon Launcher - Build Script
$root = Split-Path $MyInvocation.MyCommand.Path

Write-Host "=== MacMahon Launcher Build ===" -ForegroundColor Cyan

# Find a JDK providing javac.exe + jar.exe — checks JAVA_HOME first, then
# scans common install locations (newest version first) instead of a
# hardcoded path that breaks on every JDK update.
function Find-Jdk {
    if ($env:JAVA_HOME) {
        $javacPath = Join-Path $env:JAVA_HOME "bin\javac.exe"
        $jarPath = Join-Path $env:JAVA_HOME "bin\jar.exe"
        if ((Test-Path $javacPath) -and (Test-Path $jarPath)) {
            return @{ Javac = $javacPath; Jar = $jarPath; Java = (Join-Path $env:JAVA_HOME "bin\java.exe") }
        }
    }

    $searchRoots = @(
        "C:\Program Files\Amazon Corretto",
        "C:\Program Files\Java",
        "C:\Program Files\Eclipse Adoptium",
        "C:\Program Files\BellSoft",
        "C:\Program Files\Microsoft"
    )
    $candidates = @()
    foreach ($base in $searchRoots) {
        if (Test-Path $base) {
            $candidates += Get-ChildItem $base -Directory -ErrorAction SilentlyContinue
        }
    }
    # Newest version-looking folder name first
    $candidates = $candidates | Sort-Object Name -Descending
    foreach ($dir in $candidates) {
        $javacPath = Join-Path $dir.FullName "bin\javac.exe"
        $jarPath = Join-Path $dir.FullName "bin\jar.exe"
        if ((Test-Path $javacPath) -and (Test-Path $jarPath)) {
            return @{ Javac = $javacPath; Jar = $jarPath; Java = (Join-Path $dir.FullName "bin\java.exe") }
        }
    }
    return $null
}

$jdk = Find-Jdk
if (-not $jdk) {
    Write-Host "ERROR: No JDK found (checked JAVA_HOME and common install paths)" -ForegroundColor Red
    Write-Host "Install a JDK (Amazon Corretto recommended) or set JAVA_HOME" -ForegroundColor Red
    exit 1
}
$javac = $jdk.Javac
$jar = $jdk.Jar
$java = $jdk.Java
Write-Host "      Using JDK: $javac" -ForegroundColor Green

# Clean
if (Test-Path "$root\build") { Remove-Item "$root\build" -Recurse -Force }
New-Item -ItemType Directory -Path "$root\build" -Force | Out-Null

# Step 1: Compile
Write-Host "[1/4] Compiling..." -ForegroundColor Yellow
$sources = Get-ChildItem "$root\src" -Recurse -Filter "*.java" | Select-Object -ExpandProperty FullName
& $javac -encoding UTF-8 --release 8 -d "$root\build" @sources
if ($LASTEXITCODE -ne 0) { Write-Host "COMPILE FAILED" -ForegroundColor Red; exit 1 }
Write-Host "      OK" -ForegroundColor Green

# Step 2: Self tests — pure-logic checks; abort the build on failure
Write-Host "[2/4] Running SelfTest..." -ForegroundColor Yellow
& $java "-Djava.awt.headless=true" -cp "$root\build" launcher.SelfTest
if ($LASTEXITCODE -ne 0) { Write-Host "SELFTEST FAILED" -ForegroundColor Red; exit 1 }
# Test classes are build-time only — keep them out of the shipped jar
Remove-Item "$root\build\launcher\SelfTest*.class" -Force -ErrorAction SilentlyContinue

# Step 3: Embed MacMahon JAR
Write-Host "[3/4] Embedding MacMahon JAR..." -ForegroundColor Yellow
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

# Step 4: Package JAR (generate manifest inline)
Write-Host "[4/4] Packaging JAR..." -ForegroundColor Yellow
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
