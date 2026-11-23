#Requires -Version 7
# engine-alias.ps1 - the compile-time ALIAS OVERLAY for the engine names Java source cannot spell. Called by this tree's
# build.ps1 and by the maintainer's own client build, so both builds alias, restore and check with one tool.
#
# WHY: the client mod compiles against the game's engine jars, whose machine-made internal names may name a class or a
# member with a Java keyword ('for', 'new', 'class'), a restricted identifier ('var', '_') or a dotted name
# ('Object.for'): legal in a class file, unspellable in a .java file. Rather than reshape every such use by hand on each
# game release, the build gives each such name a spellable ALIAS for the main compile only, then puts the real name back
# into the compiled classes before the jar is packed: the jar holds the engine's real names and runs against the real
# jars, and a source that spells no alias compiles to byte-identical classes.
#
# THE RULE (ALIAS_RULE_VERSION in the tool), two parts. Rule 1 (stateless): a keyword, a reserved literal or a
# restricted identifier becomes <name>_; a name holding '.' has every '.' made '_' ('Object.for' becomes 'Object_for');
# applied to member names and to every '/' segment and '$' part of a class name ('$' parts starting with a digit are the
# compiler's own anonymous / local class names and stay). Rule 2 (stateful: it reads the jar set): an engine class whose
# internal name equals a PACKAGE (a proper '/' prefix of any class name on the engine jars or on -Classpath) takes '_'
# on its outermost simple name after rule 1 ('p/A' becomes 'p/A_', 'p/for' becomes 'p/for__'), its nested classes
# follow ('p/A$In' becomes 'p/A_$In'), and the package keeps its name: Java source resolves a qualified name to the
# class first, so no spelling (an import included) reaches a class inside a package a class hides. The class takes the
# alias because a by-name string literal is never rewritten. The back step restores a rule-2 name by its per-class
# table row, never by a segment rule. Any other name Java source cannot spell is REFUSED, by name. The build also
# refuses when two live names share an alias, when an alias equals a live class or member name on the compile class
# path, when a class alias equals a live package, when another class-path entry's declarations name a class the table
# renames, and (the back step) when an alias sits on a class that is not an engine class.
#
# Modes (exactly one):
#   -Forward -Jars <engine jars> [-Classpath <the other compile entries>]
#       copies the engine jars with every alias applied (cached by the jars' sha-256 + the rule version + the tool's own
#       hash + rule 2's hiding classes), checks the other entries, and returns ONE object: Dir (the alias folder) and Jars (the aliased copies, in
#       -Jars order). Throws on a refusal.
#   -Back -Classes <compiled classes folder> -AliasDir <the -Forward Dir>
#       rewrites, in place and through a fresh constant pool, every class holding an alias; a class holding none is
#       never written. Then the constant-pool gate: no class holds an alias spelling as a class, member, descriptor,
#       signature, inner or call-site name. Prints 'ENGINE-ALIAS: table=... rewritten=<n> of <n> classes
#       illegal-jvm-name-holders=<list or none> ms=<n>' (a holder references a member name the class-format check
#       rejects: it loads only in a JVM run with that check off, as the game runs) and returns ONE object: Holders (those
#       class names, an empty string[] when none), so a caller picks its JVM posture from it. Throws on a refusal, a
#       gate miss, or a summary row it cannot read.
#   -AliasTable -Jars <engine jars> [-Classpath <entries>]
#       print-only: the table, sorted ordinal, and every refusal check's verdict; exit 1 on a refusal.
#   -SelfTest
#       no game needed: fixture jars minted with ASM, forward -> javac -> back, the refusals; prints
#       'PASS|FAIL <row> (flips if <regression>)' rows; exit 1 on any FAIL.
# Common: -JdkBin <JDK bin folder> (javac + java; default: JAVA_HOME's, else the javac on PATH); -AsmJars <asm jar>,
#   <asm-commons jar> (default: third-party/asm beside this tree); -CacheDir (default: build/engine-alias beside this
#   script: the compiled tool, the aliased jar copies, the self-test's scratch). Nothing outside -CacheDir and -Classes
#   is ever written: the game folder is only read.
param(
    [switch] $Forward,
    [switch] $Back,
    [switch] $AliasTable,
    [switch] $SelfTest,
    [string[]] $Jars = @(),
    [string[]] $Classpath = @(),
    [string] $Classes = '',
    [string] $AliasDir = '',
    [string] $CacheDir = '',
    [string] $JdkBin = '',
    [string[]] $AsmJars = @()
)
$ErrorActionPreference = 'Stop'
if (@($Forward, $Back, $AliasTable, $SelfTest | Where-Object { $_ }).Count -ne 1) {
    throw 'engine-alias: pass exactly one of -Forward, -Back, -AliasTable, -SelfTest'
}
$sep = [string][IO.Path]::PathSeparator
$exe = if ($IsWindows) { '.exe' } else { '' }
function ConvertTo-EaFullPath([string] $Path) { $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Path) }

