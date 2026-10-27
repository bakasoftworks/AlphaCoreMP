# Vendored: Concentus (pure-Java Opus codec)

Provenance (vendored for voice chat, 2026-08-28): upstream `https://github.com/lostromb/concentus`,
branch `master`, commit `3885c4e46513ef0fc81fca100189e54f1714c6ca` (2025-09-27 "Fix the Celt decoding bug. (#60)").
Copied VERBATIM from `Java/Concentus/src/main/java/org/concentus/**` (124 files, the library only —
the `ConcentusTestConsole` demo, which uses `java.io.File`, is deliberately NOT vendored). Licence =
`LICENSE` beside this file (BSD-3-Clause, the upstream Opus notice; the mod's distributed licence file
carries it at L3). We vendor the upstream SOURCE (never the Maven repackage) so the build compiles it
with our own javac (`server/build.ps1` `$VendorSources`) and the classloader-B deny / deterministic-jar /
jdeps gates scan it like our own classes.

Audit at vendoring: zero imports of `java.io`, `java.nio`, `java.lang.reflect`, `javax.*`,
`java.util.prefs`, `java.util.concurrent`; no `Thread`/`System.exit`. `OpusException extends Exception`
(CHECKED — catch it explicitly at every decode site). Language level ≤ Java 8.

Local edits: NONE. Re-vendoring = replace the tree wholesale from a newer upstream commit and update
this note; never patch individual files in place.
