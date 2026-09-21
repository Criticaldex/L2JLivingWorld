# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repository is

A runnable **Lineage 2 Interlude private server distribution**, built on the **L2J Mobius** engine, branded
"L2 Living World" (public releases at `Teravibes/L2-Living-Worlds`). It is a *dist tree*, not an engine source
checkout: the core server engine ships as prebuilt jars in `libs/` (`GameServer.jar`, `LoginServer.jar`,
`HikariCP`, `mysql-connector-j`, `slf4j`). There is no `build.xml`/`pom.xml`/`gradle` in this repo — the engine
itself is built elsewhere (with `ant`, per `launcher/README.md`) and only the resulting jars land here.

The only Java **source** actually committed here lives under `game/data/scripts/` (`ai`, `custom`, `events`,
`handlers`, `quests`, `vehicles`, `village_master`, `conquerablehalls`) — ~870 files. These are **datapack
scripts**, compiled at runtime by the running GameServer via the system Java compiler (this is why the
launcher insists on a full JDK, not a JRE — see `launcher/README.md`). `game/config/Scripts.xml` controls
which files/folders under `scripts/` get excluded from that runtime compile.

On top of the stock L2J Mobius datapack, this project adds a **"Living World" layer**: synthetic population
of fake players and recruitable "phantom" party members with configurable AI playstyles (`game/data/
PhantomPlaystyles.xml`, `PhantomPopulations.xml`, `FakePlayerBehavior.xml`, `FakePlayerChatData.xml`,
`BotClans.xml`, scripts under `custom/FakePlayers/`), plus an optional LLM-backed in-character chat "brain".

## Repository layout

- `libs/` — prebuilt engine jars (GameServer, LoginServer, HikariCP, mysql-connector-j, slf4j). Not rebuilt
  from this repo.
- `game/`, `login/` — each server's working directory: `config/*.ini` and `*.xml` (runtime-editable
  settings), `data/` (datapack XML + the compiled-at-runtime `scripts/` tree), `log/`, `console.cfg`,
  `java.cfg` (JVM flags — currently `-XX:+UseZGC -Xmx4g -Xms2g`, `org.l2jmobius.log.ServerLogManager`).
- `db_installer/` — `DatabaseInstaller.jar` (GUI) plus raw `sql/login/*.sql` and `sql/game/*.sql` schema
  files, imported directly via the `mysql`/`mariadb` client by the launchers (no migration framework).
  Roughly a dozen of these `DROP TABLE` before recreating — never re-run the importer against a populated DB.
- `launcher/` — the "one-click" launcher. `launcher.ini` is the single config (JVM/db/client toggles).
  Windows path drives a bundled portable JDK 25 + MariaDB via PowerShell (`update.ps1`, `stop.ps1`,
  `Start-Server.bat`/`Stop-Server.bat` at repo root). Linux/Steam Deck path is `launcher/start.sh` /
  `launcher/stop.sh`, which download and manage their own portable JDK 25 + MariaDB under
  `runtime-linux/` (gitignored) and track PIDs in `launcher/.linux-pids/`.
- `brain/` — optional Flask microservice (`fpc_brain.py`) giving fake players/phantoms LLM-generated
  in-character chat (whisper/say/trade/shout), backed by DeepSeek's API or a local Ollama model. Off by
  default; enabled via `StartBrain=true` in `launcher.ini`. Persists lightweight per-player memory to
  `brain/memory/fpc_memory.json`; per-channel conversation context (`conversations`, `say_logs`,
  `trade_log`, `shout_log`) is in-process and TTL-pruned, not persisted.
- `tools/l2admin/` — a single self-contained `index.html` "Server Control Panel" for editing `game/config`
  `.ini`s and the `data/` population/playstyle/clan files with a GUI, either in a browser (File System
  Access API, Chromium-only) or hosted inside the compiled Windows launcher via WebView2, using
  `native-bridge.js` as the file-access shim. Note: its README documents companion pieces
  (`tools/fpc-editor/`, `tools/validate_playstyles.py`, `tools/crest_png_to_dds.py`,
  `tests/js/playstyle_editor_test.js`) that are **not present in this repository** — don't assume they exist
  without checking; the editor's core two files (`index.html`, `native-bridge.js`) are what's actually here.
