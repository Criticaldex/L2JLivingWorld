<#
  L2 Offline Server - One-Click Launcher
  --------------------------------------
  Boots the whole stack from a single double-click:
     DB engine  ->  first-run schema install  ->  Login server  ->  Game server  ->  (optional) brain

  Works two ways, chosen by launcher.ini:
    * Bundled pack   - DataDir set => a portable MariaDB shipped inside the pack, initialized on
                       first run. Combined with a bundled JDK at dist\jre\, the player installs nothing.
    * External DB    - DataDir blank => uses an existing MySQL/XAMPP (auto-starts it if needed).

  Driven by Start-Server.bat in the parent (dist) folder. Nothing here touches game logic -
  it only orchestrates the pieces that already exist.

  -Quiet is used by the GUI control panel: it hides the console windows (the DB
  engine and each server run without a terminal). The plain Start-Server.bat path
  does not pass it, so its behavior is unchanged.
#>

param([switch]$Quiet)

$ErrorActionPreference = 'Stop'

# ---- paths -----------------------------------------------------------------
$LauncherDir = Split-Path -Parent $MyInvocation.MyCommand.Path      # dist\launcher
$DistDir     = Split-Path -Parent $LauncherDir                       # dist
$IniPath     = Join-Path $LauncherDir 'launcher.ini'
$MarkerPath  = Join-Path $LauncherDir '.db_installed'
$ProcessRegistryPath = Join-Path $LauncherDir '.processes.json'

function Write-Head($t) { Write-Host ""; Write-Host "==== $t ====" -ForegroundColor Cyan }
function Write-Ok($t)   { Write-Host "  [ OK ] $t" -ForegroundColor Green }
function Write-Info($t) { Write-Host "  [info] $t" -ForegroundColor Gray }
function Fail($t)       { Write-Host ""; Write-Host "  [FAIL] $t" -ForegroundColor Red; Write-Host ""; exit 1 }

# ---- tiny INI parser -------------------------------------------------------
function Read-Ini($path) {
    $ini = @{}; $section = ''
    foreach ($line in Get-Content $path) {
        $l = $line.Trim()
        if ($l -eq '' -or $l.StartsWith('#') -or $l.StartsWith(';')) { continue }
        if ($l -match '^\[(.+)\]$') { $section = $Matches[1]; $ini[$section] = @{}; continue }
        $idx = $l.IndexOf('=')
        if ($idx -lt 0) { continue }
        $key = $l.Substring(0, $idx).Trim()
        $val = $l.Substring($idx + 1).Trim()
        if ($section -eq '') { continue }
        $ini[$section][$key] = $val
    }
    return $ini
}

function Get-Ini($ini, $sec, $key, $default = '') {
    if ($ini.ContainsKey($sec) -and $ini[$sec].ContainsKey($key) -and $ini[$sec][$key] -ne '') { return $ini[$sec][$key] }
    return $default
}
function Is-True($v) { return @('true','1','yes','on') -contains ("$v").ToLower() }

# ---- launcher-owned process registry --------------------------------------
# Stop-Server uses this registry to terminate only processes created by this
# launcher. PID alone is not sufficient because Windows can reuse it, so each
# record also carries the process start time, image name, and command marker.
$script:LaunchedProcesses = @()

function Save-ProcessRegistry {
    $tmp = "$ProcessRegistryPath.tmp"
    ConvertTo-Json -InputObject @($script:LaunchedProcesses) -Depth 3 |
        Set-Content -Path $tmp -Encoding UTF8
    Move-Item -Path $tmp -Destination $ProcessRegistryPath -Force
}

function Register-LaunchedProcess($role, $process, $marker) {
    Start-Sleep -Milliseconds 100
    $process.Refresh()
    if ($process.HasExited) { Fail "$role exited immediately after launch." }
    $script:LaunchedProcesses += [pscustomobject]@{
        Role        = $role
        Id          = $process.Id
        StartTicks  = "$($process.StartTime.ToUniversalTime().Ticks)"
        ProcessName = $process.ProcessName
        Marker      = $marker
    }
    Save-ProcessRegistry
}

