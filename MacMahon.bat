@echo off
chcp 65001 >nul 2>&1
title MacMahon Launcher

REM ===== Find Java 25+ =====
set "JAVA_EXE="

REM Check Corretto 25.x
for /d %%D in ("C:\Program Files\Amazon Corretto\jdk25*") do (
    if exist "%%D\bin\javaw.exe" (
        set "JAVA_EXE=%%D\bin\javaw.exe"
        goto :launch
    )
)

REM Check other common JDK 25+ locations
for /d %%D in ("C:\Program Files\Java\jdk-25*") do (
    if exist "%%D\bin\javaw.exe" (
        set "JAVA_EXE=%%D\bin\javaw.exe"
        goto :launch
    )
)
for /d %%D in ("C:\Program Files\Eclipse Adoptium\jdk-25*") do (
    if exist "%%D\bin\javaw.exe" (
        set "JAVA_EXE=%%D\bin\javaw.exe"
        goto :launch
    )
)
for /d %%D in ("C:\Program Files\BellSoft\LibericaJDK-25*") do (
    if exist "%%D\bin\javaw.exe" (
        set "JAVA_EXE=%%D\bin\javaw.exe"
        goto :launch
    )
)

REM ===== Java 25 not found =====
echo.
echo  ============================================
echo   MacMahon 3.10 requires Java 25
echo   Java 25 not found on this computer
echo  ============================================
echo.
choice /c YN /m "Install Java 25 (Amazon Corretto) now? "
if errorlevel 2 goto :manual
if errorlevel 1 goto :install

:install
echo.
echo  Installing Amazon Corretto JDK 25...
echo  (This may take 1-2 minutes)
echo.
winget install Amazon.Corretto.25.JDK --accept-source-agreements --accept-package-agreements
if %errorlevel% neq 0 (
    echo.
    echo  winget install failed.
    echo  Please install manually: https://aws.amazon.com/corretto/
    pause
    exit /b 1
)
echo.
echo  Installation complete!
echo.

REM Find newly installed path
for /d %%D in ("C:\Program Files\Amazon Corretto\jdk25*") do (
    if exist "%%D\bin\javaw.exe" (
        set "JAVA_EXE=%%D\bin\javaw.exe"
        goto :launch
    )
)
echo  ERROR: Java installed but could not find javaw.exe
pause
exit /b 1

:manual
echo.
echo  Please install Java 25 from: https://aws.amazon.com/corretto/
pause
exit /b 1

:launch
start "" "%JAVA_EXE%" -Dfile.encoding=UTF-8 -jar "%~dp0macmahon-tesuji.jar"