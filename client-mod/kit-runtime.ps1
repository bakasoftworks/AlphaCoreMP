# kit-runtime.ps1 - the joiner kit's bundled Java runtime, in one place: the modules and jlink flags of the runtime image
# the kit ships as CC_Client\jre-cc, and the rules that turn the game's own vmparams line into the kit-jvm.args file
# join-cc.bat runs that runtime with. Dot-sourced by this tree's build.ps1 (-KitRuntime) and by the maintainer's own kit
# scripts, so there is one list and one derivation, never a second copy that drifts.
#
# WHY A BUNDLED RUNTIME: the voice chat's low-latency audio path uses Java's foreign function and memory API (Java 22 or
# newer) and the game's own Java is 17. So the kit carries a jlink'd Eclipse Temurin image, and join-cc.bat, when it finds
# jre-cc\bin\javaw.exe and kit-jvm.args beside it, runs the join window and the game on that runtime (javaw @kit-jvm.args
# from starsector-core, the launcher window unchanged); without them it runs the game the normal way, as if the kit had
# never carried a runtime.
# WHY ECLIPSE TEMURIN ONLY: the kit's README names it, the kit's notices file copies that build's own NOTICE and legal
# texts (GPLv2 with the Classpath exception - jlink keeps legal\, never strip it), and it is the one runtime the kit has
# been played on. Another vendor's JDK is refused by name, never linked.
# WHY DERIVE FROM VMPARAMS: starsector.exe starts the game with the install's jre\ and the vmparams line; a kit cannot
# point starsector.exe at jre-cc, so the args file IS that line with the few deltas listed below - the player's own heap
# size, library path and class path travel as they are.

$CcKitRuntime = @{
    # The modules the image holds: what the game, LWJGL 2, JInput, the mod and the voice path load. java.instrument: a
    # Java agent may ride the same JVM; java.logging: JInput's logger; jdk.charsets: non-Latin locales; jdk.jfr: a flight
    # recording when a host asks a player for one.
    Modules    = @(
        'java.base', 'java.compiler', 'java.datatransfer', 'java.desktop', 'java.instrument', 'java.logging',
        'java.management', 'java.naming', 'java.prefs', 'java.sql', 'java.xml',
        'jdk.management', 'jdk.unsupported', 'jdk.jfr', 'jdk.charsets'
    )
    JlinkFlags = @('--strip-debug', '--no-man-pages', '--no-header-files', '--compress', 'zip-6')
    # vmparams tokens the args file leaves out: the launcher token itself; a GC mode gone from Java 25; a modal error box
    # (it would hang the bat's exit wait); preview mode (nothing on the class path is a preview class); the extended crash
    # report (a stranger's crash file would carry more of their PC than needed); and the CPU-feature, compiler-directive
    # and heap pre-touch tuning some community launch lines pin, which a lesser CPU clamps with warnings and every start
    # pays for - the game's shipped line has none of them.
    Drop       = @('java.exe', '-XX:ShenandoahGCMode=iu', '-XX:+ShowMessageBoxOnError', '--enable-preview', '-XX:+ExtensiveErrorReports',
        '-XX:UseAVX=3', '-XX:AVX3Threshold=0', '-XX:UseSSE=4', '-XX:+UseSSE42Intrinsics', '-XX:+UseFMA',
        '-XX:+UseBMI1Instructions', '-XX:+UseBMI2Instructions', '-XX:+UseCLMUL', '-XX:+UseFastStosb',
        '-XX:+AlwaysPreTouch')
    DropPrefix = @('-XX:CompilerDirectivesFile=')   # path-valued: matched on the option, whatever the path spelling
    # Added before -classpath: native access for LWJGL 2 / JInput JNI and the voice path; the Unsafe memory access LWJGL 2
    # uses (warn-only on Java 25); and the native default charset - LWJGL 2 hands OpenAL's DLL path to the C runtime as
    # default-charset bytes, which on Java 18+ is UTF-8, so a non-ASCII install path would break the load; COMPAT is
    # exactly what the install's Java 17 does.
    Add        = @('--enable-native-access=ALL-UNNAMED', '--sun-misc-unsafe-memory-access=allow', '-Dfile.encoding=COMPAT')
    # REFUSED, never dropped and never shipped: a vmparams token that makes every player's JVM write a dump, recording,
    # log or crash file of its own, or load an agent, or read options from a file this derivation never sees. A line
    # carrying one is a PC someone tuned for diagnosis; shipping it or silently stripping it are both the wrong default,
    # so the derivation stops and names the token. Prefixes, matched case-insensitively (HotSpot option names are
    # case-exact, so a miscased spelling could never start the JVM anyway - stopping here only names it earlier). -Xlog
    # is refused by its OUTPUT (Test-CcXlogWritesFile), not by prefix: the stock -Xlog:async and console outputs stay.
    # A -XX:-<flag> spelling of a refused -XX:+<flag> switches the feature OFF and passes on purpose.
    Refuse     = @('-XX:+HeapDump', '-XX:HeapDump', '-XX:+FlightRecorder', '-XX:StartFlightRecording',
        '-XX:FlightRecorderOptions', '-XX:ErrorFile', '-XX:OnError', '-XX:OnOutOfMemoryError',
        '-XX:+CreateCoredumpOnCrash', '-XX:LogFile', '-XX:+LogVMOutput', '-XX:+LogCompilation',
        '-javaagent:', '-agentlib:', '-agentpath:', '-Xloggc:', '-Dcom.sun.management',
        '-Xrun', '-XX:VMOptionsFile=', '-XX:Flags=')
}

