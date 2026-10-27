#Requires -Version 7
# build.ps1 - builds this source tree on Windows: the headless server's classes and agent jars, then the client mod
# jar. No tests, no checks, nothing is installed or copied into the game: every output lands inside this tree (but the
# server folder -ServerFolder names and the kit zip -KitZip names).
#
# OUTPUTS (the same relative paths the maintainer's internal build writes, so the two can be compared file by file):
#   server/classes                          the server, the vendored Concentus audio codec, the game-API probe classes
#   server/premain/classes + cc-premain.jar the class-transform agent, with the ASM library copied into it
#   server/premain/cc-rngpin-holder.jar     the random-pin holder classes, packed a second time on their own (the
#                                           agent adds this jar to the boot class path when the pin is armed)
#   client-mod/build/classes                the client mod's classes plus the server classes it shares
#   client-mod/mod/jars/cc-client-mod.jar   the client mod jar
#   client-mod/mod/SoundProbe.class         the mod kit's stand-alone sound self-check (a loose class, not in the jar)
#   build/kit-stage, build/kit-entries.txt  -KitZip only: the joiner kit's staged files and the entry list packed
#   client-mod/mod/jre-cc, client-mod/mod/kit-jvm.args  -KitRuntime only: the kit's own Java runtime + its launch options
#
# NEEDS: PowerShell 7; a JDK 17 or newer (javac + jar + java); for the client, a JDK 25 as well; an installed
# Starsector 0.98a (its jars are on the compile class path; nothing in the game folder is written).
#
# WHY TWO JDKs: the client's voice package (client-mod/src/cc/starsector/client/voice/ffm) uses the Java foreign
# function and memory API, final in Java 22, so a JDK 25 javac compiles it with --release 22, plus the three small
# factory classes that name it (at --release 17). Every other class targets Java 17 (the sound probe: Java 8).
#
# Usage: build.ps1 [-GameDir <Starsector folder>] [-Jdk17Bin <JDK bin>] [-Jdk25Bin <JDK 25 bin>] [-SkipClient] [-KitRuntime]
#                  [-ServerFolder <dir> -Package <cc-server zip> [-Kit <player kit zip>]]
#                  [-KitZip <kit zip to write> -Descriptor <match file>]
#   -Jdk17Bin   default: $env:JAVA_HOME\bin when it holds javac.exe, else the folder of the javac on PATH.
#   -Jdk25Bin   required unless -SkipClient.
#   -ServerFolder + -Package (always together): after the server build, lay out a runnable server folder in <dir> (an
#               empty folder, or one this step made before): this build's server classes and cc-premain.jar, and
#               everything else (Java runtime, client mod, start scripts, README) from the released server package zip.
#               A re-run never overwrites the settings files, start scripts, README or match files already there.
#               -Kit: the player kit zip the folder serves (default: the package's own).
#   -KitZip + -Descriptor (always together; not with -SkipClient): after the client build, pack the joiner kit from
#               this build's client-mod/mod through the same kit packer class the released kits are made with. The kit
#               carries no match file: the join window fetches the match from the server, so one kit joins every
#               match. -Descriptor = a redacted match file (a seat-token or password file is refused, and no seat
#               token leaves this host): the packer checks its ships against this mod and the game, and keys to its
#               match the server-side fingerprint sidecar it writes to build/kit-stage/match.txt.fingerprint; the
#               kit's own CC_Client/kit.files is that sidecar without the match term. -KitZip: a new file (an
#               existing one is never replaced), its name letters, digits, '.', '_' or '-' ending .zip (at most 80
#               characters, the server's MANIFEST pack.kit grammar), in a folder that exists. The kit carries CC_Client/THIRD-PARTY-NOTICES.txt
#               (its LICENSE.txt points there): the Concentus licence text, copied whole from
#               server/vendor/concentus/LICENSE, because that codec is built into the kit's jar.
#   -KitRuntime (not with -SkipClient): after the client build, give the kit its own Java runtime: jlink the runtime
#               image client-mod/mod/jre-cc from the -Jdk25Bin JDK (an Eclipse Temurin build - another vendor's is
#               refused by name: the kit's README and notices name Temurin), copy that JDK's NOTICE beside it, and derive
#               client-mod/mod/kit-jvm.args from -GameDir's vmparams. The module list, the jlink flags and the vmparams
#               rules are client-mod/kit-runtime.ps1's (the maintainer's kit scripts read the same file). Both stay in
#               the mod folder across builds (the image is relinked only when the JDK build or the module list changes;
#               the args are re-derived every run), so every kit packed after it carries them and join-cc.bat runs the
#               join window and the game on that runtime: the voice chat's low-latency path needs Java 22 or newer.
#               To go back to a kit without a runtime, delete the two. Without them join-cc.bat runs the game on its
#               own Java 17, and voice chat takes its higher-latency path.
#   A source host with its OWN kit, two runs: first build.ps1 -KitZip <kit zip> -Descriptor <match file>, then
#               build.ps1 -SkipClient -ServerFolder <dir> -Package <cc-server zip> -Kit <that kit zip>; the folder's
#               MANIFEST then names that kit (pack.kit) and its jar (kit.jar.md5), the folder holds the kit zip at
#               <dir>\kits\<its name> (the file to hand players), and its match\match.txt.fingerprint is keyed to that
#               kit's content (re-keyed when the package's names another build), keeping the package's match, so the
#               server's door admits players holding that kit.
#               Add -KitRuntime to the first run for a kit with its own Java runtime (above).
#               A packer refusal names its reason token: census = the match names a ship variant neither this mod nor
#               the game (-GameDir) has; match_read = the match file is unreadable or not a match file; live_token
#               = it carries a seat token the redaction could not clear; keep_list = client-mod/kit-keep-variants.txt
#               names a variant this mod does not carry, or keeps none; write = the zip or its sidecar could not be
#               written.
#   A source host: build, then -ServerFolder <empty dir> -Package <the released cc-server zip>.
#   exit 0 = built; any failure throws, naming the step.
param(
    [string] $GameDir = 'C:\Program Files (x86)\Fractal Softworks\Starsector',
    [string] $Jdk17Bin = '',
    [string] $Jdk25Bin = '',
    [switch] $SkipClient,
    [switch] $KitRuntime,
    [string] $ServerFolder = '',
    [string] $Package = '',
    [string] $Kit = '',
    [string] $KitZip = '',
    [string] $Descriptor = ''
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
if ([bool]$ServerFolder -ne [bool]$Package) { throw '-ServerFolder and -Package come together: pass both (the folder to lay out and the released cc-server zip), or neither' }
if ($Kit -and -not $ServerFolder) { throw '-Kit belongs to -ServerFolder / -Package: pass all three, or drop -Kit' }
if ($Package -and -not (Test-Path -LiteralPath $Package -PathType Leaf)) { throw "-Package is not a file: $Package (the released cc-server zip)" }
# The -KitZip / -Descriptor checks run before anything builds. A seat-token twin or a password file is refused by its
# NAME, before any read of it (the same names KitPackRules.isLeakName refuses inside the packer); the zip name keeps to
# the packer's ok row grammar (no spaces); an existing zip is refused here, not by the packer after the build.
if ([bool]$KitZip -ne [bool]$Descriptor) { throw '-KitZip and -Descriptor come together: pass both (the kit zip to write and the match file it carries), or neither' }
if ($KitZip -and $SkipClient) { throw '-KitZip packs the client build into the kit: drop -SkipClient' }
if ($KitRuntime -and $SkipClient) { throw '-KitRuntime links the kit''s Java runtime beside the client build: drop -SkipClient' }
if ($KitZip) {
    $descLeaf = (Split-Path -Leaf $Descriptor).ToLowerInvariant()
    if ($descLeaf.Contains('-tokens') -or $descLeaf.EndsWith('.password') -or $descLeaf.EndsWith('.adminpassword') -or $descLeaf -eq 'cc-door-credentials.properties') {
        throw '-Descriptor names a seat-token or password file: pass the redacted match file itself (a secret never enters a kit)'
    }
    $pathApi = $ExecutionContext.SessionState.Path
    $descriptorFull = $pathApi.GetUnresolvedProviderPathFromPSPath($Descriptor)
    $kitZipFull = $pathApi.GetUnresolvedProviderPathFromPSPath($KitZip)
    if (-not (Test-Path -LiteralPath $descriptorFull -PathType Leaf)) { throw "-Descriptor is not a file: $Descriptor (the match file the kit carries)" }
    if ((Split-Path -Leaf $kitZipFull) -notmatch '^[A-Za-z0-9._-]{1,80}\.zip$') { throw "-KitZip name must be letters, digits, '.', '_' or '-' ending .zip, at most 80 characters (the MANIFEST pack.kit grammar): $(Split-Path -Leaf $kitZipFull)" }
    if (-not (Test-Path -LiteralPath (Split-Path -Parent $kitZipFull) -PathType Container)) { throw "-KitZip's folder does not exist: $(Split-Path -Parent $kitZipFull)" }
    if (Test-Path -LiteralPath $kitZipFull) { throw "-KitZip already exists: $kitZipFull (the packer never replaces a kit; pick a new name or move the old one)" }
}

$root = $PSScriptRoot
$server = Join-Path $root 'server'
$client = Join-Path $root 'client-mod'
foreach ($need in @((Join-Path $server 'src'), (Join-Path $client 'src'))) {
    if (-not (Test-Path -LiteralPath $need -PathType Container)) { throw "not the exported source tree: $need is missing (this script builds the tree it sits in)" }
}

# ---- tools: the two JDKs, the game jars, the ASM library ------------------------------------------------------------
if (-not $Jdk17Bin) {
    if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\javac.exe') -PathType Leaf)) {
        $Jdk17Bin = Join-Path $env:JAVA_HOME 'bin'
    } else {
        $onPath = @(Get-Command javac -CommandType Application -ErrorAction SilentlyContinue)
        if ($onPath.Count -eq 0) { throw 'no JDK found: pass -Jdk17Bin <JDK 17+ bin folder>, set JAVA_HOME, or put javac on PATH' }
        $Jdk17Bin = Split-Path -Parent $onPath[0].Source
    }
}
$javac = Join-Path $Jdk17Bin 'javac.exe'
$jar = Join-Path $Jdk17Bin 'jar.exe'
$java = Join-Path $Jdk17Bin 'java.exe'
foreach ($exe in @($javac, $jar, $java)) {
    if (-not (Test-Path -LiteralPath $exe -PathType Leaf)) { throw "-Jdk17Bin holds no $(Split-Path -Leaf $exe): $Jdk17Bin" }
}
if (-not $SkipClient) {
    if (-not $Jdk25Bin) { throw '-Jdk25Bin is required for the client (a JDK 25 bin folder: its javac compiles the voice package at --release 22); or pass -SkipClient' }
    $javac25 = Join-Path $Jdk25Bin 'javac.exe'
    if (-not (Test-Path -LiteralPath $javac25 -PathType Leaf)) { throw "-Jdk25Bin holds no javac.exe: $Jdk25Bin" }
}
Write-Host "JDK: $Jdk17Bin$(if ($SkipClient) { ' (client skipped)' } else { "; JDK 25: $Jdk25Bin" })"

