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
  `runtime-linux/` (gitignored) and track PIDs in `launcher/.linux-pids/`. That directory's `.pid` files
  were committed before the gitignore rule for it existed, so they kept reappearing as modified on every
  `git status` despite being ignored — `.gitignore` doesn't retroactively untrack already-tracked files.
  Fixed once via `git rm --cached`; if a similar "ignored but still showing up" file appears elsewhere,
  check `git ls-files` for it rather than assuming the ignore rule is broken.
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
- `game/data/multisell/custom/` had a second, larger cluster of orphans beyond the misc-pages one above:
  a whole earlier draft of the merchant (`600010`/`600012`/`600014`/`600016`, plus `62000`-`62105`,
  `62208`-`62211`, `62220`-`62221`, `62300`-`62303`) that was rebuilt into the current `a/b/c/s.html` +
  `61xxx`/`62500`/`62501`/`62502` system and never deleted — none referenced from any `.html` in the repo.
  A single "does this page link here" pass isn't enough to find all dead multisell data; cross-reference
  every id actually used by `_bbsmultisell;<id>` across all of `merchant/*.html` against every file in
  `multisell/custom/` to catch drafts like this that don't hang off any specific known-orphan page.
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
- When adding a high-level farming destination to the gatekeeper, don't invent coordinates: this repo's
  `game/data/teleporters/town/*.xml` and `chamberlain/*.xml` files already contain the game's own
  canonical "teleport crystal" landing points for every dungeon (including sub-sections like
  "1st/2nd Level", "Top/Lower Level", "The Heart of ..."), which are guaranteed to be clear of aggro range
  by design — reuse those `x,y,z` values instead of picking a spot near a spawn cluster. Cross-check which
  sub-section actually holds the highest-level mobs via `game/data/spawns/**/*.xml` (mob `id` → level via
  `game/data/stats/npcs/*.xml`) before picking one, since "deepest teleport option" and "highest-level mob
  cluster" aren't always the same coordinate. Current farming buttons and their source: Monastery of
  Silence `106414,-87799,-2920` (`teleporters/town/31320.xml`); Imperial Tomb `186699,-75915,-2826`
  (`teleporters/town/31275.xml`); Antharas' Lair "Heart of Antharas' Lair" `154396,121235,-3808`
  (`teleporters/town/30080.xml`, verified adjacent to the lvl 75 Bloody Lord/Bloody Guardian cluster in
  `spawns/Giran/AntharasLair.xml`, not the lvl 60-65 mobs near the entrance); "Flame Core" (player-facing
  name for the Lavasaurus/Elder Lavasaurus zone) is Forge of the Gods' "Lower Level" `180260,-111913,-5851`
  (`teleporters/town/31275.xml`), not its "Top Level" — Lavasaurus (`id 21394`, lvl 79) and Elder Lavasaurus
  (`id 21395`, lvl 80) spawn in `spawns/Goddard/ForgeOfGods.xml` territories matching the Lower Level's
  z-range (~-5565 to -6244), while Top Level is a separate, lower-level section of the same dungeon.
- The `merchant/*.html` grade shops (A/B/C/S) price each multisell entry at exactly the item's own
  `price` attribute in `game/data/stats/items/*.xml` (1:1, no markup) — e.g. Apella Plate Armor's
  `price="7524400"` is the same `7524400` used as the adena ingredient count in `61000.xml`. When adding
  new items to these shops, pull the price from the item's own stat block rather than inventing one.
- Not every item with `crystal_type="S"` (or any grade) belongs in these shops: many are "unidentified"
  drop-form placeholders — generically named ("Dragon Scale Mail", "The Robe", "Gaz Blade") and flagged
  `is_tradable="false"` + `is_sellable="false"` in their item stats — that exist only as the raw drop
  before an in-game identify step; the real sellable item is a separate id with the set's real name
  (e.g. "Imperial Crusader Breastplate", not "Dragon Scale Mail"). Check both the name pattern and those
  two flags before adding an item id to a shop list.
- The `merchant/main.html` "Sell" button (`_bbssell;<page>`, handled in `HomeBoard.java`) doesn't do any
  selling itself — it just sends the client's generic `SellList` packet, which opens the native client sell
  dialog over whatever inventory the player has. There's no server-side concept of "what this shop buys
  back" for it; the client's `RequestSellItem` (closed, in `GameServer.jar`) accepts anything with
  `Item.isSellable() == true` at `getReferencePrice() / 2` per unit (verified by decompiling
  `RequestSellItem`/`Player` with `javap -p -c` — this repo has no JDK 25, only JDK 17, so reading these
  jars' class files (major version 69) requires either a JDK 25 `javac`/`javap`, or just trust the same
  price formula documented here). Any custom "sell all X" feature has to replicate that formula itself
  (`Player.destroyItem(ItemProcessType, objectId, count, WorldObject, boolean)` +
  `Player.addAdena(ItemProcessType, count, WorldObject, boolean)`) rather than reuse anything from the
  generic Sell flow.