function Assert-NoManagedProcesses {
    if (-not (Test-Path $ProcessRegistryPath)) { return }
    try {
        # Assign first, then wrap with @() when iterating. In Windows PowerShell 5.1
        # ConvertFrom-Json returns a multi-record array as a single non-enumerated
        # object, so @(pipeline) would nest it and $record would bind to the inner array.
        $records = Get-Content -Raw $ProcessRegistryPath | ConvertFrom-Json
    } catch {
        Fail "Process registry is unreadable: $ProcessRegistryPath. Inspect or remove it before starting."
    }
    foreach ($record in @($records)) {
        $existing = Get-Process -Id ([int]$record.Id) -ErrorAction SilentlyContinue
        if ($existing) {
            try { $ticks = "$($existing.StartTime.ToUniversalTime().Ticks)" } catch { $ticks = '' }
            if (($ticks -eq "$($record.StartTicks)") -and
                ($existing.ProcessName -eq "$($record.ProcessName)")) {
                Fail "$($record.Role) is already running (PID $($record.Id)). Use Stop-Server.bat first."
            }
        }
    }
    Remove-Item $ProcessRegistryPath -Force
}

# ---- helpers ---------------------------------------------------------------
function Test-Port($p) {
    try {
        $c = New-Object System.Net.Sockets.TcpClient
        $iar = $c.BeginConnect('127.0.0.1', [int]$p, $null, $null)
        $ok = $iar.AsyncWaitHandle.WaitOne(600)
        if ($ok -and $c.Connected) { $c.EndConnect($iar); $c.Close(); return $true }
        $c.Close(); return $false
    } catch { return $false }
}

function Wait-Port($p, $timeoutSec) {
    for ($i = 0; $i -lt $timeoutSec; $i++) {
        if (Test-Port $p) { return $true }
        Start-Sleep -Seconds 1
    }
    return $false
}

# ---- find java (returns $null if not found - never exits, so pre-flight can
#      aggregate every missing item into one screen) ---------------------------
function Find-Java($javaHome) {
    if ($javaHome -ne '') {
        $cand = Join-Path $javaHome 'bin\java.exe'
        if (Test-Path $cand) { return $cand }
        return $null
    }
    $bundled = Join-Path $DistDir 'jre\bin\java.exe'
    if (Test-Path $bundled) { return $bundled }
    if ($env:JAVA_HOME) {
        $cand = Join-Path $env:JAVA_HOME 'bin\java.exe'
        if (Test-Path $cand) { return $cand }
    }
    $onPath = (Get-Command java.exe -ErrorAction SilentlyContinue)
    if ($onPath) { return $onPath.Source }
    return $null
}

# ============================================================================
# Clear-Host throws when there is no real console screen buffer (e.g. when the GUI
# control panel captures this script's output). Never let that abort startup.
try { Clear-Host } catch { }
Write-Host "########################################################" -ForegroundColor Magenta
Write-Host "#   L2 Offline 'Living World' - One-Click Launcher     #" -ForegroundColor Magenta
Write-Host "########################################################" -ForegroundColor Magenta

# Show the installed version, read from launcher\version.txt. That file ships in
# every pack (stamped at build time) and updates itself when a patch is applied,
# so this line is always current without the player doing anything.
$VersionPath = Join-Path $LauncherDir 'version.txt'
$installedVersion = if (Test-Path $VersionPath) { (Get-Content $VersionPath -Raw).Trim() } else { 'unknown' }
Write-Host "   version $installedVersion" -ForegroundColor DarkGray
Write-Host ""

if (-not (Test-Path $IniPath)) { Fail "launcher.ini not found at $IniPath" }
$ini = Read-Ini $IniPath

