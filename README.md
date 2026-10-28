# AlphaCoreMP - Starsector combat layer multiplayer

AlphaCoreMP is an unofficial fan-made mod, not affiliated with the developers of Starsector. You need your own purchased copy of Starsector 0.98a-RC8 - nothing here includes the game.

Each player flies their own ship in one shared battle against waves of AI ships, or in two teams against each other, on a dedicated server that another player (the host) runs. Players need Windows (for now.) The server runs on Windows or Linux and supports up to 255 players per server, although exact playercount feasibility will depend on the host's CPU and how complex a battle is. It is likely that the average player's framerate will choke long before the server's simulation does.

Three READMEs cover the three ways in. The short version of each is below, and the full ones are linked.

## Players: join a match

Get `cc-joiner-kit-<date>.zip` from the newest entry on the [Releases](../../releases) page (under Assets). The kit has to match the host's server, so if the host sends you a kit, use that one.

1. You need your own Starsector 0.98a-RC8 and the host's connect code (`address:port:password`).
2. Extract the zip and copy its `CC_Client` folder into `mods\` in your Starsector folder (the one with `starsector.exe`).
3. Double-click `mods\CC_Client\join-cc.bat` (choose Run, or "More info" then "Run anyway", if Windows warns). The join window opens in your browser: paste the connect code, pick a ship and a team if you like, click "Launch".
4. In the game launcher click "Play Starsector", then choose Missions, "Multiplayer Test", and press Play.
5. In the match: Enter opens chat, `/help` lists the keys, and with "Enable microphone" on you hold "B" to talk to everyone or "Space" to talk to players near you. F9 marks a bug in the logs. When the game closes, the black window offers R to rejoin.
6. For a new kit: delete `mods\CC_Client`, copy in the new one, run `join-cc.bat` again.

Full guide: [the joiner kit README](readme/kit-README.md). The same file is inside the kit zip.

## Hosts: run a server

Get `cc-server-<date>.zip` (Windows) or `cc-server-linux-<date>.zip` (Linux) from the same [Releases](../../releases) entry. The matching joiner kit is inside the package's `kits` folder, and on the release page as its own download.

1. You need Windows 64-bit or Linux x86-64, Starsector 0.98a-RC8 installed and activated on this computer, and a UDP port players can reach (ProtonVPN port forwarding, or a router forward of UDP 51337).
2. Extract the whole zip into a new, empty folder of its own on a local disk, outside OneDrive and other synced folders, such as `C:\AlphaCoreMP-server`.
3. Run `ops-web.bat` (Linux: `sh ops-web.sh`, after the Linux steps in the full README). A black window opens, and your browser opens the ops GUI page.
4. On "Setup": in "Network", press "Fill from ProtonVPN", or with a router forward leave "Listen on host" at `0.0.0.0`, type the forwarded port and put your public address into "Players connect to". Type your Starsector folder into "Game folder". Set a "Match password" (8 to 64 letters, digits or hyphens).
5. Press "Start server" and wait for LISTENING (about a minute). Press "Copy join brick": send players that connect code privately, and the kit zip from the `kits` folder.
6. "End match" stops the server. To update, drop the new zip onto `update.bat` (Linux: `sh update.sh <zip>`) and start again: settings, passwords, bans and kept ships are kept.

Full guide: [the server README](readme/host-README.md). The same file is inside the server zip.

## Source: build it yourself

This repository is the source of the mod and the server. You do not need it to play or to host on the released server.

- Build on Windows or Linux with PowerShell 7, Starsector 0.98a-RC8, a JDK 17 or newer and a JDK 25:

      .\build.ps1 -GameDir "<Starsector folder>" -Jdk17Bin "<JDK 17>\bin" -Jdk25Bin "<JDK 25>\bin"

  On Linux: `pwsh ./build.ps1` with the same options, `-GameDir` naming a copy of the Windows install's `starsector-core` folder. `-SkipClient` builds the server only. The mod lands at `client-mod/mod/jars/cc-client-mod.jar`.
- To host on your own build: pack a joiner kit (`-KitZip` with `-Descriptor`), then lay out a server folder (`-SkipClient -ServerFolder -Package -Kit`) and start it with its `ops-web.bat` (Linux: `sh ops-web.sh`).
- Where the code is: `server/src` (the server), `client-mod/src` (the mod), `client-mod/web` (the join window and the ship editor).

Full guide: [the source README](readme/source-README.md): what is where, how it works, building, hosting on your own build, and the project's status.

## License

MIT License (`LICENSE.txt`). The third-party parts keep their own licenses, listed in [the source README](readme/source-README.md#license-and-third-party-code).