$core = Join-Path $GameDir 'starsector-core'
$gameJars = @('json.jar', 'starfarer.api.jar', 'lwjgl_util.jar')
if (-not $SkipClient) { $gameJars += @("starfarer_obf.jar", 'fs.common_obf.jar', 'fs.sound_obf.jar', 'lwjgl.jar', 'log4j-1.2.9.jar') }
$absent = @($gameJars | Where-Object { -not (Test-Path -LiteralPath (Join-Path $core $_) -PathType Leaf) })
if ($absent.Count -gt 0) { throw "game jar(s) missing under ${core}: $($absent -join ', ') (pass -GameDir <Starsector folder>)" }
$jsonJar = Join-Path $core 'json.jar'

# The ASM library ships beside the sources with its sha256; a missing or changed jar stops the build before any use.
$asmJar = Join-Path $root 'third-party\asm\asm-9.10.1.jar'
$asmPin = "$asmJar.sha256"
if (-not (Test-Path -LiteralPath $asmJar -PathType Leaf)) { throw "ASM jar missing: $asmJar" }
if (-not (Test-Path -LiteralPath $asmPin -PathType Leaf)) { throw "ASM sha256 file missing: $asmPin" }
$asmWant = ((Get-Content -LiteralPath $asmPin -Raw) -split '\s+')[0].Trim().ToLowerInvariant()
$asmHave = (Get-FileHash -LiteralPath $asmJar -Algorithm SHA256).Hash.ToLowerInvariant()
if ($asmHave -ne $asmWant) { throw "ASM jar sha256 mismatch: file $asmHave, expected $asmWant ($asmJar)" }

