@echo off
title L2 Offline Server - Configure AI brain (optional)
REM One-time, interactive setup for the OPTIONAL FPC "brain" (in-character bot
REM chat). It installs Python if needed, lets you pick Ollama (local/offline) or
REM DeepSeek (cloud API key), builds the environment and starts the brain. The
REM server works fully without this - it only adds the talking.
REM
REM After it is set up once, ticking "start brain with server" (or StartBrain=true
REM in launcher\launcher.ini) will launch it automatically on each boot.
set "BRAIN=%~dp0..\brain\setup_brain.bat"
if not exist "%BRAIN%" (
  echo Could not find the brain setup at "%BRAIN%".
  echo This pack may have been built without the optional brain.
  echo.
  pause
  exit /b 1
)
call "%BRAIN%" %*