if (-not $JdkBin) {
    if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME "bin/javac$exe") -PathType Leaf)) {
        $JdkBin = Join-Path $env:JAVA_HOME 'bin'
    } else {
        $onPath = Get-Command "javac$exe" -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($onPath) { $JdkBin = Split-Path -Parent $onPath.Source }
    }
}
if (-not $JdkBin) { throw 'engine-alias: no JDK found (pass -JdkBin <JDK bin folder>)' }
$javac = Join-Path $JdkBin "javac$exe"
$java = Join-Path $JdkBin "java$exe"
foreach ($t in @($javac, $java)) { if (-not (Test-Path -LiteralPath $t -PathType Leaf)) { throw "engine-alias: $t not found (pass -JdkBin <JDK bin folder>)" } }

if ($AsmJars.Count -eq 0) {
    $asmHome = Join-Path $PSScriptRoot '../third-party/asm'
    if (Test-Path -LiteralPath $asmHome -PathType Container) {
        $AsmJars = @(Get-ChildItem -LiteralPath $asmHome -Filter 'asm-*.jar' -File | Where-Object { $_.Name -notlike '*-sources.jar' } | Sort-Object Name | ForEach-Object { $_.FullName })
    }
}
$AsmJars = @($AsmJars | Where-Object { $_ } | ForEach-Object { ConvertTo-EaFullPath $_ })
if ($AsmJars.Count -lt 2) { throw 'engine-alias: the ASM jars are needed (pass -AsmJars <asm jar>,<asm-commons jar>)' }
foreach ($j in $AsmJars) { if (-not (Test-Path -LiteralPath $j -PathType Leaf)) { throw "engine-alias: ASM jar missing: $j" } }
if (-not $CacheDir) { $CacheDir = Join-Path $PSScriptRoot 'build/engine-alias' }
$CacheDir = ConvertTo-EaFullPath $CacheDir

# The tool: compiled once per (its source, the ASM jars) into the cache; 'compiled.ok' is written only after javac passed.
$toolSrc = Join-Path $PSScriptRoot 'engine-alias/EngineAlias.java'
if (-not (Test-Path -LiteralPath $toolSrc -PathType Leaf)) { throw "engine-alias: the tool source is missing: $toolSrc" }
$toolInputs = (@($toolSrc) + $AsmJars | ForEach-Object { (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash }) -join "`n"
$toolHash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($toolInputs))).Substring(0, 16).ToLowerInvariant()
$toolDir = Join-Path $CacheDir "tool-$toolHash"
$toolOk = Join-Path $toolDir 'compiled.ok'
if (-not (Test-Path -LiteralPath $toolOk -PathType Leaf)) {
    New-Item -ItemType Directory -Force -Path $toolDir | Out-Null
    & $javac -encoding UTF-8 --release 17 -nowarn -cp ($AsmJars -join $sep) -d $toolDir $toolSrc
    if ($LASTEXITCODE -ne 0) { throw "engine-alias: compiling the tool failed (javac exit $LASTEXITCODE)" }
    Set-Content -LiteralPath $toolOk -Value $toolHash -Encoding ascii
}
$toolCp = (@($toolDir) + $AsmJars) -join $sep

