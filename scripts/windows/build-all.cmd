@echo off
rem One-click build: Windows desktop + CLI host + Android (Rust lib / APK).
rem Examples:
rem   build-all.cmd -SkipAndroid
rem   build-all.cmd -SkipApk -InstallApk
rem   build-all.cmd -SkipWindows -SkipAndroid   (dry run: preflight + summary)
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