function Reset-Dir([string] $Path) {
    $full = [IO.Path]::GetFullPath($Path)
    if (-not $full.StartsWith(([IO.Path]::GetFullPath($root).TrimEnd('\') + '\'), [StringComparison]::OrdinalIgnoreCase)) { throw "refusing to clear a folder outside this tree: $full" }
    if (Test-Path -LiteralPath $full) { Remove-Item -LiteralPath $full -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $full | Out-Null
}

# javac reads long source lists from an @argfile (the Windows command line is capped near 32K characters): one quoted
# path per line, forward slashes (inside quotes the argfile reader treats a backslash as an escape).
function Invoke-Javac([string] $Unit, [string] $Exe, [string[]] $JavacArgs, [string[]] $SourceFiles) {
    if ($SourceFiles.Count -eq 0) { throw "${Unit}: no .java sources" }
    $argFile = Join-Path ([IO.Path]::GetTempPath()) "source-build-javac-$Unit-$PID.args"
    [IO.File]::WriteAllLines($argFile, [string[]]@($SourceFiles | ForEach-Object { '"' + $_.Replace('\', '/') + '"' }))
    try { & $Exe -encoding UTF-8 @JavacArgs "@$argFile"; $code = $LASTEXITCODE }
    finally { Remove-Item -LiteralPath $argFile -Force -ErrorAction SilentlyContinue }
    if ($code -ne 0) { throw "${Unit}: javac exited $code" }
}

function Assert-Exit([string] $Unit) { if ($LASTEXITCODE -ne 0) { throw "${Unit}: exited $LASTEXITCODE" } }

# ---- server -----------------------------------------------------------------------------------------------------------
# 1. product: the server sources and the vendored codec sources, one javac unit; the server itself needs only org.json.
$classes = Join-Path $server 'classes'
Reset-Dir $classes
$sources = @(Get-ChildItem -LiteralPath (Join-Path $server 'src') -Recurse -Filter '*.java') +
    @(Get-ChildItem -LiteralPath (Join-Path $server 'vendor') -Recurse -Filter '*.java' -ErrorAction SilentlyContinue)
Invoke-Javac 'server product' $javac @('--release', '17', '-cp', $jsonJar, '-d', $classes) @($sources.FullName)
Write-Host "server product: $($sources.Count) sources -> server/classes"

# 2. probe-api: the few classes that implement game API interfaces, compiled against the game's API jar.
$probeSources = @(Get-ChildItem -LiteralPath (Join-Path $server 'probe-api\src') -Recurse -Filter '*.java' -ErrorAction SilentlyContinue)
if ($probeSources.Count -gt 0) {
    $probeCp = "$core\starfarer.api.jar;$core\lwjgl_util.jar;$jsonJar;$classes"
    Invoke-Javac 'server probe-api' $javac @('--release', '17', '-cp', $probeCp, '-d', $classes) @($probeSources.FullName)
    Write-Host "server probe-api: $($probeSources.Count) sources -> server/classes"
}

# 3. premain: the transform agent, compiled against ASM, then every org/objectweb/asm class copied in (the jar's
# module-info.class and META-INF are left out), so the agent jar runs on any JDK 17 or newer with no extra jar.
$premain = Join-Path $server 'premain'
$premainClasses = Join-Path $premain 'classes'
Reset-Dir $premainClasses
$premainSources = @(Get-ChildItem -LiteralPath (Join-Path $premain 'src') -Recurse -Filter '*.java')
Invoke-Javac 'server premain' $javac @('--release', '17', '-cp', "$classes;$asmJar", '-d', $premainClasses) @($premainSources.FullName)
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($asmJar)
$shaded = 0
try {
    foreach ($entry in $zip.Entries) {
        if ($entry.FullName.EndsWith('/')) { continue }
        if ($entry.FullName -notlike 'org/objectweb/asm/*.class') { continue }
        $dest = Join-Path $premainClasses ($entry.FullName -replace '/', '\')
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $dest) | Out-Null
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $dest, $true)
        $shaded++
    }
} finally { $zip.Dispose() }
if ($shaded -lt 30) { throw "server premain: only $shaded ASM class(es) in $asmJar (a different jar layout?)" }
Write-Host "server premain: $($premainSources.Count) sources + $shaded ASM classes -> server/premain/classes"

# 4. the two agent-side jars. cc-premain.jar = the agent + ASM + the product unit's transform package (the registry
# classes the agent reads); cc-rngpin-holder.jar = the product unit's rngpin package alone.
$premainJar = Join-Path $premain 'cc-premain.jar'
$holderJar = Join-Path $premain 'cc-rngpin-holder.jar'
foreach ($j in @($premainJar, $holderJar)) { if (Test-Path -LiteralPath $j) { Remove-Item -LiteralPath $j -Force } }
& $jar --create --file $premainJar --manifest (Join-Path $premain 'MANIFEST.MF') -C $premainClasses . -C $classes cc/starsector/server/transform
Assert-Exit 'cc-premain.jar'
& $jar --create --file $holderJar -C $classes cc/starsector/server/rngpin
Assert-Exit 'cc-rngpin-holder.jar'
Write-Host 'server jars: server/premain/cc-premain.jar, server/premain/cc-rngpin-holder.jar'

# 4b. -ServerFolder / -Package: the package folder writer in this build's own classes lays the folder out (it refuses,
# naming why and changing nothing, on a folder that is not empty and was not made by it, a running server, or a zip that
# is not a server package). The C2 directives file is not in this tree, so the folder keeps the package's.
if ($ServerFolder) {
    $paths = $ExecutionContext.SessionState.Path
    $writerArgs = @('--kind', 'source', '--pkg', $paths.GetUnresolvedProviderPathFromPSPath($ServerFolder),
        '--base', $paths.GetUnresolvedProviderPathFromPSPath($Package), '--classes', $classes, '--premain', $premainJar,
        '--holder', $holderJar)
    if ($Kit) { $writerArgs += @('--kit', $paths.GetUnresolvedProviderPathFromPSPath($Kit)) }
    & $java -cp $classes cc.starsector.server.ops.PackageFolderWriter @writerArgs
    Assert-Exit 'server folder'
    Write-Host "server folder: $ServerFolder (from $(Split-Path -Leaf $Package))"
}

if ($SkipClient) { Write-Host 'client skipped (-SkipClient)'; exit 0 }

# ---- client mod -------------------------------------------------------------------------------------------------------
# 5. the compile class path: the game's jars (the game runtime has them all) + the server classes.
$cp = @("$core\starfarer.api.jar", "$core\starfarer_obf.jar", "$core\fs.common_obf.jar", "$core\fs.sound_obf.jar",
    "$core\lwjgl.jar", "$core\lwjgl_util.jar", "$core\log4j-1.2.9.jar", $jsonJar, $classes) -join ';'
$out = Join-Path $client 'build\classes'
Reset-Dir $out
$clientSrc = Join-Path $client 'src'

# 6. three javac passes. A (JDK 25, --release 22): the voice/ffm package; -sourcepath + -implicit:none read the types
# it names for their signatures without emitting them. B (JDK 25, --release 17): the three factories that name it.
# Main (JDK 17): everything else, with A and B's classes on the class path.
$allSrcFiles = @(Get-ChildItem -LiteralPath $clientSrc -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
$ffmSrcFiles = @($allSrcFiles | Where-Object { $_ -like '*\voice\ffm\*' })
$voiceDir = Join-Path $clientSrc 'cc\starsector\client\voice'
$bridgeSrcFiles = @('VoiceSinkFactory.java', 'VoiceSourceFactory.java', 'VoiceProbeEarFactory.java' | ForEach-Object { Join-Path $voiceDir $_ })
$srcFiles = @($allSrcFiles | Where-Object { $_ -notlike '*\voice\ffm\*' -and $bridgeSrcFiles -notcontains $_ })
if ($ffmSrcFiles.Count -eq 0 -or @($bridgeSrcFiles | Where-Object { -not (Test-Path -LiteralPath $_) }).Count -gt 0) { throw 'client: the voice/ffm sources or one of the three voice factory sources are missing' }
# -XDstringConcat=inline: string concatenation compiles to plain StringBuilder chains, not invokedynamic call sites
# whose first run spins method handles (a measured first-frame stall in game).
& $javac25 -encoding UTF-8 --release 22 -XDstringConcat=inline -Xlint:all,-restricted -sourcepath $clientSrc -implicit:none -d $out $ffmSrcFiles
Assert-Exit 'client pass A (voice/ffm, JDK 25)'
& $javac25 -encoding UTF-8 --release 17 -XDstringConcat=inline -Xlint:all -sourcepath $clientSrc -implicit:none -cp $out -d $out $bridgeSrcFiles
Assert-Exit 'client pass B (voice factories, JDK 25)'
Invoke-Javac 'client main' $javac @('--release', '17', '-XDstringConcat=inline', '-Xlint:all', '-cp', "$cp;$out", '-d', $out) $srcFiles
Write-Host "client: $($ffmSrcFiles.Count) + $($bridgeSrcFiles.Count) + $($srcFiles.Count) sources -> client-mod/build/classes"

# 7. the sound self-check probe: --release 8 so both the game's Java 17 and newer runtimes run it.
$probeSrc = Join-Path $client 'sound-check\SoundProbe.java'
if (-not (Test-Path -LiteralPath $probeSrc -PathType Leaf)) { throw "client: missing $probeSrc" }
& $javac -encoding UTF-8 --release 8 -Xlint:-options -cp "$core\lwjgl.jar" -d (Join-Path $client 'mod') $probeSrc
Assert-Exit 'SoundProbe'
Write-Host 'client: SoundProbe -> client-mod/mod/SoundProbe.class'

# 8. the fold: the client jar carries copies of the server classes both sides run (the wire codecs, the match and
# officer formats, the handshake, the shared laws), each with its nested classes (<Name>$*.class), and the whole
# vendored codec package, so client and server read and write the same formats with the same code. The maintainer's
# internal build holds this list SET-EQUAL to its own fold list on every build (a class missing here would be a
# NoClassDefFoundError in the game for a build of this tree only), so the two lists name the same entries.
$fold = @(
    'cc\starsector\server\client\PilotIntentClientSession.class',
    'cc\starsector\server\runtime\PilotIntentFrame.class',
    'cc\starsector\server\runtime\PilotIntentWireCodec.class',
    'cc\starsector\server\runtime\PilotWorldViewFrame.class',
    'cc\starsector\server\runtime\PilotWorldViewWireCodec.class',
    'cc\starsector\server\runtime\WireByteOps.class',
    'cc\starsector\server\runtime\PilotManeuverIntent.class',
    'cc\starsector\server\runtime\PilotFireIntent.class',
    'cc\starsector\server\match\MatchDescriptor.class',
    'cc\starsector\server\match\MatchDescriptorCodec.class',
    'cc\starsector\server\match\MatchDocumentRefused.class',
    'cc\starsector\server\match\MatchDocumentLaws.class',
    'cc\starsector\server\match\OfficerRosterCodec.class',
    'cc\starsector\server\match\OfficerSkillCheck.class',
    'cc\starsector\server\match\AllSkillsEnumerator.class',
    'cc\starsector\server\match\AllSkillsFleetLaw.class',
    'cc\starsector\server\match\OfficerRouting.class',
    'cc\starsector\server\protocol\SeatRole.class',
    'cc\starsector\server\runtime\DatagramFrameCodec.class',
    'cc\starsector\server\runtime\VoiceFrameCodec.class',
    'cc\starsector\server\runtime\DatagramReliability.class',
    'cc\starsector\server\runtime\HandshakeCodec.class',
    'cc\starsector\server\runtime\Handshake13.class',
    'cc\starsector\server\runtime\Handshake13Auth.class',
    'cc\starsector\server\runtime\Handshake13Flight.class',
    'cc\starsector\server\runtime\Handshake13PairBudget.class',
    'cc\starsector\server\runtime\HandshakeCrypto.class',
    'cc\starsector\server\runtime\DoorShares13.class',
    'cc\starsector\server\runtime\DoorRejectReasons.class',
    'cc\starsector\server\runtime\VariantCheckFrameCodec.class',
    'cc\starsector\server\runtime\WireTextLaw.class',
    'cc\starsector\server\runtime\ClientPostureRegistry.class',
    'cc\starsector\server\runtime\WireKnob.class',
    'cc\starsector\server\runtime\TerrainStatLaw.class',
    'cc\starsector\server\runtime\SnapDeflateCodec.class',
    # the match-document frame codec: its nested classes are named one by one, as the internal build's list names
    # them (the loop below copies them either way)
    'cc\starsector\server\runtime\MatchDocFrameCodec.class',
    'cc\starsector\server\runtime\MatchDocFrameCodec$Chunk.class',
    'cc\starsector\server\runtime\MatchDocFrameCodec$Decoded.class',
    'cc\starsector\server\runtime\MatchDocFrameCodec$Reassembler.class',
    'cc\starsector\server\match\ContentFingerprints.class',
    'cc\starsector\server\match\ContentFingerprintClientSurface.class',
    'cc\starsector\server\runtime\IdTables.class',
    'cc\starsector\server\runtime\IdTablesJson.class',
    'cc\starsector\server\runtime\ModSet.class',
    'cc\starsector\server\runtime\ModDataWalk.class',
    'cc\starsector\server\client\ChunkMergerCore.class'
)
foreach ($rel in $fold) {
    $srcPath = Join-Path $classes $rel
    if (-not (Test-Path -LiteralPath $srcPath -PathType Leaf)) { throw "fold: $rel is missing from server/classes" }
    $dstDir = Split-Path -Parent (Join-Path $out $rel)
    New-Item -ItemType Directory -Force -Path $dstDir | Out-Null
    $baseName = [IO.Path]::GetFileNameWithoutExtension($srcPath)
    $siblings = Get-ChildItem -LiteralPath (Split-Path -Parent $srcPath) -Filter "$baseName*.class" | Where-Object {
        $_.Name -eq "$baseName.class" -or $_.Name -like "$baseName`$*.class"
    }
    foreach ($cls in $siblings) { Copy-Item -LiteralPath $cls.FullName -Destination (Join-Path $dstDir $cls.Name) -Force }
}
$codecDir = Join-Path $classes 'org\concentus'
if (-not (Test-Path -LiteralPath $codecDir -PathType Container)) { throw 'fold: org\concentus is missing from server/classes' }
$codecDst = Join-Path $out 'org\concentus'
New-Item -ItemType Directory -Force -Path $codecDst | Out-Null
Get-ChildItem -LiteralPath $codecDir -Filter '*.class' | ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $codecDst $_.Name) -Force }
Write-Host "client fold: $($fold.Count) server classes + their nested classes, org/concentus"

# 9. jar resources. web/: the in-game pages, already comment-free in this tree, copied file for file. mod-skins.txt:
# this mod's own skin hull ids (sorted, LF, UTF-8 without BOM), the client's list of mod-defined hulls.
$webSrc = Join-Path $client 'web'
if (Test-Path -LiteralPath $webSrc -PathType Container) {
    $webDst = Join-Path $out 'web'
    foreach ($f in @(Get-ChildItem -LiteralPath $webSrc -Recurse -File)) {
        $dst = Join-Path $webDst ([IO.Path]::GetRelativePath($webSrc, $f.FullName))
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $dst) | Out-Null
        Copy-Item -LiteralPath $f.FullName -Destination $dst
    }
}
$skinIds = @(Get-ChildItem -LiteralPath (Join-Path $client 'mod\data\hulls\skins') -Filter '*.skin' -File | ForEach-Object {
    $m = [regex]::Match((Get-Content -LiteralPath $_.FullName -Raw), '"skinHullId"\s*:\s*"([^"]+)"')
    if ($m.Success) { $m.Groups[1].Value }
} | Sort-Object -Unique)
$skinResDir = Join-Path $out 'cc\starsector\client\editor'
New-Item -ItemType Directory -Force -Path $skinResDir | Out-Null
[IO.File]::WriteAllText((Join-Path $skinResDir 'mod-skins.txt'), (($skinIds -join "`n") + "`n"), [Text.UTF8Encoding]::new($false))
Write-Host "client resources: web files $(@(Get-ChildItem -LiteralPath (Join-Path $out 'web') -Recurse -File -ErrorAction SilentlyContinue).Count), mod skins $($skinIds.Count)"

# 10. the mod jar; --date pins every entry time, so the same classes always pack to the same jar bytes.
$jarsDir = Join-Path $client 'mod\jars'
New-Item -ItemType Directory -Force -Path $jarsDir | Out-Null
$modJar = Join-Path $jarsDir 'cc-client-mod.jar'
if (Test-Path -LiteralPath $modJar) { Remove-Item -LiteralPath $modJar -Force }
& $jar --create --date 2026-01-01T00:00:00Z --file $modJar -C $out .
Assert-Exit 'cc-client-mod.jar'
Write-Host 'client jar: client-mod/mod/jars/cc-client-mod.jar'

# 10b. -KitRuntime: the kit's own Java runtime, in the mod folder so a kit packed from it (11.) carries it and so does a
# copy of client-mod/mod. client-mod/kit-runtime.ps1 holds the module list, the jlink flags, the Temurin check and the
# vmparams rules; this step links client-mod/mod/jre-cc from the -Jdk25Bin JDK, copies the JDK's own NOTICE beside it
# (the kit's notices section cites it), writes the image stamp cc-kit-jre.txt (the kit packer reads its
# implementorVersion row for the kit README's Java line), and derives client-mod/mod/kit-jvm.args from -GameDir's
# vmparams. Cached: the same JDK build + module list + flags leaves the image alone; the args are re-derived every run.
if ($KitRuntime) {
    . (Join-Path $client 'kit-runtime.ps1')
    $jdk25Home = Split-Path -Parent $Jdk25Bin
    $implVer = Read-CcTemurinVersion $jdk25Home
    $jreDir = Join-Path $client 'mod\jre-cc'
    $stampPath = Join-Path $jreDir 'cc-kit-jre.txt'
    $stampRows = Get-CcKitRuntimeStampRows $implVer
    $cached = (Test-Path -LiteralPath $stampPath -PathType Leaf) -and (Test-Path -LiteralPath (Join-Path $jreDir 'bin\javaw.exe') -PathType Leaf) -and
        ((@(Get-Content -LiteralPath $stampPath) -join "`n") -ceq ($stampRows -join "`n"))
    if ($cached) {
        Write-Host "kit runtime: client-mod/mod/jre-cc kept ($implVer; the stamp is unchanged)"
    } else {
        $bytes = Invoke-CcKitJlink -JdkHome $jdk25Home -OutDir $jreDir -ImplVer $implVer
        $stampRows | Set-Content -LiteralPath $stampPath -Encoding ASCII
        Write-Host "kit runtime: client-mod/mod/jre-cc linked ($implVer, $($CcKitRuntime.Modules.Count) modules, $([math]::Round($bytes / 1MB, 1)) MB)"
    }
    $jdkNotice = Join-Path $jdk25Home 'NOTICE'
    if (-not (Test-Path -LiteralPath $jdkNotice -PathType Leaf)) { throw "kit runtime: $jdk25Home has no NOTICE file - the kit never ships the runtime without its notice" }
    Copy-Item -LiteralPath $jdkNotice -Destination (Join-Path $jreDir 'NOTICE') -Force
    $vm = Join-Path $GameDir 'vmparams'
    if (-not (Test-Path -LiteralPath $vm -PathType Leaf)) { throw "kit runtime: vmparams not found: $vm (pass -GameDir <Starsector folder>)" }
    $argLines = [string[]] (Convert-CcVmparamsToKitArgs (Get-Content -LiteralPath $vm -Raw))
    if ($argLines -notcontains '--enable-native-access=ALL-UNNAMED') { throw 'kit runtime: the derived kit-jvm.args lacks --enable-native-access=ALL-UNNAMED (the voice path needs it)' }
    $argLines | Set-Content -LiteralPath (Join-Path $client 'mod\kit-jvm.args') -Encoding ASCII
    Write-Host "kit runtime: client-mod/mod/kit-jvm.args ($($argLines.Count - 2) options from vmparams)"
}

<#
11. -KitZip: the joiner kit, packed from this build by the same KitPacker main the maintainer's kit script calls (dir:
mode, posture=redact), so a host building from source never needs that script. The stage = build/kit-stage/CC_Client,
a file-by-file copy of client-mod/mod (the kit's mod root, the jar just built inside it, and jre-cc + kit-jvm.args when
-KitRuntime put them there). Nothing of the match is staged and the kit carries no match file: the packer composes
CC_Client/kit.files + CC_Client/kit-manifest.txt + the root README.md itself, and writes the match-keyed sidecar (the
match id read off the descriptor) to the sidecarOut file beside the stage. So the walk SKIPS a match file already in
the mod folder (match.txt, its three siblings and kit.files: never read) and REFUSES a seat-token or password file
(never read, never copied). The entry list = the stage's files as '/'-relative names minus the three composed names
and CC_Client/match.txt (never packed), the maintainer script's form; the packer reads exactly the listed
names (an unlisted stage file is not packed, a listed name that is no file is refused) and sorts the zip itself.
Game = the census root (a roster variant the mod does not ship must be a vanilla one). A refusal throws with the
packer's reason token only (its own explanation reaches the console on stderr). Returns the entry count.
#>
# The notices' Java-runtime section, when the stage carries jre-cc: the same fields the maintainer's notices composer
# writes for the released kit, every value copied from the files on disk (the JDK's and the image's release files, the
# image's NOTICE and legal\ folder, each Microsoft DLL's own version information), none typed but Microsoft's two terms
# pages. Fail-closed: a missing file, a version mismatch between image and JDK (the source rows would then point at
# another build), a DLL that is not the JDK's own copy, or a licence file that names a Microsoft DLL stops the pack.
function Add-KitRuntimeNotices([System.Collections.Generic.List[string]] $L, [string] $Jdk, [string] $Image) {
    function Read-ReleaseMap([string] $Path) {
        if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "joiner kit: missing $Path" }
        $m = @{}
        foreach ($line in [IO.File]::ReadAllLines($Path)) { if ($line -match '^([A-Z_]+)="?(.*?)"?$') { $m[$Matches[1]] = $Matches[2] } }
        return $m
    }
    function Row($Map, [string] $Key, [string] $What) { if (-not $Map.ContainsKey($Key) -or -not "$($Map[$Key])".Trim()) { throw "joiner kit: $What has no $Key row" }; return "$($Map[$Key])".Trim() }
    $rel = Read-ReleaseMap (Join-Path $Jdk 'release')
    $imgRel = Read-ReleaseMap (Join-Path $Image 'release')
    $vendor = Row $rel 'IMPLEMENTOR' 'the JDK release file'
    $implVer = Row $rel 'IMPLEMENTOR_VERSION' 'the JDK release file'
    $jdkVer = Row $rel 'JAVA_VERSION' 'the JDK release file'
    $imgVer = Row $imgRel 'JAVA_VERSION' 'the runtime image release file'
    if ($imgVer -cne $jdkVer) { throw "joiner kit: the runtime image is JAVA_VERSION $imgVer but the JDK is $jdkVer - jre-cc was not linked from -Jdk25Bin's JDK; re-run with -KitRuntime" }
    $jdkMods = @((Row $rel 'MODULES' 'the JDK release file') -split '\s+' | Where-Object { $_ })
    $imgMods = @((Row $imgRel 'MODULES' 'the runtime image release file') -split '\s+' | Where-Object { $_ })
    $pointer = @(foreach ($k in @('SOURCE_REPO', 'SOURCE', 'BUILD_SOURCE_REPO', 'BUILD_SOURCE')) { "    $k=$(Row $rel $k 'the JDK release file')" })
    $noticePath = Join-Path $Image 'NOTICE'
    if (-not (Test-Path -LiteralPath $noticePath -PathType Leaf)) { throw 'joiner kit: jre-cc has no NOTICE file (the runtime''s own notice) - re-run with -KitRuntime' }
    $notice = [IO.File]::ReadAllLines($noticePath)
    $name = @($notice | Where-Object { $_ -match '^#\s*Notices for\s+\S' } | Select-Object -First 1) -replace '^#\s*Notices for\s+', ''
    if (-not "$name".Trim()) { throw 'joiner kit: the runtime''s NOTICE carries no "# Notices for <product>" heading' }
    $spdx = @($notice | Where-Object { $_ -match '^\s*SPDX-License-Identifier:\s*\S' } | Select-Object -First 1)
    if ($spdx.Count -eq 0) { throw 'joiner kit: the runtime''s NOTICE carries no SPDX-License-Identifier line' }
    $legalFiles = @(Get-ChildItem -LiteralPath (Join-Path $Image 'legal') -Recurse -File -ErrorAction SilentlyContinue)
    if ($legalFiles.Count -eq 0) { throw 'joiner kit: the runtime image carries no legal\ files' }
    $leaf = Split-Path -Leaf $Image
    $L.Add("Java runtime: $("$name".Trim()) ($implVer)")
    $L.Add("  What:    a Java runtime made with jlink from ${vendor}'s $implVer build")
    $L.Add("           ($($imgMods.Count) of its $($jdkMods.Count) modules)")
    $L.Add("  Where:   $leaf\ next to this file")
    $L.Add("  Licence: $($spdx[0].Trim())")
    $L.Add("           (copied from $leaf\NOTICE, the runtime's own notice file)")
    $L.Add("  The runtime's licence texts, and those of the parts it includes, are in")
    $L.Add("  $leaf\legal\ (one folder per module, $($legalFiles.Count) files).")
    $L.Add("  Source code: $vendor keeps the source code of this runtime build; we host")
    $L.Add("  no copy of it. These rows, copied from the build's own release file, point to")
    $L.Add('  where it is kept:')
    $L.AddRange([string[]] $pointer)
    $ms = @(Get-ChildItem -LiteralPath (Join-Path $Image 'bin') -Filter '*.dll' -File -ErrorAction SilentlyContinue | Sort-Object Name | ForEach-Object {
            $vi = [Diagnostics.FileVersionInfo]::GetVersionInfo($_.FullName); $co = "$($vi.CompanyName)".Trim()
            if ($co -like 'Microsoft*') { [pscustomobject]@{ Name = $_.Name; Company = $co; Product = "$($vi.ProductName)".Trim(); Rights = "$($vi.LegalCopyright)".Trim() } }
        })
    if ($ms.Count -gt 0) {
        $legalText = (@($legalFiles | ForEach-Object { [IO.File]::ReadAllText($_.FullName) }) -join "`n")
        $named = @($ms | Where-Object { $legalText.IndexOf([IO.Path]::GetFileNameWithoutExtension($_.Name), [StringComparison]::OrdinalIgnoreCase) -ge 0 })
        if ($named.Count -gt 0) { throw "joiner kit: a licence file under $leaf\legal\ names $($named[0].Name) - the Microsoft-files sentence of the notices no longer holds" }
        $rights = @($ms | ForEach-Object Rights | Sort-Object -Unique)
        if ($rights.Count -ne 1 -or -not $rights[0]) { throw "joiner kit: the Microsoft DLLs carry $($rights.Count) copyright lines - the notices expect one" }
        $changed = @($ms | Where-Object { $j = Join-Path $Jdk "bin\$($_.Name)"; -not (Test-Path -LiteralPath $j) -or (Get-FileHash -LiteralPath $j).Hash -ne (Get-FileHash -LiteralPath (Join-Path $Image "bin\$($_.Name)")).Hash })
        if ($changed.Count) { throw "joiner kit: $($changed[0].Name) in $leaf\bin is not the JDK's own copy - the notices' 'unmodified' sentence would not hold" }
        $apiSet = @($ms | Where-Object { $_.Name -like 'api-ms-win-*' })
        $names = [System.Collections.Generic.List[string]]::new()
        if ($apiSet.Count) { $names.Add("api-ms-win-*.dll ($($apiSet.Count) files)") }
        foreach ($m in $ms) { if ($m.Name -notlike 'api-ms-win-*') { $names.Add($m.Name) } }
        $L.Add("  Microsoft files: $leaf\bin\ also holds $($ms.Count) DLLs whose version information")
        $L.Add("  names $((@($ms | ForEach-Object Company | Sort-Object -Unique) -join ' / ')) as the company, as part of the runtime image:")
        $L.Add("    $($names -join ', ')")
        $L.Add("  Products: $((@($ms | Group-Object Product | Sort-Object Name | ForEach-Object { "$($_.Name) ($($_.Count))" })) -join ', '); copyright: $($rights[0])")
        $L.Add('  They are redistributed unmodified as part of the bundled Java runtime, under')
        $L.Add("  Microsoft's terms for these files:")
        $L.Add('    https://learn.microsoft.com/en-us/cpp/windows/redistributing-visual-cpp-files')
        $L.Add('    https://learn.microsoft.com/en-us/cpp/windows/universal-crt-deployment')
        $L.Add("  None of the runtime's licence files ($leaf\legal\) names them.")
    }
    $credits = [System.Collections.Generic.List[string]]::new()
    foreach ($c in @(@{ File = 'freetype.md'; Clause = '(?i)Redistribution in binary form must provide a disclaimer that states that (.+?), in the distribution documentation' },
            @{ File = 'jpeg.md'; Clause = '(?i)must state that "([^"]+)"' })) {
        foreach ($f in @($legalFiles | Where-Object Name -EQ $c.File)) {
            $raw = [IO.File]::ReadAllText($f.FullName)
            $head = [regex]::Match($raw, '(?m)^##\s*(.+?)\s*$')
            $clause = [regex]::Match(($raw -replace '\s+', ' '), $c.Clause)
            if (-not $head.Success -or -not $clause.Success) { throw "joiner kit: $($f.Name) is in the runtime image but carries no '## <title>' heading or no credit clause" }
            $sentence = $clause.Groups[1].Value.Trim()
            if ($sentence -notmatch '[.!?]$') { $sentence += '.' }
            $credits.Add("    $($head.Groups[1].Value) ($leaf\$([IO.Path]::GetRelativePath($Image, $f.FullName))):")
            $credits.Add("      $sentence")
        }
    }
    if ($credits.Count -gt 0) {
        $L.Add("  Credits the runtime's licence files ask a copy to state:")
        $L.AddRange($credits)
    }
}

function Invoke-KitPack([string] $ModDir, [string] $JavaExe, [string] $Classes, [string] $DescriptorPath, [string] $OutZip, [string] $Game, [string] $JdkHome) {
    $stage = [IO.Path]::GetFullPath((Join-Path $root 'build\kit-stage'))
    Reset-Dir $stage
    $stageMod = Join-Path $stage 'CC_Client'
    $modFull = [IO.Path]::GetFullPath($ModDir).TrimEnd('\')
    $matchNames = @('match.txt', 'match.txt.txt', 'match.txt.fingerprint', 'match.txt.password', 'kit.files')
    $secrets = @()
    foreach ($f in @(Get-ChildItem -LiteralPath $modFull -Recurse -File -Force)) {
        $rel = $f.FullName.Substring($modFull.Length + 1)
        if ($matchNames -contains $rel) { continue }
        $leaf = $f.Name.ToLowerInvariant()
        if ($leaf.Contains('-tokens') -or $leaf.EndsWith('.password') -or $leaf.EndsWith('.adminpassword') -or $leaf -eq 'cc-door-credentials.properties') { $secrets += $rel.Replace('\', '/'); continue }
        $dst = Join-Path $stageMod $rel
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $dst) | Out-Null
        Copy-Item -LiteralPath $f.FullName -Destination $dst
    }
    if ($secrets.Count -gt 0) { throw "joiner kit: client-mod/mod holds $($secrets.Count) seat-token or password file(s), never packed: $($secrets -join ', ') (move them out of the mod folder)" }
    # CC_Client/THIRD-PARTY-NOTICES.txt (the mod's LICENSE.txt points at it): the Concentus codec is built into the
    # jar, so its licence text travels with every copy of the kit - the same header, fields and whole-text rule the
    # maintainer's notices composer follows; the Java-runtime section joins it when the stage carries jre-cc.
    $licence = Join-Path $root 'server\vendor\concentus\LICENSE'
    if (-not (Test-Path -LiteralPath $licence -PathType Leaf)) { throw 'joiner kit: server/vendor/concentus/LICENSE is missing - a kit never ships the codec without its licence text' }
    $notices = [System.Collections.Generic.List[string]]::new()
    $notices.AddRange([string[]] @('THIRD-PARTY NOTICES', '', 'This package includes software made by others. Each part is listed below: what',
        'it is, where it sits and its licence. Every licence text, version and source',
        'location below is copied from the files that come with that part.',
        'Starsector itself is not included in this package.', '', ('=' * 80), 'Concentus',
        '  What:    a pure-Java Opus audio codec, used for voice chat',
        "  Where:   built into this package's jar (the org.concentus classes)",
        '  Licence: BSD-3-Clause; the full text follows, copied from its own licence file', ('-' * 80)))
    $notices.AddRange([string[]] @([IO.File]::ReadAllLines($licence)))
    $stagedJre = Join-Path $stageMod 'jre-cc'
    if (Test-Path -LiteralPath $stagedJre -PathType Container) {
        if (-not (Test-Path -LiteralPath (Join-Path $stageMod 'kit-jvm.args') -PathType Leaf)) { throw 'joiner kit: client-mod/mod has jre-cc but no kit-jvm.args - re-run with -KitRuntime (or delete jre-cc)' }
        $notices.Add(('=' * 80))
        Add-KitRuntimeNotices $notices $JdkHome $stagedJre
    }
    [IO.File]::WriteAllText((Join-Path $stageMod 'THIRD-PARTY-NOTICES.txt'), (($notices -join "`r`n") + "`r`n"), [Text.UTF8Encoding]::new($false))
    $composed = @('CC_Client/match.txt', 'CC_Client/kit.files', 'CC_Client/kit-manifest.txt', 'README.md')
    [string[]] $entries = @(Get-ChildItem -LiteralPath $stage -Recurse -File -Force |
        ForEach-Object { $_.FullName.Substring($stage.Length + 1).Replace('\', '/') } | Where-Object { $composed -notcontains $_ })
    [Array]::Sort($entries, [StringComparer]::Ordinal)
    $list = [IO.Path]::GetFullPath((Join-Path $root 'build\kit-entries.txt'))
    [IO.File]::WriteAllText($list, (($entries -join "`n") + "`n"), [Text.UTF8Encoding]::new($false))
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $sidecar = Join-Path $stage 'match.txt.fingerprint'
    # The kit ships only the custom variant definitions client-mod/kit-keep-variants.txt names (plus the match's own
    # roster ones); the packer drops every other one from the kit (a player's game receives those over the wire at
    # join). The list is the same one the released kits were packed with, so this kit carries the same variants.
    $keep = Join-Path $root 'client-mod\kit-keep-variants.txt'
    if (-not (Test-Path -LiteralPath $keep -PathType Leaf)) { throw 'joiner kit: client-mod/kit-keep-variants.txt is missing - this source tree is incomplete (the kit packer needs that list of the custom variants a kit ships)' }
    $kv = @("base=dir:$stage", "descriptor=$DescriptorPath", 'posture=redact', "out=$OutZip", "sidecarOut=$sidecar",
        "stamp=$stamp", "entries=$list", "game=$Game", "keepVariants=$keep")
    $all = @(& $JavaExe -cp $Classes cc.starsector.server.ops.KitPacker @kv 2>&1)
    $code = $LASTEXITCODE
    $rows = @($all | Where-Object { $_ -isnot [System.Management.Automation.ErrorRecord] } | ForEach-Object { "$_" })
    $errRows = @($all | Where-Object { $_ -is [System.Management.Automation.ErrorRecord] } | ForEach-Object { "$_" })
    foreach ($e in $errRows) { Write-Host $e }
    if ($code -ne 0) {
        $reason = @($rows | ForEach-Object { if ($_ -match '^kit-packer: refused reason=([a-z_]{1,32})$') { $Matches[1] } }) | Select-Object -First 1
        throw "joiner kit: exited $code, kit packer reason=$(if ($reason) { $reason } else { 'none' })"
    }
    $okRow = '^kit-packer: ok file=(\S+) entries=(\d+) jarMd5=([0-9a-f]{32}) stamp=(\S+)$'
    if ($rows.Count -ne 1 -or $rows[0] -notmatch $okRow) { throw "joiner kit: the kit packer exited 0 without its ONE ok row ($($rows.Count) stdout row(s))" }
    if ($Matches[1] -ne (Split-Path -Leaf $OutZip) -or $Matches[4] -ne $stamp) { throw "joiner kit: the packer reported file=$($Matches[1]) stamp=$($Matches[4]) for $(Split-Path -Leaf $OutZip) stamp $stamp" }
    $packedMd5 = $Matches[3]
    $packedEntries = [int]$Matches[2]
    if (@($errRows | Where-Object { $_ -cmatch '^kit-packer: variants kept=\d+ stripped=\d+$' }).Count -ne 1) { throw 'joiner kit: the kit packer exited 0 without its one variants line (kept / stripped) - it did not apply the variant list' }
    $stagedMd5 = (Get-FileHash -LiteralPath (Join-Path $stageMod 'jars\cc-client-mod.jar') -Algorithm MD5).Hash.ToLowerInvariant()
    if ($packedMd5 -ne $stagedMd5) { throw "joiner kit: the packer hashed the jar as $packedMd5 but the staged jar is $stagedMd5" }
    if (-not (Test-Path -LiteralPath $sidecar -PathType Leaf)) { throw 'joiner kit: the packer wrote no fingerprint sidecar (build/kit-stage/match.txt.fingerprint)' }
    return $packedEntries
}

if ($KitZip) {
    $kitEntries = Invoke-KitPack (Join-Path $client 'mod') $java $classes $descriptorFull $kitZipFull ($pathApi.GetUnresolvedProviderPathFromPSPath($GameDir)) (Split-Path -Parent $Jdk25Bin)
    Write-Host "joiner kit: $(Split-Path -Leaf $kitZipFull) ($kitEntries entries; sidecar build/kit-stage/match.txt.fingerprint)"
}
exit 0