$javaHome  = Get-Ini $ini 'java'     'JavaHome' ''
$dbHost    = Get-Ini $ini 'database' 'Host'     'localhost'
$dbPort    = Get-Ini $ini 'database' 'Port'     '3306'
$dbUser    = Get-Ini $ini 'database' 'User'     'root'
$dbPass    = Get-Ini $ini 'database' 'Password' ''
$dbName    = Get-Ini $ini 'database' 'Database' 'l2jmobiusinterlude'
$mysqlBin  = Get-Ini $ini 'database' 'MysqlBin' 'C:\xampp\mysql\bin'
$dataDir   = Get-Ini $ini 'database' 'DataDir'  ''
$autoMysql = Is-True (Get-Ini $ini 'database' 'AutoStartMysql' 'true')
$startLogin= Is-True (Get-Ini $ini 'servers'  'StartLogin' 'true')
$startGame = Is-True (Get-Ini $ini 'servers'  'StartGame'  'true')
$startBrain= Is-True (Get-Ini $ini 'servers'  'StartBrain' 'false')
$clientExe   = Get-Ini $ini 'client' 'ClientExe' ''
$launchClient= Is-True (Get-Ini $ini 'client' 'LaunchClient' 'false')

Assert-NoManagedProcesses

# Resolve relative MysqlBin / DataDir against dist\ so the bundled pack is portable.
function Resolve-Rel($p) {
    if ($p -eq '') { return '' }
    if ([System.IO.Path]::IsPathRooted($p)) { return $p }
    return (Join-Path $DistDir $p)
}
$mysqlBin = Resolve-Rel $mysqlBin
if ($dataDir -ne '') { $dataDir = Resolve-Rel $dataDir }
$bundledDb = ($dataDir -ne '')   # bundled MariaDB mode when a data dir is configured

$mysqlExe = Join-Path $mysqlBin 'mysql.exe'
$mysqldExe= Join-Path $mysqlBin 'mysqld.exe'

# ---- 0. Pre-flight ---------------------------------------------------------
# One screen up front listing anything that is missing, instead of failing
# three separate steps in. Required things stop the run; the rest is info.
Write-Head "0/4  Pre-flight check"
$problems = @()
$javaProbe = Find-Java $javaHome
if ($javaProbe) { Write-Ok "Java found: $javaProbe" }
else { $problems += "Java (JDK 25) not found - you are running the raw launcher, not the bundled pack. Use the pack (it ships Java at dist\jre\), or set JavaHome / install JDK 25 for a manual run." }

$dbUp = Test-Port $dbPort
if ($dbUp) {
    Write-Ok "a database is already running on port $dbPort"
} elseif (-not (Test-Path $mysqldExe)) {
    $problems += "Database engine not found: expected mysqld.exe at $mysqldExe (fix MysqlBin in launcher.ini, or bundle MariaDB)."
} else {
    Write-Ok "database engine present: $mysqldExe"
}

if ($startLogin -and -not (Test-Path (Join-Path $DistDir 'login\..\libs\LoginServer.jar'))) {
    $problems += "LoginServer.jar missing from libs\ - build with 'ant' and include it in the pack."
}
if ($startGame -and -not (Test-Path (Join-Path $DistDir 'game\..\libs\GameServer.jar'))) {
    $problems += "GameServer.jar missing from libs\ - build with 'ant' and include it in the pack."
}

if ($problems.Count -gt 0) {
    Write-Host ""
    Write-Host "  Cannot start yet - the following are missing:" -ForegroundColor Yellow
    foreach ($p in $problems) { Write-Host "    - $p" -ForegroundColor Yellow }
    Fail "Resolve the items above and run again. (A fully bundled pack ships Java + MariaDB + jars so none of this is needed.)"
}

# ---- 1. Java ---------------------------------------------------------------
Write-Head "1/4  Java runtime"
$java = Find-Java $javaHome
if (-not $java) { Fail "Could not find Java. Use the bundled pack, set JavaHome in launcher.ini, or install JDK 25." }
Write-Ok "java: $java"

