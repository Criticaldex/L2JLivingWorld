@echo off
title L2 Offline Server - Check for updates
REM Checks the public release repo for a newer version and, if you confirm,
REM downloads the small patch and applies it over this install. Your database
REM (characters, items, adena) and any configs you customized are left alone.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0..\launcher\update.ps1"
echo.
pause
