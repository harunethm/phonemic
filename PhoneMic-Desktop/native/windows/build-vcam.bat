@echo off
setlocal
rem Builds phonemic-vcam.dll ("Phone Mic Camera", the Windows 11 virtual webcam) into %1
rem (default: build\ next to this script, which the release sync never copies). Needs Visual Studio 2022 or Build Tools with the
rem "Desktop development with C++" workload and a Windows 11 SDK.

set "OUT_DIR=%~1"
if "%OUT_DIR%"=="" set "OUT_DIR=%~dp0build"
if not exist "%OUT_DIR%" mkdir "%OUT_DIR%"

set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
if not exist "%VSWHERE%" (
    echo vswhere.exe not found - install Visual Studio Build Tools with C++.
    exit /b 1
)
for /f "usebackq tokens=*" %%i in (`"%VSWHERE%" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do set "VS_PATH=%%i"
if not defined VS_PATH (
    echo No Visual Studio installation with C++ tools found.
    exit /b 1
)
call "%VS_PATH%\VC\Auxiliary\Build\vcvars64.bat" >nul 2>nul
if errorlevel 1 exit /b 1

set "OBJ_DIR=%TEMP%\phonemic-vcam-obj"
if not exist "%OBJ_DIR%" mkdir "%OBJ_DIR%"

rem /Brepro: no timestamps, so an unchanged source rebuilds byte-identical - the app keys the
rem installed copy on the DLL's hash, and a needless change would ask users to "Update webcam".
cl /nologo /LD /EHsc /O2 /MT /W3 /std:c++17 /DUNICODE /D_UNICODE /DNOMINMAX /Brepro ^
   "%~dp0phonemic-vcam.cpp" /Fo"%OBJ_DIR%\\" /Fe"%OUT_DIR%\phonemic-vcam.dll" ^
   /link /Brepro /DEF:"%~dp0phonemic-vcam.def" /IMPLIB:"%OBJ_DIR%\phonemic-vcam.lib"
if errorlevel 1 exit /b 1
echo Built %OUT_DIR%\phonemic-vcam.dll
