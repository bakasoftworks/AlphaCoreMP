#Requires -Version 7
# engine-map-codegen.ps1 - turns the engine map into the client mod's constant table: reads one engine map (default:
# engine-map/linux-0.98a-RC8.tsv beside this script) and writes <Out>/cc/starsector/client/EngineNameTable.java. Called
# by this tree's build.ps1 and by the maintainer's own client build, so there is one parser and one table layout, never
# a second copy that drifts: one map always generates the same file bytes.
#
# WHY: the Linux download of the game is a separate build of the engine, and some of the engine's internal class and
# member names the mod reaches by reflection are spelled differently there. The map lists them. The generated table holds
# them as constants only (no I/O, no resources: the game's loader for mod classes denies the file APIs) and compiles with
# the mod's sources into its jar. EngineNames translates a reflective call site's Windows spelling through it, and
# EngineLinkProbe proves every row when the game loads the mod.
#
# THE MAP: UTF-8, TAB-separated, LF or CRLF. Blank lines and '#' lines are skipped, but for '# id=<map id>' (required,
# once). Rows:
#   class<TAB><Windows internal name><TAB><this build's internal name>
#   <kind><TAB><Windows owner><TAB><name><TAB><Windows descriptor><TAB><this build's name>
#       kind = static-method | method | static-field | field (the static bit picks the probe's lookup)
#   touched<TAB><mod class internal name>      a mod class the kit agent rewrites on this map
# CASE-SENSITIVE throughout: some names differ from others only in letter case, so every compare here is ordinal (.NET
# Dictionary / HashSet with StringComparer.Ordinal, -ceq / -cnotmatch / -cmatch, [regex] which is case-sensitive) - a
# PowerShell @{} or -eq would fold them. Non-ASCII letters are written as \uXXXX escapes, so the generated file is
# ASCII whatever encoding a reader assumes.
#
# Usage: engine-map-codegen.ps1 -Out <gen folder> [-Tsv <map file>]
#   Returns ONE object: MapId, Classes, Members, Touched (the row counts) and Path (the file written); prints nothing.
#   A bad row throws, naming its line, before anything is written (run as its own pwsh process: exit 1).
param(
    [Parameter(Mandatory = $true)] [string] $Out,
    [string] $Tsv = ''
)
$ErrorActionPreference = 'Stop'
if (-not $Tsv) { $Tsv = Join-Path $PSScriptRoot 'engine-map/linux-0.98a-RC8.tsv' }
if (-not (Test-Path -LiteralPath $Tsv -PathType Leaf)) { throw "engine map missing: $Tsv" }

