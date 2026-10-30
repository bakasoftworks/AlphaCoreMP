# AlphaCoreMP

AlphaCoreMP is a multiplayer mod for Starsector 0.98a-RC8, together with the dedicated server that runs its
battles. This tree is the source of both. Each player flies their own ship: together against AI ships or in two
teams against each other, depending on the match the host picks. The server package's own match holds 32 players
defending a station against waves of AI ships (the host can switch it to two player teams); the other included
matches hold 2 to 4 players, and the host can change the player count.

## TL;DR

For readers who know their way around - everything here is covered in full below.

- To play, you do not need this source: use the joiner kit. To host on the released server, use
  the server package. This tree is for building the mod and the server, or for hosting on your own build.
- Build, from the tree's root folder (the one holding `build.ps1`), on Windows or Linux with PowerShell 7,
  Starsector 0.98a-RC8, a JDK 17 or newer and a JDK 25:

      .\build.ps1 -GameDir "<Starsector folder>" -Jdk17Bin "<JDK 17>\bin" -Jdk25Bin "<JDK 25>\bin"

  On Linux: `pwsh ./build.ps1` with the same options, `-GameDir` naming a copy of the Windows install's
  `starsector-core` folder ([Build](#build) says why). `-SkipClient` builds the server only. The mod lands at
  `client-mod/mod/jars/cc-client-mod.jar`.
- To host on your own build: pack a joiner kit (`-KitZip` with `-Descriptor`), then lay out a server folder
  (`-SkipClient -ServerFolder -Package -Kit`) and start it with its `ops-web.bat` (Linux: `sh ops-web.sh`); see
  [Host a match on your own build](#host-a-match-on-your-own-build).
- Where the code is: `server/src` (the server), `client-mod/src` (the mod), `client-mod/web` (the join window and
  the ship editor); start at [What is where](#what-is-where). MIT License; the third-party parts keep their own
  licenses.

How it works:

- The server is a headless Java program: it simulates the combat with no rendering, audio or UI, owns the match
  state (the client holds no authority) and serves it to the players over UDP.
- The mod draws the served match with the game's real engine on the player's machine, reconciled against the
  server's authoritative snapshots, and sends the player's input up.

## Status

This is a pre-release, published so anyone can read the code and build the mod. The server is released separately
as two ready-to-run packages, each with its own README for the host: `cc-server-<stamp>.zip` for Windows,
`cc-server-linux-<stamp>.zip` for Linux. Get them, the same release, from where you got this source; if they are
not there, ask whoever shared it with you.

This tree builds the mod and the server's classes. Not in this tree: the package's build script (not published),
and the server's start and stop scripts and settings template, which come from the package
([Host a match on your own build](#host-a-match-on-your-own-build)); the hosting README itself sits beside this file
as `host-README.md`.

## Build

The build runs on Windows or Linux, installs nothing and writes nothing into the game folder. You need:

- PowerShell 7 (`pwsh`). On Windows, not the older Windows PowerShell that comes with Windows. On Linux, install it
  as Microsoft describes (apt or dnf packages, or a tar.gz):
  https://learn.microsoft.com/powershell/scripting/install/installing-powershell-on-linux
- Starsector 0.98a-RC8's game files: the build compiles against the game's jars; nothing from the game is copied
  into this tree. On Windows, the installed game. On Linux, a copy of the Windows install's `starsector-core`
  folder (`scp` or a USB stick, about 220 MB): the Linux download of the same version is a different build of the
  game, which the mod does not compile against and the server refuses to start on.
- A JDK 17 or newer, which the build runs on; the server is compiled for Java 17.
- A JDK 25, or any JDK whose `javac` accepts `--release 22`, for the voice code that uses Java's foreign function
  API; a server-only build does not need it.

On Linux, use Linux builds of both JDKs, for example Eclipse Temurin's tar.gz files: unpack them anywhere and pass
each one's `bin` folder.

On Windows, if you downloaded this tree as a zip, Windows may mark its files as from the internet, and PowerShell 7
then refuses `build.ps1`, saying it `is not digitally signed`: run `Get-ChildItem -Recurse -File | Unblock-File`
once from the tree's root folder before the first build (not only `build.ps1`: `-KitRuntime` also loads
`client-mod/kit-runtime.ps1`). A `git clone` has no such mark, and PowerShell's default execution policy needs no
change. Linux has no such mark.

From the tree's root folder (the one holding `build.ps1`), on Windows:

    .\build.ps1 -GameDir "<Starsector folder>" -Jdk17Bin "<JDK 17>\bin" -Jdk25Bin "<JDK 25>\bin"

On Linux:

    pwsh ./build.ps1 -GameDir ~/starsector-core -Jdk17Bin "<JDK 17>/bin" -Jdk25Bin "<JDK 25>/bin"

On Windows, leave out `-GameDir` if the game is in the usual folder,
`C:\Program Files (x86)\Fractal Softworks\Starsector`. On Linux there is no usual folder, so `-GameDir` is always
needed: the folder holding the game's jars (the copied `starsector-core`, or a folder with `starsector-core`
inside). On either system, leave out `-Jdk17Bin` if `JAVA_HOME` (else the first `javac` on `PATH`) is a JDK 17 or
newer (version not checked). The first line names the JDKs it picked; success ends with exit code 0 and:

    client jar: client-mod/mod/jars/cc-client-mod.jar

`-SkipClient` builds the server only.

## Play with what you built

### How players join

The normal way to play is the joiner kit the host sends you: it carries the mod and everything needed to join
that host's match, with its own README. A mod you built yourself cannot join through `join-cc.bat`, which runs
only from a kit extract, whose manifest it checks first. A server lets in the players who hold its kit, so to
play on your build you normally host.

### Host a match on your own build

Two runs, in order (add `-GameDir`, `-Jdk17Bin` if needed; on Linux, `pwsh ./build.ps1`, `/` in the paths, and
`-GameDir` always):

    .\build.ps1 -Jdk25Bin "<JDK 25>\bin" -KitZip "<folder>\<kit name>.zip" -Descriptor "<folder>\match.txt"
    .\build.ps1 -SkipClient -ServerFolder "<empty folder>" -Package "<cc-server zip>" -Kit "<folder>\<kit name>.zip"

The first packs a [joiner kit](#pack-a-joiner-kit) with the package zip's `match-defaults\match.txt`, copied into
a folder of your own; the second lays out a server folder serving it (its `README.md` covers hosting). Start it
with `serve.bat`, or `ops-web.bat` for the ops GUI (a web page), and send players the kit in its `kits`
folder. Without `-Kit`, it serves the package's own kit. A server folder takes only the kit it was made with, so
after a code change use a new kit name and folder.

On Linux, make both runs there and pass the Linux package (`-Package "<cc-server-linux zip>"`): it supplies the
folder's Java runtime, start scripts (`serve.sh`, `stop.sh`, `ops-web.sh`) and native libraries, and this build's
server classes are the same on both systems. Lay the folder out on a Linux file system, at a path without spaces or
colons, and start it right there with `sh serve.sh`, or `sh ops-web.sh` for the ops GUI. The folder's `README.md`
has the rest of the Linux steps: the tools to install, the game files the server runs on (the same copy of the
Windows `starsector-core`) and the activation. The kit is still for players on Windows; packed on Linux, it has no
Java runtime of its own ([below](#give-the-kit-its-own-java-runtime)). A folder laid out on Windows from the Linux
package and copied onto a Linux disk starts the same way (the start scripts restore the execute bits a copy from
Windows loses).

### Pack a joiner kit

`-KitZip` packs the kit; `-Descriptor` is a match file whose ships the packer checks against this mod and the
game. Pass the package's `match.txt`, as above: the server folder keeps the package's match whichever file you
pass, so the packer then checks that match's ships.

The kit holds `CC_Client` (a copy of `client-mod/mod` with the jar you just built), a player `README.md`, the
manifest `join-cc.bat` checks, license notices, and every custom ship variant the mod has. It never holds a match file
(the server sends the match, so one kit joins every match on a server that serves it) or a secret: a seat-token or
password file in `client-mod/mod` stops packing.

### Give the kit its own Java runtime

The game runs on its own Java 17; the voice chat's low-latency path needs Java 22 or newer. `-KitRuntime` makes a
runtime with jlink from the JDK 25, which must then be an Eclipse Temurin build: `client-mod/mod/jre-cc`, with
`client-mod/mod/kit-jvm.args` derived from the game's `vmparams`. Add it to the `-KitZip` run; every kit packed
from then on runs the game on it.

- Players run with these options, heap size included, not their own `vmparams`: build from a game whose
  `vmparams` suits them.
- A `vmparams` option that would make every player's Java write a dump, recording, log or crash file, load an
  agent or read options from another file stops the build, which names it.
- Delete `jre-cc` and `kit-jvm.args` to pack kits without a runtime again.

`-KitRuntime` is a Windows step: jlink builds a runtime for the system it runs on, and this one is a Windows
runtime for the players' PCs (`join-cc.bat` runs the game on it). On Linux the build refuses the switch, and a kit
packed there has no runtime: players run the game on its own Java 17, and voice chat takes its higher-latency path.
A `client-mod/mod` that already carries `jre-cc` (copied from Windows) is refused for packing on Linux too, since the
kit's notices read the version information of the runtime's Windows DLLs: pack that kit on Windows, or delete
`jre-cc` and `kit-jvm.args`.

### Install the mod by hand

After a build, copy the folder `client-mod/mod` to `<Starsector folder>\mods\CC_Client`, then enable AlphaCoreMP
in the game launcher's mod list. A hand install is for looking at the mod in the game or working on it: its
Multiplayer Test mission sets up a local fight with no server (the briefing says
`AlphaCoreMP: not launched via the joiner kit - LOCAL only`), since only `join-cc.bat` gives the mod a server's
address and the game has no place to type one. To play on your build,
[host a match](#host-a-match-on-your-own-build).

## Known issues

This is a pre-release, and a player will still see these:

- Brief "ghost" bursts from your own autofire guns: shots the server did not fire.
- Damage shown on the other player's ship that is then taken back (roughly one hit in seven).
- An occasional Breach missile flying off its line.
- A ship's drive effect that lingers after the ship dies.
- The `/help` overlay holds the ship keys until you press Esc or Tab.
- The size cap on the client log is new and has not yet been exercised in a real match.
- Voice chat works on PC (Windows) only.

## For mod authors: the pilot contract

In a match, the battle runs on the server and each player's game shows it. The server has no local player, so to an
unmodified mod every ship a player flies there is an AI ship: a mod that gives ships abilities on their own keys runs
them by its own AI on the server, while the player's own game runs them from the key, and the two can disagree. The
pilot contract closes that gap with two ship custom-data keys. It needs no AlphaCoreMP code to compile or run: the
same mod jar runs in single player (where the keys never appear), on the server and in every player's game. Hosts
set the mod to "required", so the server and every player run the same version.

- Abilities built on MagicLib's subsystems need no change on the server: it changes MagicLib's ability class in
  memory as it loads, so each pilot's abilities do there what they did on that pilot's own screen. Other players'
  games still run them by the mod's own AI unless the mod reads `cc_client.pilot` (below). A host can turn this
  off ("MagicLib abilities follow the pilot").
- Any other ability mod reads and writes the two keys in its own ability code (for example, a subclass of its
  ability class).

### The two keys

| Key | Written by | Value |
| --- | --- | --- |
| `cc_client.pilot` | AlphaCoreMP: the server, and each player's game | `int[]{seat, mask, role, flags}` on a ship a player flies; absent on an AI-flown ship |
| `cc_client.abilityLocal` | Your mod, on the player's own ship, each frame | An `Integer` mask |

`cc_client.pilot` (read it each frame; never write it):

- `[0]` seat: the player's seat in the match, 1 or more.
- `[1]` mask: bit k (k = 0 to 7) is set while the pilot has your ability slot k on (turning on or active) on their
  own screen. A mask of 0 means a player flies the ship and has nothing on: if your toggle is active, that is the
  cue to switch it off.
- `[2]` role: 0 = the server's own mark on the ship it simulates; 1 = a player's game marking its copy of another
  player's ship. A mod can use 1 to skip a side effect the server already performs, such as spawning something.
- `[3]` flags: bit 0 = the MagicLib change follows this pilot on this ship; other mods can ignore it.

The server writes the key on every frame it applies the pilot's input, before the battle advances, so an every-frame
plugin sees it in the same frame, and removes it when the player leaves the ship (the ship is lost, or the player
takes another one or leaves the match). After a player takes a new ship, the mask stays 0 until five frames in a
row arrive with no ability on, so a key still held from the old ship does not fire on the new one.

`cc_client.abilityLocal`: write it each frame on the player's own ship (in that player's game, the engine's
`getPlayerShip()`): an `Integer` whose bit k is set while your ability slot k is on (turning on or active) as your
mod accepted it - the state, not the raw key. AlphaCoreMP reads it after the battle's plugins ran that frame and
sends it to the server. A value that is not an `Integer` is ignored, as are bits above 7. Write it with
`ship.setCustomData(...)`: on a ship that holds no custom data yet, `getCustomData()` returns a map the ship does not
keep, so a `put` into it is lost.

Slots: number a ship's abilities 0 to 7 the same way on every computer, from something every side shares (the
ship's own setup), never from a key binding. Eight slots per ship reach the server; give any further ability its
unmodified behavior.

### A ship a player flies, or the AI

| Where your code runs | A player flies the ship when | AI-flown |
| --- | --- | --- |
| The server | `cc_client.pilot` is on it (role 0) | The key is absent |
| A player's game, that player's own ship | It is `getPlayerShip()`; no key is written on it | - |
| A player's game, another player's ship | `cc_client.pilot` is on it (role 1) | The key is absent |
| Single player | It is `getPlayerShip()`, as always | The key never appears |

On the server, `getPlayerShip()` called from a ship's own code (a hullmod's `advanceInCombat`, a ship system script,
a weapon's every-frame script; not a hit effect) returns that ship, or a module's parent, while a player flies it,
if the host turned "Each pilot's ship counts as the player's" on (off as shipped). Elsewhere it is never a ship a
player flies (it can be an AI ship or a station): in plugins that loop over all ships, in classes that read
`cc_client.pilot` themselves, and in classes that read the keyboard or mouse, draw, or change the battle's time
multiplier. Check the key there. Keyboard input never reaches the server: an ability on your own hotkey still needs
the two keys above. A check that works everywhere:

    Object m = ship.getCustomData().get("cc_client.pilot");
    int[] pilot = m instanceof int[] && ((int[]) m).length >= 2 ? (int[]) m : null;
    // pilot != null: a player flies this ship; pilot[1] holds their ability slots
    // pilot == null: run the mod's own logic unchanged (its key on the local player's ship, its AI elsewhere)

### What stays compatible

The two key names stay. `cc_client.pilot` only ever grows at the end: positions 0 to 3 keep their meaning, so a
reader that uses `[0]` and `[1]` checks `length >= 2`, one that uses `[3]` checks `length >= 4`, and both accept a
longer array. A missing key, or a value that is not an `int[]`, means the ship is AI-flown.

## Reference

### Build options

| Option | What it does |
| --- | --- |
| `-GameDir`, `-Jdk17Bin`, `-Jdk25Bin` | The game and the two JDKs (`-GameDir` always needed on Linux). |
| `-SkipClient` | Server only. |
| `-KitRuntime` | The kit's own Java runtime (Windows only). |
| `-KitZip` + `-Descriptor` | Pack a joiner kit. |
| `-ServerFolder` + `-Package` | Lay out a server folder. |
| `-Kit` | The kit that folder serves. |

`-Kit` needs `-ServerFolder`; `-KitZip` and `-KitRuntime` do not go with `-SkipClient`. `build.ps1`'s header
details `-ServerFolder`, `-KitZip` and `-KitRuntime`.

### What the build writes

- `server/classes`: the server.
- `server/premain/cc-premain.jar`: the Java agent, and beside it `cc-rngpin-holder.jar`.
- `client-mod/mod/jars/cc-client-mod.jar`: the mod.
- `client-mod/mod/SoundProbe.class`: the sound self-check probe.
- With `-KitRuntime`: `client-mod/mod/jre-cc` and `kit-jvm.args`, the kit's Java runtime and launch options.

### What is where

| Path | What it holds |
| --- | --- |
| `server/src` | The dedicated server. |
| `server/premain` | A Java agent the server JVM loads with `-javaagent`: bytecode transforms at class load. |
| `server/compat` | The agent's MagicLib class rewrite, built on ASM alone and packed into the agent jar. |
| `server/probe-api` | Three classes compiled against the game's API, which the server loads by name. |
| `server/vendor/concentus` | Concentus, the voice chat's audio codec library. Third-party, own `LICENSE` and `README.md`. |
| `server/native-linux` | The source of `libjawt.so` ([below](#native-libraries-for-the-linux-server)). |
| `client-mod/src` | The mod's code. |
| `client-mod/stubs` | Compile-only MagicLib stand-ins the mod's MagicLib support builds against; never in the jar. |
| `client-mod/mod` | The mod folder as installed: `mod_info.json`, `data/`, `join-cc.bat`, helper scripts, `jars/` after a build. |
| `client-mod/web` | The join window's pages (`join/`) and the ship editor's. |
| `client-mod/sound-check` | The sound self-check probe the kit runs, a loose class beside `join-cc.bat`. |
| `client-mod/kit-runtime.ps1` | The kit's Java runtime: module list, jlink flags, and the rules deriving its JVM options from `vmparams`. |
| `third-party/asm` | The ASM 9.10.1 jar the agent build bundles into the agent jar. Third-party, own `LICENSE.txt`. |
| `build.ps1` | Builds everything. |

### Native libraries for the Linux server

The build does not make the two native libraries the Linux server package carries in `native/linux`:

- `liblwjgl64.so`: LWJGL 2.9.3's own Linux library, unchanged, from `lwjgl-platform-2.9.3-natives-linux.jar` in
  LWJGL's 2.9.3 release on Maven Central.
- `libjawt.so`: built with gcc on Linux from `server/native-linux`; the command is in the header of `jawt-shim.c`.

The package preloads `libjawt.so`, a one-function stand-in for the Java runtime's AWT bridge that LWJGL's Linux
library asks for and the package's trimmed runtime lacks.

## Troubleshooting

| Message | What to do |
| --- | --- |
| Windows PowerShell refuses the script (may cite `#requires`) | Use PowerShell 7 (`pwsh`). |
| `no JDK found` | Pass `-Jdk17Bin`, set `JAVA_HOME`, or put `javac` on `PATH`. |
| `the build knows no usual game folder on Linux` | Pass `-GameDir`: the copy of the Windows `starsector-core`. |
| `game jar(s) missing under <folder>` | Point `-GameDir` at the game (on Linux: the copy of the Windows `starsector-core`). |
| `<step>: javac exited <n>`, `client pass ...: exited <n>` | See the compiler's lines; check the first line names a JDK 17+ (and 25). On Linux, check `-GameDir` holds the Windows game files, not the Linux download's. |
| `not a JDK folder (no release file)` | Point `-Jdk25Bin` at a full JDK 25's `bin`. |
| `-KitZip name must be letters, digits` | Use only letters, digits, `.`, `_`, `-`; at most 80 characters before `.zip`. |
| `server folder: exited <code>` | `2`: refused, nothing changed (see its `REFUSED` line). Other: failed; run the same command again - the folder will not start until it completes once. |
| `client-mod/mod holds ... seat-token or password file(s)` | Move them out of the mod folder. |
| `-KitRuntime runs on Windows only` | Drop the switch, or give the kit its runtime on Windows. |
| `client-mod/mod carries jre-cc` (Linux) | Pack that kit on Windows, or delete `client-mod/mod/jre-cc` and `kit-jvm.args`. |
| `... concentus/LICENSE is missing` | Get the file again from the published source. |
| `kit packer reason=<word>` | `census`: a ship variant neither the mod nor the game has. `match_read`: unreadable or not a match file. `live_token`: an uncleared seat token. `write`: could not write. Else: read the lines above. |

## License and third-party code

The original AlphaCoreMP code and files are under the MIT License: see `LICENSE.txt`. In short, you may use, copy,
change, merge, publish, share, sublicense and sell copies, as long as the copyright notice and license text go with
every copy or substantial part; there is no warranty. Not covered by it:

- Concentus, in `server/vendor/concentus`: BSD-3-Clause, see its `LICENSE`.
- ASM, in `third-party/asm`: BSD-3-Clause, see its `LICENSE.txt`.
- Files under `client-mod/mod/data` that derive from the game's own data. The MIT grant does not cover
  them; they stay with the game.
- The game itself.

A joiner kit carries the Concentus license text in `CC_Client/THIRD-PARTY-NOTICES.txt`, because that codec is
built into the mod jar.

## Disclaimer

AlphaCoreMP is an unofficial fan-made mod, not affiliated with or
endorsed by the developers of Starsector.
You need your own purchased copy of Starsector; this tree does not include the game.

## Authorship

The code in this tree was written by Claude, Anthropic's AI model, working with the project's maintainer, who directed, tested and reviewed it.

Maintainer: Myscellanea
