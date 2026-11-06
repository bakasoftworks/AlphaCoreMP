# Changelog

What changed in each AlphaCoreMP release, newest first. The downloads ride each release on its GitHub release page.

## 0.6.3 - 2026-11-06

- Hosts: each round line in the server log now reports late pacer wake-ups (`sleep_late_frames=` and `sleep_late_max_ms=`), so a host whose machine hands the server a coarse timer can see it in the log. The server's behaviour is unchanged.
- The joiner kit must be updated to 0.6.3: the version number is part of the kit's content check, so an older kit is refused at the door.
- Correction to the 0.6.1 notes: the tab-map retreat route described there is untested in play.
- No other change to the server or the client mod.

## 0.6.2 - 2026-11-05

- Linux hosts: the hosting README's step 2 now explains how to use "Join from this PC" and "Launch debug WATCH" on the host itself: unpack the Linux download of the game, put `CC_Client` from the joiner kit into its `mods` folder, and type that folder into "Player game folder" at the first start; the server keeps running from "Game folder". Step 4's example now names the copied Windows folder, never the "Player game folder".
- The joiner kit must be updated to 0.6.2: the version number is part of the kit's content check, so a 0.6.1 kit is refused at the door. No other change to the server or the client mod.

## 0.6.1 - 2026-11-05

