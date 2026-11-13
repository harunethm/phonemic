@echo off
setlocal
if not defined APP_VERSION set "APP_VERSION=0.4.0"

rem Rebuilds PhoneMic-Desktop and packages it as a standalone Windows .exe (with its
rem own bundled Java runtime), then drops a zip in ..\..\phonemic\windows-release\ for
rem publishing via release.sh.

if not defined JAVA_HOME (
    echo JAVA_HOME is not set. Point it at a JDK 17+ install, e.g.:
    echo   set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot
    exit /b 1
)

rem Gradle treats the current directory as the project, so run from this script's folder.
cd /d "%~dp0"
call "%~dp0gradlew.bat" installDist
if errorlevel 1 exit /b 1

rem Windows 11 webcam ("Phone Mic Camera"): built next to the jars so jpackage ships it.
rem Non-fatal on purpose - without it the app still works, the camera just falls back to OBS
rem or preview only. Needs Visual Studio (Build Tools) with C++; GitHub's runners have it.
call "%~dp0native\windows\build-vcam.bat" "%~dp0build\install\PhoneMic-Desktop\lib"
if errorlevel 1 echo ::warning::phonemic-vcam.dll failed to build - Phone Mic Camera will be unavailable

if exist "%~dp0dist" rmdir /s /q "%~dp0dist"

"%JAVA_HOME%\bin\jpackage.exe" ^
  --type app-image ^
  --input "%~dp0build\install\PhoneMic-Desktop\lib" ^
  --dest "%~dp0dist" ^
  --name "PhoneMic-Desktop" ^
  --main-jar "PhoneMic-Desktop.jar" ^
  --main-class "com.scylla.tool.phonemic.pc.MainKt" ^
  --icon "%~dp0src\main\resources\icons\app-icon.ico" ^
  --app-version "%APP_VERSION%" ^
  --vendor "Scylla"
if errorlevel 1 exit /b 1

set "RELEASE_DIR=%~dp0..\..\phonemic\windows-release"
if not exist "%RELEASE_DIR%" mkdir "%RELEASE_DIR%"
powershell -NoProfile -Command "Compress-Archive -Path '%~dp0dist\PhoneMic-Desktop' -DestinationPath '%RELEASE_DIR%\PhoneMic-Desktop-windows.zip' -Force"

echo Done. Run dist\PhoneMic-Desktop\PhoneMic-Desktop.exe
echo Zipped for release at %RELEASE_DIR%\PhoneMic-Desktop-windows.zip

rem Local builds also install for this user: %LOCALAPPDATA%\Programs\Phone Mic plus Desktop and
rem Start menu shortcuts. CI (GitHub Actions sets CI=true) only builds. Opt out: set NO_INSTALL=1
if not defined CI if not defined NO_INSTALL (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install-local.ps1"
    if errorlevel 1 echo Build is fine; only the local install step failed - see above.
)
