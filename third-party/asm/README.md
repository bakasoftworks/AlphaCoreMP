# Vendored ASM (org.ow2.asm:asm)

The one bytecode-manipulation library every `-javaagent` jar of this project shades in
(the product's `server/premain/cc-premain.jar`, and the maintainer's internal tracing agents), via
the ASM helper script `asm-vendor.ps1` of the internal build. Replaces the JDK-internal `jdk.internal.org.objectweb.asm`
link, which JDK 25 removed (the JDK moved to `java.lang.classfile`).

| field | value |
|---|---|
| artifact | `org.ow2.asm:asm:9.10.1` (core only — no tree/commons/util) |
| source | `https://repo1.maven.org/maven2/org/ow2/asm/asm/9.10.1/asm-9.10.1.jar` |
| sha256 | `ed825d10ab1399c8c0cb669e688cf0c8c82629b4c8399b58352b68e92ca10fcb` (Central's published `.jar.sha256`, re-verified on download 2026-08-29; 126151 B) |
| license | BSD-3-Clause — `LICENSE.txt` (ASM's own text; keep it beside the jar) |
| class files | reads through JDK 25+ (`Opcodes.V25`); our visitors use the `ASM8` API level |

## Refresh recipe (a JDK-family bump that outgrows this ASM)

1. Pick the release from `https://repo1.maven.org/maven2/org/ow2/asm/asm/maven-metadata.xml`.
2. `Invoke-WebRequest` the jar into this dir; fetch Central's `.jar.sha256`; write it to
   `asm-<ver>.jar.sha256` (`<hash>  asm-<ver>.jar`); delete the old pair.
3. Bump `$script:CcAsmVersion` in the ASM helper `asm-vendor.ps1` (the ONLY version constant of the internal
   build; the published tree's `build.ps1` names the jar file once - bump it there too).
4. Rebuild all three agents (`local/trace-agent/build.ps1`, `local/rng-pin-agent/build.ps1` +
   `smoke.ps1`, `server/build.ps1`) and run the transform-registry check `verify-transform-registry.ps1` (7/7).

`*.jar` is gitignored repo-wide; `.gitignore` carries an explicit negation for this dir so the
pinned jar IS tracked (a build must never depend on a network fetch).
