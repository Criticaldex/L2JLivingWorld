@echo off
REM ===========================================================================
REM  One-step setup + launch for the FPC brain - local Ollama model or DeepSeek
REM  cloud API, your choice.
REM
REM  What it does:
REM    1. Asks which AI provider to use: Ollama (local/offline) or DeepSeek
REM       (cloud, needs an API key). Asked every run - press Enter to keep
REM       whatever you picked last time, or answer again to switch.
REM    2. Ollama chosen: installs Ollama if missing, starts its server, pulls
REM       the chosen chat model (default gemma3:12b).
REM       DeepSeek chosen: asks for (or reuses the saved) API key. Ollama is
REM       never installed/started/pulled on this path.
REM    3. Creates a Python virtualenv and installs requirements.
REM    4. Writes the local .env file with the resolved settings.
REM    5. Starts fpc_brain.py.
REM
REM  Usage:
REM    setup_brain.bat                                - if the brain is already set up
REM                                                      (.env + .venv exist) it starts
REM                                                      straight away with no provider
REM                                                      question; otherwise it runs the
REM                                                      first-time setup (asks O/D).
REM    setup_brain.bat --reset                        - wipes the saved .env first, so
REM                                                      everything is asked fresh again
REM                                                      (including the DeepSeek key).
REM                                                      This is how you switch provider.
REM    setup_brain.bat --auto                         - non-interactive launch used by the
REM                                                      one-click launcher: if the brain is
REM                                                      already configured (.env + .venv), it
REM                                                      just starts fpc_brain.py with no
REM                                                      prompts and no pauses. If it is NOT
REM                                                      configured yet, it prints a short
REM                                                      note and exits WITHOUT blocking the
REM                                                      server (exit code 2), so a boot with
REM                                                      StartBrain=true never hangs on a
REM                                                      prompt. Configure it once by running
REM                                                      setup_brain.bat with no arguments.
REM    set OLLAMA_MODEL=llama3.1 & setup_brain.bat     - override the Ollama model
REM ===========================================================================

setlocal enabledelayedexpansion
cd /d "%~dp0"

echo.
echo ==^> FPC brain setup
echo ==^> Working folder:
cd
echo.

REM --- Basic project file checks --------------------------------------------

if not exist fpc_brain.py (
    echo ERROR: fpc_brain.py not found in this folder.
    echo Put setup_brain.bat in the same folder as fpc_brain.py.
    if /i not "%~1"=="--auto" pause
    exit /b 1
)

if not exist requirements.txt (
    echo ERROR: requirements.txt not found in this folder.
    echo.
    echo Create a file named requirements.txt with these lines:
    echo flask
    echo openai
    echo python-dotenv
    echo.
    if /i not "%~1"=="--auto" pause
    exit /b 1
)

REM --- 0a. --auto: non-interactive launch for the one-click launcher ---------
REM The launcher calls this when StartBrain=true. It must never prompt or pause,
REM so a normal server boot cannot hang here. If the brain is already configured
REM (an .env and a .venv exist), start it straight away. If it is not configured
REM yet, print a short note and exit with code 2 so the launcher just skips it;
REM the tester configures it once by double-clicking setup_brain.bat normally.

if /i "%~1"=="--auto" (
    if not exist .env (
        echo ==^> Brain not configured yet - skipping auto-start.
        echo     Run setup_brain.bat once ^(no arguments^) to choose a provider and set it up.
        exit /b 2
    )
    if not exist .venv (
        echo ==^> Brain Python environment missing - skipping auto-start.
        echo     Run setup_brain.bat once ^(no arguments^) to build it.
        exit /b 2
    )
    call .venv\Scripts\activate.bat
    if errorlevel 1 (
        echo ==^> Could not activate the brain virtualenv - skipping auto-start.
        exit /b 2
    )
    echo ==^> Starting the FPC brain on http://127.0.0.1:5000 ^(auto^) ...
    python fpc_brain.py
    exit /b !errorlevel!
)

REM --- 0b. --reset wipes the saved config so everything is asked fresh -------

