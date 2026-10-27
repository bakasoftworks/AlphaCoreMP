# explorer-env.ps1 - the Explorer-lane launch environment of the AlphaCoreMP joiner kit.
# Runs on the player's PC from join-cc.bat. On a PC where the game's sound library only loads in a
# game that Explorer started, the bat hands the start to Explorer (explorer-launch.ps1, same
# folder), and Explorer's children inherit the USER environment (HKCU\Environment) - so the launch
# settings ride the registry for the seconds until the game's own Java exists.
#
# ONE registry mechanism for the whole life of those values (scan row 7 BATCRED-1 / BATCRED-2):
# the write, the read-back, the snapshot of the player's own JAVA_TOOL_OPTIONS, the "is it ours"
# test, the delete and the restore all go through the .NET registry API in THIS script. The bat
# used to write through PowerShell and test / delete through reg.exe; on a PC whose policy refused
# only one of the two, the kit's values stayed in the user environment for good and the player's
# own were lost. Now a PC that cannot run this script never has anything written (the bat then
# starts the game directly), and a PC that can run it can also clean up.
#
# -Mode apply : reads the launch settings from THIS process's environment (JAVA_TOOL_OPTIONS,
#   _JAVA_OPTIONS, CC_PREDICT_TRANSPORT, CC_MATCH_DESCRIPTOR - never from a command line) and
#   writes them to the user environment. Order: (1) the "applied" mark under the snapshot key,
#   (2) the snapshot of the player's own JAVA_TOOL_OPTIONS (value + type), (3) ours. Every write is
#   read back; any failure undoes what was written (the clean below) and exits 1.
#   Exit 0 = applied and verified. Exit 1 = not applied; the undo ran (an "undo failed" row = it
#   could not finish: the mark stays and the next clean tries again).
# -Mode clean : removes what apply wrote and puts the player's own JAVA_TOOL_OPTIONS back. Ours =
#   the "applied" mark, a JAVA_TOOL_OPTIONS carrying -Dcc.kit.launch=explorer (an older kit wrote
#   no mark), and a _JAVA_OPTIONS whose VALUE is the kit's mods-folder override (Test-KitJopt: the
#   one shape the kit has ever written; an older kit on a PC that refused reg.exe left it with no
#   CC_KIT_JOPT mark, so the value decides, never the mark). A JAVA_TOOL_OPTIONS
#   the player changed since is theirs: it is left alone and the snapshot is dropped, never
#   re-applied over it. The player's value goes back OVER ours in ONE write (never delete, then
#   restore: a clean cut between the two lost it); the snapshot is dropped only after
#   that verified write. Exit 0 = nothing of ours remains. Exit 4 = something of ours could not be
#   removed (the mark stays, so the next run tries again).
# The values hold the server address, the pilot name and the handoff file's id - no credential
# (join-cc.bat's credential handoff). Prints single-line rows only, never a value.
# -EnvKey / -SnapKey: other HKCU subkeys standing in for the two real ones (the host's drill); the
# "environment changed" broadcast runs only for the real key.
# Windows PowerShell 5.1 compatible (no ternary, no null-coalescing).
param(
    [string]$Mode = 'clean',
    [string]$EnvKey = 'Environment',
    [string]$SnapKey = 'Software\CC_Client'
)
$ErrorActionPreference = 'Stop'
$marker = '-Dcc.kit.launch=explorer'
$noExpand = [Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames
$kindSz = [Microsoft.Win32.RegistryValueKind]::String
$kindExpand = [Microsoft.Win32.RegistryValueKind]::ExpandString

# raw (unexpanded) value, or $null when the value is absent
function Get-Raw($key, [string]$name) { return $key.GetValue($name, $null, $noExpand) }

# write + read back; throws when the stored value is not the one asked for
function Set-Checked($key, [string]$name, [string]$value, $kind) {
    $key.SetValue($name, $value, $kind)
    $back = Get-Raw $key $name
    if (($null -eq $back) -or ("$back" -cne $value)) { throw (New-Object InvalidOperationException('read-back mismatch')) }
}

# delete + read back; throws when the value is still there
function Remove-Checked($key, [string]$name) {
    $key.DeleteValue($name, $false)
    if ($null -ne (Get-Raw $key $name)) { throw (New-Object InvalidOperationException('value still present')) }
}

# Explorer re-reads its environment block on WM_SETTINGCHANGE; the user-scope setter sends it
# (apply sets CC_KIT_ENVSYNC, clean removes it again - one broadcast each).
function Send-EnvChanged($state) {
    if ($EnvKey -ne 'Environment') { return }
    try {
        [Environment]::SetEnvironmentVariable('CC_KIT_ENVSYNC', $state, 'User')
    } catch {
        Write-Output ('  explorer-env: environment broadcast failed (' + $_.Exception.GetType().Name + ').')
    }
}

# The kit's own _JAVA_OPTIONS value: the mods-folder override join-cc.bat sets (the one shape the
# kit has written since that lane first shipped). The VALUE decides whose it is - a CC_KIT_JOPT
# mark can be missing beside ours (an older kit on a PC that refused reg.exe) or stale beside the
# player's own (a clean cut between the two deletes).
function Test-KitJopt($v) {
    return ($v -is [string]) -and
        ($v.IndexOf('settings.paths.mods=', [StringComparison]::OrdinalIgnoreCase) -ge 0) -and
        ($v.IndexOf('\saves\common\cc_mods', [StringComparison]::OrdinalIgnoreCase) -ge 0)
}
# the one token join-cc.bat writes (quoted or not) - what the clean takes out of the value
$kitJoptToken = '"?-Dcom\.fs\.starfarer\.settings\.paths\.mods=[^"]*\\saves\\common\\cc_mods"?'

# Returns $true when nothing of ours remains.
function Invoke-Clean {
    $hkcu = [Microsoft.Win32.Registry]::CurrentUser
    # look first, READ-ONLY, at BOTH keys: a key is opened for writing only when something of ours
    # is in it (a key that refuses writes and holds nothing of ours is clean, not a failure)
    $applied = $false; $prev = $null; $prevType = $null; $snapRows = $false
    $snapLook = $hkcu.OpenSubKey($SnapKey)
    if ($null -ne $snapLook) {
        $applied = ($null -ne (Get-Raw $snapLook 'EnvApplied'))
        $prev = Get-Raw $snapLook 'JtoPrev'
        $prevType = Get-Raw $snapLook 'JtoPrevType'
        $snapRows = $applied -or ($null -ne $prev) -or ($null -ne $prevType)
    }
    $cur = $null; $joptOurs = $false; $joptMark = $false; $pathRows = $false
    $look = $hkcu.OpenSubKey($EnvKey)
    if ($null -ne $look) {
        $cur = Get-Raw $look 'JAVA_TOOL_OPTIONS'
        $joptOurs = Test-KitJopt (Get-Raw $look '_JAVA_OPTIONS')
        $joptMark = ($null -ne (Get-Raw $look 'CC_KIT_JOPT'))
        $pathRows = ($null -ne (Get-Raw $look 'CC_PREDICT_TRANSPORT')) -or ($null -ne (Get-Raw $look 'CC_MATCH_DESCRIPTOR'))
    }
    $oursInPlace = ($cur -is [string]) -and $cur.Contains($marker)
    # the player's own value is owed back when ours sits over it - or when the value is GONE
    # beside the mark and a snapshot (a clean that was cut after its delete, before its restore)
    $restore = ($null -ne $look) -and ($prev -is [string]) -and ($prev.Length -gt 0) -and
        ($oursInPlace -or ($applied -and ($null -eq $cur)))
    # the transport / descriptor rows carry no mark of their own: ours only beside one of the marks
    if ($oursInPlace -or $restore -or $joptOurs -or $joptMark -or ($applied -and $pathRows)) {
        $uenv = $hkcu.OpenSubKey($EnvKey, $true)
        if ($restore) {
            $kind = $kindSz
            if ("$prevType" -eq 'REG_EXPAND_SZ') { $kind = $kindExpand }
            Set-Checked $uenv 'JAVA_TOOL_OPTIONS' $prev $kind
        } elseif ($oursInPlace) {
            Remove-Checked $uenv 'JAVA_TOOL_OPTIONS'
        }
        if ($joptOurs) {
            # Only the kit's own token goes. A value the player edited by hand (their own options
            # typed beside ours) keeps the rest, in ONE write and under its own value kind; a value
            # that is nothing but our token is deleted. A value our token shape does not match at
            # all is the old case: it names the kit's mods folder, so it is removed whole.
            $joptCur = "$(Get-Raw $uenv '_JAVA_OPTIONS')"
            $joptRest = ([regex]::Replace($joptCur, $kitJoptToken, '', 'IgnoreCase')).Trim()
            if (($joptRest.Length -eq 0) -or ($joptRest -ceq $joptCur.Trim())) {
                Remove-Checked $uenv '_JAVA_OPTIONS'
            } else {
                Set-Checked $uenv '_JAVA_OPTIONS' $joptRest ($uenv.GetValueKind('_JAVA_OPTIONS'))
            }
        }
        if ($joptMark) { Remove-Checked $uenv 'CC_KIT_JOPT' }
        if ($applied -or $oursInPlace) {
            Remove-Checked $uenv 'CC_PREDICT_TRANSPORT'
            Remove-Checked $uenv 'CC_MATCH_DESCRIPTOR'
        }
        Send-EnvChanged $null
    }
    # the snapshot goes only now: after the verified restore, or when nothing of ours was in place
    # (it was restored already, or the player changed the value since)
    if ($snapRows) {
        $snap = $hkcu.OpenSubKey($SnapKey, $true)
        Remove-Checked $snap 'JtoPrev'
        Remove-Checked $snap 'JtoPrevType'
        Remove-Checked $snap 'EnvApplied'
    }
    return $true
}

function Invoke-Apply {
    $jto = $env:JAVA_TOOL_OPTIONS
    if ([string]::IsNullOrEmpty($jto) -or (-not $jto.Contains($marker))) {
        throw (New-Object InvalidOperationException('no Explorer-lane launch options in this window'))
    }
    $hkcu = [Microsoft.Win32.Registry]::CurrentUser
    $uenv = $hkcu.CreateSubKey($EnvKey)
    $snap = $hkcu.CreateSubKey($SnapKey)
    Set-Checked $snap 'EnvApplied' '1' $kindSz
    $own = Get-Raw $uenv 'JAVA_TOOL_OPTIONS'
    if ($null -ne $own) {
        $ownKind = $uenv.GetValueKind('JAVA_TOOL_OPTIONS')
        if (($ownKind -ne $kindSz) -and ($ownKind -ne $kindExpand)) {
            # not a text value: it could not be put back as it was, so it is never overwritten
            throw (New-Object InvalidOperationException('the user JAVA_TOOL_OPTIONS is not a text value'))
        }
        if (($own.Length -gt 0) -and (-not $own.Contains($marker))) {
            $typeName = 'REG_SZ'
            if ($ownKind -eq $kindExpand) { $typeName = 'REG_EXPAND_SZ' }
            Set-Checked $snap 'JtoPrevType' $typeName $kindSz
            Set-Checked $snap 'JtoPrev' $own $kindSz
        }
    }
    Set-Checked $uenv 'JAVA_TOOL_OPTIONS' $jto $kindSz
    # the mods root rides the same way - unless the player has their OWN _JAVA_OPTIONS user
    # variable (any value that is not the kit's override - Test-KitJopt): it is theirs and stays.
    # CC_KIT_JOPT is still written beside ours: an OLDER kit's clean removes ours only beside it.
    # Only the kit's own shape is ever written: apply never stores a value the clean would not claim.
    $jopt = $env:_JAVA_OPTIONS
    if (Test-KitJopt $jopt) {
        $ownJopt = Get-Raw $uenv '_JAVA_OPTIONS'
        if (($null -ne $ownJopt) -and (-not (Test-KitJopt $ownJopt))) {
            Write-Output '  NOTE: your own _JAVA_OPTIONS is set - the host''s mod set is not applied on the Explorer lane.'
        } else {
            Set-Checked $uenv 'CC_KIT_JOPT' '1' $kindSz
            Set-Checked $uenv '_JAVA_OPTIONS' $jopt $kindSz
        }
    } elseif (-not [string]::IsNullOrEmpty($jopt)) {
        Write-Output '  NOTE: unrecognised mod-folder setting - the host''s mod set is not applied on the Explorer lane.'
    }
    foreach ($name in @('CC_PREDICT_TRANSPORT', 'CC_MATCH_DESCRIPTOR')) {
        $v = [Environment]::GetEnvironmentVariable($name)
        if (-not [string]::IsNullOrEmpty($v)) { Set-Checked $uenv $name $v $kindSz }
    }
    Send-EnvChanged 'explorer'
}

if ($Mode -eq 'apply') {
    try {
        Invoke-Apply
        exit 0
    } catch {
        Write-Output ('  explorer-env: the launch settings could not be applied (' + $_.Exception.GetType().Name + ').')
        try { [void](Invoke-Clean) } catch { Write-Output ('  explorer-env: undo failed (' + $_.Exception.GetType().Name + ').') }
        exit 1
    }
}
if ($Mode -eq 'clean') {
    try {
        [void](Invoke-Clean)
        exit 0
    } catch {
        Write-Output ('  explorer-env: the launch settings could not be removed (' + $_.Exception.GetType().Name + ').')
        exit 4
    }
}
Write-Output '  explorer-env: unknown mode.'
exit 1