# Quiet mode (GUI control panel): hide the console/terminal windows. The DB engine
# has no GUI, so it runs fully hidden. The Java servers launch via javaw.exe so their
# terminal window is gone while the in-process Swing GUI window still opens - EnableGUI
# stays on, no headless flag. stop.ps1 still matches them: it records whatever process
# name we launch (javaw) and verifies the jar on the command line.
$dbWindowStyle   = if ($Quiet) { 'Hidden' } else { 'Minimized' }
$serverLaunchExe = $java
if ($Quiet) {
    $javawCandidate = Join-Path (Split-Path -Parent $java) 'javaw.exe'
    if (Test-Path $javawCandidate) { $serverLaunchExe = $javawCandidate }
    else { Write-Info "javaw.exe not found next to java.exe; server terminals will stay visible." }
}

# ---- 2. Database engine ----------------------------------------------------
Write-Head "2/4  Database (MySQL/MariaDB)"

if (Test-Port $dbPort) {
    Write-Ok "database already running on port $dbPort"
} elseif ($autoMysql) {
    if (-not (Test-Path $mysqldExe)) { Fail "DB not running and mysqld.exe not found at $mysqldExe. Fix MysqlBin in launcher.ini." }

    if ($bundledDb) {
        # Bundled/portable MariaDB: initialize the data dir on first run, then start with --datadir.
        $needInit = -not (Test-Path (Join-Path $dataDir 'mysql')) -and -not (Test-Path (Join-Path $dataDir 'ibdata1'))
        if ($needInit) {
            Write-Info "first run - initializing bundled MariaDB data dir at $dataDir ..."
            # Start from a clean data dir - a partial dir left by a failed attempt breaks init.
            if (Test-Path $dataDir) { Remove-Item $dataDir -Recurse -Force -ErrorAction SilentlyContinue }
            New-Item -ItemType Directory -Path $dataDir -Force | Out-Null
            $installExe = $null
            foreach ($n in @('mariadb-install-db.exe','mysql_install_db.exe')) {
                $c = Join-Path $mysqlBin $n
                if (Test-Path $c) { $installExe = $c; break }
            }
            if (-not $installExe) { Fail "Bundled MariaDB is missing mariadb-install-db.exe / mysql_install_db.exe in $mysqlBin." }
            # NOTE: the Windows mariadb-install-db.exe only really accepts --datadir - it
            # auto-detects its base folder and REJECTS --basedir and the Linux-only
            # --auth-root-authentication-method. A plain init already creates root@localhost
            # with an EMPTY password, which is what Database.ini expects. Relax
            # ErrorActionPreference for the call because the tool logs progress to stderr,
            # which would otherwise be treated as fatal.
            $prevEAP = $ErrorActionPreference
            $ErrorActionPreference = 'Continue'
            & $installExe "--datadir=$dataDir" 2>&1 | ForEach-Object { Write-Host "    $_" }
            $code = $LASTEXITCODE
            $ErrorActionPreference = $prevEAP
            if ($code -ne 0) { Fail "MariaDB data dir initialization failed (code $code). See messages above." }
            Write-Ok "MariaDB data dir initialized"
        }
        Write-Info "starting bundled MariaDB (port $dbPort) ..."
        # Pass a single, explicitly-quoted argument string: Start-Process -ArgumentList
        # with an ARRAY does not quote elements, so a data dir path containing spaces
        # (e.g. "New folder") gets split and mysqld fails to start. Quoting fixes that.
        $mysqldArgLine = "--datadir=`"$dataDir`" --port=$dbPort --skip-name-resolve --console"
        Start-Process -FilePath $mysqldExe -WorkingDirectory $mysqlBin `
            -ArgumentList $mysqldArgLine -WindowStyle $dbWindowStyle | Out-Null
    } else {
        # External engine (XAMPP etc.): start it with its own configured data dir.
        Write-Info "starting mysqld from $mysqlBin ..."
        Start-Process -FilePath $mysqldExe -WorkingDirectory $mysqlBin -WindowStyle $dbWindowStyle | Out-Null
    }

    if (Wait-Port $dbPort 60) { Write-Ok "database is up on port $dbPort" }
    else { Fail "the database did not open port $dbPort within 60s. Check its (minimized) console window for errors, and avoid folder paths with spaces." }
} else {
    Fail "Nothing is listening on port $dbPort and AutoStartMysql=false. Start your DB and retry."
}

