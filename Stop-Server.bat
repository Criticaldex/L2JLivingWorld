@echo off
title L2 Offline Server - Stop
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0launcher\stop.ps1"
set "STOP_EXIT=%ERRORLEVEL%"
echo.
if not "%STOP_EXIT%"=="0" (
  echo One or more components could not be stopped safely.
)
timeout /t 3 >nul
exit /b %STOP_EXIT%