- Crafting materials and recipes are identifiable purely from item stats: `etcitem_type="MATERIAL"` /
  `etcitem_type="RECIPE"` in `game/data/stats/items/*.xml`, exposed at runtime as
  `((EtcItem) item.getTemplate()).getItemType() == EtcItemType.MATERIAL` (or `.RECIPE`). This is what backs
  the CB merchant's "Sell Materials/Recipes" button: `_bbscraftsellask` (dynamic confirmation page, built by
  `HomeBoard.getSellableCraftItems()`, listing each matching item/qty/adena value + a total, via
  `merchant/sellcraft_ask.html`) and `_bbscraftsell` (the actual destroy + `addAdena`, gated behind the same
  confirmation step — no undo). Named `_bbscraftsell*` rather than `_bbssell*` specifically so it doesn't
  collide with the existing `command.startsWith("_bbssell")` branch in `HomeBoard.onCommand`.

## Subclass eligibility restrictions (hardcoded in the closed engine)

- `Player.ini`'s `MaxSubclass`, `BaseSubclassLevel`, `MaxSubclassLevel`, `AltSubClassWithoutQuests`, and
  `AltSubclassEverywhere` do **not** cover the full eligibility check for "Add subclass" — there's an
  additional, config-invisible restriction compiled into `libs/GameServer.jar`'s
  `org.l2jmobius.gameserver.model.actor.instance.VillageMaster` (no source in this repo; found by
  decompiling the class with `javap`, since the engine source isn't shipped here). It has two parts:
  1. **Subclass "family" grouping** (`subclassSetMap`, built in `VillageMaster.<clinit>` from five
     `subclasseSet1`-`subclasseSet5` `EnumSet`s of `PlayerClass`): analogous classes across the three base
     races are mutually exclusive as subclasses — e.g. Set5 = `{SORCERER, SPELLSINGER, SPELLHOWLER}` (and
     by extension Spellhowler's 3rd-class rename, Storm Screamer). If your main or any existing subclass
     resolves into one of these classes, none of the others in its set can ever be added as a subclass,
     and vice versa. Same pattern for knight types (Set1), rogue types (Set2), archer types (Set3), and
     summoner types (Set4).
  2. **Overlord/Warsmith are permanently unselectable** as a subclass (`neverSubclassed`), removed from the
     candidate pool (`mainSubclassSet`) at class-init time — unrelated to and not fixed by
     `AltSubClassWithoutQuests`/`AltSubclassEverywhere`.
  3. Separately (not data-driven, inline logic in `VillageMaster#getSubclasses`): Elf and Dark Elf classes
     are mutually exclusive as subclasses of each other, regardless of the above sets.
  The in-game failure message (`game/data/html/villagemaster/SubClass_Fail.htm`) is a single generic
  "you aren't eligible" page mentioning Mimir's Elixir/quest items — it's static boilerplate shown for
  *any* failed eligibility check, not just missing quest items, so it does not indicate the real cause.
- To lift restriction #1/#2 without touching the closed jar: `game/data/scripts/custom/SubclassUnlock/
  SubclassUnlock.java` reflectively clears `VillageMaster.subclassSetMap` and re-adds
  `neverSubclassed` (Overlord/Warsmith) into `mainSubclassSet` at server boot (datapack scripts run as
  plain classpath code against an unnamed module, so `setAccessible(true)` on those private static fields
  needs no `--add-opens`). Restriction #3 (Elf/Dark-Elf mutual ban) is inline bytecode, not a data field,
  so this reflection approach can't remove it — that would require actually binary-patching the compiled
  method in `GameServer.jar`.

## Death handling and custom skill effects

- Datapack effect classes under `game/data/scripts/handlers/skill/effects/*.java` get their `onExit()`
  called from inside the *engine's* `Player.doDie()` (via `stopEffects()`/`finishEffects()`) whenever a
  dying player has that effect active — not just on natural buff expiry. If `onExit()` throws, it aborts
  whatever `doDie()` still had left to do (broadcasting the death animation, sending the "return to
  village" popup), even though the player's dead-flag/HP were already committed a few lines earlier. The
  visible symptom is exactly that split: the player is dead server-side but the client shows nothing until
  a relog resyncs it. `ResurrectionSpecial.java` hit this because `effector.asPlayer()` can legitimately
  return `null` (original caster not resolvable as a Player anymore), which then NPEs deep inside the
  engine's `reviveRequest()`. Any effect's `onExit()`/`onStart()` must treat `effector` as possibly null
  or non-Player — a crash there is a death-sequence bug, not just a lost buff.