# True when an -Xlog token sends its output to a FILE. The output is the second ':' field (-Xlog:<what>:<output>:...);
# file=<path> and a BARE name both write a file (measured on the install's Java 17 and the kit's Java 25: -Xlog:gc:name.log
# creates name.log), so anything but '' / stdout / stderr there counts. A drive-letter path splits early on its own ':'
# and still lands a non-console field, so it is caught too. stdout / stderr are matched case-EXACTLY: HotSpot reads
# -Xlog:gc:STDOUT as a bare file name and writes a file called STDOUT.
function Test-CcXlogWritesFile([string]$Token) {
    if (-not $Token.StartsWith('-Xlog:', [StringComparison]::OrdinalIgnoreCase)) { return $false }
    if ($Token -match '(?i)file=') { return $true }
    $fields = $Token.Substring(6).Split(':')
    return ($fields.Count -ge 2 -and $fields[1] -ne '' -and $fields[1] -cnotin @('stdout', 'stderr'))
}

# The vmparams line -> the kit-jvm.args lines (two comment rows, then one option per line, '\' -> '/' in paths: a
# backslash is an escape inside an @argfile). The ONE derivation every caller runs; throws on a line that is not the
# game's launcher line, on a refused token, or on a token with whitespace.
function Convert-CcVmparamsToKitArgs([string]$VmText) {
    $r = $CcKitRuntime
    $tokens = @($VmText -split '\s+' | Where-Object { $_ -ne '' })
    if ($tokens.Count -eq 0 -or $tokens[0] -ne 'java.exe') { throw "vmparams does not start with java.exe (got '$(if ($tokens.Count) { $tokens[0] })') - not the stock launcher line" }
    if ($tokens[-1] -ne 'com.fs.starfarer.StarfarerLauncher') { throw "vmparams does not end with the launcher main class (got '$($tokens[-1])')" }
    foreach ($t in $tokens) {
        $hit = @($r.Refuse | Where-Object { $t.StartsWith($_, [StringComparison]::OrdinalIgnoreCase) }) | Select-Object -First 1
        if ($hit -or (Test-CcXlogWritesFile $t)) {
            throw "vmparams carries '$t' (refused: $(if ($hit) { $hit } else { '-Xlog output to a file' })) - it would make every joiner's JVM write a dump/recording/log file or load an agent; remove it from the vmparams the kit is minted from"
        }
    }
    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add("# kit-jvm.args - JVM options for the bundled jre-cc, derived from the game's own vmparams")
    $lines.Add("# deltas vs vmparams: -{$($r.Drop -join ' ') $($r.DropPrefix -join ' ')*} +{$($r.Add -join ' ')} paths \\->/ ; launcher window kept (no -DlaunchDirect)")
    $added = $false
    foreach ($t in $tokens) {
        if ($r.Drop -contains $t) { continue }
        if (@($r.DropPrefix | Where-Object { $t.StartsWith($_) }).Count -gt 0) { continue }
        if ($t -eq '-classpath' -and -not $added) { foreach ($a in $r.Add) { $lines.Add($a) }; $added = $true }
        if ($t -match '\s') { throw "vmparams token with whitespace is unsupported: '$t'" }
        $fixed = ($t -replace '\\\\', '/') -replace '\\', '/'
        $lines.Add($fixed)
    }
    if (-not $added) { throw 'vmparams carries no -classpath token' }
    return $lines.ToArray()   # a flat string[]: the same shape however a caller collects it ($x = ..., @(...), a cast)
}

