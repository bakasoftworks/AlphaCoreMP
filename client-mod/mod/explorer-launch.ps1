# explorer-launch.ps1 - starts Starsector THROUGH the Explorer shell for the AlphaCoreMP joiner kit.
# Runs on the player's PC from join-cc.bat when the sound self-check found that the game's own
# OpenAL64.dll will not load inside a Java that a script started (exit code 20 there), while an
# Explorer-started game loads it fine. Explorer's children inherit the USER environment
# (HKCU\Environment), which the bat filled with the launch settings moments before.
#
# WHY a shortcut and not "explorer.exe <starsector.exe>": Explorer does NOT pass the caller's
# working directory on to the program it opens, and the game's thin starsector.exe launcher must
# start IN the install folder (it changes into starsector-core and runs jre\bin\java.exe from
# there; from anywhere else it shows an error box and never starts Java). A .lnk carries its own
# "Start in" folder and Explorer honours it - exactly what a Start-menu shortcut does.
#
# Steps: remember which game processes already exist -> mint the shortcut in %TEMP% -> open it
# via explorer.exe -> wait for a NEW java.exe/javaw.exe running out of the install (a game that
# was already open, or another program's Java, never counts) -> delete the shortcut.
# Exit 0 = the game's Java is up (the bat removes the launch settings from the registry now).
# Exit 3 = nothing appeared within TimeoutSec (the settings stay for the launch that may still come).
# Exit 2 = the launch cannot work or did not work: no starsector.exe under the root, the shortcut
#          could not be minted, or the launcher sat for FailAfterSec without starting Java (its
#          error box - it is closed here). The bat then starts the game directly instead.
# Prints single-line rows only, never an environment-variable value (the launch env carries a
# match credential). Windows PowerShell 5.1 compatible (no ternary, no null-coalescing).
param(
    [string]$Root = $env:CCROOT,
    [string]$Lnk = '',
    [int]$TimeoutSec = 60,
    [int]$FailAfterSec = 30
)
$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'

if ([string]::IsNullOrEmpty($Root)) { Write-Output '  explorer-launch: no install root given.'; exit 2 }
try { $Root = [IO.Path]::GetFullPath($Root).TrimEnd('\') } catch { Write-Output '  explorer-launch: bad install root.'; exit 2 }
$exe = $Root + '\starsector.exe'
if (-not (Test-Path -LiteralPath $exe)) { Write-Output '  explorer-launch: starsector.exe not found under the install root.'; exit 2 }
if ([string]::IsNullOrEmpty($Lnk)) {
    $tmp = $env:TEMP
    if ([string]::IsNullOrEmpty($tmp)) { $tmp = $env:TMP }
    if ([string]::IsNullOrEmpty($tmp)) { $tmp = $Root }
    # a name of this process's own: two consoles launching at once never share (or delete) one shortcut
    $Lnk = $tmp.TrimEnd('\') + '\cc-starsector-launch-' + $PID + '.lnk'
}
$prefix = $Root + '\'

# java/javaw/starsector processes whose image lives under the install (path prefix WITH the
# separator: a sibling folder sharing the name prefix is not the install). Path is null for a
# process this user may not open - such a process is invisible here, before and after alike.
function GameProcs {
    Get-Process -Name java, javaw, starsector -ErrorAction SilentlyContinue |
        Where-Object { $_.Path -and $_.Path.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase) }
}

$before = @{}
foreach ($p in @(GameProcs)) { $before[[int]$p.Id] = $true }

$minted = $false
try {
    $shell = New-Object -ComObject WScript.Shell
    $sc = $shell.CreateShortcut($Lnk)
    $sc.TargetPath = $exe
    $sc.WorkingDirectory = $Root
    $sc.WindowStyle = 1
    $sc.Save()
    $minted = Test-Path -LiteralPath $Lnk
} catch { $minted = $false }
if (-not $minted) {
    Remove-Item -LiteralPath $Lnk -Force -ErrorAction SilentlyContinue
    Write-Output '  explorer-launch: could not create the launch shortcut.'
    exit 2
}

$rc = 3
try {
    & explorer.exe $Lnk
    $sw = [Diagnostics.Stopwatch]::StartNew()
    while ($true) {
        Start-Sleep -Seconds 2
        $fresh = @(GameProcs | Where-Object { -not $before.ContainsKey([int]$_.Id) })
        $secs = [int]$sw.Elapsed.TotalSeconds
        $java = @($fresh | Where-Object { $_.ProcessName -ne 'starsector' })
        if ($java.Count -gt 0) {
            Write-Output ('  explorer-launch: the game''s Java is up after ' + $secs + ' s.')
            $rc = 0
            break
        }
        $launcher = @($fresh | Where-Object { $_.ProcessName -eq 'starsector' })
        if ($launcher.Count -gt 0 -and $secs -ge $FailAfterSec) {
            Write-Output ('  explorer-launch: the launcher started but no Java appeared in ' + $secs + ' s - closing it.')
            foreach ($l in $launcher) { Stop-Process -Id $l.Id -Force -ErrorAction SilentlyContinue }
            $rc = 2
            break
        }
        if ($secs -ge $TimeoutSec) {
            Write-Output ('  explorer-launch: nothing appeared within ' + $secs + ' s.')
            $rc = 3
            break
        }
    }
} finally {
    Remove-Item -LiteralPath $Lnk -Force -ErrorAction SilentlyContinue
}
exit $rc
