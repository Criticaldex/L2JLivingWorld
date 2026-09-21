@echo off
title L2 Offline Server - Launcher
REM One-click boot for the whole stack. Edit launcher\launcher.ini for your machine.
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0launcher\launcher.ps1"
set "LAUNCH_EXIT=%ERRORLEVEL%"
echo.
if not "%LAUNCH_EXIT%"=="0" (
  echo Startup failed ^(exit %LAUNCH_EXIT%^) - see the messages above.
) else (
  echo Launcher finished. The servers, if started, run in their own windows.
)
REM Always hold the window open so any error stays readable. Note: Windows
REM PowerShell can report exit 0 even on a script error, so we never rely on the
REM exit code alone to decide whether to pause - we always do.
echo.
pause
endlocal
