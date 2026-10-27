# sound-check.ps1 - Windows-side sound-init diagnostic for the AlphaCoreMP joiner kit.
# Ships inside the kit and runs on the player's PC in the
# background from join-cc.bat; it appends loader/security/OS facts and a direct LoadLibrary
# reproduction to the shared sound-check log so the host can read WHY the game's own
# OpenAL64.dll fails to load (Windows error 126, "module not found") inside a script-started
# Java while it loads fine when the game is started from Explorer. Never throws, always exits 0,
# bounded runtime (~60 s worst case), no admin needed; a denied read produces one row, never a
# crash. Prints NO environment-variable values (the launch env carries a match credential).
# Every output line is single-line printable ASCII, <= 400 chars; at most ~250 rows.
# Windows PowerShell 5.1 compatible; also runs on pwsh 7 (no ternary, no null-coalescing).
param(
    [string]$GameDir = '',
    [string]$Log = '',
    [string]$Launch = ''
)

$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'

# ---- log plumbing --------------------------------------------------------------------------
# This setup runs before the big never-throw try below, so every step is guarded: a null
# $env:TEMP or $PSScriptRoot would raise a TERMINATING parameter-binding error on Join-Path
# (which $ErrorActionPreference='Continue' does not swallow) and skip the final RESULT row +
# exit 0. Join-Path is therefore only ever called with a base coerced non-null first.
if ([string]::IsNullOrEmpty($Log)) {
    $tmpBase = $env:TEMP
    if ([string]::IsNullOrEmpty($tmpBase)) { $tmpBase = $env:TMP }
    if ([string]::IsNullOrEmpty($tmpBase)) { $tmpBase = '.' }
    $Log = Join-Path $tmpBase 'cc-sound-check.log'
}
try {
    $logDir = Split-Path -Parent $Log
    if ($logDir -and -not (Test-Path -LiteralPath $logDir)) {
        New-Item -ItemType Directory -Force -Path $logDir | Out-Null
    }
} catch {}
if ([string]::IsNullOrEmpty($GameDir)) {
    $srBase = $PSScriptRoot
    if ([string]::IsNullOrEmpty($srBase)) { $srBase = '.' }
    try { $GameDir = (Resolve-Path -LiteralPath (Join-Path $srBase '..\..')).Path } catch { $GameDir = (Join-Path $srBase '..\..') }
} else {
    # join-cc.bat passes "<kit folder>..\.." - resolve it, or the working-folder test (isCore) and the
    # folder classes compare against a path that still holds the ".." and never match
    try { $GameDir = [System.IO.Path]::GetFullPath($GameDir) } catch {}
}

$script:rowCount = 0
$script:rowCap = 250

# CONTENT LAW: this log rides the session report to the HOST, who may
# be a stranger to the player. A row states what decides whether the game's sound library loads -
# loader policy switches, the antivirus product NAME, the kit's own files, the LoadLibrary results -
# and gives everything else about the PC as a COUNT or a yes/no: no exclusion list, no AutoRun or
# policy value, no event text, no security-product or module path. Every row also has the player's
# profile folder replaced by %USERPROFILE% (San below - the one seam every row of THIS script
# passes; the Java probe's rows in the same file make the same replacement on their side).
$script:homeRe = ''
try { if ("$env:USERPROFILE".Length -ge 4) { $script:homeRe = [regex]::Escape("$env:USERPROFILE") } } catch {}

function San([string]$s) {
    if ($null -eq $s) { return '' }
    if ($script:homeRe) { $s = [regex]::Replace($s, $script:homeRe, '%USERPROFILE%', 'IgnoreCase') }
    $s = $s -replace '[\r\n\t]', ' '
    $sb = New-Object System.Text.StringBuilder
    foreach ($ch in $s.ToCharArray()) {
        $code = [int][char]$ch
        if ($code -ge 32 -and $code -le 126) { [void]$sb.Append($ch) } else { [void]$sb.Append('.') }
    }
    $out = $sb.ToString()
    if ($out.Length -gt 400) { $out = $out.Substring(0, 400) }
    return $out
}

# counted middle rows (respects the cap so a runaway loop cannot flood the file)
function Row([string]$s) {
    try {
        if ($script:rowCount -ge $script:rowCap) { return }
        Add-Content -LiteralPath $Log -Value (San $s) -Encoding ascii -ErrorAction SilentlyContinue
        $script:rowCount = $script:rowCount + 1
    } catch {}
}

# uncounted rows: the mandatory first (start) and last (RESULT) lines always land
function ForceRow([string]$s) {
    try { Add-Content -LiteralPath $Log -Value (San $s) -Encoding ascii -ErrorAction SilentlyContinue } catch {}
}

function B($v) { if ($v) { return 'true' } else { return 'false' } }

