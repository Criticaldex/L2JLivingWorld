<#
  L2 Offline Server - Update checker / applier (prototype)
  --------------------------------------------------------
  Checks the public release repo for a newer version and, on confirmation,
  downloads the small "patch" zip and overlays it on this install. The patch
  carries only files that changed (jar + datapack/config/html/tools), never the
  bundled database, so characters, items and adena are preserved.

  Releases come in pairs per version:
     vX.Y.Z          - full pack (fresh install: unzip and run)
     vX.Y.Z-patch    - patch overlay (existing install: what THIS script applies)

  This is the script-path updater, run from scripts\Check-Updates.bat. The
  compiled launcher (LivingWorld.exe) has its own updater in Updater.cs.

  Usage:
    update.ps1                 - check; if newer, ask before applying the patch
    update.ps1 -CheckOnly      - only report installed vs latest, never apply
    update.ps1 -Yes            - apply without the confirmation prompt (non-interactive)
#>

param(
    [switch]$CheckOnly,   # report only, never prompt or apply
    [switch]$Yes          # auto-confirm the apply (skip the y/N prompt)
)

$ErrorActionPreference = 'Stop'

# ---- paths -----------------------------------------------------------------
$LauncherDir = Split-Path -Parent $MyInvocation.MyCommand.Path      # ...\launcher
$InstallRoot = Split-Path -Parent $LauncherDir                       # folder with game\, login\, libs\, launcher\
$VersionPath = Join-Path $LauncherDir 'version.txt'
$StopScript  = Join-Path $LauncherDir 'stop.ps1'

# Public repo the update check looks at. Keep in sync with the launcher (Updater.cs).
$UpdateRepo  = 'Teravibes/L2-Living-Worlds'

function Info($t) { Write-Host "  [info] $t" -ForegroundColor Gray }
function Ok($t)   { Write-Host "  [ OK ] $t" -ForegroundColor Green }
function Warn($t) { Write-Host "  [warn] $t" -ForegroundColor Yellow }
function Die($t)  { Write-Host ""; Write-Host "  [FAIL] $t" -ForegroundColor Red; Write-Host ""; exit 1 }

# ---- version helpers -------------------------------------------------------
# Reduce a tag to its comparable base: drop a leading 'v' and any '-suffix'
# (so 'v0.1.12' and 'v0.1.12-patch' compare equal). Returns e.g. '0.1.12'.
function Get-BaseVersion([string]$tag) {
    $t = "$tag".Trim()
    if ($t -eq '') { return '' }
    if ($t.StartsWith('v') -or $t.StartsWith('V')) { $t = $t.Substring(1) }
    $dash = $t.IndexOf('-')
    if ($dash -ge 0) { $t = $t.Substring(0, $dash) }
    return $t
}

# Compare two base version strings numerically. Returns -1, 0 or 1.
function Compare-BaseVersion([string]$a, [string]$b) {
    $pa = @($a -split '\.'); $pb = @($b -split '\.')
    $n = [Math]::Max($pa.Count, $pb.Count)
    for ($i = 0; $i -lt $n; $i++) {
        $ia = 0; $ib = 0
        if ($i -lt $pa.Count) { [void][int]::TryParse($pa[$i], [ref]$ia) }
        if ($i -lt $pb.Count) { [void][int]::TryParse($pb[$i], [ref]$ib) }
        if ($ia -lt $ib) { return -1 }
        if ($ia -gt $ib) { return 1 }
    }
    return 0
}

# ============================================================================
Write-Host ""
Write-Host "==== L2 Offline Server - update check ====" -ForegroundColor Cyan

$installedTag  = if (Test-Path $VersionPath) { (Get-Content $VersionPath -Raw).Trim() } else { '' }
$installedBase = Get-BaseVersion $installedTag
if ($installedTag -eq '') {
    Warn "This install has no launcher\version.txt - treating it as older than any release."
    $installedBase = '0'
} else {
    Info "Installed version: $installedTag"
}

# ---- query the public releases --------------------------------------------
Info "Contacting github.com/$UpdateRepo ..."
try {
    $headers = @{ 'User-Agent' = 'L2-Updater'; 'Accept' = 'application/vnd.github+json' }
    $releases = Invoke-RestMethod -Uri "https://api.github.com/repos/$UpdateRepo/releases" -Headers $headers -TimeoutSec 20
} catch {
    Die "Could not reach the release server: $($_.Exception.Message)"
}
if (-not $releases -or @($releases).Count -eq 0) { Die "No releases are published yet at $UpdateRepo." }

