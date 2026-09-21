<#
  Safe shutdown companion for launcher.ps1.
  Stops only launcher-owned processes recorded in .processes.json, then shuts
  down bundled MariaDB with the same launcher.ini settings used at startup.
#>

$ErrorActionPreference = 'Stop'

$LauncherDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$DistDir = Split-Path -Parent $LauncherDir
$IniPath = Join-Path $LauncherDir 'launcher.ini'
$ProcessRegistryPath = Join-Path $LauncherDir '.processes.json'
$hadFailure = $false

function Write-Ok($text) { Write-Host "  [ OK ] $text" -ForegroundColor Green }
function Write-Info($text) { Write-Host "  [info] $text" -ForegroundColor Gray }
function Write-Warn($text) {
    $script:hadFailure = $true
    Write-Host "  [FAIL] $text" -ForegroundColor Red
}

function Read-Ini($path) {
    $ini = @{}; $section = ''
    foreach ($line in Get-Content $path) {
        $value = $line.Trim()
        if ($value -eq '' -or $value.StartsWith('#') -or $value.StartsWith(';')) { continue }
        if ($value -match '^\[(.+)\]$') { $section = $Matches[1]; $ini[$section] = @{}; continue }
        $index = $value.IndexOf('=')
        if ($index -lt 0 -or $section -eq '') { continue }
        $key = $value.Substring(0, $index).Trim()
        $ini[$section][$key] = $value.Substring($index + 1).Trim()
    }
    return $ini
}

function Get-Ini($ini, $section, $key, $default = '') {
    if ($ini.ContainsKey($section) -and $ini[$section].ContainsKey($key) -and
        $ini[$section][$key] -ne '') {
        return $ini[$section][$key]
    }
    return $default
}

function Resolve-Rel($path) {
    if ($path -eq '') { return '' }
    if ([System.IO.Path]::IsPathRooted($path)) { return $path }
    return (Join-Path $DistDir $path)
}

function Test-LocalPort($port) {
    try {
        $client = New-Object System.Net.Sockets.TcpClient
        $result = $client.BeginConnect('127.0.0.1', [int]$port, $null, $null)
        $connected = $result.AsyncWaitHandle.WaitOne(600)
        if ($connected -and $client.Connected) {
            $client.EndConnect($result)
            $client.Close()
            return $true
        }
        $client.Close()
        return $false
    } catch {
        return $false
    }
}

function Wait-LocalPortClosed($port, $timeoutSeconds) {
    for ($index = 0; $index -lt $timeoutSeconds; $index++) {
        if (-not (Test-LocalPort $port)) { return $true }
        Start-Sleep -Seconds 1
    }
    return $false
}

