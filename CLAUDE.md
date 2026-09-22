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
  in-character chat (whisper/say/trade/shout), backed by a local Ollama model or one of five OpenAI-
  compatible cloud APIs (DeepSeek, OpenAI, Groq, OpenRouter, Mistral — pick via `setup_brain.sh`/
  `setup_brain.bat`'s provider menu, or `--reconfigure` to switch later without losing saved keys). Off by
  default; enabled via `StartBrain=true` in `launcher.ini`. Persists lightweight per-player memory to
  `brain/memory/fpc_memory.json`; per-channel conversation context (`conversations`, `say_logs`,
  `trade_log`, `shout_log`) is in-process and TTL-pruned, not persisted.
  **Gotcha**: upstream's `v0.1.21-patch` added this multi-provider menu (plus `--reconfigure`/`--reset`) to
  `setup_brain.bat` (Windows) but never ported the same logic to `setup_brain.sh` (Linux/Steam Deck) — that
  patch's `.sh` was still the old Ollama-only script with no `--reconfigure` flag at all, silently ignored
  and falling through to the Ollama path regardless of what you asked for. Ported the `.bat`'s menu/model-
  suggestion/key-reuse logic into `setup_brain.sh` by hand (commit after `5308e20b`) so both platforms have
  the same provider set now. If a future upstream patch touches `setup_brain.bat` again, check whether
  `setup_brain.sh` needs the same change manually — they are not kept in sync upstream.
  **Also**: `brain/.env` (where every provider's API key actually lives) was tracked in git since the
  initial commit — only placeholder/empty values were ever in it here, but it was one careless `commit -a`
  away from leaking a real key. Untracked and gitignored as of `feb63964`; if your own clone already
  committed a real key into it before pulling that fix, check your git history and rotate the key.
  Provider model IDs drift over time (Groq retired `llama-3.3-70b-versatile`/`llama-3.1-8b-instant` at some
  point after this repo's setup scripts were written, replaced by `openai/gpt-oss-120b` as the flagship
  model) — if a configured provider starts 404ing with "model does not exist", don't trust any hardcoded
  suggestion (including the ones in `setup_brain.sh`/`.bat`/`fpc_brain.py`); query
  `GET https://api.groq.com/openai/v1/models` (or the equivalent for whichever provider) with the real key
  to get the live, authoritative list.
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

## Applying upstream engine updates (`libs/GameServer.jar`)

- Updates ship as "-patch" GitHub releases at `Teravibes/L2-Living-Worlds` (`gh release list --repo
  Teravibes/L2-Living-Worlds`), e.g. `v0.1.21-patch`. Each release's `L2J-Offline-Patch.zip` is meant to be
  dropped straight over an existing install (see its own `PATCH-README.txt`); `launcher/version.txt` here
  tracks which one we're on.
- `libs/GameServer.jar` inside each patch is a **fresh full rebuild**, not a binary/incremental diff — so
  it's always cumulative. You do **not** need to apply every intermediate `-patch` release in sequence; the
  latest one's jar already contains every earlier engine-side fix. Datapack/script/asset files bundled
  alongside it, by contrast, are only as fresh as whatever that specific release actually touched — before
  trusting a "just take the latest patch" shortcut for those, diff the target release's zip file list
  against one or two versions back (`comm -23`/`comm -13` on sorted `unzip -l` name lists) to confirm no
  in-between file was dropped from the bundle.
- Before overwriting the jar, decompile-diff (`javap -p` on both, via `unzip <jar> 'org/l2jmobius/...'`)
  any class whose signature a datapack script calls, and grep the new jar's class list
  (`unzip -l old.jar | sort > a; unzip -l new.jar | sort > b; comm -13/-23 a b`) for anything removed —
  method **removals** (not just additions) are the real compatibility risk, since scripts compile against
  whatever's in the jar at boot. Example: the `v0.1.21-patch` jar replaced (not overloaded)
  `ConfigLoader.init()` with `ConfigLoader.init(String)`, coupled with a new
  `GameServerLaunchArgumentsParser` (reads system property `gameConfigPath`, defaults to `"config"` — same
  behavior as before when unset, so no launcher change needed); the jar and
  `game/data/scripts/handlers/chat/commands/admin/AdminReload.java` (the only caller of that method) must
  be updated together or `.reload config` fails to compile.
- That same release introduced a new, currently-**inert** plugin framework:
  `org.l2jmobius.gameserver.modules.*` (`ModuleManager`, `ModuleManifestReader`, `ModuleValidator`,
  `ModuleContext`, etc.), gated by `./config/Modules.ini` (`EnableModules`, `ModulesRoot` — defaults to a
  `modules` subfolder under the gameserver's cwd, i.e. `game/modules/`). That ini file doesn't exist in this
  repo, so the framework stays fully off — updating the jar alone changes nothing. `game/modules/` had to be
  un-ignored (`!game/modules/` added after the generic bare `modules` rule in `.gitignore`, which predates
  this feature and is a leftover from the engine's own Ant build tooling, not related to it) to keep the two
  bundled reference modules (`hello-world`, `custom-item`) tracked. Each module is a self-contained
  directory with a `module.json` manifest (`entrypoint`, optional `reserves`/`resources` for item-id ranges
  and datapack roots it owns) and a `GameModule` Java entry point taking a `ModuleContext`
  (`.config()`/`.handlers()`/`.events()`/`.logging()`) — `custom-item`'s own doc comment calls its demo item
  "the Living World Token", i.e. this is clearly meant as the future home for this project's custom
  features instead of hand-editing `game/data/scripts/custom/*`. Not adopted for anything yet; the two
  bundled modules are kept only as reference/documentation of the extension point, both disabled by default
  (each needs its own `config/module.ini` with `Enabled = True`, on top of the global `EnableModules`
  switch).

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
  narrower column instead of erroring — the bypass still works, only the rendering is broken: the overflow
  portion of the button texture renders as a detached, blank bordered fragment next to the visible part.
  **`colspan` on that `<td>` alone does not fix this** in the actual client (verified via screenshot on
  `merchant/{a,b,c,s}.html`'s "Back to Merchant" button, which already had `colspan="3"` from an earlier fix
  attempt — commit `0996ecc7` — and was still visibly broken). The only fix that actually works is matching
  the button's own `width` to the grid's column width (114 here), even if that means the label looks tight;
  keeping `colspan` alongside it is harmless but not load-bearing.
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
- The CB merchant's "Sell Junk" button (`_bbscraftsellask` → dynamic confirmation page listing item/qty/adena
  value + total via `merchant/sellcraft_ask.html`, then `_bbscraftsell` for the actual destroy + `addAdena`,
  gated behind that confirmation step — no undo) sells everything `isSellable()` in the player's inventory
  **except** items also purchasable from this same merchant's own multisells (grade shops `61000`-`61055`,
  scrolls `62501`, misc items `62500`, pets `62502`, hair accessories `62503`, quest/clan `600024` —
  `HomeBoard.getSellableJunkItems()`/`getMerchantCatalogItemIds()`). `MultisellData` (closed, in
  `GameServer.jar`) exposes no public "get list by id" lookup, only `separateAndSend()` (fire-and-forget) —
  so the merchant's catalog item ids are read via reflection into its private `_entries` map, the same
  pattern `custom/SubclassUnlock/SubclassUnlock.java` uses for `VillageMaster`'s private fields. Named
  `_bbscraftsell*` rather than `_bbssell*` specifically so it doesn't collide with the existing
  `command.startsWith("_bbssell")` branch in `HomeBoard.onCommand`.
- Community Board HTML has a **hard 12270-character cap**, enforced client-side in the closed
  `HtmlUtil.sendCBHtml` (decompiled via `javap -p -c`): it splits the page across up to three `ShowBoard`
  packets of ≤4090 chars each, and if the full HTML is ≥12270 chars it discards it entirely and shows
  `Error: HTML was too long!` instead — there's no partial rendering or truncation, the whole page just
  fails. The "Sell Junk" confirmation list hit this once real (cluttered) inventories were used, since it no
  longer filters by item type. Fixed by grouping rows by item id (`HomeBoard.SellSummary`, merging duplicate
  stacks into one row with summed qty/adena) and hard-capping the rendered list to
  `MAX_SELLCRAFT_LIST_ROWS` (40) with a "+N more item types" summary row for the rest — the adena total
  shown/paid is always computed from the full unfiltered list, only the *display* is capped. Any other
  dynamically-built CB page that lists per-inventory-item or per-row data (not just this one) needs the same
  cap-and-summarize treatment if the row count isn't bounded by design.

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

## Fake player combat AI gaps (closed engine)

- Peace-zone (`ZoneId.PEACE`/`NO_PVP`) protection in the closed `AttackableAI` is only checked at the
  moment a fight *starts* — `isAggressiveTowards()` (generic proactive-aggro decision) and
  `lambda$thinkActive$0` (the fake-player-specific target-pick, the **only** place
  `FakePlayersConfig.FAKE_PLAYER_AGGRO_PLAYERS` is read anywhere in the engine — confirmed via
  `grep -rla FAKE_PLAYER_AGGRO_PLAYERS` across every decompiled class) both check it. `thinkAttack()` — the
  ~1200-line method that drives every attack tick once hate already exists — has **zero** zone checks
  anywhere in it. So once a fake player starts hating a player (e.g. with `FakePlayerAggroPlayers = True`,
  or simply because the fight started outside town and either side then walked into a peace zone), nothing
  in the AI stops it from continuing to fight there. `game/data/scripts/custom/FakePlayers/
  PeaceZoneCombatStopTask.java` is a datapack-side workaround for this: a 3-second sweep
  (`ThreadPool.scheduleAtFixedRate`) over every fake player currently `isInCombat()`, force-disengaging
  (`abortAttack`/`abortCast`/`clearAggroList`/`setIntention(ACTIVE)`) any whose own zone or current
  target's zone is `PEACE`. It's a mitigation, not a real fix — the actual bug is in the closed
  `AttackableAI.class` and would need either an upstream engine patch or binary-patching the compiled
  method (same class of problem as the subclass restrictions above, but a method body edit rather than a
  static field, so not something to attempt via reflection).
- **"Fake player" is not one system — there are two entirely different closed-engine implementations**,
  and this matters a lot for any combat-AI investigation:
  1. Ambient/vending population (`EnableFakePlayers`/`FakePlayerBehavior` in `FakePlayers.ini`, driven by
     `FakePlayerBehaviorManager`) — these are real `Npc`/`Attackable` instances. `FAKE_PLAYER_AGGRO_PLAYERS`
     etc. are read only by `AttackableAI` (confirmed via `grep -rla FAKE_PLAYER_AGGRO_PLAYERS` across every
     decompiled class), so they only ever affect this system.
  2. Phantoms — both recruited buddies (`!lf`, `PhantomManager#spawnPartyMember`/`spawnFriendRegular`) *and*
     auto-hunt field hunters (`PhantomManager#spawnPhantom`, `PhantomAutoHuntingZones`) — are actual
     **`Player`** instances (`PhantomPartyManager$Member.npc` is declared as `Player`, not `Npc`), fully
     puppeteered every tick by `PhantomPartyManager`. `FakePlayerAggroPlayers` has **zero** effect on these;
     don't waste time toggling it while testing phantom retaliation.
  Any fix or investigation into "why doesn't a fake player fight back" has to know which of these two it's
  actually looking at, or it'll trace the wrong class entirely (early sessions on this burned a lot of time
  doing exactly that).
- **Peace-zone gap (system 1, Npc-based):** the closed `AttackableAI` only checks `ZoneId.PEACE`/`NO_PVP` at
  the moment a fight *starts* (`isAggressiveTowards()`, and `lambda$thinkActive$0` — the only place
  `FAKE_PLAYER_AGGRO_PLAYERS` is read). `thinkAttack()` — the ~1200-line method driving every attack tick
  once hate already exists — has zero zone checks anywhere, so a fight that starts outside town (or is
  enabled via `FakePlayerAggroPlayers`) keeps going if either side crosses into a peace zone. Fixed with
  `game/data/scripts/custom/FakePlayers/PeaceZoneCombatStopTask.java`: a 3-second sweep
  (`ThreadPool.scheduleAtFixedRate`) over every `isInCombat()` fake player, force-disengaging
  (`abortAttack`/`abortCast`/`clearAggroList`/`setIntention(ACTIVE)`) any whose own zone or current target's
  zone is `PEACE`. A datapack mitigation, not a real fix — the actual bug is a method body in the closed
  `AttackableAI.class`, not something reflection can patch (unlike the subclass restrictions above, which
  are a static field).
- **No retaliation at all against a player attacker (both systems):** for system 1 (Npc-based),
  `Attackable.addDamageHate()` looks structurally correct and `FakePlayerBehaviorManager` defers to
  ongoing combat instead of fighting the AI for control, so on paper it should "just work" — it wasn't
  confirmed broken, just never observed working either. For system 2 (Player-based phantoms/hunters), the
  answer is structural, not a bug: a `Player` has no aggro list at all (`Attackable`-only concept), and
  `PlayerAI` has no `onActionAttacked` override (inherits `CreatureAI`'s trivial
  `clientStartAutoAttack()`-only version) — there is no mechanism for a real-player-shaped bot to decide to
  fight back on its own. `PhantomPartyManager`'s own tick only reads *Player/Monster* combat state to drive
  its own hunting/follow logic, never to react to "something is attacking me" (confirmed via
  `//phantom debug on` / `//debug_on`, which logs to the gameserver **console** — not `game/log/` — and
  stays completely silent for a player attacker). Fixed uniformly for both systems by
  `game/data/scripts/custom/FakePlayers/FakePlayerPvpRetaliateTask.java`: listens globally
  (`Containers.Global()`) for `EventType.ON_CREATURE_DAMAGE_RECEIVED` (fires for any `Creature`, Player or
  Npc, unlike the Attackable-only events). **Gotcha that cost a full debugging round-trip**: the first
  version filtered targets with `Creature.isFakePlayer()` alone, which is only ever set by the Npc-based
  system (`Npc`/`FakePlayerBehaviorManager`) — Player-typed phantoms/recruits/buddies/regulars only set a
  *private* `Player.isBuddyBot` field with no public getter, so `isFakePlayer()` is always false for them
  and the listener silently never matched a single hit. Detecting them needs
  `PhantomManager.getInstance().isPhantom(player)`/`isRecruit(player)`/`isBuddy(player)`/`isRegular(player)`
  instead (all public). If a fake-player script needs to check "is this Creature bot-controlled" again,
  check both — `isFakePlayer()` for Npc-based, the four `PhantomManager` methods for Player-based — never
  just one. Remembers the last player to hit each fake player for 8 seconds,
  and every 200ms both sets `Intention.ATTACK` against that player and calls `Creature.doAttack()` directly
  — always overriding whatever the fake player (or `PhantomPartyManager`, for phantoms) was otherwise having
  it do, since a player hitting it is meant to take priority. Live-tested via `//phantom debug on` and the
  startup/first-hit log lines below: `setIntention` alone only landed **one** hit before
  `PhantomPartyManager`'s own 1-second tick (confirmed via its `startTicking()` bytecode:
  `ThreadPool.scheduleAtFixedRate(..., 1000, 1000)`) reclaimed the target, then the phantom went straight
  back to its monster with no further reaction — `setIntention` is apparently a no-op while the AI is mid-
  action from PhantomPartyManager's own commands (casting, moving), so it only "got through" on the one
  lucky window it wasn't busy. Calling `doAttack()` directly forces the swing regardless of that busy state;
  200ms (vs. the original 400ms) buys more chances per PhantomPartyManager cycle. This still isn't a clean
  fix — it's out-racing a 1-second tick from outside the closed class that owns it — so don't be surprised
  if it still stutters under heavier PhantomPartyManager activity; if `//phantom debug on` needs revisiting
  after future changes here, that trace is *mob-combat-only* and will show nothing for a player attacker
  either way (see above). Skips (and won't start) any of this in a peace zone; pairs with
  `PeaceZoneCombatStopTask` rather than duplicating its job. If a future engine update actually implements
  native retaliation for either system, this task will just keep re-confirming the same already-correct
  intention every cycle — harmless, but worth removing at that point.
- Both `PeaceZoneCombatStopTask` and `FakePlayerPvpRetaliateTask` log an `INFO` line on successful startup
  (`"...: started, ..."`), and the latter also logs once per new retaliation episode
  (`"...: <target> hit by <attacker>, forcing retaliation."`, not per-tick, so it won't spam). This was
  added *after* a debugging session where a fresh `java0.log` from live testing showed zero errors but also
  gave no way to tell whether either script had loaded at all — neither logged anything on success at the
  time, so "no errors" and "not working" looked identical. When something in this pair seems not to be
  firing, check for these lines first before re-deriving the mechanism from bytecode again.

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
