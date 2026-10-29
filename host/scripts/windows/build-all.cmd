@echo off
rem Windows 无界面 CLI 被控端构建：host/ + MSYS2 UCRT64 + --features system-libs。
rem 用法：
rem   build-all.cmd
rem   build-all.cmd -Jobs 4
rem   build-all.cmd -Msys2Root C:\msys64
rem   build-all.cmd -DryRun        (只做环境预检)
rem 没有 Flutter/Dart/NDK/vcpkg 步骤；Android 端在 android\ 下用 Gradle 单独构建。
chcp 65001 >nul
setlocal
set "PS=%ProgramFiles%\PowerShell\7\pwsh.exe"
if not exist "%PS%" set "PS=%LOCALAPPDATA%\Microsoft\WindowsApps\pwsh.exe"
if not exist "%PS%" set "PS=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
echo Using PowerShell: "%PS%"
"%PS%" -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-all.ps1" %*
set "RC=%ERRORLEVEL%"
echo.
echo ===== build finished, exit code %RC% =====
pause
exit /b %RC%