if /i "%~1"=="--reset" (
    if exist .env (
        echo ==^> --reset: removing existing .env - you will be asked to reconfigure.
        del /f /q .env
    )
)

REM --- 0c. Already set up? Skip the O/D question and go straight to launch ----
REM If an .env (chosen provider) and a .venv (built Python env) already exist,
REM the brain was configured on a previous run, so a plain double-click / the
REM "Set up / configure brain" button starts it immediately - no provider
REM question. To change provider later, run: setup_brain.bat --reset (that wipes
REM .env and asks fresh). --reset deletes .env above, so it never skips here.

if exist .env if exist ".venv\Scripts\activate.bat" goto smart_launch

REM --- Load any existing configuration as defaults for the prompts below ----

set "EXIST_PROVIDER="
set "EXIST_MODEL="
set "EXIST_KEY="
if exist .env (
    for /f "usebackq tokens=1,* delims==" %%A in (".env") do (
        if /i "%%A"=="PROVIDER" set "EXIST_PROVIDER=%%B"
        if /i "%%A"=="OLLAMA_MODEL" set "EXIST_MODEL=%%B"
        if /i "%%A"=="DEEPSEEK_API_KEY" set "EXIST_KEY=%%B"
    )
)

REM --- 1. Ask which provider to use, every run --------------------------------

echo Choose an AI provider for the FPC brain:
echo   [O] Ollama   - local model, free, fully offline (needs a decent GPU/CPU)
echo   [D] DeepSeek - cloud API, needs an API key, works on any PC
echo.

set "PROVIDER_CHOICE="
if defined EXIST_PROVIDER (
    set /p "PROVIDER_CHOICE=Use Ollama or DeepSeek? [O/D] (Enter = keep '!EXIST_PROVIDER!'): "
) else (
    set /p "PROVIDER_CHOICE=Use Ollama or DeepSeek? [O/D]: "
)

if "!PROVIDER_CHOICE!"=="" (
    if defined EXIST_PROVIDER (
        set "PROVIDER=!EXIST_PROVIDER!"
    ) else (
        echo ERROR: You must choose O or D on first setup.
        pause
        exit /b 1
    )
) else if /i "!PROVIDER_CHOICE:~0,1!"=="O" (
    set "PROVIDER=ollama"
) else if /i "!PROVIDER_CHOICE:~0,1!"=="D" (
    set "PROVIDER=deepseek"
) else (
    echo ERROR: Please answer O or D.
    pause
    exit /b 1
)

echo ==^> Provider: !PROVIDER!
echo.

if /i "!PROVIDER!"=="deepseek" goto setup_deepseek
goto setup_ollama

:setup_deepseek
REM --- 2a. DeepSeek: reuse or ask for the API key, skip Ollama entirely ------

set "DEEPSEEK_API_KEY="
if defined EXIST_KEY (
    set /p "KEY_CHOICE=Use saved DeepSeek API key ending '...!EXIST_KEY:~-4!'? [Y/n]: "
    if /i not "!KEY_CHOICE:~0,1!"=="N" set "DEEPSEEK_API_KEY=!EXIST_KEY!"
)

if not defined DEEPSEEK_API_KEY (
    set /p "DEEPSEEK_API_KEY=Enter your DeepSeek API key: "
)

if "!DEEPSEEK_API_KEY!"=="" (
    echo ERROR: A DeepSeek API key is required when using DeepSeek.
    pause
    exit /b 1
)

echo ==^> DeepSeek configured.
goto after_provider_setup

:setup_ollama
REM --- 2b. Ollama: install if missing, start the server, pull the model ------

if "%OLLAMA_MODEL%"=="" (
    if defined EXIST_MODEL (
        set "OLLAMA_MODEL=!EXIST_MODEL!"
    ) else (
        set "OLLAMA_MODEL=gemma3:12b"
    )
)

echo ==^> Ollama model: !OLLAMA_MODEL!
echo.

REM Try common Ollama install paths first, in case PATH is not refreshed.
if exist "%LOCALAPPDATA%\Programs\Ollama\ollama.exe" (
    set "PATH=%LOCALAPPDATA%\Programs\Ollama;%PATH%"
)