# ---- 3. First-run schema install ------------------------------------------
Write-Head "3/4  Database schema"
if (Test-Path $MarkerPath) {
    Write-Ok "already installed (delete $($MarkerPath | Split-Path -Leaf) in launcher\ to reinstall)"
} else {
    if (-not (Test-Path $mysqlExe)) { Fail "First-run install needs mysql.exe at $mysqlExe. Fix MysqlBin in launcher.ini." }
    $skipInstall = $false

    # The mysql client prints a harmless stderr warning on a passwordless login
    # ("--ssl-verify-server-cert is disabled ..."). Under ErrorActionPreference=Stop
    # that stderr write aborts the script, so relax it for the DB-client calls below.
    # Real failures are still caught via $LASTEXITCODE after each call.
    $ErrorActionPreference = 'Continue'

    $mysqlArgs = @('-h', $dbHost, '-P', $dbPort, '-u', $dbUser)
    if ($dbPass -ne '') { $mysqlArgs += "--password=$dbPass" }

    # SAFETY: never run the schema import over a database that already has tables -
    # a number of the SQL files DROP TABLE before recreating (e.g. accounts), which
    # would wipe an existing server. If the DB already has tables, skip and mark done.
    $existingCount = (& $mysqlExe @mysqlArgs '-N' '-B' '-e' "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$dbName';" 2>$null)
    if ($LASTEXITCODE -ne 0) { Fail "Could not connect to the database. Check DB credentials in launcher.ini." }
    $existingCount = ("$existingCount").Trim()
    if ($existingCount -match '^\d+$' -and [int]$existingCount -gt 0) {
        Write-Ok "existing database '$dbName' detected ($existingCount tables) - skipping install to protect your data"
        Set-Content -Path $MarkerPath -Value "pre-existing db, install skipped $(Get-Date -Format s)"
        # fall through to server launch
        $skipInstall = $true
    }

    if (-not $skipInstall) {
    Write-Info "first run detected, empty database - installing '$dbName' schema ..."

    # create database
    & $mysqlExe @mysqlArgs '-e' "CREATE DATABASE IF NOT EXISTS ``$dbName`` CHARACTER SET utf8 COLLATE utf8_unicode_ci;" 2>$null
    if ($LASTEXITCODE -ne 0) { Fail "Could not connect / create database. Check DB credentials in launcher.ini." }

    $dbArgs = $mysqlArgs + $dbName
    foreach ($group in @('login','game')) {
        $sqlDir = Join-Path $DistDir "db_installer\sql\$group"
        if (-not (Test-Path $sqlDir)) { Write-Info "no $group SQL folder, skipping"; continue }
        $files = Get-ChildItem -Path $sqlDir -Filter *.sql | Sort-Object Name
        Write-Info "$group : $($files.Count) tables"
        foreach ($f in $files) {
            Get-Content -Raw $f.FullName | & $mysqlExe @dbArgs 2>$null
            if ($LASTEXITCODE -ne 0) { Fail "Error importing $($f.Name)" }
        }
    }
    New-Item -ItemType File -Path $MarkerPath -Force | Out-Null
    Set-Content -Path $MarkerPath -Value "installed $(Get-Date -Format s)"
    Write-Ok "schema installed"
    } # end if (-not $skipInstall)
}

# ---- 4. Launch servers -----------------------------------------------------
Write-Head "4/4  Servers"