function Invoke-EaTool([string[]] $ToolArgs) {
    $lines = @(& $java -cp $toolCp EngineAlias @ToolArgs)
    $code = $LASTEXITCODE
    foreach ($l in $lines) { Write-Host $l }
    [pscustomobject]@{ Code = $code; Lines = $lines }
}

if ($SelfTest) {
    $r = Invoke-EaTool @('selftest', '--work', (Join-Path $CacheDir 'selftest'))
    exit $r.Code
}

if ($Back) {
    if (-not $Classes -or -not $AliasDir) { throw 'engine-alias -Back: pass -Classes <compiled classes folder> and -AliasDir <the -Forward Dir>' }
    $r = Invoke-EaTool @('back', '--alias-dir', (ConvertTo-EaFullPath $AliasDir), '--classes', (ConvertTo-EaFullPath $Classes))
    if ($r.Code -ne 0) { throw "engine-alias -Back: exit $($r.Code) - the rows above name the cause" }
    # The holder list rides back as data as well as print, read from the tool's one summary row and fail-closed: a
    # missing row or field throws rather than reading as 'none', because a caller that took 'none' would run a holder
    # in a verifying JVM and die there with a ClassFormatError far from this cause.
    $sumRow = @($r.Lines | Where-Object { $_.StartsWith('ENGINE-ALIAS: ') })
    if ($sumRow.Count -ne 1) { throw "engine-alias -Back: the tool printed $($sumRow.Count) ENGINE-ALIAS summary rows (want 1)" }
    $holderField = [regex]::Match($sumRow[0], ' illegal-jvm-name-holders=(\S+)')
    if (-not $holderField.Success) { throw 'engine-alias -Back: the summary row carries no illegal-jvm-name-holders= field' }
    $holders = [string[]]@()
    if ($holderField.Groups[1].Value -cne 'none') { $holders = [string[]]@($holderField.Groups[1].Value -split ',') }
    return [pscustomobject]@{ Holders = $holders }
}

if ($Jars.Count -eq 0) { throw 'engine-alias: pass -Jars <the engine jars>' }
$jarList = @($Jars | ForEach-Object { ConvertTo-EaFullPath $_ })
foreach ($j in $jarList) { if (-not (Test-Path -LiteralPath $j -PathType Leaf)) { throw "engine-alias: engine jar missing: $j" } }
$leaves = @($jarList | ForEach-Object { Split-Path -Leaf $_ })
if (@($leaves | Sort-Object -Unique).Count -ne $leaves.Count) { throw "engine-alias: two engine jars share a file name ($($leaves -join ', '))" }
$toolArgs = @('--jars', ($jarList -join $sep))
$cpList = @($Classpath | Where-Object { $_ } | ForEach-Object { ConvertTo-EaFullPath $_ })
if ($cpList.Count -gt 0) { $toolArgs += @('--cp', ($cpList -join $sep)) }

if ($AliasTable) {
    $r = Invoke-EaTool (@('table') + $toolArgs)
    exit $r.Code
}

$r = Invoke-EaTool (@('forward') + $toolArgs + @('--cache', $CacheDir, '--tool', $toolHash))
if ($r.Code -ne 0) { throw "engine-alias -Forward: exit $($r.Code) - the rows above name the cause" }
$dirRow = @($r.Lines | Where-Object { $_.StartsWith('ENGINE-ALIAS-DIR ') })
if ($dirRow.Count -ne 1) { throw 'engine-alias -Forward: the tool printed no ENGINE-ALIAS-DIR row' }
$dir = $dirRow[0].Substring('ENGINE-ALIAS-DIR '.Length)
[pscustomobject]@{ Dir = $dir; Jars = @($leaves | ForEach-Object { Join-Path $dir $_ }) }