# a folder as a CLASS, never its path (the content law: a PATH entry is the player's own)
function DirClass([string]$d) {
    try {
        $x = "$d".TrimEnd('\')
        foreach ($c in @(@{ n = 'windows'; p = $env:WINDIR }, @{ n = 'game'; p = $GameDir }, @{ n = 'profile'; p = $env:USERPROFILE },
                @{ n = 'programfiles'; p = $env:ProgramFiles }, @{ n = 'programfiles'; p = ${env:ProgramFiles(x86)} })) {
            $p = "$($c.p)".TrimEnd('\')
            if ($p.Length -ge 3 -and $x.StartsWith($p, [System.StringComparison]::OrdinalIgnoreCase)) { return $c.n }
        }
    } catch {}
    return 'other'
}

# ---- PE header helpers ---------------------------------------------------------------------
function Get-PeMachine([string]$path) {
    try {
        $fs = New-Object System.IO.FileStream($path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
        try {
            if ($fs.Length -lt 0x40) { return '?' }
            $buf = New-Object byte[] 0x40
            [void]$fs.Read($buf, 0, 0x40)
            $e = [System.BitConverter]::ToInt32($buf, 0x3C)
            if ($e -le 0 -or ($e + 6) -gt $fs.Length) { return '?' }
            $fs.Position = $e
            $hb = New-Object byte[] 6
            [void]$fs.Read($hb, 0, 6)
            if ($hb[0] -ne 0x50 -or $hb[1] -ne 0x45) { return '?' }
            $m = [System.BitConverter]::ToUInt16($hb, 4)
            if ($m -eq 0x8664) { return 'x64' }
            elseif ($m -eq 0x14c) { return 'x86' }
            elseif ($m -eq 0xaa64) { return 'arm64' }
            else { return ('0x' + $m.ToString('x')) }
        } finally { $fs.Close() }
    } catch { return '?' }
}

function RvaToOff([uint32]$rva, $secs) {
    foreach ($s in $secs) {
        if ($rva -ge $s.va -and $rva -lt ($s.va + $s.span)) { return [int]($rva - $s.va + $s.prd) }
    }
    return -1
}

function Get-PeImports([string]$path, [int]$maxNames) {
    $names = @()
    try {
        $fi = Get-Item -LiteralPath $path -ErrorAction Stop
        if ($fi.Length -gt 33554432) { return $names }
        $b = [System.IO.File]::ReadAllBytes($path)
        if ($b.Length -lt 0x40) { return $names }
        $e = [System.BitConverter]::ToInt32($b, 0x3C)
        if ($e -le 0 -or ($e + 24) -ge $b.Length) { return $names }
        if ($b[$e] -ne 0x50 -or $b[$e + 1] -ne 0x45) { return $names }
        $numSec = [System.BitConverter]::ToUInt16($b, $e + 6)
        $optSize = [System.BitConverter]::ToUInt16($b, $e + 20)
        $optOff = $e + 24
        if (($optOff + 2) -ge $b.Length) { return $names }
        $magic = [System.BitConverter]::ToUInt16($b, $optOff)
        $ddOff = 0
        if ($magic -eq 0x20b) { $ddOff = $optOff + 112 }
        elseif ($magic -eq 0x10b) { $ddOff = $optOff + 96 }
        else { return $names }
        if (($ddOff + 12) -ge $b.Length) { return $names }
        $impRva = [System.BitConverter]::ToUInt32($b, $ddOff + 8)
        if ($impRva -eq 0) { return $names }
        $secOff = $optOff + $optSize
        $secs = @()
        for ($i = 0; $i -lt $numSec; $i++) {
            $so = $secOff + ($i * 40)
            if (($so + 40) -gt $b.Length) { break }
            $va = [System.BitConverter]::ToUInt32($b, $so + 12)
            $vsz = [System.BitConverter]::ToUInt32($b, $so + 8)
            $rsz = [System.BitConverter]::ToUInt32($b, $so + 16)
            $prd = [System.BitConverter]::ToUInt32($b, $so + 20)
            $span = $vsz
            if ($rsz -gt $span) { $span = $rsz }
            $secs += [pscustomobject]@{ va = $va; span = $span; prd = $prd }
        }
        $impOff = RvaToOff $impRva $secs
        if ($impOff -lt 0 -or $impOff -ge $b.Length) { return $names }
        $cur = $impOff
        $guard = 0
        while ($true) {
            if ($guard -ge $maxNames) { break }
            if (($cur + 20) -gt $b.Length) { break }
            $origThunk = [System.BitConverter]::ToUInt32($b, $cur + 0)
            $nameRva = [System.BitConverter]::ToUInt32($b, $cur + 12)
            if ($nameRva -eq 0 -and $origThunk -eq 0) { break }
            if ($nameRva -ne 0) {
                $noff = RvaToOff $nameRva $secs
                if ($noff -ge 0 -and $noff -lt $b.Length) {
                    $sbn = New-Object System.Text.StringBuilder
                    $j = $noff
                    while ($j -lt $b.Length -and $b[$j] -ne 0 -and ($j - $noff) -lt 128) {
                        [void]$sbn.Append([char]$b[$j]); $j = $j + 1
                    }
                    $nm = $sbn.ToString()
                    if ($nm.Length -gt 0) { $names += $nm }
                }
            }
            $cur = $cur + 20
            $guard = $guard + 1
        }
    } catch {}
    return $names
}

# ---- registry helpers ----------------------------------------------------------------------
function FmtRegVal($v) {
    if ($null -eq $v) { return '' }
    if ($v -is [byte[]]) {
        $sb = New-Object System.Text.StringBuilder
        $n = 0
        foreach ($by in $v) {
            if ($n -ge 32) { [void]$sb.Append('..'); break }
            [void]$sb.Append($by.ToString('x2')); $n = $n + 1
        }
        return ('0x' + $sb.ToString())
    }
    if ($v -is [System.Array]) { return (($v | ForEach-Object { "$_" }) -join ',') }
    return ("$v")
}

function RegGetSingle($path, $name) {
    try {
        $k = Get-Item -LiteralPath $path -ErrorAction Stop
        $names = $k.GetValueNames()
        if ($names -contains $name) { return $k.GetValue($name) }
        return $null
    } catch { return $null }
}

# returns $null on absent/denied key, else an array of "name=value" strings (bounded)
function RegValues($path, $max) {
    $out = @()
    try {
        $k = Get-Item -LiteralPath $path -ErrorAction Stop
        $names = $k.GetValueNames()
        $c = 0
        foreach ($vn in $names) {
            if ($c -ge $max) { $out += '(more..)'; break }
            $label = $vn
            if ([string]::IsNullOrEmpty($label)) { $label = '(default)' }
            $out += ($label + '=' + (FmtRegVal ($k.GetValue($vn))))
            $c = $c + 1
        }
    } catch { return $null }
    return $out
}

# ---- native loader (P/Invoke) --------------------------------------------------------------
$loaderReady = $false
try {
    Add-Type -Namespace CcSnd -Name Loader -MemberDefinition @'
[System.Runtime.InteropServices.DllImport("kernel32.dll", SetLastError=true, CharSet=System.Runtime.InteropServices.CharSet.Unicode)]
public static extern System.IntPtr LoadLibraryExW(string lpFileName, System.IntPtr hFile, uint dwFlags);
[System.Runtime.InteropServices.DllImport("kernel32.dll", SetLastError=true, CharSet=System.Runtime.InteropServices.CharSet.Ansi)]
public static extern System.IntPtr LoadLibraryA(string lpFileName);
[System.Runtime.InteropServices.DllImport("kernel32.dll", SetLastError=true)]
public static extern bool FreeLibrary(System.IntPtr hModule);
[System.Runtime.InteropServices.DllImport("kernel32.dll", SetLastError=true, CharSet=System.Runtime.InteropServices.CharSet.Unicode)]
public static extern bool MoveFileExW(string lpExistingFileName, string lpNewFileName, uint dwFlags);
'@ -ErrorAction Stop
    $loaderReady = $true
} catch {}

function LlMsg([int]$err) {
    if ($err -lt 0) { return 'pinvoke-failed' }
    try { return (New-Object System.ComponentModel.Win32Exception($err)).Message } catch { return '' }
}

function LoadEx([string]$path, [uint32]$flags) {
    $h = [IntPtr]::Zero
    $err = -1
    try {
        $h = [CcSnd.Loader]::LoadLibraryExW($path, [IntPtr]::Zero, $flags)
        $err = [System.Runtime.InteropServices.Marshal]::GetLastWin32Error()
    } catch { $err = -1 }
    $ok = ($h -ne [IntPtr]::Zero)
    # On success GetLastError is stale (Win32 does not reset it), so report err=0.
    if ($ok) { try { [void][CcSnd.Loader]::FreeLibrary($h) } catch {}; $err = 0 }
    return [pscustomobject]@{ ok = $ok; err = $err; msg = (LlMsg $err) }
}

function LoadA([string]$path) {
    $h = [IntPtr]::Zero
    $err = -1
    try {
        $h = [CcSnd.Loader]::LoadLibraryA($path)
        $err = [System.Runtime.InteropServices.Marshal]::GetLastWin32Error()
    } catch { $err = -1 }
    $ok = ($h -ne [IntPtr]::Zero)
    if ($ok) { try { [void][CcSnd.Loader]::FreeLibrary($h) } catch {}; $err = 0 }
    return [pscustomobject]@{ ok = $ok; err = $err; msg = (LlMsg $err) }
}

# true if OpenAL64.dll is already mapped into this process (it self-pins in DllMain, so once any
# absolute-path load makes it resident a later bare-name/relative load matches it by base name).
function OalResident() {
    try {
        foreach ($m in (Get-Process -Id $PID).Modules) {
            if ("$($m.ModuleName)" -ieq 'OpenAL64.dll') { return $true }
        }
    } catch {}
    return $false
}

function LlFmt($res) { if ($res.ok) { return 'ok' } else { return ('err ' + $res.err) } }

function LlRow([string]$variant, [string]$path, $res) {
    Row ('oscheck loadlib ' + $variant + ' path=' + $path + ' ok=' + (B $res.ok) + ' err=' + $res.err + ' msg=' + $res.msg)
}

# ---- process-mitigation summariser ---------------------------------------------------------
function MitigSummary($m) {
    $on = @()
    foreach ($grp in $m.PSObject.Properties) {
        $gv = $grp.Value
        if ($null -eq $gv) { continue }
        if ($gv -is [string] -or $gv.GetType().IsValueType) {
            if ("$gv" -match '^(ON|AUDIT|ENABLE|1|True)$') { $on += $grp.Name }
            continue
        }
        foreach ($p in $gv.PSObject.Properties) {
            if ($null -eq $p.Value) { continue }
            $pv = "$($p.Value)"
            if ($pv -match '^(ON|AUDIT|ENABLE|1|True)$') { $on += ($grp.Name + '.' + $p.Name) }
        }
    }
    return ($on -join ',')
}

function MitigRun([string]$scope, [string]$name) {
    try {
        if ($scope -eq 'system') { $m = Get-ProcessMitigation -System -ErrorAction Stop }
        else { $m = Get-ProcessMitigation -Name $name -ErrorAction Stop }
        $onList = MitigSummary $m
        if ([string]::IsNullOrEmpty($onList)) { $onList = 'none-on' }
        Row ('oscheck mitig scope=' + $scope + ' on=' + $onList)
        return $onList
    } catch {
        Row ('oscheck mitig scope=' + $scope + ' unavailable=' + $_.Exception.Message)
        return 'unavailable'
    }
}

# ---- event-log scanner ---------------------------------------------------------------------
$script:matchRe = '(?i)starsector|openal|java|lwjgl|jre-cc|CC_Client|cc-client'
function ScanLog([string]$label, $hash) {
    try {
        $evts = @(Get-WinEvent -FilterHashtable $hash -MaxEvents 200 -ErrorAction Stop)
        $nm = 0; $shown = 0; $matched = 0
        foreach ($ev in $evts) {
            $msg = ''
            if ($null -ne $ev.Message) { $msg = ($ev.Message -replace '\s+', ' ') }
            if ($msg -match $script:matchRe) {
                $matched = $matched + 1
                if ($shown -lt 12) {
                    # time + event id only - the event TEXT (paths, threat names) never rides the report
                    Row ('oscheck event log=' + $label + ' ' + $ev.TimeCreated.ToString('yyyy-MM-ddTHH:mm:ss') + '|' + $ev.Id)
                    $shown = $shown + 1
                }
            } else { $nm = $nm + 1 }
        }
        Row ('oscheck event log=' + $label + ' matched=' + $matched + ' nonmatch=' + $nm + ' scanned=' + $evts.Count)
        return $matched
    } catch {
        Row ('oscheck event log=' + $label + ' none=' + $_.Exception.Message)
        return 0
    }
}

# ---- write-access probe --------------------------------------------------------------------
function WriteTest([string]$label, [string]$dir) {
    try {
        if ([string]::IsNullOrEmpty($dir) -or -not (Test-Path -LiteralPath $dir)) { Row ('oscheck writetest target=' + $label + ' dir-absent'); return }
        $tf = Join-Path $dir ('cc-oscheck-wt-' + [guid]::NewGuid().ToString('N').Substring(0, 8) + '.tmp')
        $allowed = $false
        try {
            Set-Content -LiteralPath $tf -Value 'x' -ErrorAction Stop
            $allowed = $true
            Remove-Item -LiteralPath $tf -Force -ErrorAction SilentlyContinue
        } catch { $allowed = $false }
        if ($allowed) { Row ('oscheck writetest target=' + $label + ' allowed') } else { Row ('oscheck writetest target=' + $label + ' denied') }
    } catch { Row ('oscheck writetest target=' + $label + ' error=' + $_.Exception.Message) }
}

# ============================================================================================
# result accumulator (filled as sections run; drives the final RESULT row)
$acc = @{
    loadlib_abs = 'n/a'; loadlib_rel = 'n/a'; loadlib_datafile = 'n/a'; loadlib_tempcopy = 'n/a'
    imports_missing = 'n/a'; cwdpolicy = 'absent'; ifeo = 'none'; sac = 'n/a'; wdac = 'n/a'
    applocker_dll = 'n/a'; srp = 'absent'; av = 'none'; mitig_java = 'none'; defender_hits = '0'
    path_shadow = '0'; dll_signed = 'n/a'; dll_motw = 'none'; cwd_iscore = 'n/a'
}
$core = Join-Path $GameDir 'starsector-core'
$kit = $PSScriptRoot
$oalPath = Join-Path $core 'native\windows\OpenAL64.dll'
$lwjglPath = Join-Path $core 'native\windows\lwjgl64.dll'

try {

    # -- start row ---------------------------------------------------------------------------
    $caption = '?'; $build = '?'; $arch = '?'
    try {
        $osc = Get-CimInstance -ClassName Win32_OperatingSystem -ErrorAction Stop
        $caption = "$($osc.Caption)"; $build = "$($osc.BuildNumber)"; $arch = "$($osc.OSArchitecture)"
    } catch {
        try {
            $caption = [Environment]::OSVersion.VersionString
            if ([Environment]::Is64BitOperatingSystem) { $arch = '64-bit' } else { $arch = '32-bit' }
        } catch {}
    }
    $elev = 'false'
    try {
        $wp = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
        $elev = (B ($wp.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)))
    } catch {}
    ForceRow ('oscheck start launch=' + $Launch + ' ps=' + "$($PSVersionTable.PSVersion)" + ' elevated=' + $elev + ' os=' + $caption + '/' + $build + ' arch=' + $arch)
    if (-not $loaderReady) {
        Row 'oscheck loadlib error=addtype-failed (native LoadLibrary reproduction skipped)'
        $acc.loadlib_abs = 'err addtype'; $acc.loadlib_rel = 'err addtype'
        $acc.loadlib_datafile = 'err addtype'; $acc.loadlib_tempcopy = 'err addtype'
    }

    # -- 7: file identity --------------------------------------------------------------------
    try {
        $fileList = @(
            @{ n = 'OpenAL64.dll'; p = $oalPath },
            @{ n = 'OpenAL32.dll'; p = (Join-Path $core 'native\windows\OpenAL32.dll') },
            @{ n = 'lwjgl64.dll'; p = $lwjglPath },
            @{ n = 'core-stray-OpenAL64'; p = (Join-Path $core 'OpenAL64.dll') },
            @{ n = 'jre-java.exe'; p = (Join-Path $GameDir 'jre\bin\java.exe') },
            @{ n = 'starsector.exe'; p = (Join-Path $GameDir 'starsector.exe') },
            @{ n = 'kit-javaw.exe'; p = (Join-Path $kit 'jre-cc\bin\javaw.exe') },
            @{ n = 'kit-java.exe'; p = (Join-Path $kit 'jre-cc\bin\java.exe') }
        )
        $sigWanted = @('OpenAL64.dll', 'OpenAL32.dll', 'lwjgl64.dll', 'core-stray-OpenAL64')
        foreach ($f in $fileList) {
            try {
                if (-not (Test-Path -LiteralPath $f.p)) { Row ('oscheck file name=' + $f.n + ' exists=false path=' + $f.p); continue }
                $fi = Get-Item -LiteralPath $f.p -ErrorAction Stop
                $md5 = '?'
                try { $md5 = (Get-FileHash -LiteralPath $f.p -Algorithm MD5 -ErrorAction Stop).Hash } catch { $md5 = 'err' }
                $pe = Get-PeMachine $f.p
                # Authenticode validation can trigger online CRL/OCSP revocation lookups (a network
                # call with multi-second per-endpoint timeouts on a firewalled box), so restrict it
                # to the DLLs actually under investigation - NOT the signed exes (java/starsector),
                # which would add unbounded latency for no signal and threaten the runtime budget.
                $sigStatus = 'skip'; $cn = ''
                if ($sigWanted -contains $f.n) {
                    try {
                        $sig = Get-AuthenticodeSignature -LiteralPath $f.p -ErrorAction Stop
                        $sigStatus = "$($sig.Status)"
                        if ($sig.SignerCertificate) {
                            $subj = "$($sig.SignerCertificate.Subject)"
                            $mm = [regex]::Match($subj, 'CN=("[^"]*"|[^,]+)')
                            if ($mm.Success) { $cn = ($mm.Groups[1].Value -replace '"', '') } else { $cn = ($subj -replace '"', '') }
                        }
                    } catch { $sigStatus = 'err' }
                }
                $zone = 'none'
                try {
                    $zi = Get-Content -LiteralPath $f.p -Stream Zone.Identifier -ErrorAction Stop
                    $zt = ($zi -join ' ')
                    $zm = [regex]::Match($zt, 'ZoneId=(\d+)')
                    if ($zm.Success) { $zone = $zm.Groups[1].Value } else { $zone = 'present' }
                } catch { $zone = 'none' }
                Row ('oscheck file name=' + $f.n + ' exists=true bytes=' + $fi.Length + ' md5=' + $md5 + ' pe=' + $pe + ' sig=' + $sigStatus + ' cn=' + $cn + ' motw=' + $zone + ' attr=' + "$($fi.Attributes)" + ' mtime=' + $fi.LastWriteTime.ToString('yyyy-MM-ddTHH:mm:ss'))
                if ($f.n -eq 'OpenAL64.dll') { $acc.dll_signed = $sigStatus; $acc.dll_motw = $zone }
            } catch { Row ('oscheck file name=' + $f.n + ' error=' + $_.Exception.Message) }
        }
    } catch { Row ('oscheck file error=' + $_.Exception.Message) }

    # -- 7b: OpenAL64 import table + dependency resolution -----------------------------------
    try {
        if (Test-Path -LiteralPath $oalPath) {
            $imports = @(Get-PeImports $oalPath 20)
            if ($imports.Count -eq 0) {
                Row 'oscheck imports dll=OpenAL64.dll count=0 list=(parse-empty)'
                $acc.imports_missing = 'parse-empty'
            } else {
                $parts = @(); $missing = @()
                foreach ($nm in $imports) {
                    if (-not $loaderReady) { $parts += ($nm + ':?') ; continue }
                    $r = LoadEx $nm 0x2
                    if ($r.ok) { $parts += ($nm + ':ok'); continue }
                    # The bare-name probe used THIS process's search path (System32/PATH), not
                    # OpenAL64's own folder. A dependency shipped next to OpenAL64.dll resolves at
                    # real load time; retry against native\windows before calling it system-missing.
                    $localDep = Join-Path $core ('native\windows\' + $nm)
                    $rLocal = $null
                    if (Test-Path -LiteralPath $localDep) { $rLocal = LoadEx $localDep 0x2 }
                    if ($null -ne $rLocal -and $rLocal.ok) { $parts += ($nm + ':local-ok') }
                    else { $parts += ($nm + ':miss'); $missing += $nm }
                }
                Row ('oscheck imports dll=OpenAL64.dll count=' + $imports.Count + ' list=' + ($parts -join ','))
                if (-not $loaderReady) { $acc.imports_missing = 'loader-na' }
                elseif ($missing.Count -eq 0) { $acc.imports_missing = '0' }
                else { $acc.imports_missing = ($missing -join ',') }
            }
        } else { Row 'oscheck imports dll=OpenAL64.dll absent'; $acc.imports_missing = 'dll-absent' }
    } catch { Row ('oscheck imports error=' + $_.Exception.Message) }

    # -- 8: direct LoadLibrary reproduction --------------------------------------------------
    # OpenAL64.dll self-pins in its DllMain, so the FIRST absolute-path load leaves it resident
    # for the rest of the process; a later bare-name OR relative load then matches the resident
    # module BY BASE NAME and reads a false 'ok'. So the two search-order-sensitive variants run
    # FIRST, while OpenAL64 is not yet resident: f (bare name), then e (relative with the real
    # Win32 CWD). The path-qualified variants (control, a-d, g) run after and are unaffected by
    # base-name residency.
    $tmpDll = $null
    if ($loaderReady) {
        try {
            # Ground truth: the ambient Win32 CWD this process was handed by join-cc.bat is the
            # same CWD java.exe gets. If it is NOT starsector-core, LWJGL resolves its relative
            # native paths against the wrong base and reports 126 with no security policy involved
            # - a distinct cause from 'CWD excluded from the DLL search'. Record it so a reader can
            # tell the two apart from the log alone (surfaced as cwd_iscore in the RESULT row).
            $cwdNow = ''
            try { $cwdNow = [System.IO.Directory]::GetCurrentDirectory() } catch {}
            $isCore = $false
            try { $isCore = [string]::Equals($cwdNow, $core, [System.StringComparison]::OrdinalIgnoreCase) } catch {}
            Row ('oscheck cwd isCore=' + (B $isCore))
            $acc.cwd_iscore = (B $isCore)

            # f: bare-name search-order test. Must precede any OpenAL64 load (incl. e below, which
            # loads it) so residency cannot mask the result; resident-before should read false.
            $fPre = OalResident
            $rf = LoadA 'OpenAL64.dll'
            Row ('oscheck loadlib f-bare-name path=OpenAL64.dll ok=' + (B $rf.ok) + ' err=' + $rf.err + ' msg=' + $rf.msg + ' resident-before=' + (B $fPre))

            # e: LWJGL's exact failing shape - a RELATIVE path with the real Win32 CWD set to
            # starsector-core. Push-Location changes only PowerShell's provider location, NOT the
            # native process CWD that LoadLibraryA resolves a relative path against; only
            # SetCurrentDirectory does. Restored in a finally so the rest of the run is unaffected.
            $re2 = [pscustomobject]@{ ok = $false; err = -1; msg = 'skipped-no-core' }
            if (Test-Path -LiteralPath $core) {
                $savedCwd = ''
                try { $savedCwd = [System.IO.Directory]::GetCurrentDirectory() } catch {}
                try {
                    [System.IO.Directory]::SetCurrentDirectory($core)
                    $re2 = LoadA 'native\windows\OpenAL64.dll'
                } finally {
                    if ($savedCwd) { try { [System.IO.Directory]::SetCurrentDirectory($savedCwd) } catch {} }
                }
            }
            LlRow 'e-rel-cwd-core' 'native\windows\OpenAL64.dll' $re2
            $acc.loadlib_rel = (LlFmt $re2)

            # control + absolute variants (path-qualified; base-name residency now irrelevant).
            $ctrl = LoadEx $lwjglPath 0
            LlRow 'control-lwjgl64-abs-flags0' $lwjglPath $ctrl

            $ra = LoadEx $oalPath 0
            LlRow 'a-abs-flags0' $oalPath $ra
            $acc.loadlib_abs = (LlFmt $ra)

            $rb = LoadEx $oalPath 0x8
            LlRow 'b-abs-altered-search' $oalPath $rb

            $rc = LoadEx $oalPath 0x2
            LlRow 'c-abs-datafile' $oalPath $rc
            $acc.loadlib_datafile = (LlFmt $rc)

            $rd = LoadEx $oalPath (0x100 -bor 0x1000)
            LlRow 'd-abs-search-dirs' $oalPath $rd

            # g: load a copy from a neutral location (tests the DLL identity independent of its
            # install path): a folder of THIS run (its process id) in the player's own app-data
            # folder - never the shared temp folder - and an existing folder of that name is refused,
            # never reused or removed (it is not ours). A blank or relative LOCALAPPDATA skips the
            # step. Log a NON-expanding label, never the resolved path - that
            # embeds the player's account name. The copy self-pins too, so FreeLibrary cannot
            # unload it and the file stays a mapped/locked image; report the cleanup OUTCOME
            # instead of a silent no-op, and best-effort schedule delete-on-reboot (needs elevation,
            # so it is not relied on); join-cc.bat's start sweep removes a day-old leftover folder.
            $tmpDir = ''
            $lad = "$env:LOCALAPPDATA"
            if ($lad -match '^[A-Za-z]:\\|^\\\\') { $tmpDir = Join-Path $lad ('cc-starsector\oal-oscheck-' + $PID) }
            $tmpDll = ''
            if ($tmpDir) { $tmpDll = Join-Path $tmpDir 'OpenAL64.dll' }
            $tmpLabel = '%LOCALAPPDATA%\cc-starsector\oal-oscheck-PID\OpenAL64.dll'
            $madeDir = $false
            $rg = [pscustomobject]@{ ok = $false; err = -1; msg = 'skipped' }
            try {
                if (-not $tmpDir) { $rg = [pscustomobject]@{ ok = $false; err = -1; msg = 'skipped-no-localappdata' } }
                elseif (Test-Path -LiteralPath $tmpDir) { $rg = [pscustomobject]@{ ok = $false; err = -1; msg = 'skipped-folder-exists' } }
                elseif (Test-Path -LiteralPath $oalPath) {
                    New-Item -ItemType Directory -Path $tmpDir -ErrorAction Stop | Out-Null
                    $madeDir = $true
                    Copy-Item -LiteralPath $oalPath -Destination $tmpDll -Force -ErrorAction Stop
                    $rg = LoadEx $tmpDll 0
                }
            } catch { $rg = [pscustomobject]@{ ok = $false; err = -1; msg = ('copy-failed:' + $_.Exception.GetType().Name) } }
            LlRow 'g-tempcopy-abs' $tmpLabel $rg
            $acc.loadlib_tempcopy = (LlFmt $rg)
            $cleanup = 'ok'
            if (-not $madeDir) { $cleanup = 'none' }
            try {
                if ($madeDir -and (Test-Path -LiteralPath $tmpDll)) {
                    Remove-Item -LiteralPath $tmpDll -Force -ErrorAction SilentlyContinue
                    if (Test-Path -LiteralPath $tmpDll) {
                        $cleanup = 'locked'
                        try { if ([CcSnd.Loader]::MoveFileExW($tmpDll, $null, 0x4)) { $cleanup = 'reboot-scheduled' } } catch {}
                    }
                }
                if ($madeDir -and (Test-Path -LiteralPath $tmpDir)) {
                    Remove-Item -LiteralPath $tmpDir -Recurse -Force -ErrorAction SilentlyContinue
                    if (Test-Path -LiteralPath $tmpDir) { try { [void][CcSnd.Loader]::MoveFileExW($tmpDir, $null, 0x4) } catch {} }
                }
            } catch {}
            Row ('oscheck loadlib g-tempcopy cleanup=' + $cleanup)
        } catch { Row ('oscheck loadlib error=' + $_.Exception.Message) }
    }

    # -- 9: loader / security policy ---------------------------------------------------------
    try {
        $smPath = 'HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager'
        $cwd = RegGetSingle $smPath 'CWDIllegalInDllSearch'
        if ($null -eq $cwd) { Row 'oscheck policy CWDIllegalInDllSearch=absent'; $acc.cwdpolicy = 'absent' }
        else { $cv = (FmtRegVal $cwd); Row ('oscheck policy CWDIllegalInDllSearch=' + $cv); $acc.cwdpolicy = $cv }
        $safe = RegGetSingle $smPath 'SafeDllSearchMode'
        if ($null -eq $safe) { Row 'oscheck policy SafeDllSearchMode=absent' } else { Row ('oscheck policy SafeDllSearchMode=' + (FmtRegVal $safe)) }
    } catch { Row ('oscheck policy sessmgr-error=' + $_.Exception.Message) }

    try {
        $kern = 'HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager\kernel'
        $mo = RegGetSingle $kern 'MitigationOptions'
        $ma = RegGetSingle $kern 'MitigationAuditOptions'
        $moS = 'absent'; if ($null -ne $mo) { $moS = (FmtRegVal $mo) }
        $maS = 'absent'; if ($null -ne $ma) { $maS = (FmtRegVal $ma) }
        Row ('oscheck policy kernel.MitigationOptions=' + $moS + ' kernel.MitigationAuditOptions=' + $maS)
    } catch { Row ('oscheck policy kernel-error=' + $_.Exception.Message) }

    try {
        $ifeoBase = 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion\Image File Execution Options'
        $ifeoNames = @()
        foreach ($img in @('java.exe', 'javaw.exe', 'starsector.exe', 'cmd.exe', 'powershell.exe')) {
            $vals = RegValues ($ifeoBase + '\' + $img) 10
            if ($null -eq $vals) { Row ('oscheck ifeo image=' + $img + ' absent') }
            elseif ($vals.Count -eq 0) { Row ('oscheck ifeo image=' + $img + ' present novalues') }
            else {
                $imgNames = @()
                foreach ($vv in $vals) { $imgNames += (($vv -split '=')[0]); $ifeoNames += ($img + ':' + (($vv -split '=')[0])) }
                Row ('oscheck ifeo image=' + $img + ' values=' + $vals.Count + ' names=' + ($imgNames -join ','))
            }
        }
        if ($ifeoNames.Count -eq 0) { $acc.ifeo = 'none' } else { $acc.ifeo = ($ifeoNames -join ',') }
    } catch { Row ('oscheck ifeo error=' + $_.Exception.Message) }

    $mJava = MitigRun 'java' 'java.exe'
    [void](MitigRun 'javaw' 'javaw.exe')
    [void](MitigRun 'system' '')
    $acc.mitig_java = $mJava

    try {
        foreach ($hv in @(@{ h = 'HKCU:'; n = 'hkcu' }, @{ h = 'HKLM:'; n = 'hklm' })) {
            $ar = RegGetSingle ($hv.h + '\Software\Microsoft\Command Processor') 'AutoRun'
            if ($null -eq $ar) { Row ('oscheck autorun hive=' + $hv.n + ' absent') } else { Row ('oscheck autorun hive=' + $hv.n + ' set=yes') }
        }
    } catch { Row ('oscheck autorun error=' + $_.Exception.Message) }

    try {
        $sac = RegGetSingle 'HKLM:\SYSTEM\CurrentControlSet\Control\CI\Policy' 'VerifiedAndReputablePolicyState'
        if ($null -eq $sac) { Row 'oscheck sac VerifiedAndReputablePolicyState=absent'; $acc.sac = 'absent' }
        else {
            $sacL = "$sac"
            if ("$sac" -eq '0') { $sacL = '0-off' } elseif ("$sac" -eq '1') { $sacL = '1-enforce' } elseif ("$sac" -eq '2') { $sacL = '2-eval' }
            Row ('oscheck sac VerifiedAndReputablePolicyState=' + $sacL); $acc.sac = $sacL
        }
    } catch { Row ('oscheck sac error=' + $_.Exception.Message); $acc.sac = 'n/a' }

    try {
        $dg = Get-CimInstance -Namespace 'root\Microsoft\Windows\DeviceGuard' -ClassName Win32_DeviceGuard -ErrorAction Stop
        $ssr = (@($dg.SecurityServicesRunning) -join '|')
        Row ('oscheck wdac ci=' + "$($dg.CodeIntegrityPolicyEnforcementStatus)" + ' umci=' + "$($dg.UsermodeCodeIntegrityPolicyEnforcementStatus)" + ' vbs=' + "$($dg.VirtualizationBasedSecurityStatus)" + ' ssr=' + $ssr)
        $acc.wdac = "$($dg.UsermodeCodeIntegrityPolicyEnforcementStatus)"
    } catch { Row ('oscheck wdac unavailable=' + $_.Exception.Message); $acc.wdac = 'n/a' }

    try {
        [xml]$ap = Get-AppLockerPolicy -Effective -Xml -ErrorAction Stop
        $dllColl = $null
        foreach ($rc in $ap.AppLockerPolicy.RuleCollection) { if ($rc.Type -eq 'Dll') { $dllColl = $rc } }
        if ($null -eq $dllColl) { Row 'oscheck applocker dll-collection=absent'; $acc.applocker_dll = 'no' }
        else {
            $ruleCount = 0
            try { $ruleCount = @($dllColl.ChildNodes | Where-Object { $_.NodeType -eq 'Element' }).Count } catch {}
            Row ('oscheck applocker dll-collection enforcement=' + "$($dllColl.EnforcementMode)" + ' rules=' + $ruleCount)
            if ($ruleCount -gt 0) { $acc.applocker_dll = 'yes' } else { $acc.applocker_dll = 'no' }
        }
    } catch { Row ('oscheck applocker unavailable=' + $_.Exception.Message); $acc.applocker_dll = 'n/a' }

    try {
        $srpPath = 'HKLM:\SOFTWARE\Policies\Microsoft\Windows\Safer\CodeIdentifiers'
        $dl = RegGetSingle $srpPath 'DefaultLevel'
        $te = RegGetSingle $srpPath 'TransparentEnabled'
        $ps2 = RegGetSingle $srpPath 'PolicyScope'
        if ($null -eq $dl -and $null -eq $te -and $null -eq $ps2) { Row 'oscheck srp CodeIdentifiers=absent'; $acc.srp = 'absent' }
        else {
            $dlS = 'absent'; if ($null -ne $dl) { $dlS = (FmtRegVal $dl) }
            Row ('oscheck srp DefaultLevel=' + $dlS + ' TransparentEnabled=' + (FmtRegVal $te) + ' PolicyScope=' + (FmtRegVal $ps2))
            $acc.srp = $dlS
        }
    } catch { Row ('oscheck srp error=' + $_.Exception.Message); $acc.srp = 'n/a' }

    try {
        $sysPolVals = RegValues 'HKLM:\SOFTWARE\Policies\Microsoft\Windows\System' 20
        if ($null -eq $sysPolVals) { Row 'oscheck syspolicy WindowsSystem=absent' }
        elseif ($sysPolVals.Count -eq 0) { Row 'oscheck syspolicy WindowsSystem present novalues' }
        else { Row ('oscheck syspolicy WindowsSystem values=' + $sysPolVals.Count) }
    } catch { Row ('oscheck syspolicy error=' + $_.Exception.Message) }

    # -- 10: security products + shadowing ---------------------------------------------------
    try {
        $avNames = @()
        foreach ($cls in @('AntiVirusProduct', 'AntiSpywareProduct', 'FirewallProduct')) {
            try {
                $prods = @(Get-CimInstance -Namespace 'root\SecurityCenter2' -ClassName $cls -ErrorAction Stop)
                # the antivirus NAME + state is the diagnostic (the product that may refuse the dll);
                # the other classes are a count, and no product path rides the report
                if ($prods.Count -eq 0) { Row ('oscheck secproduct class=' + $cls + ' none') }
                elseif ($cls -ne 'AntiVirusProduct') { Row ('oscheck secproduct class=' + $cls + ' count=' + $prods.Count) }
                $c = 0
                foreach ($pr in $prods) {
                    if ($cls -ne 'AntiVirusProduct') { break }
                    if ($c -ge 10) { break }
                    $stateHex = '?'
                    try { $stateHex = '0x' + ([int]$pr.productState).ToString('x') } catch {}
                    Row ('oscheck secproduct class=' + $cls + ' name=' + "$($pr.displayName)" + ' state=' + $stateHex)
                    if ($pr.displayName) { $avNames += "$($pr.displayName)" }
                    $c = $c + 1
                }
            } catch { Row ('oscheck secproduct class=' + $cls + ' unavailable=' + $_.Exception.Message) }
        }
        if ($avNames.Count -gt 0) { $acc.av = ($avNames -join '|') } else { $acc.av = 'none' }
    } catch { Row ('oscheck secproduct error=' + $_.Exception.Message) }

    try {
        $mp = Get-MpComputerStatus -ErrorAction Stop
        Row ('oscheck defender mode=' + "$($mp.AMRunningMode)" + ' realtime=' + "$($mp.RealTimeProtectionEnabled)")
    } catch { Row ('oscheck defender status-unavailable=' + $_.Exception.Message) }

    try {
        $pref = Get-MpPreference -ErrorAction Stop
        $ids = @($pref.AttackSurfaceReductionRules_Ids)
        $acts = @($pref.AttackSurfaceReductionRules_Actions)
        # counts only: how many attack-surface rules are on, plus the ONE rule that refuses
        # low-prevalence executables (the kit's own files are exactly that) - never the rule list,
        # never an exclusion path or process name
        $asrOn = 0
        $prevalence = 'off'
        for ($i = 0; $i -lt $ids.Count; $i++) {
            if ($i -ge 30) { break }
            $a = ''
            if ($i -lt $acts.Count) { $a = "$($acts[$i])" }
            if ($a -ne '0' -and $a -ne '') {
                $asrOn = $asrOn + 1
                if ("$($ids[$i])" -eq '01443614-cd74-433a-b99e-2ecdc07bfc25') { $prevalence = $a }
            }
        }
        Row ('oscheck defender asr enabled=' + $asrOn + ' prevalence=' + $prevalence)
        # a read without admin rights returns ONE placeholder entry ("N/A: Must be an administrator
        # ...") - that is "unknown", never an exclusion to count
        foreach ($ex in @(@{ n = 'exclusionpath'; v = $pref.ExclusionPath }, @{ n = 'exclusionprocess'; v = $pref.ExclusionProcess })) {
            $vals = @($ex.v | Where-Object { $_ })
            if (@($vals | Where-Object { "$_" -match '^N/A' }).Count -gt 0) { Row ('oscheck defender ' + $ex.n + ' count=n/a') }
            else { Row ('oscheck defender ' + $ex.n + ' count=' + $vals.Count) }
        }
        Row ('oscheck defender flags rtmoff=' + "$($pref.DisableRealtimeMonitoring)" + ' pua=' + "$($pref.PUAProtection)" + ' cfa=' + "$($pref.EnableControlledFolderAccess)")
    } catch { Row ('oscheck defender prefs-unavailable=' + $_.Exception.Message) }

    try {
        foreach ($sd in @(@{ n = 'System32'; p = (Join-Path $env:WINDIR 'System32\OpenAL32.dll') }, @{ n = 'SysWOW64'; p = (Join-Path $env:WINDIR 'SysWOW64\OpenAL32.dll') })) {
            if (Test-Path -LiteralPath $sd.p) {
                $fi = Get-Item -LiteralPath $sd.p -ErrorAction Stop
                $ver = ''
                try { $ver = "$($fi.VersionInfo.FileVersion)" } catch {}
                Row ('oscheck sysoal dir=' + $sd.n + ' present bytes=' + $fi.Length + ' ver=' + $ver)
            } else { Row ('oscheck sysoal dir=' + $sd.n + ' absent') }
        }
    } catch { Row ('oscheck sysoal error=' + $_.Exception.Message) }

    try {
        $shadowCount = 0
        $targets = @('OpenAL64.dll', 'OpenAL32.dll', 'lwjgl64.dll', 'soft_oal.dll')
        $dirs = @()
        if ($env:PATH) { $dirs = $env:PATH -split ';' }
        $dc = 0
        foreach ($d in $dirs) {
            if ($dc -ge 200) { break }
            $dc = $dc + 1
            if ([string]::IsNullOrWhiteSpace($d)) { continue }
            foreach ($t in $targets) {
                try {
                    $fp = Join-Path $d $t
                    if (Test-Path -LiteralPath $fp -ErrorAction SilentlyContinue) {
                        $sz = 0
                        try { $sz = (Get-Item -LiteralPath $fp -ErrorAction Stop).Length } catch {}
                        Row ('oscheck pathshadow in=' + (DirClass $d) + ' dll=' + $t + ' bytes=' + $sz)
                        $shadowCount = $shadowCount + 1
                    }
                } catch {}
            }
        }
        $acc.path_shadow = "$shadowCount"
        if ($shadowCount -eq 0) { Row 'oscheck pathshadow matches=0' }
    } catch { Row ('oscheck pathshadow error=' + $_.Exception.Message) }

    try {
        $mods = @((Get-Process -Id $PID).Modules)
        $winDir = "$env:WINDIR"
        # Skip the DLLs THIS script just loaded in item 8 (self-pinning OpenAL64 + lwjgl64 + the
        # temp copy stay mapped after FreeLibrary) so the scan surfaces only FOREIGN modules -
        # the injected AV / anti-exploit hook DLLs it is meant to expose - not our own images.
        $skipPaths = @()
        if ($oalPath) { $skipPaths += $oalPath.ToLowerInvariant() }
        if ($lwjglPath) { $skipPaths += $lwjglPath.ToLowerInvariant() }
        if ($tmpDll) { $skipPaths += ([string]$tmpDll).ToLowerInvariant() }
        $mc = 0
        foreach ($md in $mods) {
            if ($mc -ge 40) { break }
            $fn = ''
            try { $fn = "$($md.FileName)" } catch {}
            if (-not $fn) { continue }
            if ($skipPaths -contains $fn.ToLowerInvariant()) { continue }
            $co = ''
            try { $co = "$($md.FileVersionInfo.CompanyName)" } catch {}
            $underWin = $false
            if ($winDir) { $underWin = $fn.ToLowerInvariant().StartsWith($winDir.ToLowerInvariant()) }
            $isMs = $false
            if ($co -and $co -match 'Microsoft') { $isMs = $true }
            if ((-not $isMs) -and (-not $underWin)) {
                Row ('oscheck module name=' + "$($md.ModuleName)" + ' company=' + $co)
                $mc = $mc + 1
            }
        }
        if ($mc -eq 0) { Row 'oscheck module nonms-nonwindir=0' }
    } catch { Row ('oscheck module error=' + $_.Exception.Message) }

    # -- 11: event logs, last 48 h -----------------------------------------------------------
    $startT = (Get-Date).AddHours(-48)
    $acc.defender_hits = "$(ScanLog 'defender' @{ LogName = 'Microsoft-Windows-Windows Defender/Operational'; StartTime = $startT; Id = @(1006,1007,1008,1015,1116,1117,1118,1119,1121,1122,1123,1124,1125,1126,1127,5007,5008) })"
    [void](ScanLog 'codeintegrity' @{ LogName = 'Microsoft-Windows-CodeIntegrity/Operational'; StartTime = $startT; Id = @(3033,3034,3076,3077,3089) })
    [void](ScanLog 'mitig-kernel' @{ LogName = 'Microsoft-Windows-Security-Mitigations/KernelMode'; StartTime = $startT })
    [void](ScanLog 'mitig-user' @{ LogName = 'Microsoft-Windows-Security-Mitigations/UserMode'; StartTime = $startT })
    [void](ScanLog 'app-errors' @{ LogName = 'Application'; ProviderName = @('Windows Error Reporting', 'Application Error'); StartTime = $startT })

    # -- 12: write-access facts --------------------------------------------------------------
    WriteTest 'kit' $kit
    WriteTest 'starsector-core' $core

} catch {
    ForceRow ('oscheck fatal=' + $_.Exception.Message)
}

# ---- final RESULT row ----------------------------------------------------------------------
try {
    $mitigShort = "$($acc.mitig_java)"; if ($mitigShort.Length -gt 90) { $mitigShort = $mitigShort.Substring(0, 90) }
    $imMiss = "$($acc.imports_missing)"; if ($imMiss.Length -gt 80) { $imMiss = $imMiss.Substring(0, 80) }
    $avShort = "$($acc.av)"; if ($avShort.Length -gt 60) { $avShort = $avShort.Substring(0, 60) }
    $ifeoShort = "$($acc.ifeo)"; if ($ifeoShort.Length -gt 60) { $ifeoShort = $ifeoShort.Substring(0, 60) }
    ForceRow ('oscheck RESULT loadlib_abs=' + $acc.loadlib_abs + ' loadlib_rel=' + $acc.loadlib_rel + ' loadlib_datafile=' + $acc.loadlib_datafile + ' loadlib_tempcopy=' + $acc.loadlib_tempcopy + ' cwd_iscore=' + $acc.cwd_iscore + ' imports_missing=' + $imMiss + ' cwdpolicy=' + $acc.cwdpolicy + ' ifeo=' + $ifeoShort + ' sac=' + $acc.sac + ' wdac=' + $acc.wdac + ' applocker_dll=' + $acc.applocker_dll + ' srp=' + $acc.srp + ' av=' + $avShort + ' mitig_java=' + $mitigShort + ' defender_hits=' + $acc.defender_hits + ' path_shadow=' + $acc.path_shadow + ' dll_signed=' + $acc.dll_signed + ' dll_motw=' + $acc.dll_motw)
} catch {}

exit 0
