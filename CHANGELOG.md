# Changelog

What changed in each AlphaCoreMP release, newest first. The downloads ride each release on its GitHub release page.

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