function Stop-RecordedProcesses {
    Write-Host ""
    Write-Host "==== Launcher-owned processes ====" -ForegroundColor Cyan
    if (-not (Test-Path $ProcessRegistryPath)) {
        Write-Info "No process registry found; no Java or brain processes were targeted."
        Write-Info "For servers started by an older launcher, close their own windows manually."
        return
    }

    try {
        # Assign first, then wrap with @(). In Windows PowerShell 5.1 a multi-record
        # array is returned by ConvertFrom-Json as a single non-enumerated object, so
        # @(pipeline) would nest it into a one-element array and $record would bind to
        # the inner array (making $record.Id an Object[]). Wrapping the variable flattens
        # correctly for one record, many records, or none.
        $records = Get-Content -Raw $ProcessRegistryPath | ConvertFrom-Json
    } catch {
        Write-Warn "Process registry is unreadable; refusing to guess which processes belong to the server."
        return
    }

    foreach ($record in @($records)) {
        $processId = [int]$record.Id
        $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
        if (-not $process) {
            Write-Info "$($record.Role) is already stopped (stale PID $processId)."
            continue
        }

        try { $startTicks = "$($process.StartTime.ToUniversalTime().Ticks)" } catch { $startTicks = '' }
        if (($startTicks -ne "$($record.StartTicks)") -or
            ($process.ProcessName -ne "$($record.ProcessName)")) {
            Write-Warn "PID $processId no longer matches the recorded $($record.Role); it was not terminated."
            continue
        }

        try {
            $winProcess = Get-CimInstance Win32_Process -Filter "ProcessId = $processId"
            $commandLine = "$($winProcess.CommandLine)"
        } catch {
            Write-Warn "Could not verify the command line for $($record.Role) (PID $processId); it was not terminated."
            continue
        }
        if (($record.Marker -ne '') -and
            ($commandLine.IndexOf("$($record.Marker)", [StringComparison]::OrdinalIgnoreCase) -lt 0)) {
            Write-Warn "PID $processId lacks the recorded $($record.Role) command marker; it was not terminated."
            continue
        }

        & taskkill.exe /PID $processId /T /F *> $null
        if ($LASTEXITCODE -ne 0) {
            Write-Warn "Could not stop $($record.Role) (PID $processId)."
            continue
        }
        Write-Ok "$($record.Role) stopped (PID $processId)."
    }

    if (-not $script:hadFailure) {
        Remove-Item $ProcessRegistryPath -Force
    } else {
        Write-Info "The process registry was retained for inspection: $ProcessRegistryPath"
    }
}

function Stop-BundledDatabase {
    Write-Host ""
    Write-Host "==== Database ====" -ForegroundColor Cyan
    if (-not (Test-Path $IniPath)) {
        Write-Warn "launcher.ini not found at $IniPath; bundled MariaDB was not touched."
        return
    }

    $ini = Read-Ini $IniPath
    $dataDir = Get-Ini $ini 'database' 'DataDir' ''
    if ($dataDir -eq '') {
        Write-Info "External MySQL/MariaDB mode; database left running."
        return
    }

    $mysqlBin = Resolve-Rel (Get-Ini $ini 'database' 'MysqlBin' 'mariadb\bin')
    $dbHost = Get-Ini $ini 'database' 'Host' 'localhost'
    $dbPort = Get-Ini $ini 'database' 'Port' '3306'
    $dbUser = Get-Ini $ini 'database' 'User' 'root'
    $dbPassword = Get-Ini $ini 'database' 'Password' ''

    if (-not (Test-LocalPort $dbPort)) {
        Write-Info "Bundled MariaDB is already stopped on port $dbPort."
        return
    }

    $adminExe = $null
    foreach ($name in @('mariadb-admin.exe', 'mysqladmin.exe')) {
        $candidate = Join-Path $mysqlBin $name
        if (Test-Path $candidate) { $adminExe = $candidate; break }
    }
    if (-not $adminExe) {
        Write-Warn "MariaDB admin client not found in configured MysqlBin: $mysqlBin"
        return
    }

    $arguments = @('-h', $dbHost, "--port=$dbPort", '-u', $dbUser)
    if ($dbPassword -ne '') { $arguments += "--password=$dbPassword" }
    $arguments += 'shutdown'

    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & $adminExe @arguments *> $null
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = $previousPreference

    if ($exitCode -ne 0) {
        Write-Warn ("Bundled MariaDB shutdown failed on " + $dbHost + ":" + $dbPort + " (exit code $exitCode).")
        return
    }
    if (-not (Wait-LocalPortClosed $dbPort 15)) {
        Write-Warn "MariaDB accepted shutdown but port $dbPort is still open."
        return
    }
    Write-Ok "Bundled MariaDB stopped on port $dbPort."
}

Stop-RecordedProcesses
Stop-BundledDatabase

Write-Host ""
if ($hadFailure) {
    Write-Host "Shutdown completed with errors. Review the messages above." -ForegroundColor Red
    exit 1
}
Write-Host "Shutdown completed successfully." -ForegroundColor Green
exit 0