# Find the newest base version across all (non-draft) releases, then locate the
# matching '-patch' release, which carries the overlay we actually apply.
$latestBase = '0'
foreach ($rel in @($releases)) {
    if ($rel.draft) { continue }
    $b = Get-BaseVersion $rel.tag_name
    if ($b -eq '') { continue }
    if ((Compare-BaseVersion $b $latestBase) -gt 0) { $latestBase = $b }
}
if ($latestBase -eq '0') { Die "No usable release tags found at $UpdateRepo." }

$latestTag = "v$latestBase"
Info "Latest version:    $latestTag"

# ---- ensure the one-click launcher exe -------------------------------------
# LivingWorld.exe is the compiled launcher. It is too large (bundled runtime) to
# ride the incremental patch, so it ships as its own release asset and is fetched
# here when an existing install is missing it or has an older copy. This is
# independent of the server version below: a tester whose server is current but
# who has never had the exe still gets it. Compares the local exe's FileVersion
# to the latest release.
function Get-ExeBaseVersion($exePath) {
    if (-not (Test-Path $exePath)) { return '' }
    try {
        $fv = (Get-Item $exePath).VersionInfo.FileVersion
        return Get-BaseVersion "$fv"
    } catch { return '' }
}

function Ensure-LauncherExe($releases, $latestTag, $latestBase) {
    $exePath = Join-Path $InstallRoot 'LivingWorld.exe'
    $localBase = Get-ExeBaseVersion $exePath
    $need = (-not (Test-Path $exePath)) -or ($localBase -eq '') -or ((Compare-BaseVersion $localBase $latestBase) -lt 0)
    if (-not $need) { Info "Launcher (LivingWorld.exe) is current."; return }

    $rel = @($releases) | Where-Object { $_.tag_name -eq $latestTag } | Select-Object -First 1
    if (-not $rel) { Info "No $latestTag release found for the launcher exe; it will arrive with the next full pack."; return }
    $asset = @($rel.assets) | Where-Object { $_.name -ieq 'LivingWorld.exe' } | Select-Object -First 1
    if (-not $asset) { Info "No LivingWorld.exe asset on $latestTag; the launcher will arrive with the next full pack."; return }

    if ($CheckOnly) { Info "A newer one-click launcher (LivingWorld.exe) is available; run the updater to fetch it."; return }

    Info "Downloading the one-click launcher (LivingWorld.exe) ..."
    $tmpExe = Join-Path $env:TEMP ("LivingWorld_" + [DateTime]::Now.ToString('yyyyMMdd_HHmmss') + '.exe')
    try {
        Invoke-WebRequest -Uri $asset.browser_download_url -OutFile $tmpExe -UseBasicParsing -TimeoutSec 300
    } catch {
        Warn "Could not download the launcher exe: $($_.Exception.Message). Start-Server.bat still works."
        return
    }
    try {
        Copy-Item $tmpExe -Destination $exePath -Force
        Ok "One-click launcher ready: double-click LivingWorld.exe (Start-Server.bat still works too)."
    } catch {
        Warn "Downloaded the launcher but could not replace $exePath (close it if it is open, then retry). Start-Server.bat still works."
    } finally {
        Remove-Item $tmpExe -Force -ErrorAction SilentlyContinue
    }
}

Ensure-LauncherExe $releases $latestTag $latestBase

# ---- up to date? -----------------------------------------------------------
$cmp = Compare-BaseVersion $installedBase $latestBase
if ($cmp -ge 0) {
    Ok "You are up to date."
    exit 0
}

Write-Host ""
Write-Host "  An update is available:  $installedTag  ->  $latestTag" -ForegroundColor Green

if ($CheckOnly) {
    Info "Run scripts\Check-Updates.bat (or the launcher's Update button) to download and apply it."
    exit 0
}

