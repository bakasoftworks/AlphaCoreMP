# joiner-debrief.ps1 - AlphaCoreMP session report bundle.
# Runs on the JOINER's (seat-2 user's) machine; zero mod/code dependencies. Ships INSIDE the
# CC_Client mod folder and is normally invoked AUTOMATICALLY by join-cc.bat with -WaitForGameExit:
# it waits for the game to close, then collects the cheap facts only the far end has - their
# starsector.log (the game's own log) + the mod's cc-client.log sidelog (the [CC-PILOT-WATCH]
# rows moved there with the log divorce), env facts, ping to host - plus
# the sound self-check log and periodic non-Microsoft loaded-module snapshots of the
# live game - zips them to the Desktop. The joining user never touches PowerShell.
# -NoBundle (logs opt-out): wait for the game to exit (the bat's rejoin
# prompt needs that), then write NOTHING - no zip, no temp copies.
# Manual run (host-side debugging) still works:
#   powershell -ExecutionPolicy Bypass -File joiner-debrief.ps1 [-ServerHost <host>]
# Compatible with Windows PowerShell 5.1 and pwsh 7+.
param(
    [string]$GameDir = 'C:\Program Files (x86)\Fractal Softworks\Starsector',
    [string]$ServerHost = '',
    [string]$OutDir = '',
    [switch]$WaitForGameExit,
    [switch]$NoBundle,
    # Optional context: the sound self-check log to bundle + probe,
    # and launch-path labels (plain / kit) recorded into the manifest for support triage.
    [string]$SoundLog = '',
    [string]$Launch = '',
    [string]$KitLaunch = ''
)
$ErrorActionPreference = 'Continue'
if ($OutDir -eq '') {
    # GetFolderPath follows a OneDrive-redirected Desktop; the literal join is the fallback.
    $OutDir = [Environment]::GetFolderPath('Desktop')
    if (-not $OutDir) { $OutDir = Join-Path $env:USERPROFILE 'Desktop' }
}
try { $GameDir = (Resolve-Path -LiteralPath $GameDir -ErrorAction Stop).Path } catch {}

# Every process running out of the game folder (starsector.exe and/or its bundled jre java.exe).
# The folder is matched WITH its trailing separator: a sibling folder whose name merely starts with
# the game folder's name (Starsector-old) is not the game folder.
function Get-GameProcess([string]$root) {
    $prefix = $root.TrimEnd('\') + '\'
    Get-Process -ErrorAction SilentlyContinue | Where-Object {
        $p = $null
        try { $p = $_.Path } catch {}
        $p -and $p.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)
    }
}

# Module + game-exe snapshot state, filled during the wait loop below.
$moduleSet = New-Object 'System.Collections.Generic.HashSet[string]'
$gameExeSeen = New-Object 'System.Collections.Generic.HashSet[string]'
$moduleNote = ''

# One cheap pass over every game process: record the first-seen exe
# name+path, and one bounded row per loaded module that is NOT a Microsoft/Windows-dir DLL
# (third-party injected code is the signal). Fully guarded - a denied Modules read notes once.
function Add-GameModuleSnapshot([string]$root) {
    foreach ($proc in (Get-GameProcess $root)) {
        try {
            $pp = ''
            try { $pp = $proc.Path } catch {}
            $ex = $proc.ProcessName
            if ($pp) { [void]$script:gameExeSeen.Add(("{0} | {1}" -f $ex, $pp)) }
            else { [void]$script:gameExeSeen.Add($ex) }
            foreach ($mod in $proc.Modules) {
                if ($script:moduleSet.Count -ge 200) { break }
                try {
                    $mp = $mod.FileName
                    if ($mp -and $env:WINDIR -and $mp.StartsWith($env:WINDIR, [System.StringComparison]::OrdinalIgnoreCase)) { continue }
                    $co = ''; $ver = ''
                    try { $co = [string]$mod.FileVersionInfo.CompanyName } catch {}
                    try { $ver = [string]$mod.FileVersionInfo.FileVersion } catch {}
                    if ($co -match '^Microsoft') { continue }
                    $row = ("{0} | {1} | {2} | {3}" -f $mod.ModuleName, $mp, $co, $ver)
                    $row = ($row -replace '\s+', ' ').Trim()
                    if ($row.Length -gt 400) { $row = $row.Substring(0, 400) }
                    [void]$script:moduleSet.Add($row)
                } catch {}
            }
        } catch {
            if (-not $script:moduleNote) { $script:moduleNote = ("module read failed: " + ($_.Exception.Message -replace '\s+', ' ')) }
        }
    }
}