if exist "%ProgramFiles%\Ollama\ollama.exe" (
    set "PATH=%ProgramFiles%\Ollama;%PATH%"
)

where ollama >nul 2>&1
if errorlevel 1 (
    echo ==^> Ollama not found.

    if exist "%~dp0OllamaSetup.exe" (
        echo ==^> Installing bundled OllamaSetup.exe...
        start /wait "" "%~dp0OllamaSetup.exe"
    ) else (
        where winget >nul 2>&1
        if errorlevel 1 (
            echo ==^> winget not available. Downloading Ollama installer directly...

            if not exist "%TEMP%\fpc_brain_setup" mkdir "%TEMP%\fpc_brain_setup"

            powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $url='https://ollama.com/download/OllamaSetup.exe'; $out=Join-Path $env:TEMP 'fpc_brain_setup\OllamaSetup.exe'; Invoke-WebRequest -Uri $url -OutFile $out"

            if errorlevel 1 (
                echo ERROR: Could not download Ollama installer.
                echo Check internet connection or download OllamaSetup.exe manually.
                pause
                exit /b 1
            )

            echo ==^> Running downloaded Ollama installer...
            start /wait "" "%TEMP%\fpc_brain_setup\OllamaSetup.exe"
        ) else (
            echo ==^> Installing Ollama via winget...
            winget install --id Ollama.Ollama -e --accept-package-agreements --accept-source-agreements

            if errorlevel 1 (
                echo WARNING: winget install failed. Trying direct download instead...

                if not exist "%TEMP%\fpc_brain_setup" mkdir "%TEMP%\fpc_brain_setup"

                powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $url='https://ollama.com/download/OllamaSetup.exe'; $out=Join-Path $env:TEMP 'fpc_brain_setup\OllamaSetup.exe'; Invoke-WebRequest -Uri $url -OutFile $out"

                if errorlevel 1 (
                    echo ERROR: Could not download Ollama installer.
                    pause
                    exit /b 1
                )

                echo ==^> Running downloaded Ollama installer...
                start /wait "" "%TEMP%\fpc_brain_setup\OllamaSetup.exe"
            )
        )
    )

    REM Refresh PATH after installer.
    if exist "%LOCALAPPDATA%\Programs\Ollama\ollama.exe" (
        set "PATH=%LOCALAPPDATA%\Programs\Ollama;%PATH%"
    )

    if exist "%ProgramFiles%\Ollama\ollama.exe" (
        set "PATH=%ProgramFiles%\Ollama;%PATH%"
    )

    where ollama >nul 2>&1
    if errorlevel 1 (
        echo ERROR: Ollama installer finished, but ollama.exe is still not available.
        echo Close this Command Prompt window, open a new one, and run this BAT again.
        pause
        exit /b 1
    )
) else (
    echo ==^> Ollama already installed.
)

REM --- Make sure the Ollama server is up --------------------------------------

curl -fsS http://127.0.0.1:11434/api/tags >nul 2>&1
if errorlevel 1 (
    echo ==^> Starting Ollama server in the background...
    start "" /b ollama serve

    for /l %%i in (1,1,30) do (
        timeout /t 1 /nobreak >nul
        curl -fsS http://127.0.0.1:11434/api/tags >nul 2>&1
        if not errorlevel 1 goto ollama_up
    )
)

:ollama_up
curl -fsS http://127.0.0.1:11434/api/tags >nul 2>&1
if errorlevel 1 (
    echo ERROR: Ollama server did not come up.
    echo Try closing this window, opening a new Command Prompt, and running this BAT again.
    pause
    exit /b 1
)

echo ==^> Ollama server is up.

REM --- Pull the model ----------------------------------------------------------

echo ==^> Pulling model "!OLLAMA_MODEL!". First run may download several GB.
ollama pull !OLLAMA_MODEL!

if errorlevel 1 (
    echo ERROR: Failed to pull the model "!OLLAMA_MODEL!".
    pause
    exit /b 1
)