# ---- locate the patch asset on the '-patch' release ------------------------
$patchRelease = @($releases) | Where-Object { $_.tag_name -eq "$latestTag-patch" } | Select-Object -First 1
if (-not $patchRelease) {
    Warn "Found $latestTag but no companion '$latestTag-patch' release."
    Warn "Automatic update needs the patch overlay. Download the full $latestTag pack manually to upgrade."
    exit 0
}
$asset = @($patchRelease.assets) | Where-Object { $_.name -like '*.zip' } |
    Sort-Object { if ($_.name -like '*atch*') { 0 } else { 1 } } | Select-Object -First 1
if (-not $asset) {
    Die "The $latestTag-patch release has no .zip asset attached. Cannot auto-update."
}
$sizeMb = [math]::Round($asset.size / 1MB, 1)
Info "Patch download: $($asset.name)  ($sizeMb MB)"

# ---- confirm ---------------------------------------------------------------
if (-not $Yes) {
    Write-Host ""
    Write-Host "  This stops the server, then overlays the patch on:" -ForegroundColor Yellow
    Write-Host "    $InstallRoot" -ForegroundColor Yellow
    Write-Host "  Your database and customized configs are NOT touched." -ForegroundColor Yellow
    $answer = Read-Host "  Apply the update now? (y/N)"
    if ($answer -notmatch '^(y|yes)$') { Info "Update cancelled - nothing was changed."; exit 0 }
}

# ---- stop the server before overwriting its files --------------------------
if (Test-Path $StopScript) {
    Info "Stopping the server (if running) before applying files ..."
    # stop.ps1 calls exit, so run it as its own process rather than inline.
    $stop = Start-Process -FilePath 'powershell.exe' `
        -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$StopScript`"") `
        -Wait -PassThru
    if ($stop.ExitCode -ne 0) {
        Warn "Stop reported problems (exit $($stop.ExitCode)). Continue only if the server windows are closed."
        if (-not $Yes) {
            $go = Read-Host "  Continue applying anyway? (y/N)"
            if ($go -notmatch '^(y|yes)$') { Info "Update cancelled."; exit 0 }
        }
    }
} else {
    Warn "stop.ps1 not found - make sure the server is fully stopped before continuing."
}

# ---- download --------------------------------------------------------------
$tmpRoot = Join-Path $env:TEMP ("l2update_" + [DateTime]::Now.ToString('yyyyMMdd_HHmmss'))
New-Item -ItemType Directory -Path $tmpRoot -Force | Out-Null
$zipPath = Join-Path $tmpRoot 'patch.zip'
Info "Downloading patch ..."
try {
    Invoke-WebRequest -Uri $asset.browser_download_url -OutFile $zipPath -UseBasicParsing -TimeoutSec 300
} catch {
    Die "Download failed: $($_.Exception.Message)"
}
Ok "Downloaded $([math]::Round((Get-Item $zipPath).Length / 1MB, 1)) MB"

# ---- extract ---------------------------------------------------------------
$extractDir = Join-Path $tmpRoot 'extract'
Info "Extracting ..."
try {
    Expand-Archive -Path $zipPath -DestinationPath $extractDir -Force
} catch {
    Die "Could not extract the patch zip: $($_.Exception.Message)"
}

# ---- overlay onto the install ----------------------------------------------
# robocopy merges the extracted tree into the install, overwriting changed files
# and leaving everything else (the database, configs it doesn't carry) alone.
# Belt and braces: never copy a 'mariadb' folder even if one somehow appears in
# the zip, and drop the patch's own readme rather than littering the install.
# robocopy exit codes below 8 are success.
Info "Applying files to $InstallRoot ..."
robocopy $extractDir $InstallRoot /E /XD mariadb /XF PATCH-README.txt /NFL /NDL /NJH /NJS /NP /R:1 /W:1 | Out-Null
$code = $LASTEXITCODE
$global:LASTEXITCODE = 0
if ($code -ge 8) { Die "Applying the patch failed (robocopy code $code). The install may be partially updated." }
Ok "Patch applied."

# ---- stamp the new version -------------------------------------------------
Set-Content -Path $VersionPath -Value $latestTag -Encoding ASCII -NoNewline
Ok "Updated to $latestTag."

# ---- cleanup ---------------------------------------------------------------
Remove-Item -Path $tmpRoot -Recurse -Force -ErrorAction SilentlyContinue

Write-Host ""
Write-Host "  Done. Start the server again (Start-Server.bat) to run the updated build." -ForegroundColor Green
Write-Host ""
exit 0
