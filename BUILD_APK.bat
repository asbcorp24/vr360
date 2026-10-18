@echo off
setlocal EnableExtensions EnableDelayedExpansion

cd /d "%~dp0"
title VR360 Classroom APK Builder

echo ============================================================
echo   VR360 Classroom - Android APK build
echo ============================================================
echo.

rem ------------------------------------------------------------
rem 1. Java 17
rem ------------------------------------------------------------
if not defined JAVA_HOME (
    if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" (
        set "JAVA_HOME=%ProgramFiles%\Android\Android Studio\jbr"
    ) else if exist "%LOCALAPPDATA%\Programs\Android Studio\jbr\bin\java.exe" (
        set "JAVA_HOME=%LOCALAPPDATA%\Programs\Android Studio\jbr"
    )
)

if defined JAVA_HOME (
    set "PATH=%JAVA_HOME%\bin;%PATH%"
)

where java >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Java not found.
    echo.
    echo Install Android Studio or JDK 17, then run this file again.
    echo Expected Android Studio Java:
    echo   %ProgramFiles%\Android\Android Studio\jbr
    echo.
    pause
    exit /b 1
)

echo [OK] Java:
java -version 2>&1 | findstr /i "version"

rem ------------------------------------------------------------
rem 2. Android SDK
rem ------------------------------------------------------------
if not defined ANDROID_HOME (
    if defined ANDROID_SDK_ROOT (
        set "ANDROID_HOME=%ANDROID_SDK_ROOT%"
    ) else if exist "%LOCALAPPDATA%\Android\Sdk" (
        set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
    )
)

if not defined ANDROID_SDK_ROOT (
    if defined ANDROID_HOME (
        set "ANDROID_SDK_ROOT=%ANDROID_HOME%"
    )
)

if not defined ANDROID_HOME (
    echo.
    echo [ERROR] Android SDK not found.
    echo.
    echo Open Android Studio - SDK Manager and install Android SDK,
    echo or set ANDROID_HOME manually.
    echo Typical path:
    echo   %LOCALAPPDATA%\Android\Sdk
    echo.
    pause
    exit /b 1
)

if not exist "%ANDROID_HOME%\platform-tools" (
    echo.
    echo [ERROR] Android SDK is incomplete:
    echo   %ANDROID_HOME%
    echo.
    echo Install Android SDK Platform Tools in Android Studio SDK Manager.
    pause
    exit /b 1
)

set "PATH=%ANDROID_HOME%\platform-tools;%PATH%"

echo [OK] Android SDK: %ANDROID_HOME%

rem ------------------------------------------------------------
rem 3. Gradle wrapper
rem ------------------------------------------------------------
if not exist "gradlew.bat" (
    echo.
    echo [ERROR] gradlew.bat not found.
    echo Run BUILD_APK.bat from the vr360 project root.
    pause
    exit /b 1
)

rem ------------------------------------------------------------
rem 4. Optional clean build
rem ------------------------------------------------------------
if /I "%~1"=="clean" (
    echo.
    echo [1/3] Cleaning previous build...
    call gradlew.bat --no-daemon clean
    if errorlevel 1 goto :build_error
)

rem ------------------------------------------------------------
rem 5. Build debug APK
rem ------------------------------------------------------------
echo.
echo [2/3] Building APK...
echo.

call gradlew.bat --no-daemon :app:assembleDebug
if errorlevel 1 goto :build_error

set "SOURCE_APK=app\build\outputs\apk\debug\app-debug.apk"
if not exist "%SOURCE_APK%" (
    echo.
    echo [ERROR] Build completed but APK was not found:
    echo   %SOURCE_APK%
    pause
    exit /b 1
)

rem ------------------------------------------------------------
rem 6. Copy to dist
rem ------------------------------------------------------------
if not exist "dist" mkdir "dist"

set "OUTPUT_APK=dist\VR360-Classroom-debug.apk"
copy /Y "%SOURCE_APK%" "%OUTPUT_APK%" >nul

if errorlevel 1 (
    echo.
    echo [ERROR] Cannot copy APK to dist folder.
    pause
    exit /b 1
)

echo.
echo [3/3] SUCCESS
echo ============================================================
echo APK:
echo   %CD%\%OUTPUT_APK%
echo ============================================================
echo.

rem ------------------------------------------------------------
rem Optional: BUILD_APK.bat install
rem            BUILD_APK.bat clean install
rem ------------------------------------------------------------
set "DO_INSTALL=0"
if /I "%~1"=="install" set "DO_INSTALL=1"
if /I "%~2"=="install" set "DO_INSTALL=1"

if "%DO_INSTALL%"=="1" (
    where adb >nul 2>&1
    if errorlevel 1 (
        echo [WARNING] adb not found. APK was built successfully.
        echo.
        pause
        exit /b 0
    )

    echo Connected Android devices:
    adb devices
    echo.
    echo Installing APK...
    adb install -r "%OUTPUT_APK%"

    if errorlevel 1 (
        echo.
        echo [ERROR] APK build succeeded, but installation failed.
        pause
        exit /b 2
    )

    echo.
    echo [OK] APK installed on device.
)

echo.
pause
exit /b 0

:build_error
echo.
echo ============================================================
echo [ERROR] APK BUILD FAILED
echo ============================================================
echo.
echo Check the Gradle error above.
echo.
pause
exit /b 1
