@echo off
setlocal EnableExtensions EnableDelayedExpansion

set "SCRIPT_DIR=%~dp0"
set "PROJECT_FILE=%SCRIPT_DIR%BlockChatWindowsCaptureHelper\BlockChatWindowsCaptureHelper.vcxproj"
set "OUT_DIR=%SCRIPT_DIR%.build\Release\"
set "INT_DIR=%SCRIPT_DIR%.build\obj\Release\"

if not exist "%PROJECT_FILE%" (
	echo BlockChat Windows helper project not found: "%PROJECT_FILE%"
	exit /b 1
)

set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
if not exist "%VSWHERE%" (
	echo vswhere.exe not found. Install Visual Studio 2022 or Build Tools with Desktop C++.
	exit /b 1
)

for /f "usebackq delims=" %%I in (`"%VSWHERE%" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do (
	set "VS_INSTALL=%%I"
)

if not defined VS_INSTALL (
	echo Visual Studio C++ tools not found. Install Desktop development with C++.
	exit /b 1
)

set "VSDEVCMD=%VS_INSTALL%\Common7\Tools\VsDevCmd.bat"
if not exist "%VSDEVCMD%" (
	echo VsDevCmd.bat not found: "%VSDEVCMD%"
	exit /b 1
)

call "%VSDEVCMD%" -host_arch=amd64 -arch=amd64
if errorlevel 1 (
	echo Failed to initialize Visual Studio build environment.
	exit /b 1
)

if not exist "%SCRIPT_DIR%.build" mkdir "%SCRIPT_DIR%.build"
if not exist "%OUT_DIR%" mkdir "%OUT_DIR%"
if not exist "%INT_DIR%" mkdir "%INT_DIR%"

msbuild "%PROJECT_FILE%" ^
	/nologo ^
	/m ^
	/p:Configuration=Release ^
	/p:Platform=x64 ^
	/p:OutDir=%OUT_DIR% ^
	/p:IntDir=%INT_DIR%
if errorlevel 1 (
	echo BlockChat Windows helper build failed.
	exit /b 1
)

if not exist "%OUT_DIR%BlockChatWindowsCaptureHelper.exe" (
	echo BlockChat Windows helper executable missing after build.
	exit /b 1
)

echo Built "%OUT_DIR%BlockChatWindowsCaptureHelper.exe"
exit /b 0