function Start-JavaServer($name, $workDir, $jarRelative) {
    $cfgPath = Join-Path $workDir 'java.cfg'
    $jarPath = Join-Path $workDir $jarRelative
    if (-not (Test-Path $jarPath)) { Fail "$name jar not found at $jarPath (did you build with 'ant' and copy the jar?)" }
    $params = ''
    if (Test-Path $cfgPath) { $params = (Get-Content -Raw $cfgPath).Trim() }
    $argLine = "$params -jar `"$jarRelative`""
    Write-Info "launching $name ..."
    $serverProcess = Start-Process -FilePath $serverLaunchExe -ArgumentList $argLine -WorkingDirectory $workDir -PassThru
    Register-LaunchedProcess $name $serverProcess ([System.IO.Path]::GetFileName($jarPath))
    Write-Ok "$name started (PID $($serverProcess.Id))"
}

if ($startLogin) {
    Start-JavaServer 'Login Server' (Join-Path $DistDir 'login') '..\libs\LoginServer.jar'
    Start-Sleep -Seconds 3   # let the login server bind before the game server registers
}
if ($startGame) {
    Start-JavaServer 'Game Server' (Join-Path $DistDir 'game') '..\libs\GameServer.jar'
}

if ($startBrain) {
    # The bundled pack ships the brain under dist\brain\ (setup_brain.bat lives
    # alongside fpc_brain.py + knowledge\). A raw source checkout instead keeps
    # setup_brain.bat at the project root, one level above dist\. Look in the
    # pack location first, then fall back to the source layout.
    $brainBat = Join-Path $DistDir 'brain\setup_brain.bat'
    if (-not (Test-Path $brainBat)) {
        $brainBat = Join-Path (Split-Path -Parent $DistDir) 'setup_brain.bat'
    }
    if (Test-Path $brainBat) {
        # --auto = non-interactive: setup_brain.bat starts the brain only if it has
        # already been configured (.env + .venv). If it has not, it prints a short
        # note and exits without prompting, so a normal boot never hangs waiting for
        # input. Configure the brain once by double-clicking setup_brain.bat, or use
        # the "Set up / configure brain" button in the Control Panel.
        Write-Info "launching FPC brain (auto - only if already configured) ..."
        $brainProcess = Start-Process -FilePath 'cmd.exe' -ArgumentList "/c `"$brainBat`" --auto" -PassThru
        Register-LaunchedProcess 'FPC Brain' $brainProcess ([System.IO.Path]::GetFileName($brainBat))
        Write-Ok "brain launch requested (PID $($brainProcess.Id))"
        Write-Info "if the brain is not set up yet, run setup_brain.bat once to configure it."
    } else {
        Write-Info "StartBrain=true but setup_brain.bat not found in brain\ or next to dist\ - skipping"
    }
}

# ---- 5. Optional game client -----------------------------------------------
# Launch the L2 client too, so one double-click starts the whole thing. It is
# deliberately NOT added to the launcher process registry: Stop-Server shuts down
# the servers and DB but leaves the game client open (it just disconnects).
if ($launchClient) {
    Write-Head "Game client"
    if ($clientExe -eq '') {
        Write-Info "LaunchClient=true but ClientExe is blank in launcher.ini - skipping."
    } elseif (-not (Test-Path $clientExe)) {
        Write-Info "game client not found at: $clientExe - skipping (fix ClientExe in launcher.ini)."
    } else {
        # Wait for the game server to actually bind its port (7777, stock CT_0
        # Interlude) so the client does not come up to a dead server list. If it
        # is not up in time, launch anyway - the user asked for the client.
        if ($startGame) {
            Write-Info "waiting for the game server (port 7777) before launching the client ..."
            # First boot compiles datapack scripts, which can take a while, so allow
            # a generous window; launch anyway if it is still not up by then.
            if (-not (Wait-Port 7777 180)) { Write-Info "game port 7777 not open yet - launching the client anyway." }
        }
        $clientDir = Split-Path -Parent $clientExe
        Write-Info "launching game client: $clientExe"
        Start-Process -FilePath $clientExe -WorkingDirectory $clientDir | Out-Null
        Write-Ok "game client launched"
    }
}

Write-Host ""
Write-Host "All requested components launched. Each server runs in its own window." -ForegroundColor Green
Write-Host "Close those windows (or run Stop-Server.bat) to shut down." -ForegroundColor Green
Write-Host ""
