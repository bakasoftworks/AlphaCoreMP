# AlphaCoreMP - Starsector multiplayer server package

AlphaCoreMP is an unofficial fan-made mod, not affiliated with or endorsed by the developers of Starsector.
You need your own purchased copy of Starsector - this package does not include the game.

## TL;DR

For hosts who know their way around - everything here is covered in full below.

1. You need Windows 64-bit or Linux x86-64, Starsector 0.98a-RC8 installed and activated on this computer, and a UDP port players can reach (ProtonVPN port forwarding, or a router forward of UDP 51337).
2. Extract the whole zip into a new, empty folder of its own on a local disk, outside OneDrive and other synced folders, such as `C:\AlphaCoreMP-server`.
3. Run `ops-web.bat` (Linux: `sh ops-web.sh`, after the steps under [Linux](#linux-x86-64)). A black window opens, and your browser opens the ops GUI page, `http://127.0.0.1:7717/#<key>`.
4. On "Setup": in "Network", press "Fill from ProtonVPN", or with a router forward leave "Listen on host" at `0.0.0.0`, type the forwarded port and put your public address into "Players connect to". Type your Starsector folder into "Game folder". Set a "Match password" (8 to 64 letters, digits or hyphens).
5. Press "Start server" and wait for LISTENING (about a minute). Press "Copy join brick": send players that connect code privately, and the kit zip from the `kits` folder.
6. "End match" stops the server. To update, drop the new zip onto `update.bat` (Linux: `sh update.sh <zip>`) and start again: settings, passwords, bans and kept ships are kept.

## What this is

This folder runs an AlphaCoreMP server: it hosts multiplayer matches for players who use the AlphaCoreMP joiner kit, running the game engine headlessly from your own Starsector install. It brings its own Java runtime (the `jre` folder), so there is nothing else to install. The server has an ops GUI, the ops GUI, which you open with `ops-web.bat` (Windows) or `ops-web.sh` (Linux); it can also be reached over an SSH tunnel (see [The ops GUI](#the-ops-gui)) and is used for server configuration and management. AlphaCoreMP's own code is under the MIT License (see [Licenses](#licenses) at the end of this file).

## Contents

- Getting started: [What you need](#what-you-need), [Your first match](#your-first-match), [Stopping the server](#stopping-the-server), [Hosting again later](#hosting-again-later), [The players' kit and the connect code](#the-players-kit-and-the-connect-code), [Updating to a newer package](#updating-to-a-newer-package)
- Reference: [The ops GUI](#the-ops-gui), [Playing as the host](#playing-as-the-host), [Kicking and banning players](#kicking-and-banning-players), [Changing the match](#changing-the-match), [Mods](#mods), [Custom ships](#custom-ships), [The host's ship list](#the-hosts-ship-list), [Test matches (Launch debug WATCH)](#test-matches-launch-debug-watch), [When a player reports a problem](#when-a-player-reports-a-problem), [Running without the ops GUI (serve.bat and stop.bat)](#running-without-the-ops-gui-servebat-and-stopbat), [Commands](#commands), [Windows Firewall and antivirus](#windows-firewall-and-antivirus), [Linux (x86-64)](#linux-x86-64), [What the server writes](#what-the-server-writes)
- When something goes wrong: [Troubleshooting](#troubleshooting), [Exit codes](#exit-codes), [Why a start was refused](#why-a-start-was-refused), [Why an update was refused](#why-an-update-was-refused)
- [Licenses](#licenses)

## What you need

- Windows 64-bit, or Linux x86-64.
- A purchased copy of Starsector 0.98a-RC8, installed on this computer and activated under the Windows user account that runs the server (start the game from its launcher once and enter your key).
- A UDP port players can reach: (a) ProtonVPN port forwarding (Windows): connect the ProtonVPN app with port forwarding on, then press "Fill from ProtonVPN" in the ops GUI - my own test servers run this way; or (b) a router port forward (UDP 51337 as shipped) allowed in your firewall - not yet tested with players from outside. If your router's status page shows an internet address from `100.64` to `100.127`, you are behind carrier-grade NAT and (b) cannot work. For an always-on server, I highly recommend (b), as VPN leases are unstable.
- A folder of its own for this package on this computer's own disk, writable by your account and outside cloud-synced folders (OneDrive, Google Drive, Dropbox and the like; Desktop and Documents may be synced by OneDrive), such as `C:\AlphaCoreMP-server`: the server rewrites its files in place at every start and update, and a syncing folder can hold them back or change them.

## Your first match

For Windows; on Linux, first do the steps under [Linux](#linux-x86-64), then start the ops GUI with `sh ops-web.sh`.

1. Make a new, empty folder (see [What you need](#what-you-need)) and extract the whole zip into it (it has no folder around its files: you then see `ops-web.bat`, this `README.md` and folders such as `jre` and `kits`).
2. Double-click `ops-web.bat`. Because the zip came from the internet, Windows may ask first: in an "Open File - Security Warning" box choose "Run"; in a blue "Windows protected your PC" box click "More info", then "Run anyway". A black window opens: the ops GUI program (keep it open while you host). It prints

   ```
   open http://127.0.0.1:7717/#<key>
   ```

   and opens that address in your browser (the part after the `#` is the ops GUI's key; if no page opens, copy the whole address there).
3. The page opens on "Players": click "Setup". In "Network", (a) with ProtonVPN port forwarding on, press "Fill from ProtonVPN" to fill "Listen on host", "port" and "Players connect to"; (b) with a router port forward, leave "Listen on host" at `0.0.0.0`, type the forwarded UDP port into "port", and in "Players connect to" (`<public IP>:51337` as shipped) put your public internet address (your router's status page shows it) or a host name for it in place of `<public IP>`. Into "Game folder" type or paste the folder you installed Starsector in, the one holding the `starsector-core` folder, usually:

   ```
   C:\Program Files (x86)\Fractal Softworks\Starsector
   ```

   There is no Browse button (a browser cannot pick a folder on the computer that runs the server).
4. In "Before start", set the "Match password" players type to join (required: 8 to 64 letters, digits or hyphens, starting with a letter or digit, not a common password). "Admin password" is optional (12 to 64 characters, not the match password): it unlocks the admin commands in game; the host on this computer needs none. Leave "Match shape" (the game mode) and the rest as they are.
5. Press "Start server" ("SERVER" group, bottom bar). The headline at the top reads STARTING, with a count of seconds, while the engine loads (often about a minute). The first time, Windows may ask whether to allow "OpenJDK Platform binary": click "Allow access". LISTENING: the server is ready for players. REFUSED or FAILED: the line under it says why.
6. Press "Copy join brick" (end of "Network"): it copies the connect code, `<address>:<port>:<match password>`, checked against the running server's port, and shows it for 45 seconds. It refuses, saying why (and calling "Players connect to" "Advertise"), while `<public IP>` is not replaced, the port is not the running server's or no server runs.
7. Send each player the kit zip from the `kits` folder and, privately, the code: anyone who has it can join. The kit's `README.md` tells players (who need Starsector 0.98a-RC8 too) how to join; they press "Play" when you say GO. Each player who joins shows as a row on "Players". One player with a ship may join per internet address unless you raise "Seats per address" (see [Kicking and banning players](#kicking-and-banning-players)).
8. When you are done, press "End match" ("MATCH" group) and confirm. Players cannot connect? See [Troubleshooting](#troubleshooting).

## Stopping the server

- "End match" ("MATCH" group) is the normal way: confirm "End the match?"; players get a clean goodbye, the server exits and the headline reads MATCH ENDED ("Start server" then begins a new match at once).
- "Kill (fallback)", under "More", is for a server that does not answer.
- A hard stop ("Kill (fallback)", `stop.bat` or `stop.sh`, or Ctrl+C in the `serve.bat` window) ends the server process at once, so ban-list changes or custom ship exports it has not finished writing can be lost. "End match" does not cut these off: before the server exits it waits a few seconds for ban-list changes and ship exports still being saved; a save that is not done by then is lost, and the server's log says so.
- Closing the browser tab stops nothing: the ops GUI and the server keep running.
- "Close ops GUI" (top right of the page, after a confirm) ends the ops GUI program itself; the page then reads OPS GUI CLOSED. On Windows, closing the ops GUI's black window does the same. Either way only the ops GUI ends: a server it started keeps running, and keeps its port, until you end the match or run `stop.bat`. Double-click `ops-web.bat` again to reach it: the page shows that server still running, and "End match" stops it. On Linux, end the match before you close the terminal that runs `ops-web.sh`.
- Do not end the launcher (the `java.exe` that starts the server and starts it again after a failure) with Task Manager or `taskkill /F`: the server it started keeps running and keeps the port; if that happened, end the server's `java.exe` too before you start again.

## Hosting again later

1. Open the ops GUI's address again if its black window is still open; otherwise double-click `ops-web.bat` (the key, and so a bookmark of the address, stays the same).
2. On "Setup", type only what you change. Password boxes that read "saved - type to replace" keep the saved passwords when left empty ("Show saved passwords" shows them, after a confirm).
3. Press "Start server", then "Copy join brick". The code changes only with the address, port or match password (with ProtonVPN, both buttons ask ProtonVPN again first); then send the new one. Players keep their kit until an update brings a new one.

## The players' kit and the connect code

- The kit is `cc-joiner-kit-<date>-<time>.zip` in the `kits` folder: the joiner kit this package was made with. Give it to every player once and leave it there: the server will not start without it. The server turns away any other kit; a newer kit comes with a newer server package (see [Updating to a newer package](#updating-to-a-newer-package)).
- The kit is the game code only, so the same kit joins every match shape; on a Linux server the same: the kit is for Windows players. It holds no secret (no match and no seat entry), so players may pass it on.
- The connect code is the secret: keep it off streams and recordings. Without the ops GUI, type it yourself:

  ```
  <your address>:<server.bindPort>:<your password>
  ```

## Updating to a newer package

1. Download the new `cc-server-<date>-<time>.zip` (Linux: `cc-server-linux-<date>-<time>.zip`). Keep it outside this folder.
2. Stop the server and close the ops GUI's black window.
3. Drop the zip onto `update.bat` (Linux: `sh update.sh <zip>`). Its window shows this folder's version, the zip's version and how many files change, extracts the zip over this folder and says "extracted ...".
4. Start the ops GUI (or `serve.bat`) as before: that start finishes the update. Its line starting "updated to `<new version>`" lists what changed and says "your settings, passwords, bans, kept ships, mods and recordings are kept"; with a new players' kit, it also says "players need the new kit:": give that kit zip from the `kits` folder to every player (an old kit is turned away).

If the update window closed before it finished, run `update.bat` again with the same zip. If you ever extracted a new zip over the folder by hand, just start the ops GUI: it repairs the folder and says so. A refusing `update.bat` changes nothing (see [Why an update was refused](#why-an-update-was-refused)).

Kept: your settings (`launcher.properties` and `cc-server.properties`), the passwords, the ban list, hidden and kept ships, your match shapes, your mods, recordings (the `run` folder) and the ops GUI key. A setting this version adds is added with its shipped value; one you never changed moves to this version's value; one you changed stays. A folder's first update (or one after its `run` folder was deleted) only adds the new settings.

Replaced: the program (the `jre` and `classes` folders and the other program files), the scripts, this README, and the shipped match defaults in `match-defaults\`.

## The ops GUI

### Opening it

- `ops-web.bat` (Windows) and `sh ops-web.sh` (Linux: it starts a virtual screen like `serve.sh` and needs the same tools) start the ops GUI. Its page is at `http://127.0.0.1:7717/`, on THIS computer only.
- The address the ops GUI prints ends in its key: open exactly that address (a plain `http://127.0.0.1:7717/` shows only a sentence asking for the key). On Windows an ops GUI started in a window (a double-click, or a command prompt) also opens it in your browser. An ops GUI started without a terminal prints no address: open `http://127.0.0.1:7717/#<the key from run\console.token>`.
- For another port, run it from a command prompt or a shell with the port after the name (`ops-web.bat 8080`, or `sh ops-web.sh 8080`), and put that port in place of 7717.
- To use the ops GUI from another computer, tunnel to this one:

  ```
  ssh -L 7717:127.0.0.1:7717 <this computer's address>
  ```

  then open the SAME printed address there.
- Run one ops GUI per package folder. While one is running, a second `ops-web.bat` (or `sh ops-web.sh`) starts no second ops GUI: it says "the ops GUI is already running since <time> (build <stamp>)", on Windows opens the running one's page in your browser with its key, and ends. That is the way back to the page after closing its tab. The old refusal ("another console (or `update.bat`) holds `run\console.lock` for this folder - close it first") remains only while `update.bat` holds the lock, or when the running ops GUI left no `run\console.running` card (an older build): close it, or wait for the update.
- `ops-web.bat` ends with code 3 when `jre\bin\java.exe` is missing, like `serve.bat`; `ops-web.sh` ends with `serve.sh`'s own codes 2 and 3.

### The page

- "Players", "Setup" and "Mods" at the top switch the page; "?" opens the help (a line for every control, and the keyboard keys).

### The ops GUI key

- It is kept in `run\console.token` (Linux: `run/console.token`) and stays the same across ops GUI restarts, so a saved address keeps working. To make a new key, stop the ops GUI, delete that file and start it again; every open tab then needs the new address.
- Every copy of that file is a working key until then: keep backups and synced copies of the package folder as private as the folder itself. On Linux the file is readable only by the account that started the ops GUI; on Windows anyone who can read the package folder can read it and use the ops GUI, so on a shared computer keep the package in a folder other accounts cannot open.

### Network settings

- "Listen on host": `0.0.0.0` (every network connection of this computer) or one of this computer's own addresses; `127.0.0.1` with your public address in "Players connect to" works when a tunnel on this computer forwards the port to it ("Copy join brick" only checks that "Players connect to" names the running server's port and is a plain `<address>:<port>`).
- "Start server" writes `server.bindHost` and `server.bindPort` into `cc-server.properties`; `serve.bat` and `serve.sh` read the same two. Leave `bind.host=` and `bind.port=` in `launcher.properties` blank: Start refuses while either is set (it would override the ops GUI's address and port).
- "Copy join brick" saves "Players connect to" into `launcher.properties` as `advertise=`, so the next ops GUI start shows it again; the launcher itself never reads that row.
- "Fill from ProtonVPN" asks the VPN gateway for your public address and reads the forwarded port from the ProtonVPN app's log. While the addresses stay as filled, Start and "Copy join brick" ask again first, also after an ops GUI restart. The ops GUI never opens or renews a port.

## Playing as the host

The host has the admin commands in game and the ship list in the kit's ship editor ("Open editor" in the join window).

On the server computer (Windows): extract `CC_Client` from the joiner kit into the `mods` folder of the Game folder the server uses, start the server, press "Join from this PC", then "Launch" in the join window (it is filled in with this server's address, port and password). That game window is the host, with no admin password. The join window remembers the password in your Windows profile, as for any server.

If the server restarts while that game is open, it is no longer the host: sign in as on another PC (needs an "Admin password"), or close the game and its black `join-cc` window and press "Join from this PC" again.

On another PC: set an "Admin password" (before Start), then type it into the "admin password" box on the ship editor's "Ships on this server" panel and press "Sign in as host" (once per game launch, and again after every server restart), or type `/auth <admin password>` in the game's chat; the game never saves it.

To delete the saved admin password, tick "Remove the admin password" and press "Start server".

## Kicking and banning players

- On "Players", pick a seat row, open "Seat actions", press "Kick selected", "Ban player" (the player key, each player's own id, AND the address they connect from) or "Ban source" (the address only: everyone behind it) and confirm. To ban an address or range, type it into "Ban address or range" and press "Ban address".
- The ops GUI lists the first 64 bans ("N more not listed"): `unban cid <key>` (or `unban src <key>`) in the command file still lifts any ban - every ban is in `cc-server.bans` (see [Commands](#commands)). A pasted log or a shared bans file hands nobody a player key (both hold only its fingerprint), but the bans file holds banned addresses and seat names.
- An address of this computer itself (`127.x.x.x` or `::1`, a "Join from this PC" seat) is never kept in the bans file: `ban-ip` refuses it, and a `ban-src` on such a seat lasts until the server stops.
- One player with a ship may join per internet address (a home behind one router is one; spectators and this computer's own address do not count); a second is turned away. "Seats per address" ("Advanced", before Start; `moderation.maxSeatsPerSource`): 0 to 255, 0 = no limit. Players behind one tunnel or gateway count as one unless its address is in `moderation.sourceCapExempt`.

## Changing the match

Players never need a match file: their join windows fetch the match from the server, so nothing is sent and the kit stays the same; anyone in a game closes it and presses R in the black `join-cc` window. Every "Apply" on the "Setup" tab reaches players this way. "Before start" is locked while a server runs: end the match first.

- "Match shape": pick one, press "Start server"; the password carries over.
- Map size, station, background, deploy CR and player count: pick the match shape, change a value ("players" beside "Match shape", 32 as shipped; the rest in the "Battle map" row under "Advanced", map size as "half size"; "?" gives each range), press the "Apply" beside it; it takes effect at the next Start.
- "New match shape..." (under "Advanced") makes a shape, ready when the ops GUI says: `created <name>` - pick it and press "Start server".
- PvP mode: set "PvP mode" to "On", press "Start server". Players fight in two teams, South and North, each from its own map edge; no station, nobody wins, the match runs until you end it. Players pick a team in the join window or type `/team north` or `/team south` in game (from their next respawn). Waves start paused; an admin presses F12 to start them, and they fight for North. Needs a "bastion:fortress" match shape and 2 or more players. Changing it makes a new match: players press "Refresh", and anyone in a game closes it and presses R.
- The `match` folder is the server's own. The shipped shapes are copied from `match-defaults\` into `match\` once, at the first start; the ops GUI writes the copies, their `.fingerprint` files and the password files, and an update replaces `match-defaults\` and keeps your copies. Change a match only in the ops GUI (its "Apply" buttons and "New match shape..."), never by editing a match file by hand: the server may start a hand-edited file without warning, players who joined that match before can keep their old copy, and the ops GUI's next change to that match rebuilds the file from the settings it shows. Without the ops GUI, you write the password file yourself (see [Running without the ops GUI](#running-without-the-ops-gui-servebat-and-stopbat)). To undo a hand edit to a shipped shape, delete that match file and its `.fingerprint` file; the next start makes a fresh copy from `match-defaults\`.

## Mods

- Extract each mod's folder into `serve-mods\` (Linux: `serve-mods/`), next to `CC_Client`, the server's own mod. The "Mods" tab lists what is there: set a mod to "required" (the server loads it; every player must have it), "optional" (players may run it; the server does not load it, for graphics-only mods such as GraphicsLib) or "off", then press "Start server". Without the ops GUI, write the ids into `cc-server.properties` (`mods.enabled=id1,id2` and `mods.optional=id3`; the id is the "id" in the mod's `mod_info.json`) and start `serve.bat` (Linux: `sh serve.sh`).
- "Remove" sets the mod to off; to take it off the list, delete its folder from `serve-mods` while the server is stopped. Nothing in `serve-mods` is ever deleted by the server.
- At every start the launcher enables the required mods together with the mods they declare as dependencies, writes `serve-mods\enabled_mods.json` (Linux: `serve-mods/enabled_mods.json`) and copies in any mod you added from another folder ("Add folder" in the "Mods" tab). A required mod missing from the searched folders refuses the start and names it (reason "mods").
- Players need the same mods at the same version in their own `mods` folder: the joiner kit compares against the server's list before the game starts and tells them what is missing, and the server turns away a mismatched set. A mod that needs the game's graphics cannot be required: the server runs without a window.

## Custom ships

Players may build their own ship in the kit's ship editor and fly it. This is on by default (`custom.submissions=true` in `cc-server.properties`). While the server runs, the "accept customs" box (in the "Customs" menu, "CUSTOMS" group) or the `custom-submissions on|off` command switches it, but cannot switch it on for a server started with `custom.submissions=false` (set it to `true` and start again). The `custom-...` commands (see [Commands](#commands)) ban, unban or export one ship, or refuse or allow one player's ships.

A custom ship lasts only while the server runs. "Keep for everyone" saves it in `variants-inbox\kept` (Linux: `variants-inbox/kept`); every start checks it against the custom ship rules again and lists it on `/ship` (the in-game ship list) for everyone, with its builder's ship name. The join window shows the first ships of the list; any listed id can be typed there. A `.variant` file you copy into `variants-inbox\kept` is listed by the next start; its name must equal the `variantId` inside (needs `custom.submissions=true` at start). A kept ship cannot be `match.joinerDefaultVariant` in `cc-server.properties` (kept ships are listed only after the server has started); once the server runs, `set-joiner-default` can name one. After a restart, `custom-ban <id>` no longer names that ship's kept copy, which stays on `/ship`: "Forget kept" (`custom-forget`) takes it off.

## The host's ship list

The host manages the list every player picks from with `/ship`, in the kit's ship editor, on its "Ships on this server" panel, which shows while you play in a match on your server, signed in as the host (see [Playing as the host](#playing-as-the-host)).

Each ship there has "Load" (open it in the editor), and "Remove" or "Put back". "Remove" takes a ship off `/ship` for every player, now and after restarts; a player already flying it, or whose seat's default ship it is, keeps it. "Put back" returns it to `/ship`. A removed kept ship's file goes back to `variants-inbox`. The server's default ship cannot be removed (change it in the ops GUI first), and the last listed ship stays.

"Promote", on the ship you are building, adds it to `/ship` for every player as a kept ship; it does not change your own next respawn. A ship on the list never changes in place: "Promote" on a ship you loaded from the list and then changed keeps your changed copy under the name you gave it and takes the original off `/ship` ("Put back" on the original brings it back and drops the changed copy); "Promote" on a loaded ship you did not change puts it back if it was removed. The server checks each copy against the custom ship rules, so "Promote" needs `custom.submissions=true`, and a few of the ships that come with the server cannot be kept unchanged.

The panel needs a running match; otherwise use the ops GUI: on its "Players" tab, the list "Ships that come with the server" (below "Kept for everyone") has "Hide" and "Show", the same as "Remove" and "Put back".

## Test matches (Launch debug WATCH)

"Launch debug WATCH" (Windows only) starts a test match plus your own join window, and a second one when a "Second game folder" is set ("Setup" tab, under "Debug"). Every player gets hull bars and the debug keys; your windows sign in as admin, like "Join from this PC", with no admin password. For normal play, use "Start server". It also writes each window's logs into `run\flight-<date>-<time>\client\` (`client2\` for the second window) on this computer: around 200 MB per hour per window, up to 1 GB per window by default; past that the file starts over, keeping only its first lines. `watch.clientLogMaxMiB` in `launcher.properties` sets that cap in MiB (96 to 65536; blank means 1024; any other value is refused). Delete that folder when you are done with it. When its server stops ("End match", F10 or "Kill (fallback)"), the ops GUI closes the join windows it opened.

## When a player reports a problem

Ask for their session report zip (`cc-debrief-<date>-<time>-<number>.zip`, saved on their Desktop when their game closes). Each time they pressed F9 in the match, its `cc-client.log` holds a numbered marker line (#1, #2, ...) for that moment, beside the game's own `starsector.log`. Each press also writes a marker line with their seat number into the server's own log of that start (`lives\life-N.out.log` in its `run\flight` folder), even when the player switched the report off. To see what happened, read both logs around the marker the player names.

## Running without the ops GUI (serve.bat and stop.bat)

The fallback when you cannot or do not want to use a browser: these scripts run the same server as "Start server".

1. Double-click `serve.bat` once: it makes your settings files, then stops at "serve-launcher refused: the row `game.root` in the launcher properties is blank; set it to the game's install folder".
2. In `launcher.properties` set `game.root=` to the game folder (see [Your first match](#your-first-match), step 3): paste the path after the `=` (backslashes and a path in quotes both work). In `cc-server.properties` set `server.bindPort=` to the forwarded UDP port.
3. Write the match password file, beside the match file that `match.descriptor=` names, with `.password` added (as shipped, `match\match.txt.password`): one line `password=<your match password>` (the "Match password" rules). An optional admin password goes into `match\match.txt.adminpassword` the same way (12 to 64 characters, not the match password); the notes above `match.descriptor=` describe both. In Notepad, choose "Save as type: All files", or `.txt` gets added.
4. Start: double-click `serve.bat`. Its window shows a line each time the server starts ("serve-launcher: life 1 started ..., output in `<log>`"); the server's own output goes to that log file. Keep the window open while the server runs. A server that fails is started again, up to 6 starts in all. `serve.bat` ends with the launcher's own exit code; with any code but 0, its window waits for a key so you can read the last lines.
5. Stop: double-click `stop.bat`. It creates the empty file `run\stop`; the launcher checks for it once a second, ends the server and exits with code 0. Ctrl+C in the server window also stops it (the launcher then exits with code 130). Either can lose a save still in progress (see [Stopping the server](#stopping-the-server)).

The settings files: `launcher.properties` (how the server program starts: `game.root`, `java.home`, the timer row, the recording rows `record=` and `record.maxMiB=`) and `cc-server.properties` (the network, the match and moderation: `server.bindHost`, `server.bindPort`, custom ships and more) are made at the first start (ops GUI or `serve.bat`) from `launcher.template.properties` and `cc-server.template.properties`; an update replaces only the templates. The ops GUI writes most settings; you can edit the files in a text editor (Notepad is fine; lines starting with `#` are notes). Every setting the ops GUI can set has a note in `cc-server.properties`. A misspelled setting name is reported at start, and ignored; a change counts from the next start. Leave `java.home=` blank (the server then uses the `jre` folder in this package).

## Commands

These work with or without an ops GUI. While the server runs, put commands into the file `run\ops\ops-cmd.txt` (Linux: `run/ops/ops-cmd.txt`), one per line, and save it: the server reads the file, runs each line and deletes it. The ops GUI buttons send these same lines. A line the server cannot read is skipped, with a line in the server's log. `<seat>` is a seat number as the ops GUI shows it; `show` prints the current setting.

### Match

- `end-match`: end the match (players get a clean goodbye)
- `end-round`: end the current round
- `set-sim-speed <speed>`: run the battle slower or faster: 0.25 to 2.0, 1 = normal speed
- `sim-speed-auto on|off`: the automatic speed control on or off
- `set-joiner-default <ship id>|none`: the ship a joining player gets unless they pick one; `none` clears it

### Players

- `kick <seat>`: disconnect that seat's player
- `switch-team <seat> instant|respawn`: move that seat's player to the other team, at once or at their next respawn

### Bans

See also [Kicking and banning players](#kicking-and-banning-players).

- `ban <seat>`: ban that seat's player: their player key AND the address they connect from (after they left: the seat's last player and address); the address part follows the setting `moderation.chatSourceHold` (shipped: `match`)
- `ban-src <seat>`: ban the network address that seat's player connects from (everyone behind that address)
- `ban-ip <address or range>`: ban an address or a whole range, e.g. `1.2.3.4` or `1.2.3.0/24` (IPv4 ranges `/8` to `/31`, with the host bits zero: `1.2.3.0/24`, not `1.2.3.4/24`); for IPv6 copy a key from the bans listing, or type a provider range like `2a0b:f4c0::/32` (`/16` to `/64`)
- `ban-session <session id>`: ban a connected observer (no seat)
- `ban-cid <key>`: ban a player who is offline, by the player key the server's listings print; the address they last connected from is banned with it when the server still has it
- `unban cid <key>`: lift a player ban
- `unban ip|src <key>`: lift an address ban; `<key>` is the address or range exactly as the bans listing prints it (an IPv6 range is stored in a shorter form than you typed)
- `bans`: write every ban to the server's log: player keys as one-way fingerprints, addresses in full

### Custom ships

`<id>` is a custom ship's `cc-v...` id.

- `custom-submissions on|off`: accept new custom ships, or not
- `custom-reject <seat>`: refuse that seat's player's custom ships: waiting ones are dropped, accepted ones banned
- `custom-allow <seat>`: undo `custom-reject` for that seat's player
- `custom-ban <id>`: no player may pick that custom ship again
- `custom-unban <id>`: undo `custom-ban`
- `custom-export <id>`: save that custom ship as a file in `variants-inbox` (players do not see it)
- `custom-keep <id>`: keep that custom ship for every player, also after restarts
- `custom-keep-export <file name>`: keep an exported file (`cc-v...` or `kept-...`) from `variants-inbox` for every player, also after restarts
- `custom-forget <ship id>`: take a kept ship off `/ship` (its file moves back to `variants-inbox`)

### Ship list

`<id>` is a ship id as `/ship` lists it; see [The host's ship list](#the-hosts-ship-list).

- `ship-hide <id>`: take a ship that comes with the server off `/ship` for every player, also after restarts
- `ship-show <id>`: put a hidden ship back on `/ship`

### The host can type these in the game's chat

Admin only; the ship editor's "Ships on this server" panel sends them for you.

- `/shiplist`: list the ships: listed, hidden, kept, removed
- `/shipkeep <id>`: keep that custom ship (`cc-v...`) on `/ship` for every player, also after restarts
- `/shipremove <id>`: take that ship off `/ship` for every player (a kept ship's file moves back to `variants-inbox`)
- `/shiprestore <id>`: put a hidden or removed ship back on `/ship`

### Waves and terrain

- `skip-wave`: end the current wave and send the next one
- `pause-wave`: pause the wave timer, or resume it
- `wave-category <faction> [reset]`: the faction the next waves come from: `remnant`, `derelict`, `omega`, `threat`, `shrouded`, `hegemony`, `persean_league`, `tritachyon`, `luddic_church`, `luddic_path`, `sindrian_diktat`, `pirates`, or `ziggurat` (one boss wave); `reset` also goes back to the first wave
- `wave-set <1-9>`: that wave spawns next
- `endless on|off|show`: waves go on past the last one, or not
- `wave-inflate on|off|show`: vary the enemy ships' loadouts, or not
- `spawn-foe <ship id> [count] [officer] [@x,y]`: add enemy AI ships now: `count` 1 to 32 (default 1), `officer` gives them an officer, `@x,y` places them on the map
- `spawn-friend <ship id> [count] [officer] [@x,y]`: the same, on the players' side
- `despawn <ship number>`: remove one AI ship from the battle by its number (no wreck); player ships are refused
- `terrain show|<type> [<level>]`: show or set the battle's terrain hazard and its strength (the ops GUI's "Terrain" choices)

### Settings the server sends to every player's game

- `knob list`: list them
- `knob <name> show|<value>`: show or change one

### Voice chat

- `voice <setting> <value>`: change one voice setting while the server runs
- `voice set <setting>=<value> ...`: change several at once (all or none)

Mods have no command: set them before Start (see [Mods](#mods)).

## Windows Firewall and antivirus

The first time the server starts, Windows may ask whether to allow "OpenJDK Platform binary" (Eclipse Adoptium): this package's own Java (`jre\bin\java.exe`). Click "Allow access"; if you refuse, players cannot reach the server (allow that `java.exe` later under "Allow an app through Windows Firewall"). Windows remembers the answer for this folder only: after you move or re-extract the package, it asks again.

Each server start on Windows also runs a short PowerShell command that asks Windows to keep the server's timers precise (Windows 11 otherwise coarsens them for a program without a window a few seconds after it starts, which makes the ticks less even). An antivirus may report that command: it compiles a few lines of code and opens the server process. The server runs either way; when the command was blocked, the ticks are a little less even. To skip it, add the line `timer.optOut=off` to `launcher.properties` (it is listed there as `# timer.optOut=off`: removing the `#` does the same). It counts for `serve.bat` and for an ops GUI Start, and an update keeps it.

## Linux (x86-64)

This package runs on x86-64 only (its `jre` folder and the game's native libraries are x86-64 builds): on any other processor (an ARM server, a Raspberry Pi) `serve.sh` and `ops-web.sh` refuse with code 2 and say so.

1. Install the tools the server needs (Debian / Ubuntu):

   ```
   sudo apt install unzip xvfb libgl1-mesa-dri libglx-mesa0 x11-xserver-utils xauth libxcursor1 libxrandr2 libxxf86vm1 libxinerama1 libxtst6
   ```

2. The server runs the WINDOWS build of the game (the Linux download of the same version is a different build, refused at start with "cannot check the installed game"): copy the `starsector-core` folder from a Windows install of this version to the host (`scp` or a USB stick; about 220 MB; the game's own logs are not needed). On Windows it is usually in

   ```
   C:\Program Files (x86)\Fractal Softworks\Starsector\starsector-core
   ```

3. Unzip the package into a folder whose path has no spaces or colons, on a Linux file system (not a Windows share).
4. Set the game folder at your first start (step 6): type it into "Game folder" in the ops GUI, or, after a first `sh serve.sh` has made `launcher.properties`, set `game.root=` there. Use the folder you copied, with `/` between folder names and no colon in the path, e.g. `/home/<you>/starsector-core`
5. Activation: the server reads it from the Java preferences of the account that runs it. On a host without a screen, copy it from the computer where you activated the game: there, in a Command Prompt in the folder of an extracted Windows package (`cc-server-<date>-<time>.zip`), run

   ```
   jre\bin\java -cp classes cc.starsector.server.ops.PrefsNode export > activation.xml
   ```

   then on the host, in this folder, as the account that runs the server (`sh serve.sh`, or the ops GUI),

   ```
   chmod u+x jre/bin/java
   ./jre/bin/java -cp classes cc.starsector.server.ops.PrefsNode import < activation.xml
   ```

   Then delete `activation.xml` on both machines - it holds your key. A host with a screen may instead start the game from its launcher once there and enter the key.
6. Ops GUI: `sh ops-web.sh` (it makes `serve.sh`'s checks, the graphics library included, before it starts, since the server it starts needs them; it opens no browser: on a host without a screen, reach its page through the ssh tunnel under [The ops GUI](#the-ops-gui)). Without it: `sh serve.sh` starts (it starts a virtual screen and restores the execute bits the zip drops), `sh stop.sh` stops. `serve.sh`'s own exit codes: 2 = this computer is not x86-64, a tool or graphics library is missing, or this folder's path has a space or a colon; 3 = the `jre` folder is incomplete or cannot be made executable.

"Join from this PC" and "Launch debug WATCH" are Windows only: join from a Windows PC with the "Copy join brick" code. "Fill from ProtonVPN" on Linux fills the addresses only: type the forwarded port and keep it open as ProtonVPN's Linux guide describes (untested so far).

## What the server writes

Everything below is inside this folder.

- `run`: one `flight-<date>-<time>` folder per start ("Start server" or `serve.bat`): the launch receipt and each server start's logs (`lives\life-N.out.log` and `.err.log`). If the server process itself crashes, its crash report `hs_err_pid<number>.log` (and `replay_pid<number>.log`) lands in that `lives` folder too; no memory dump is written. `record=diag` or `record=full` in `launcher.properties` (the ops GUI's "Recording", under "Debug") adds `gc.log` and `serve-blocks.jfr` to each flight folder, and `full` also `wire.ccwr` (all network traffic; `wire-2.ccwr` and on after a restart): up to `record.maxMiB` MiB (default 4096) each time the server starts, and again each time it restarts itself after a crash (up to 6 per Start). Only AlphaCoreMP's own tools read them; `wire.ccwr` holds chat and names. It grows with every start: delete old flight folders while the server is stopped.
- `run\ops`: `ops-status.txt`, the server's status line, and `ops-cmd.txt`, the command file the server reads and deletes
- `run\update`: the update log (`update.log`), and the `MANIFEST.txt` and the two settings templates the last update was finished against
- `run\stale`: files an update set aside: a file in a program folder that the new package does not list, or a program file you edited that the new package no longer has. Nothing there is deleted; delete it yourself once you are sure you do not need it
- `cc-server.bans`: the ban list (player keys as fingerprints, addresses in full; `cc-server.bans.tmp` while it is being saved)
- `cc-server.hidden-ships`: the ships that come with the server that you took off `/ship` ("Remove" or `ship-hide`), one id per line (`cc-server.hidden-ships.tmp` while it is being saved). If the server cannot read it, it starts anyway, says so in the ops GUI and saves no hide until you fix or delete the file.
- `generated`: a folder: its `customs` folder keeps each ship a player submits that the server accepts
- `variants-inbox`: made when a ship is exported or kept: exports are your files; ships in its `kept` folder are on `/ship` for every player.

## Troubleshooting

### Players cannot connect

- After a Start with "Fill from ProtonVPN" the ops GUI tests the forwarded address once ("Reachability"); "Check from the internet" (beside "Players connect to") repeats it in any mode. No answer in ProtonVPN mode: check that port forwarding is on in ProtonVPN, press "Fill from ProtonVPN", then "End match" and "Start server" again; allow Java through the firewall if Windows asked. With a router port forward, no answer proves little (many home routers cannot loop a packet back in): ask a player to try.
- Behind carrier-grade NAT (see [What you need](#what-you-need)) a router port forward cannot work. An old kit is turned away: hand out the kit from the `kits` folder. "seats-per-address limit is reached": see [Kicking and banning players](#kicking-and-banning-players).

### The headline reads

- REFUSED: the line under it starts "REFUSED before launch: `<reason>`", then the launcher's "serve-launcher refused:" line (`serve.bat`'s window shows it too) says what to change (see [Why a start was refused](#why-a-start-was-refused)).
- FAILED: a start failed (the line under it says what). While starts are left, the launcher starts it again (up to 6 starts in all); after the last one, or after code 78, nothing restarts (see [Exit codes](#exit-codes)).
- SERVER GONE (no clean end - killed or crashed): the server ended without a clean end; "Start server" starts a new one.
- STALE (server wedged? snapshot stopped): the server process is alive but stopped reporting; if it stays so, "Kill (fallback)" ends it.
- WRONG KEY: the address holds a key the ops GUI does not accept (an old one): open the printed address, or add `#` and the key from `run\console.token`. CONSOLE UNREACHABLE: the ops GUI is not running (was its black window closed?) or the ssh tunnel is down: start `ops-web.bat` again. OPS GUI CLOSED: "Close ops GUI" was pressed on this page; double-click `ops-web.bat` to start it again.

`serve.bat`, `ops-web.bat` and their `.sh` twins stop with "an update is running or was interrupted" (exit code 2) while an update is extracting or after one stopped half-way: wait for the update's window to finish, or run `update.bat` again with the same zip.

## Exit codes

`serve.bat` ends with the launcher's own code.

- 0: the server was stopped (`stop.bat`) or ended on its own without error
- 1: the server kept failing and the limit of 6 starts was used up; the window (or the ops GUI page) shows its last error line and the path of its error log. A setting the server cannot use fails this way within about a minute: the error line names the setting; fix it in `cc-server.properties` and start again.
- 2: refused before starting: one "serve-launcher refused:" line says why and what to change (see [Why a start was refused](#why-a-start-was-refused))
- 3: a file is missing or changed - run `update.bat` again with the zip you downloaded (Linux: `sh update.sh <zip>`). `serve.bat` also ends with 3 when `jre\bin\java.exe` is missing.
- 4: a stop (`stop.bat` or the ops GUI's "Kill (fallback)") arrived while the server was starting; nothing started. Start again when you mean to.
- 78: the game's activation or version check on this computer refused; the line above the code says which. Activation: start the game from its launcher once, enter your key, then start the server again. Version ("cannot check the installed game"): the game folder is not Starsector 0.98a-RC8, or it holds the Linux download (see [Linux (x86-64)](#linux-x86-64)). Unlike code 1, the launcher does not retry after this one.
- 130: Ctrl+C in the `serve.bat` window stopped the server.

## Why a start was refused

Each refusal is also written as `reason=<name>` into the file `run\flight-<date>-<time>\wrapper-receipt.txt`. The names:

- `template_missing`: `serve.argv` is missing, unreadable or empty
- `template_token`: `serve.argv` holds a placeholder the launcher does not know
- `template_leak`: `serve.argv` names a folder of the computer it was made on
- `path_unsafe`: (Linux only) this folder's path has a space or a colon, or `game.root` a colon: move to paths with neither
- `java_missing`: no Java runtime where `java.home` points (blank = the `jre` folder in this package); on Linux also a `jre/bin/java` that is not executable (run `sh serve.sh`)
- `display_missing`: (Linux only) `DISPLAY` is not set: start the server with `sh serve.sh` (it starts a virtual screen)
- `mesa_missing`: software rendering needs `opengl32.dll` and `libgallium_wgl.dll` beside `jre\bin\java.exe` (or set `gl.driver=hardware`)
- `game_root`: `game.root` is blank, or names a folder that does not hold the game (its `starsector-core` folder and its native libraries): set the ops GUI's "Game folder"
- `game_jar_missing`: a game file that `serve.argv` names is missing from the install
- `config_missing`: `cc-server.properties` is missing, unreadable or larger than 256 KB
- `descriptor_row`: `cc-server.properties` has no usable `match.descriptor` row
- `descriptor_missing`: the match file that row names does not exist
- `password_missing`: the match file is the public copy, so players join by password only: set the "Match password" ([Your first match](#your-first-match), step 4)
- `admin_sidecar`: the optional `match\match.txt.adminpassword` file is present without a match password file, or holds the same password (every player would be admin)
- `sidecar_missing`: the match file's `.fingerprint` file is missing
- `sidecar_jar`: the `.fingerprint` file holds no readable `jar=` value
- `sidecar_stale`: the `.fingerprint` file was made for a different client build: every player would be turned away. The line names the match file: delete it and its `.fingerprint` and start again (a shipped shape is copied back; a shape you made: make it again with "New match shape...")
- `sidecar_pair`: the `.fingerprint` file belongs to another match text: press that shape's "Apply" again on the "Setup" tab, or delete both match files and start again (as for `sidecar_stale`)
- `row_parse`: a setting, or a password file, does not parse; the line names it and says why
- `record_row`: `record=` in `launcher.properties` is not `off`, `diag` or `full`, `record.maxMiB=` (read for `full` only) is not a whole number from 1024 to 262144, or `serve.argv` already sets a line recording adds
- `record_disk`: `record=full`, but the disk holding `run` has less free space than `record.maxMiB` plus 1024 MiB: free space, lower `record.maxMiB`, or set `record=off`
- `bind_port`: `server.bindPort` is not a fixed port from 1 to 65535
- `mods`: a required or optional mod (or a dependency a mod declares) is in none of the searched folders, is in two of them, or its folder name collides with another mod's; the line names the mod (see [Mods](#mods))
- `manifest_missing`: `MANIFEST.txt` is missing, unreadable or lacks a row the launcher needs (exit code 2)
- `manifest_mismatch`: a file differs from `MANIFEST.txt` (exit code 3): run `update.bat` again with the zip you downloaded
- `locked`: another launcher is already running from this folder (ops GUI only)
- `serve_running`: a server started from this folder is still running but its launcher is gone, so `stop.bat` cannot reach it: end that process (the line names its pid) in Task Manager / `taskkill`, then start again (ops GUI only)

## Why an update was refused

`update.bat` (`sh update.sh` on Linux) changes nothing when it refuses; its one "update refused:" line says why:

- the zip is older than this folder: an update never goes back to an older version; download the newest zip
- the zip is for the other system (a Linux zip on Windows, or a Windows zip on Linux): download the zip for this computer
- a server or the ops GUI is running from this folder: stop the server and close the ops GUI's window, then run it again
- an update to another version was interrupted: run `update.bat` again with the zip that update was given (the line names its version)
- the zip is not a server package, or lists a file a server package never holds: download it again
- this folder was built from source: rebuild it with `build.ps1`; `update.bat` is for downloaded packages
- cannot tell whether the server (or the ops GUI) is running: a lock or status file under `run\` (Linux: `run/`) is not readable by your account (started by another account?): update as the account that runs the server

Also: "already installed" is no refusal: the zip is this folder's own version, it is extracted again anyway, and the next start repairs the folder. "update failed:" (exit code 1) means the extract stopped half-way: run `update.bat` again with the same zip.

## Licenses

AlphaCoreMP's own code is free to use under the MIT License (see `LICENSE.txt`). The third-party parts it bundles keep their own licenses (see `THIRD-PARTY-NOTICES.txt`). This software is provided as is, without warranty of any kind. Use it at your own risk.