if ($WaitForGameExit) {
    Write-Host ""
    Write-Host "LEAVE THIS WINDOW OPEN while you play."
    if ($NoBundle) {
        Write-Host "(Session logs/report are turned OFF - nothing will be saved.)"
    } else {
        Write-Host "When you quit the game, a session report is saved to your Desktop automatically."
    }
    $deadline = (Get-Date).AddMinutes(3)
    $seen = $false
    while (-not $seen -and (Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 3
        $seen = [bool](Get-GameProcess $GameDir)
    }
    if ($seen) {
        # Every 3rd pass (~15 s) take a cheap loaded-module snapshot.
        $waitIter = 0
        while (Get-GameProcess $GameDir) {
            $waitIter++
            if (($waitIter % 3) -eq 0) { Add-GameModuleSnapshot $GameDir }
            Start-Sleep -Seconds 5
        }
        Write-Host ""
        if (-not $NoBundle) { Write-Host "Game closed - saving the session report..." }
    } else {
        Write-Host ""
        if (-not $NoBundle) {
            Write-Host "The game does not appear to be running - saving a session report anyway..."
        }
    }
}

# logs opt-out: the wait above already served the bat's rejoin prompt - leave
# without writing a single byte anywhere.
if ($NoBundle) {
    Write-Host "Session logs/report are OFF (join window checkbox) - nothing was saved."
    exit 0
}

# The stamp carries this process id after the clock second: two consoles that finish in the same
# second never share a work folder or a report zip.
$stamp = (Get-Date -Format 'yyyyMMdd-HHmmss') + "-$PID"
$work = Join-Path $env:TEMP "cc-debrief-$stamp"
# The work folder holds copies of the player's logs: it is removed in the finally below whatever
# happens on the way to the zip (the body up to the zip is deliberately not re-indented).
try {
New-Item -ItemType Directory -Path $work -Force | Out-Null
$m = New-Object System.Collections.Generic.List[string]
$m.Add("cc joiner debrief bundle $stamp")
$m.Add("game_dir=$GameDir")
$m.Add("os=$([System.Environment]::OSVersion.VersionString)")

# 1) the game log.
$log = Join-Path $GameDir 'starsector-core\starsector.log'
if (Test-Path $log) {
    Copy-Item $log (Join-Path $work 'starsector.log')
    $m.Add("starsector_log=copied bytes=$((Get-Item $log).Length)")
} else {
    $m.Add("starsector_log=NOT_FOUND path=$log")
}

# 1b) the mod's own sidelog (log divorce: the mod's [CC-PILOT-WATCH] rows live here, NOT in
# starsector.log). ONLY the live file is sent: the mod rotates its log once per game start, never by
# size, so cc-client.log.1 is always an EARLIER session - rows the player did not ask to send. The
# in-session size cap (96 MiB) restarts the live file IN PLACE (truncate, the session's first rows
# re-written, one "sidelog RESTART" row), never renaming it to .1, so the premise holds.
foreach ($ccName in @('cc-client.log')) {
    $ccLog = Join-Path $GameDir "starsector-core\$ccName"
    if (Test-Path $ccLog) {
        Copy-Item $ccLog (Join-Path $work $ccName)
        $m.Add("$($ccName -replace '[.]', '_')=copied bytes=$((Get-Item $ccLog).Length)")
    }
}

# 2) the JRE the game actually runs on - the kit's own jre-cc\ when bundled (DRAGON leg (b),
# join-cc.bat launches on it whenever it sits next to this script), else the install's.
$java = Join-Path $GameDir 'jre\bin\java.exe'
$kitJava = Join-Path $PSScriptRoot 'jre-cc\bin\java.exe'
if (Test-Path -LiteralPath $kitJava) { $java = $kitJava }
if (Test-Path $java) {
    # leak fix: join-cc.bat ferries the seat credential via JAVA_TOOL_OPTIONS and
    # this child inherits it — a console java.exe echoes "Picked up JAVA_TOOL_OPTIONS: ...
    # -Dcc.predict.matchPassword=..." on stderr, which rode $v into manifest.txt into the zip.
    # Clear the echoed-options vars for THIS process only (the game holds its own env snapshot),
    # and belt-drop any residual "Picked up ..." banner line (a tester's own _JAVA_OPTIONS is
    # not ours to publish either).
    # CRASH-1 (scan row 7): the launcher's THIRD options variable, JDK_JAVA_OPTIONS (the player's
    # own - e.g. proxy options), prints "NOTE: Picked up JDK_JAVA_OPTIONS: <value>", which the
    # "^Picked up " filter let through into java_version= - cleared and filtered like the other two.
    $env:JAVA_TOOL_OPTIONS = $null
    $env:_JAVA_OPTIONS = $null
    $env:JDK_JAVA_OPTIONS = $null
    # `java -version` writes to stderr: under Windows PowerShell 5.1 the 2>&1 lines arrive as
    # ErrorRecords whose Out-String rendering is the mangled "NativeCommandError" block - stringify
    # each line first ("$_" = the bare text on 5.1 and pwsh alike), then join.
    $v = ((& $java -version 2>&1) | ForEach-Object { "$_" } | Out-String)
    $v = (($v -split "`r?`n") | Where-Object { $_ -notmatch '^(NOTE: )?Picked up ' }) -join ' | '
    $v = $v.Trim()
    if ($v.Length -gt 300) { $v = $v.Substring(0, 300) }
    $m.Add("java_version=$v")
} else {
    $m.Add("java_version=bundled_jre_not_found")
}

# Manifest riders: launch labels, kit-JRE presence, the game's own
# reported Java + sound-init tail read from the copied logs, sidelog kit/sound rows, the module
# snapshot set, and the sound self-check probe result. All squeezed to single bounded lines.
$m.Add("launch=$Launch")
$m.Add("kit_launch=$KitLaunch")
$jreCc = Join-Path $PSScriptRoot 'jre-cc\bin\javaw.exe'
$m.Add("jre_cc_present=" + ((Test-Path -LiteralPath $jreCc).ToString().ToLower()))
$exeSeen = if ($gameExeSeen.Count -gt 0) { ($gameExeSeen -join ' ;; ') } else { 'none' }
$m.Add("game_exe_seen=$exeSeen")

$copiedLog = Join-Path $work 'starsector.log'
$gameJava = 'none'
if (Test-Path -LiteralPath $copiedLog) {
    $gj = Select-String -Path $copiedLog -Pattern 'Java version:' -ErrorAction SilentlyContinue | Select-Object -Last 1
    if ($gj) { $gameJava = ($gj.Line -replace '\s+', ' ').Trim() }
}
$m.Add("game_java=$gameJava")

$soundInit = 'none'
if (Test-Path -LiteralPath $copiedLog) {
    $si = Select-String -Path $copiedLog -Pattern 'OpenAL|Fatal:|nalListenerfv|sound initialization|UnsatisfiedLinkError' -ErrorAction SilentlyContinue | Select-Object -Last 6
    if ($si) {
        $rows = @()
        foreach ($ln in $si) {
            $t = ($ln.Line -replace '\s+', ' ').Trim()
            if ($t.Length -gt 200) { $t = $t.Substring(0, 200) }
            $rows += $t
        }
        $soundInit = ($rows -join ' || ')
    }
}
$m.Add("sound_init=$soundInit")

$sidelogKit = 'none'
$copiedCc = Join-Path $work 'cc-client.log'
if (Test-Path -LiteralPath $copiedCc) {
    $sk = Select-String -Path $copiedCc -Pattern 'kit launch|sound-retry|sound second chance' -ErrorAction SilentlyContinue | Select-Object -Last 6
    if ($sk) {
        $rows = @()
        foreach ($ln in $sk) {
            $t = ($ln.Line -replace '\s+', ' ').Trim()
            if ($t.Length -gt 200) { $t = $t.Substring(0, 200) }
            $rows += $t
        }
        $sidelogKit = ($rows -join ' || ')
    }
}
$m.Add("sidelog_kit=$sidelogKit")

if ($moduleSet.Count -gt 0) { $modJoined = ($moduleSet -join ' ;; ') }
elseif ($moduleNote) { $modJoined = $moduleNote }
else { $modJoined = 'none' }
$m.Add("modules_nonms=$modJoined")
if ($moduleNote) { $m.Add("modules_note=$moduleNote") }

$soundProbe = 'no sound log'
if ($SoundLog -ne '' -and (Test-Path -LiteralPath $SoundLog)) {
    $sp = Select-String -Path $SoundLog -Pattern '^probe RESULT' -ErrorAction SilentlyContinue | Select-Object -Last 3
    if ($sp) {
        $rows = @()
        foreach ($ln in $sp) {
            $t = ($ln.Line -replace '\s+', ' ').Trim()
            if ($t.Length -gt 200) { $t = $t.Substring(0, 200) }
            $rows += $t
        }
        $soundProbe = ($rows -join ' || ')
    } else {
        $soundProbe = 'none'
    }
}
$m.Add("sound_probe=$soundProbe")

# 3) mod list + enabled_mods.json.
$mods = Join-Path $GameDir 'mods'
if (Test-Path $mods) {
    $names = (Get-ChildItem $mods -Directory | ForEach-Object { $_.Name }) -join ', '
    $m.Add("mods_installed=$names")
    $enabled = Join-Path $mods 'enabled_mods.json'
    if (Test-Path $enabled) { Copy-Item $enabled $work }
}

# 4) ping to the host (only if given).
if ($ServerHost -ne '') {
    try {
        $pings = Test-Connection -ComputerName $ServerHost -Count 4 -ErrorAction Stop
        $times = @()
        foreach ($p in $pings) {
            if ($null -ne $p.PSObject.Properties['ResponseTime']) { $times += $p.ResponseTime }
            elseif ($null -ne $p.PSObject.Properties['Latency']) { $times += $p.Latency }
        }
        $avg = ($times | Measure-Object -Average).Average
        $m.Add("ping_host=$ServerHost avg_ms=$avg samples_ms=$($times -join ',')")
    } catch {
        $m.Add("ping_host=$ServerHost FAILED $($_.Exception.Message)")
    }
}

# The player's profile folder never rides the manifest or the sound-check log copy - ONE replacement,
# applied line by line to both (a module, game-exe or game-folder path under the profile carries the
# Windows account name, and the probe's own output filter misses native lines - a JVM crash banner,
# the sound library's own log - that can name a path under the profile).
$homeRe = ''
try { if ("$env:USERPROFILE".Length -ge 4) { $homeRe = [regex]::Escape("$env:USERPROFILE") } } catch {}

# 5) extra shipped files: the sound self-check log (profile folder
# replaced, see above; its bytes= is the SOURCE length), the game's vmparams, and the kit JVM args -
# each copied under a stable name when present.
if ($SoundLog -ne '' -and (Test-Path -LiteralPath $SoundLog)) {
    $slOut = @(Get-Content -LiteralPath $SoundLog -ErrorAction SilentlyContinue | ForEach-Object { if ($homeRe) { [regex]::Replace("$_", $homeRe, '%USERPROFILE%', 'IgnoreCase') } else { "$_" } })
    Set-Content -Path (Join-Path $work 'sound-check.log') -Value $slOut -ErrorAction SilentlyContinue
    $m.Add("sound_log=copied bytes=$((Get-Item -LiteralPath $SoundLog).Length)")
} else {
    $m.Add("sound_log=NOT_FOUND")
}
$vmparams = Join-Path $GameDir 'vmparams'
if (Test-Path -LiteralPath $vmparams) { Copy-Item -LiteralPath $vmparams (Join-Path $work 'vmparams.txt') -ErrorAction SilentlyContinue }
$kitArgs = Join-Path $PSScriptRoot 'kit-jvm.args'
if (Test-Path -LiteralPath $kitArgs) { Copy-Item -LiteralPath $kitArgs (Join-Path $work 'kit-jvm.args.txt') -ErrorAction SilentlyContinue }

$mOut = @($m | ForEach-Object { if ($homeRe) { [regex]::Replace("$_", $homeRe, '%USERPROFILE%', 'IgnoreCase') } else { "$_" } })
Set-Content -Path (Join-Path $work 'manifest.txt') -Value $mOut
$zip = Join-Path $OutDir "cc-debrief-$stamp.zip"
Compress-Archive -Path (Join-Path $work '*') -DestinationPath $zip -Force
} finally {
    # the work dir used to be left behind - every session parked a starsector.log
    # copy (tens of MB) under %TEMP% forever (found 2026-08-21). The zip has it all.
    Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
}
Write-Host ""
Write-Host "Session report saved:"
Write-Host "  $zip"
Write-Host "If the host asks for the session report, send back that ONE file."