echo ==^> Ollama model is ready.

:after_provider_setup

REM Preserve whichever DeepSeek key/model were on record when the OTHER
REM provider was chosen this run, so switching back and forth later does not
REM lose them.
if not defined DEEPSEEK_API_KEY set "DEEPSEEK_API_KEY=!EXIST_KEY!"
if not defined OLLAMA_MODEL set "OLLAMA_MODEL=!EXIST_MODEL!"

REM --- 3. Python env + deps ----------------------------------------------------

REM Make an already-installed Python visible even if PATH wasn't refreshed yet.
call :ensure_python_on_path

REM Detect a REAL Python. Windows 10/11 ship zero-byte "App execution alias"
REM stubs named python.exe / python3.exe in %LOCALAPPDATA%\Microsoft\WindowsApps
REM that only open the Microsoft Store. Plain `where python` finds those stubs -
REM so it looks installed - but running them just prints "Python was not found"
REM and fails. Gate on actually running python (and reject the Store alias) so a
REM machine with only the stub still triggers the auto-install below.
call :have_real_python
if errorlevel 1 (
    echo ==^> Python not found. Installing it automatically...

    where winget >nul 2>&1
    if errorlevel 1 (
        echo ==^> winget not available. Downloading the official Python installer...
        if not exist "%TEMP%\fpc_brain_setup" mkdir "%TEMP%\fpc_brain_setup"
        powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $url='https://www.python.org/ftp/python/3.12.8/python-3.12.8-amd64.exe'; $out=Join-Path $env:TEMP 'fpc_brain_setup\python-installer.exe'; Invoke-WebRequest -Uri $url -OutFile $out"
        if errorlevel 1 (
            echo ERROR: Could not download the Python installer.
            echo Install Python manually from https://python.org - tick "Add python.exe to PATH".
            pause
            exit /b 1
        )
        echo ==^> Running the Python installer silently ^(this can take a minute^)...
        start /wait "" "%TEMP%\fpc_brain_setup\python-installer.exe" /quiet InstallAllUsers=0 PrependPath=1 Include_pip=1 Include_launcher=1
    ) else (
        echo ==^> Installing Python via winget...
        winget install --id Python.Python.3.12 -e --accept-package-agreements --accept-source-agreements
        if errorlevel 1 (
            echo WARNING: winget install failed. Downloading the official installer instead...
            if not exist "%TEMP%\fpc_brain_setup" mkdir "%TEMP%\fpc_brain_setup"
            powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $url='https://www.python.org/ftp/python/3.12.8/python-3.12.8-amd64.exe'; $out=Join-Path $env:TEMP 'fpc_brain_setup\python-installer.exe'; Invoke-WebRequest -Uri $url -OutFile $out"
            if errorlevel 1 (
                echo ERROR: Could not download the Python installer.
                pause
                exit /b 1
            )
            echo ==^> Running the Python installer silently ^(this can take a minute^)...
            start /wait "" "%TEMP%\fpc_brain_setup\python-installer.exe" /quiet InstallAllUsers=0 PrependPath=1 Include_pip=1 Include_launcher=1
        )
    )

    REM Make the freshly installed Python visible to THIS window (PrependPath only
    REM affects newly opened shells).
    call :ensure_python_on_path

    call :have_real_python
    if errorlevel 1 (
        echo ERROR: Python was installed, but it is not visible in this window yet.
        echo Close this Command Prompt, open a new one, and run this BAT again.
        echo.
        echo If it keeps failing, the Microsoft Store "App execution alias" for
        echo Python may be shadowing the real install. Turn it off under Settings
        echo ^> Apps ^> Advanced app settings ^> App execution aliases ^(switch off
        echo both python.exe and python3.exe^), then run this BAT again.
        pause
        exit /b 1
    )
) else (
    echo ==^> Python already installed.
)

python --version
if errorlevel 1 (
    echo ERROR: Python command exists but failed to run.
    pause
    exit /b 1
)