- `scripts/` — Windows helper batch files (`Check-Updates.bat`, `Configure-Brain.bat`) that wrap the
  launcher/brain setup flows.
- `start_all.sh` — root-level convenience script: starts `brain/setup_brain.sh` in the background, polls
  `http://127.0.0.1:5000/chat` until ready, then execs `launcher/start.sh`.

## Running the server

- **Windows**: double-click `Start-Server.bat` / `Stop-Server.bat` at repo root (thin wrappers around the
  PowerShell launcher). First run auto-installs the DB schema if the target DB is empty (checked via table
  count, so it's safe to re-run against an already-set-up DB).
- **Linux/Steam Deck**: `./start_all.sh` (brings up the brain first if configured) or `launcher/start.sh`
  directly; stop with `launcher/stop.sh`. Both are `x86_64`-only and will download a portable JDK 25 +
  MariaDB 11.4.5 into `runtime-linux/` on first run if none is found.
- Default login: any username/password auto-creates an account; the `admin` account specifically gets
  GM access on every character the moment it enters the world (hook is in the closed engine's
  `EnterWorld.java`, not present as source in this repo).
- There is no "run tests" or "lint" command for this repo — it's a game server distribution, not a library.
  When editing `game/data/scripts/*.java`, the only real verification is booting the server and watching
  `game/log/` for script-compile errors, since compilation happens at server start, not via a separate
  build/test step.

## Working with config vs. code

- Gameplay tuning (rates, PvP, custom systems) lives in `game/config/*.ini` and `game/config/Custom/*.ini`
  — these are read at boot and many support `//reload config` in-game without a restart. Prefer editing
  these over touching scripts when a feature already exposes a config toggle.
- `game/data/*.xml` (skills, populations, playstyles, bot clans, drop/spoil tables, etc.) is the other main
  editable surface; some of it (`PhantomPlaystyles.xml`, `BotClans.xml`) is also editable live through
  `tools/l2admin/index.html`, which round-trips these files byte-for-byte on unrelated lines — if hand-editing
  these files, preserve existing comments/formatting since the GUI editor's safety check depends on it.
  Playstyle changes apply live (`//phantom playstyle`); bot clan/alliance changes require a restart.
- New Java functionality for the population/phantom system (e.g. new fields the l2admin panel exposes) has
  to be compiled into `GameServer.jar` upstream (outside this repo) before the corresponding config value
  does anything — that jar is prebuilt here, not compiled from this repo's sources.

## Community Board custom pages — gotchas

- `game/data/html/CommunityBoard/Custom/` pages are plain files read on demand; there's no manifest of
  "which pages are reachable." A page only shows up in-game if some other reachable page has a `bypass`
  button pointing at it — nothing enforces that the graph stays connected. This repo had a whole cluster
  of pages (`merchant/misc.html` + its `misc_*.html` category children, and their backing
  `multisell/custom/*.xml` files) that were fully built but never wired up from `main.html`, sitting
  as dead weight. When adding or changing a CB page, trace the actual bypass chain from `main.html`
  rather than assuming a page you can see on disk is actually reachable.
- Multi-column button grids (`<table><tr><td>...<button width=114>...</td>...</tr></table>`) size each
  column to the widest button placed in it across *all* rows. A later row with a single, wider button in
  one `<td>` (e.g. a 145px "Back" button under a grid of 114px buttons) gets visually clipped to the
  narrower column instead of erroring — the bypass still works, only the rendering is broken. Fix by
  either matching the button width to the grid, or giving that `<td>` a `colspan` spanning the full grid.
- The Community Board gatekeeper (`CommunityBoard/Custom/gatekeeper/main.html`, `_bbsteleport;<name>`) does
  **not** read `game/data/teleporters/others/50009.xml` — that file backs the separate NPC-dialog teleporter
  ("Fiorella"). The CB gatekeeper's actual destinations are the flat `CommunityTeleportList` in
  `game/config/Custom/CommunityBoard.ini` (`Name,X,Y,Z` entries), looked up by
  `HomeBoard.java`'s `_bbsteleport` handler. Adding a CB gatekeeper destination means editing *both* that
  ini list and the button in `main.html` — editing the teleporter XML does nothing for this button.
