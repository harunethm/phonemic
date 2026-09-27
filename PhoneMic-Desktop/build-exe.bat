@echo off
setlocal

rem Rebuilds PhoneMic-Desktop and packages it as a standalone Windows .exe (with its
rem own bundled Java runtime), then drops a zip in ..\..\phonemic\windows-release\ for
rem publishing via release.sh.

if not defined JAVA_HOME (
    echo JAVA_HOME is not set. Point it at a JDK 17+ install, e.g.:
    echo   set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot
    exit /b 1
)

call "%~dp0gradlew.bat" installDist
if errorlevel 1 exit /b 1

if exist "%~dp0dist" rmdir /s /q "%~dp0dist"

"%JAVA_HOME%\bin\jpackage.exe" ^
  --type app-image ^
  --input "%~dp0build\install\PhoneMic-Desktop\lib" ^
  --dest "%~dp0dist" ^
  --name "PhoneMic-Desktop" ^
  --main-jar "PhoneMic-Desktop.jar" ^
  --main-class "com.scylla.tool.phonemic.pc.MainKt" ^
  --icon "%~dp0src\main\resources\icons\app-icon.ico" ^
  --app-version "0.1.1" ^
  --vendor "Scylla"
if errorlevel 1 exit /b 1

set "RELEASE_DIR=%~dp0..\..\phonemic\windows-release"
if not exist "%RELEASE_DIR%" mkdir "%RELEASE_DIR%"
powershell -NoProfile -Command "Compress-Archive -Path '%~dp0dist\PhoneMic-Desktop' -DestinationPath '%RELEASE_DIR%\PhoneMic-Desktop-windows.zip' -Force"

echo Done. Run dist\PhoneMic-Desktop\PhoneMic-Desktop.exe
echo Zipped for release at %RELEASE_DIR%\PhoneMic-Desktop-windows.zip