# The JDK's IMPLEMENTOR_VERSION (its release file), which must name an Eclipse Temurin build - else a throw.
function Read-CcTemurinVersion([string]$JdkHome) {
    $release = Join-Path $JdkHome 'release'
    if (-not (Test-Path -LiteralPath $release -PathType Leaf)) { throw "not a JDK folder (no release file): $JdkHome" }
    $implVer = (Get-Content -LiteralPath $release | Where-Object { $_ -like 'IMPLEMENTOR_VERSION=*' } | Select-Object -First 1) -replace '^IMPLEMENTOR_VERSION=', '' -replace '"', ''
    if ($implVer -notlike 'Temurin-*') { throw "$JdkHome is not an Eclipse Temurin JDK (IMPLEMENTOR_VERSION='$implVer') - the kit bundles Eclipse Temurin only" }
    return $implVer
}

# The rows of the image's cc-kit-jre.txt stamp that describe the image itself (the kit packer reads implementorVersion=
# for the kit README's Java line); a caller may add rows of its own around them.
function Get-CcKitRuntimeStampRows([string]$ImplVer) {
    return @("implementorVersion=$ImplVer", "modules=$($CcKitRuntime.Modules -join ',')",
        "jlink=$($CcKitRuntime.JlinkFlags -join ' ')", 'license=GPLv2+Classpath-Exception (legal\ preserved - never strip)')
}

# jlink the image from $JdkHome into $OutDir (replaced whole), then check it: the launchers, the module image and the
# base module's licence texts are there, and its java -version names the JDK's own build. Returns the image's byte count.
function Invoke-CcKitJlink([string]$JdkHome, [string]$OutDir, [string]$ImplVer) {
    $jlink = Join-Path $JdkHome 'bin\jlink.exe'
    if (-not (Test-Path -LiteralPath $jlink -PathType Leaf)) { throw "jlink not found: $jlink (a JDK 25 is expected; a jmods-less JDK links from its own run-time image)" }
    if (Test-Path -LiteralPath $OutDir) { Remove-Item -Recurse -Force -LiteralPath $OutDir }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $OutDir) | Out-Null
    & $jlink --add-modules ($CcKitRuntime.Modules -join ',') @($CcKitRuntime.JlinkFlags) --output $OutDir 2>&1 | ForEach-Object { Write-Host "[jlink] $_" }
    if ($LASTEXITCODE -ne 0) { throw "jlink failed (exit $LASTEXITCODE)" }
    foreach ($must in @('bin\java.exe', 'bin\javaw.exe', 'lib\modules', 'legal\java.base\LICENSE', 'legal\java.base\ASSEMBLY_EXCEPTION')) {
        if (-not (Test-Path -LiteralPath (Join-Path $OutDir $must))) { throw "jlink image is missing $must" }
    }
    $banner = (& (Join-Path $OutDir 'bin\java.exe') -version 2>&1 | Out-String).Trim()
    if ($banner -notmatch [regex]::Escape($ImplVer)) { throw "image java -version does not report $ImplVer :`n$banner" }
    return (Get-ChildItem -LiteralPath $OutDir -Recurse -File | Measure-Object Length -Sum).Sum
}