- Fixed a crash with the Arma Armatura mod on the server: when a player picked the Valkazard Kai or the Raiden EX, every player's game crashed once its blade drones launched (0.4.0 to 0.6.0). Fighters launched from another player's or an AI carrier now carry a fighter wing on your screen, as the game expects, and a wing that refills after losing every fighter follows a live fighter on the radar.
- A spawned AI ship of a mod that randomizes its weapons at spawn (Arma Armatura's Aleste) now carries the same weapons on every player's screen; before, each game rolled its own.
- Retreating with a healthy ship (the bottom edge's Enter prompt, or a retreat order from the tab map) no longer strands you as a shuttle with no respawn while the other players see your ship bouncing at the map edge: the server now reads a healthy departure as a departure, not a kill, and you fly back in. `/despawn`, the end of a transfer pod and a phase dive now vanish the ship on the other screens rather than kill it.
- With the chat box open, Enter sends the line and does nothing else, so typing a command can no longer retreat your ship. With chat closed at the bottom edge, Enter still opens chat and retreats, as the game's prompt says.
- A kill that leaves no wreck on the server (a ship blown apart by one heavy volley) now leaves no wreck on the players' screens either. Tested in rows only; not yet seen in a match.
- Linux hosts: "Player game folder" is required for "Join from this PC" and "Launch debug WATCH". A blank row is now refused by name (0.6.0 judged the Windows game files in "Game folder" instead and asked for a kit under a folder that could never start the game), and the Setup tab's hint states the rule for each platform. A set folder that holds no game is named, without the "clear it" advice that could only fail on Linux.
- The joiner kit must be updated to 0.6.1: the connection format changed, and a 0.4.0 to 0.6.0 kit is refused at the door with "kit outdated".
- The hosting README's ops GUI section adds a caution for hosts who reach the ops GUI through an ssh tunnel: keep the host's OpenSSH current (an older OpenSSH on musl-based Linux accepts over-long passwords on accounts that should take none).

## 0.6.0 - 2026-11-04

- New "Player game folder" (Setup tab, beside "Game folder"): on a Linux host, the Linux install of the game that "Join from this PC" and "Launch debug WATCH" start your own windows from, while the server keeps running the Windows game files in "Game folder". Blank means "Game folder". A folder set there that holds no game refuses the press and names the row. Without it, those two buttons could not work on a Linux host, though 0.5.0 said "Join from this PC" did.
- "Launch debug WATCH" works on a Linux host too, with a "Player game folder" set (proven in WSL only; the second window joins on this computer there, since the VPN route is Windows only).
- The ops GUI's Mods tab "Needs" column reads a mod's `mod_info.json` the way the game does, so a `#` comment no longer hides a dependency, and it names a pulled-in library whose own requirement is missing.
- A player's game that closes while the server is still talking to it now finishes its last messages before it says goodbye. 0.5.0 said this of a player's game; it was true of the test client only. The player's game does it since 0.6.0.
- Voice chat on Linux uses Java's own sound line, where ALSA routes to PipeWire or PulseAudio. The 0.5.0 note's "about 74 ms more delay" was not measured on Linux; the delay to the speaker there is unmeasured.

## 0.5.0 - 2026-11-04

- The kit runs on Linux: copy `CC_Client` into `mods/` of a Linux Starsector 0.98a-RC8 and run `bash mods/CC_Client/join-cc.sh` from that folder. The game runs on its own Java (`jre_linux`); the kit's bundled Java stays Windows-only. The kit README's "On Linux" section lists the differences and the sound and `xrandr` packages a distribution may lack.
- The Linux game is a different build of the same version, so the kit's compatibility agent translates the mod's engine references as the game loads. No game file is copied or changed.
- A Linux host's ops GUI has "Join from this PC" too: start `ops-web.sh` from a terminal inside your desktop session. "Launch debug WATCH" stays Windows-only.
- Voice chat on Linux uses Java's own sound line: it works where ALSA routes to PipeWire or PulseAudio (the usual desktop), with about 74 ms more delay to the speaker than on Windows.
- A player's game that closes while the server is still talking to it now finishes its last messages before it says goodbye.
- The ops GUI's Mods tab has a "Needs" column: which mods a mod needs, which mods need it, and when an unticked mod loads anyway because a required mod needs it (or an optional one would refuse the start).

## 0.4.0 - 2026-11-03

- The 0.4.0 kit is required. A 0.3.0 or older kit is told at the door that it is outdated.
- Other players' screens follow each pilot ("Setup", "Advanced"; `pilotship.replicas`, on as shipped): a player whose game runs on the kit's own Java runs a pilot's ship hullmods, ship system and weapon scripts from that pilot's held keys, so cables, smoke, debris and jets show on their screen as the pilot fires them. Other starts see them through the server's corrections.
- MagicLib abilities of other pilots fire on your screen too, on the kit's own Java start: the kit's agent changes MagicLib's ability class in memory as it loads. The host can turn the server half off.
- Mod ships are pickable: ships kept in a mod's variant subfolders (boss ones included) are on the pick list. Hide the ones you do not want on the ops GUI's "Players" tab; the hide list now holds every shipped ship.
- LunaLib settings: the server's values come from `luna-settings\<modId>.json.data` (the "Mods" tab names the file) and are kept across server updates. The host README's LunaLib section has the three things to know.
- Keys stay in the game: keys typed while the game window is not active, or while a game dialog is open, never leave your game.
- A mod ship's loadout no longer swaps on respawn, and the Aleste's travel drive fires on the server.
- The ops GUI: an "on" box per mod on the "Mods" tab, a narrow layout that fits one seat card, and a named message when Windows has reserved the chosen port.
- Linux hosts: a `stop.sh` followed within a second by `serve.sh` no longer stops the new server at boot.

## 0.3.0 - 2026-10-30

- Every player needs the 0.3.0 kit. An older kit is refused when it joins and the join window says the kit is outdated.
- Mod hotkey abilities follow the pilot without MagicLib: players' held keys and mouse buttons reach the server, and the pilot's ship reads them in its own scripts (hullmods, ship system, weapon every-frame scripts), whatever keys the player bound. Not covered: the mouse pointer, one-by-one keyboard events, and battle-wide combat plugins. Details in the host README's Mods section.
- "Each pilot's ship counts as the player's" now ships on (it was off in 0.2.0); it is what lets those abilities follow the pilot. The host can turn it off in the ops GUI before Start ("Setup", "Before start", "Advanced").
- Known limit: a game control rebound during a match keeps its old key on the server for mod abilities until the next match starts. Set your keys before the match.
- Host: the ops GUI's "Players" table now sizes to its seats; with three or more players it showed two rows and a scrollbar.
- Host, Windows: with a "Second game folder" set, "Launch debug WATCH" joins its second window through the VPN's public address, as a remote player would, when the server is bound to the VPN address; the first window joins on this computer.

## 0.2.0 - 2026-10-30

- Every player needs the 0.2.0 kit. A 0.1.x kit is refused when it joins and the join window says the kit is outdated.
- Ships from the mods the server runs can be picked in the join window and flown.
- MagicLib abilities follow the pilot: an ability from a MagicLib-based mod, pressed on its own hotkey, now fires on the server for the ship that player flies instead of running there by the mod's AI. On as shipped; the host can turn it off in the ops GUI before Start ("Setup", "Before start", "Advanced": "MagicLib abilities follow the pilot").
- New host switch "Each pilot's ship counts as the player's" (off as shipped, same place): the server treats each player's ship as the player's ship inside that ship's own hullmods, ship system and weapon scripts, so an effect a mod or the game gives only to the player's ship works on the server as it does on that player's screen. Not for hit effects, battle-wide scripts, or scripts that read the keyboard or mouse or draw.
- Mod authors: the pilot contract in the source README tells a mod, from two custom-data keys on a ship, whether a player flies it and which ability slots the pilot holds. It needs no AlphaCoreMP code to compile or run.
- The host README has a Mods section: which mods work as they are, which need care, and what LazyLib, LunaLib and MagicLib do on the server (LunaLib's version checker contacts GitHub from the server computer at every start).

## 0.1.2 - 2026-10-28

- Mods that bring their own `settings.json` now load on the server the way they do in the game: a mod's rule-command packages and plugins are read after the mod list is set, so library mods such as LazyLib and MagicLib start on a server that requires them instead of stopping it at the rules load. Mod combat code that reads the keyboard sees no keys held on the server instead of crashing it.
- After an in-place update, the report says that players need the new kit only when the kit's content actually changed; an update that touches only match settings prints no such line.
- Both READMEs open with a short summary that says what to attach when reporting an issue (hosts: the server's run log; players: the session report), and the host README has a Mods section.
- The public repository carries a CHANGELOG with one section per release.

## 0.1.1 - 2026-10-27

- The joiner kit now carries every custom ship variant the mod ships, not only the ones the built-in match shapes use. The kit has to match the host's server: a 0.1.1 host needs the 0.1.1 kit, and an older kit is turned away.
- Building from source works on Linux too: the one `build.ps1` runs under PowerShell 7 on Windows and on Linux. The server builds against either game download; the client mod needs the Windows game files, as the source README explains.
- Hosts update in place: stop the server, close the ops GUI, drop the new zip onto `update.bat` (Linux: `sh update.sh <zip>`) and start again. Settings, passwords, bans, kept ships and recordings are kept. Players replace their `CC_Client` folder with the one from the new kit.

## 0.1.0 - 2026-10-27

AlphaCoreMP 0.1.0 - the first public release.
