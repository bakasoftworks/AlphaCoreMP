# AlphaCoreMP - Starsector multiplayer joiner kit

AlphaCoreMP is an unofficial fan-made mod, not affiliated with or endorsed by the developers of Starsector.

This kit lets you play multiplayer Starsector on a server another player (the host) runs. [Set up the kit](#set-up-the-kit-once) once, then [Join a match](#join-a-match) to play.

## TL;DR

For players who know their way around - everything here is covered in full below.

1. You need your own Starsector 0.98a-RC8 and the host's connect code (`address:port:password`).
2. Extract this zip and copy its `CC_Client` folder into `mods\` in your Starsector folder (the one with `starsector.exe`).
3. Double-click `mods\CC_Client\join-cc.bat` (choose Run, or "More info" then "Run anyway", if Windows warns). The join window opens in your browser: paste the connect code, pick a ship and a team if you like, click "Launch".
4. In the game launcher click "Play Starsector", then choose Missions, "Multiplayer Test", and press Play.
5. In the match: Enter opens chat, `/help` lists the keys, and with "Enable microphone" on you hold "B" to talk to everyone or "Space" to talk to players near you. F9 marks a bug in the logs. When the game closes, the black window offers R to rejoin.
6. For a new kit: delete `mods\CC_Client`, copy in the new one, run `join-cc.bat` again.

## Contents

- [What you need](#what-you-need), [Set up the kit (once)](#set-up-the-kit-once), [Join a match](#join-a-match), [Next time](#next-time), [In the match](#in-the-match), [After the game closes](#after-the-game-closes), [When the host changes the match](#when-the-host-changes-the-match), [Your own ship builds (optional)](#your-own-ship-builds-optional), [If something goes wrong](#if-something-goes-wrong)
- [This kit brings its own Java](#this-kit-brings-its-own-java), [Updating the kit](#updating-the-kit), [The session report](#the-session-report), [What the report carries](#what-the-report-carries), [What it changes on your PC](#what-it-changes-on-your-pc), [To remove the kit](#to-remove-the-kit), [About](#about)

## What you need

Your own purchased Starsector 0.98a-RC8 (the host's version; this kit has no game) and the host's connect code (`address:port:password`). Its last part is the match password the host announces for everyone. That is all you need: the join window fetches the match from the server by itself.

## Set up the kit (once)

Extract this zip and copy its `CC_Client` folder into `mods\` in your Starsector folder (with `starsector.exe`), usually:

```
C:\Program Files (x86)\Fractal Softworks\Starsector
```

Windows usually copies it without asking. If it shows an administrator prompt, choose Continue.

Never run `join-cc.bat` inside the zip window: it must be in `mods\CC_Client`.

## Join a match

1. Double-click `mods\CC_Client\join-cc.bat`. Because the kit came from the internet, Windows may show a warning first: in an "Open File - Security Warning" box choose Run; in a blue "Windows protected your PC" box click "More info", then "Run anyway". Do this only for a kit whose source you trust. Leave its black console window open while you play.
2. Your browser opens the join window.
3. Paste the connect code into its top box. If Windows Firewall asks about Java, choose "Allow access" (it may ask twice: "Zulu Platform x64 Architecture" for the join window, then "OpenJDK Platform binary" for the game). After a Cancel, joining usually still works; if not, allow that Java under "Allow an app through Windows Firewall".
4. Once the server answers: keep Ship at "(server default)" or pick one; in a two-team match pick South, North or let the server pick; Mods should say "all present", else get each flagged mod where the host did, put it in `mods\` and press "Refresh".
5. Click "Launch".
6. In the Starsector launcher click "Mods...": nothing to tick (`join-cc.bat` starts the game with exactly the host's mod list; your own list stays for a normal start). If AlphaCoreMP shows YELLOW, tell the host before playing. Then click "Play Starsector".
7. Choose Missions, "Multiplayer Test": the text under it shows whether the server is reachable (select it again to refresh).
8. When the host says GO, press Play: that connects you and claims your seat (nothing before Play holds a seat).

## Next time

Run `join-cc.bat`, click "Launch": entries are remembered (the password only for that address and port).

## In the match

- Enter opens chat and `/help` lists commands and keys. Your mic is used only with "Enable microphone" (join window) or "Mic" (`/settings`) ON. Hold "B" for global voice chat and "Space" for local (proximity) voice chat. Voice settings are under `/settings`.
- Spotted a potential bug? Press F9: the logs mark the moment, your ship shows "BUG MARKED IN LOG #1" (then #2 ...), the server gets only the number and time. Send the host the report zip from your Desktop (marks are in its `cc-client.log`) and say what you saw.

## After the game closes

Match over, crash or disconnect: the black window saves the session report (if ticked) and offers R (rejoin) or Q (quit).

## When the host changes the match

The game says so. Close it and press R in the black window (the "join console") to join the new match.

## Your own ship builds (optional)

At the title screen, "Open editor" in the join window opens a ship editor. Its Fly button makes a build into your next ship (in a match: your next respawn) if the host allows custom ships. The ship editor has many keyboard and mouse shortcuts: press "?" in the editor to list them, and try your scroll wheel.

## If something goes wrong

Read the message (KIT VERSION CHECK FAILED, NOT CONNECTED under the mission, a NOTE line): most say what to do - and tell the host what you saw.

- Kit outdated or not the host's kit: see [Updating the kit](#updating-the-kit).
- Wrong match password: paste the code again or type it as announced.
- NO MATCH YET: the host's server must be running; run `join-cc.bat` again and press "Refresh".
- "Launch failed: Could not save the server's match as `match.txt`": your Windows account likely cannot write to the Starsector folder; a Starsector install outside `Program Files` (e.g. `C:\Games\Starsector`) avoids this.
- Tab gone or "join window not answering": run `join-cc.bat` again and use the tab it reopens; close the new black window ("Launch cancelled"), keep the first.
- No page in the browser: a plain window opens after about 45 seconds.
- "Error in sound initialization" only via `join-cc.bat`: the kit checks and repairs the launch itself; the report zip carries the result - send it to the host.

## This kit brings its own Java

The kit's own Java runtime is `CC_Client\jre-cc` (Eclipse Temurin 25.0.4.1+1, about 50 MB): `join-cc.bat` runs the game on it, a normal start does not. If Windows or your antivirus blocks `jre-cc\bin\javaw.exe`, `join-cc.bat` falls back to your normal launcher with a NOTE (the game still joins); voice chat latency may then be worse than on the kit's Java 25. If the game hangs while loading, rename `kit-jvm.args` (for example to `kit-jvm.args.off`) and start again: the game then runs on the Java that ships with Starsector and voice chat uses its slower path. Tell the host it happened.

## Updating the kit

For a new kit, delete `mods\CC_Client`, copy in the new zip's `CC_Client`, run `join-cc.bat`. Remembered entries stay.

## The session report

`cc-debrief-<date>-<time>-<number>.zip`, saved to your Desktop when the game closes. Send it to the host if they ask; it is a plain zip - open and read it first. To skip it, untick "Save a session report zip on the Desktop" in the join window (then no `cc-client.log` is written).

## What the report carries

- `starsector.log`, this mod's `cc-client.log`, the Java settings (`vmparams`, this kit's `kit-jvm.args`), your mod list (folder names under `mods\`, `enabled_mods.json`).
- A manifest: Windows and Java version, game folder, how the game was started, a ping to the host, and the name and location of each non-Microsoft program loaded into the game.
- A sound-check log: whether a sound device was found (not its name), this PC's text encodings, the folders the check ran from (your profile folder as `%USERPROFILE%`).
- Only if the sound check failed: your antivirus's name, whether Windows' loader and security policies are on or off, and COUNTS of security rules, exclusions and recent security events, never their contents.

No password and no other file of yours. Both logs are copied unchanged: `cc-client.log` names the server, the other pilots and when you played; a folder path in either can show your Windows user name.

## What it changes on your PC

### The Windows registry

On some PCs `join-cc.bat` starts the game through Windows Explorer; for those seconds it may put its launch settings in your user environment (`HKCU\Environment`): `JAVA_TOOL_OPTIONS`, `_JAVA_OPTIONS`, `CC_KIT_JOPT`, `CC_PREDICT_TRANSPORT`, `CC_MATCH_DESCRIPTOR`, `CC_KIT_ENVSYNC`, plus `EnvApplied` under `HKCU\Software\CC_Client` and, if you had your own `JAVA_TOOL_OPTIONS`, a copy (`JtoPrev`, `JtoPrevType`) to put it back. They hold the server address, your pilot name and a file id (a launch file's name), no password. They go once the game has started (failing that: when the game closes or at the next run), and your `JAVA_TOOL_OPTIONS` is put back. An `_JAVA_OPTIONS` of your own is left alone (that start then skips the host's mod list). The empty `HKCU\Software\CC_Client` key stays behind.

### Files it keeps

- `%LOCALAPPDATA%\cc-starsector\`: `join-settings.properties` (address, port, pilot name, ship, report tick, the match password in plain text; delete it to make the window forget), `cid-master` (player id), `join-window.log` (no password), an address file in `join\`, short-lived launch files (two hold the password and player id) and a sound-library copy.
- `mods\CC_Client\match.txt` (the match, saved on Launch).
- `saves\common\`: `cc_mods` (links to your mods, not copies, plus one for this kit and an `enabled_mods.json`), `cc_variants` (your ship builds, cached server builds and lists), `cc_client_settings.data` (voice and HUD settings, mic name), `cc_editor_endpoint.data` (how the join window finds the ship editor).
- `starsector-core\`: `cc-client.log`, `cc-client.log.1` and, after a crash, `hs_err_pid*.log` and `replay_pid*.log` (both: server address, port, pilot name, file id; no password; never in the report).
- `%USERPROFILE%\`: `.cc-starsector-window-*.properties` (the game window's position when you play in a window; its name can show your pilot name).
- Temp: `cc-sound-check-<number>.log`, the report's work folder (both deleted when the game closes). Desktop: the report zips.

## To remove the kit

Delete `mods\CC_Client` and the files kept above.

Delete `saves\common\cc_mods` itself in File Explorer: that should remove only the links and leave your own mod folders in `mods\` as they are. Never open a link inside `cc_mods` and delete what you see there - that is your real mod folder.

## About

AlphaCoreMP's own code is free to use under the MIT License (see `CC_Client\LICENSE.txt`); the third-party parts it bundles keep their own licenses (see `CC_Client\THIRD-PARTY-NOTICES.txt`). This software is provided as is, without warranty of any kind. Use it at your own risk.