if not exist .venv (
    echo ==^> Creating Python virtualenv .venv...
    python -m venv .venv

    if errorlevel 1 (
        echo ERROR: Could not create Python virtualenv.
        echo Check that Python is installed correctly.
        pause
        exit /b 1
    )
) else (
    echo ==^> Python virtualenv already exists.
)

call .venv\Scripts\activate.bat

if errorlevel 1 (
    echo ERROR: Failed to activate Python virtualenv.
    pause
    exit /b 1
)

echo ==^> Installing Python requirements...
python -m pip install --upgrade pip

if errorlevel 1 (
    echo ERROR: Failed to upgrade pip.
    pause
    exit /b 1
)

python -m pip install -r requirements.txt

if errorlevel 1 (
    echo ERROR: Failed to install Python requirements.
    pause
    exit /b 1
)

REM --- 4. Write the resolved .env (always overwritten with this run's choice) -

echo ==^> Writing .env...
(
    echo PROVIDER=!PROVIDER!
    echo OLLAMA_MODEL=!OLLAMA_MODEL!
    echo DEEPSEEK_API_KEY=!DEEPSEEK_API_KEY!
) > .env

REM --- 5. Launch ---------------------------------------------------------------

:launch_brain
echo.
echo ==^> Starting the FPC brain on http://127.0.0.1:5000 ...
echo Press CTRL+C to stop it.
echo.

python fpc_brain.py

echo.
echo FPC brain exited with code !errorlevel!.
pause

endlocal
goto :eof

REM --- Smart launch: already-configured fast path (from the check near the top).
REM Activate the existing venv and jump straight to launching, skipping the
REM provider question and all install steps.
:smart_launch
echo.
echo ==^> Brain already set up - starting it now.
echo     ^(To change provider, run: setup_brain.bat --reset^)
call .venv\Scripts\activate.bat
if errorlevel 1 (
    echo ERROR: Could not activate the existing Python virtualenv.
    echo Run: setup_brain.bat --reset   to rebuild it.
    pause
    exit /b 1
)
goto launch_brain

REM ===========================================================================
REM  Subroutines
REM ===========================================================================

:ensure_python_on_path
REM Add the usual per-user / all-users Python install locations to PATH for THIS
REM window. The installer's PrependPath only affects newly opened shells, so a
REM Python we just installed (or one installed but not yet on PATH) would be
REM invisible without this. Any working python is fine - a venv is created from it.
if exist "%LOCALAPPDATA%\Programs\Python" (
    for /f "delims=" %%P in ('dir /b /ad /o-n "%LOCALAPPDATA%\Programs\Python\Python3*" 2^>nul') do (
        if exist "%LOCALAPPDATA%\Programs\Python\%%P\python.exe" (
            set "PATH=%LOCALAPPDATA%\Programs\Python\%%P;%LOCALAPPDATA%\Programs\Python\%%P\Scripts;!PATH!"
        )
    )
)
for %%D in ("%ProgramFiles%\Python313" "%ProgramFiles%\Python312" "%ProgramFiles%\Python311") do (
    if exist "%%~D\python.exe" set "PATH=%%~D;%%~D\Scripts;!PATH!"
)
goto :eof

:have_real_python
REM Succeeds (errorlevel 0) only when a usable Python is on PATH. Returns 1 when
REM none is found OR the only match is the Microsoft Store "App execution alias"
REM stub in WindowsApps (which reports as present via `where` but cannot run).
set "REALPY="
for /f "delims=" %%I in ('where python 2^>nul') do (
    echo %%I | find /i "\WindowsApps\" >nul
    if errorlevel 1 (
        if not defined REALPY set "REALPY=%%I"
    )
)
if not defined REALPY exit /b 1
REM A non-alias python.exe exists on PATH; confirm it actually runs.
"%REALPY%" --version >nul 2>&1
if errorlevel 1 exit /b 1
REM Pin its folder to the front of PATH so every later plain `python` call in
REM this window resolves to it and never to the WindowsApps Store alias.
for %%I in ("%REALPY%") do set "PATH=%%~dpI;%%~dpIScripts;!PATH!"
exit /b 0