$emId = $null
$emClassMap = [System.Collections.Generic.Dictionary[string, string]]::new([StringComparer]::Ordinal)
$emClassOrder = [System.Collections.Generic.List[string]]::new()
$emMembers = [System.Collections.Generic.List[object]]::new()
$emMemberKeys = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
$emTouched = [System.Collections.Generic.List[string]]::new()
$emTouchedSet = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
$emKinds = @('static-method', 'method', 'static-field', 'field')
# One JVM name token: no whitespace / control char, no quote or backslash (it rides a Java literal), no '.' ';' '['
# (an internal name is '/'-separated; a dot would collide with the dotted form).
function Assert-EmName([string]$s, [string]$what, [int]$lineNo) {
    if ([string]::IsNullOrEmpty($s) -or $s -cmatch '[\s\x00-\x1f"\\.;\[]') { throw "engine map line ${lineNo}: bad $what '$s'" }
}
$emLineNo = 0
foreach ($emLine in [IO.File]::ReadAllLines($Tsv, [Text.Encoding]::UTF8)) {
    $emLineNo++
    if ($emLine -ceq '') { continue }
    if ($emLine.StartsWith('#', [StringComparison]::Ordinal)) {
        if ($emLine.StartsWith('# id=', [StringComparison]::Ordinal)) {
            if ($null -ne $emId) { throw "engine map line ${emLineNo}: a second '# id=' header" }
            $emId = $emLine.Substring(5)
            if ($emId -cnotmatch '^[A-Za-z0-9._-]{1,64}$') { throw "engine map line ${emLineNo}: bad map id '$emId'" }
        }
        continue
    }
    $f = $emLine.Split("`t")
    if ($f[0] -ceq 'class') {
        if ($f.Count -ne 3) { throw "engine map line ${emLineNo}: a class row has 3 fields, found $($f.Count)" }
        Assert-EmName $f[1] 'class key' $emLineNo; Assert-EmName $f[2] 'class target' $emLineNo
        if ($emClassMap.ContainsKey($f[1])) { throw "engine map line ${emLineNo}: duplicate class key $($f[1])" }
        $emClassMap.Add($f[1], $f[2]); $emClassOrder.Add($f[1])
    } elseif ($emKinds -ccontains $f[0]) {
        if ($f.Count -ne 5) { throw "engine map line ${emLineNo}: a member row has 5 fields, found $($f.Count)" }
        Assert-EmName $f[1] 'member owner' $emLineNo; Assert-EmName $f[2] 'member name' $emLineNo; Assert-EmName $f[4] 'member target name' $emLineNo
        $isMethod = $f[0].EndsWith('method', [StringComparison]::Ordinal)
        $descOk = if ($isMethod) { $f[3] -cmatch '^\([^\s"\\]*\)[^\s"\\]+$' } else { $f[3] -cmatch '^\[*([BCDFIJSZ]|L[^\s"\\;]+;)$' }
        if (-not $descOk) { throw "engine map line ${emLineNo}: descriptor '$($f[3])' does not fit kind $($f[0])" }
        if (-not $emMemberKeys.Add("$($f[1])`t$($f[2])`t$($f[3])")) { throw "engine map line ${emLineNo}: duplicate member row $($f[1]).$($f[2])$($f[3])" }
        $emMembers.Add([pscustomobject]@{ Kind = $f[0]; Owner = $f[1]; Name = $f[2]; Desc = $f[3]; Target = $f[4] })
    } elseif ($f[0] -ceq 'touched') {
        if ($f.Count -ne 2) { throw "engine map line ${emLineNo}: a touched row has 2 fields, found $($f.Count)" }
        Assert-EmName $f[1] 'touched class' $emLineNo
        if (-not $emTouchedSet.Add($f[1])) { throw "engine map line ${emLineNo}: duplicate touched row $($f[1])" }
        $emTouched.Add($f[1])
    } else {
        throw "engine map line ${emLineNo}: unknown row kind '$($f[0])'"
    }
}
if ($null -eq $emId) { throw "engine map has no '# id=' header: $Tsv" }
# A Linux descriptor = the Windows one with every exact class key in an L...; slot replaced (an inner class keeps its
# own key: a nested class is renamed only by its own class row, never by its outer class's). Valid descriptors carry no
# bare 'L' outside a class slot, so the left-to-right scan is exact.
function Convert-EmDesc([string]$desc) {
    return [regex]::Replace($desc, 'L([^;]+);', { param($mt)
        $k = $mt.Groups[1].Value
        if ($emClassMap.ContainsKey($k)) { 'L' + $emClassMap[$k] + ';' } else { $mt.Value }
    })
}
function ConvertTo-EmJavaLit([string]$s) {
    $sb = [Text.StringBuilder]::new('"')
    foreach ($ch in $s.ToCharArray()) {
        if ([int]$ch -gt 0x7e) { [void]$sb.Append(('\u{0:X4}' -f [int]$ch)) } else { [void]$sb.Append($ch) }
    }
    return $sb.Append('"').ToString()
}
function ConvertTo-EmDotted([string]$internal) { return $internal.Replace('/', '.') }
$emGen = [Text.StringBuilder]::new()
[void]$emGen.AppendLine('// GENERATED by client-mod/engine-map-codegen.ps1 from the engine map (map id ' + $emId + '); every build rewrites it - edit the map, never this file.')
[void]$emGen.AppendLine('package cc.starsector.client;')
[void]$emGen.AppendLine('')
[void]$emGen.AppendLine('/** The engine map as constants: see EngineNames (the reader) and EngineLinkProbe (the proof). */')
[void]$emGen.AppendLine('final class EngineNameTable {')
[void]$emGen.AppendLine('    private EngineNameTable() {}')
[void]$emGen.AppendLine('')
[void]$emGen.AppendLine('    static final String MAP_ID = ' + (ConvertTo-EmJavaLit $emId) + ';')
[void]$emGen.AppendLine('    /** Pairs: Windows dotted name, this map''s dotted name. */')
[void]$emGen.AppendLine('    static final int CLASS_STRIDE = 2;')
[void]$emGen.AppendLine('    /** Rows: KIND, Windows owner (dotted), name, Windows descriptor, this map''s name, this map''s descriptor. */')
[void]$emGen.AppendLine('    static final int MEMBER_STRIDE = 6;')
[void]$emGen.AppendLine('    static final int M_KIND = 0;')
[void]$emGen.AppendLine('    static final int M_OWNER = 1;')
[void]$emGen.AppendLine('    static final int M_NAME = 2;')
[void]$emGen.AppendLine('    static final int M_WIN_DESC = 3;')
[void]$emGen.AppendLine('    static final int M_LINUX_NAME = 4;')
[void]$emGen.AppendLine('    static final int M_LINUX_DESC = 5;')
[void]$emGen.AppendLine('')
[void]$emGen.AppendLine('    static final String[] CLASSES = {')
foreach ($k in $emClassOrder) {
    [void]$emGen.AppendLine('        ' + (ConvertTo-EmJavaLit (ConvertTo-EmDotted $k)) + ', ' + (ConvertTo-EmJavaLit (ConvertTo-EmDotted $emClassMap[$k])) + ',')
}
[void]$emGen.AppendLine('    };')
[void]$emGen.AppendLine('')
[void]$emGen.AppendLine('    static final String[] MEMBERS = {')
foreach ($mr in $emMembers) {
    $cells = @($mr.Kind, (ConvertTo-EmDotted $mr.Owner), $mr.Name, $mr.Desc, $mr.Target, (Convert-EmDesc $mr.Desc)) | ForEach-Object { ConvertTo-EmJavaLit $_ }
    [void]$emGen.AppendLine('        ' + ($cells -join ', ') + ',')
}
[void]$emGen.AppendLine('    };')
[void]$emGen.AppendLine('')
[void]$emGen.AppendLine('    /** The mod classes the agent rewrites on this map (dotted): the probe defines each once at application load. */')
[void]$emGen.AppendLine('    static final String[] REMAPPED = {')
foreach ($t in $emTouched) { [void]$emGen.AppendLine('        ' + (ConvertTo-EmJavaLit (ConvertTo-EmDotted $t)) + ',') }
[void]$emGen.AppendLine('    };')
[void]$emGen.AppendLine('}')
$emPath = Join-Path $Out 'cc/starsector/client/EngineNameTable.java'
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $emPath) | Out-Null
[IO.File]::WriteAllText($emPath, $emGen.ToString().Replace("`r`n", "`n"), [Text.UTF8Encoding]::new($false))
[pscustomobject]@{ MapId = $emId; Classes = $emClassOrder.Count; Members = $emMembers.Count; Touched = $emTouched.Count; Path = $emPath }
