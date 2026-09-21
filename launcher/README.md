# One-Click Launcher + Bundled Pack

Goal: a player starts the whole offline "Living World" server by double-clicking one file,
having **installed nothing** — no JDK, no XAMPP, no manual database setup.

There are two roles:

- **You (developer)** run `build-pack` once to produce `L2J-Offline-OneClick.zip`.
- **The player** unzips it on any Windows PC and double-clicks `Start-Server.bat`.

Nothing here changes game logic — it only orchestrates and bundles the pieces that already exist.

---

## A. Build the pack (you, once, on your dev/live PC)

Requirements on the build PC: **JDK 25** (a full JDK, not a JRE) and **Ant** — the same tools you
already use to build `GameServer.jar`. Then, from `dist\launcher\`:

```
build-pack.bat
```

or with options:

```
powershell -ExecutionPolicy Bypass -File build-pack.ps1 -Version v0.1.12 -MariaDbZip C:\downloads\mariadb-11.4.5-winx64.zip
```

A baseline `launcher\version.txt` is committed in the repo (currently `v0.1.14`) and ships in the pack, so
every build shows a real version in the Control Panel and compares correctly in the update checker.
Pass `-Version <tag>` to override it for a specific release; without it, the committed baseline is used.
**Per release, bump `dist\launcher\version.txt` (or pass `-Version`) to the tag you publish.**

What it does:

1. Builds the server jars with `ant` (or reuse an existing build with `-SkipBuild`).
2. Stages the full server (the ant zip is already a complete `dist\`).
3. **Bundles a full JDK 25** into `pack\jre\`. It must be a full JDK: the server compiles datapack
   scripts at runtime via the system Java compiler, which a plain JRE does not have.
4. **Bundles portable MariaDB** into `pack\mariadb\` (downloads it, or use `-MariaDbZip` for an
   offline copy).
5. Wires `launcher.ini` to the bundled JDK + MariaDB and points backup tooling at it.
6. Zips everything to **`L2J-Offline-OneClick.zip`** (in the project root by default).

Useful switches: `-JdkHome <path>` (which JDK to bundle), `-MariaDbVersion 11.4.5`,
`-MariaDbUrl <url>`, `-OutDir <path>`, `-SkipBuild`.

> The zip is large (~0.5 GB) because a JDK and a database engine are inside it. That's the price of
> "installs nothing."

## B. Run the pack (the player)

1. Unzip `L2J-Offline-OneClick.zip` anywhere.
2. Double-click **`Start-Server.bat`**.
3. In the game client, log in as **`admin` / `admin`** — accounts are auto-created on first login
   (`AutoCreateAccounts`), and every character on the `admin` account is automatically granted
   **master (GM) access** the moment it enters the world (hook in `EnterWorld.java`), so the player
   gets full admin commands (`//admin` etc.) without ever opening the database. Any other
   username/password works too and creates a normal player account.

On first run it initializes the bundled MariaDB, imports the schema into an empty database, then
launches the login and game servers. Later runs skip straight to launching. `Stop-Server.bat` shuts
the servers and the bundled DB down cleanly.

---

## Checking for updates from inside the pack (automatic)

The pack can update itself. Double-click **`scripts\Check-Updates.bat`** (or use the **Check for
updates** button in the launcher). It:

1. Reads `launcher\version.txt` (stamped at build time by `-Version`).
2. Asks the public repo `Teravibes/L2-Living-Worlds` for the newest release. Each version is published
   twice: `vX.Y.Z` (the full pack) and `vX.Y.Z-patch` (the overlay). The checker compares by version
   number, ignoring the `-patch` suffix.
3. If you are behind, it asks for confirmation, then **stops the server, downloads the `-patch` asset,
   overlays it on your install, and stamps the new version.** Your `mariadb\` database and any configs
   you customized are never touched (the patch zip does not contain them).

`update.ps1` is the single implementation behind both the `.bat` and the Control Panel button. It never
applies anything without asking first.

## Updating an existing install (patch zip, manual)

The automatic checker above downloads exactly this artifact. You can also apply it by hand. Alongside
the full `L2J-Offline-OneClick.zip`, the build produces a small **`L2J-Offline-Patch.zip`** containing
only the files that changed this release (`libs\GameServer.jar` and `launcher\version.txt` plus whatever
is listed in `patch-manifest.txt`). A tester who already has the pack installed can update **without
losing their database**:

1. `Stop-Server.bat`.
2. Unzip `L2J-Offline-Patch.zip` **over** the existing install folder, overwriting when asked.
3. `Start-Server.bat`.

The patch carries no `mariadb\` folder, so the player's database (characters, items, adena) is never
touched. It also deliberately excludes configs a player may have customized (e.g. `Rates.ini`) — new
config keys are called out in the release notes instead.

**Per release:** edit `dist/launcher/patch-manifest.txt` — clear last release's entries and list the
datapack/config/html files that changed this time (one path per line, relative to the pack root; the
jar is auto-included). The build **fails** if a listed file isn't in the pack, so a typo can't ship a
broken patch. No manifest → the patch step is skipped and only the full pack is produced.

---

## What the launcher does, in order

1. **Pre-flight** — one screen listing anything missing (Java / DB engine / jars) before it starts.
2. **Java** — bundled `dist\jre\` → `JAVA_HOME` → `PATH`.
3. **Database** —
   - *Bundled MariaDB* (`DataDir` set in `launcher.ini`): initializes the data dir on first run
     (root, no password), then starts it with that data dir.
   - *External MySQL/XAMPP* (`DataDir` blank): uses a running instance, or auto-starts `mysqld`
     from `MysqlBin`.
4. **Schema — only when the database is empty.** Imports all `db_installer/sql/{login,game}` tables
   through the `mysql` client, then writes a `.db_installed` marker.
   **Safe on an existing server:** it first counts tables in the target DB and *skips the import if
   any exist* — because ~14 SQL files `DROP TABLE` before recreating (e.g. `accounts`), importing
   over a live DB would wipe those. Auto-skip means running on a set-up machine never touches data.
5. **Login server, then Game server** — each in its own console window, using each `java.cfg`.
6. Optional **Python brain** (off by default). When `StartBrain=true`, the launcher calls
   `setup_brain.bat --auto`, which starts the brain **only if it has already been configured**
   (an `.env` + `.venv` exist) and otherwise skips without prompting, so a normal boot never hangs.
7. Optional **game client** (off by default). When `LaunchClient=true`, the launcher waits for the game
   port, then opens `ClientExe`. Stop-Server leaves the client open.

## Optional AI brain (in-character bot chat)

The bots can hold in-character chat through a small local Flask service, the "brain". It is **optional
and off by default** — the server and all the bots work fully without it; this only adds the talking.

- **Set it up once:** double-click **`scripts\Configure-Brain.bat`** (or the **Set up / configure brain**
  button in the launcher). It installs Python if needed and lets you choose **Ollama** (free, local, offline,
  needs a decent GPU/CPU) or **DeepSeek** (cloud API, needs a key). It writes `.env`, builds the
  virtualenv, and starts the brain on `http://127.0.0.1:5000`.
- **After that, it starts straight in:** once set up, the same button/`scripts\Configure-Brain.bat` skips the
  provider question and just launches the brain. To **switch provider**, run `setup_brain.bat --reset`
  (wipes `.env` and asks fresh).
- **Start it automatically with the server:** set `StartBrain=true` in `launcher\launcher.ini`, or tick
  **Start the FPC brain with the server** in the Control Panel. On boot the launcher launches the brain
  non-interactively — but only after you have configured it once with the step above.

## Launch the game client too (one double-click for everything)

The launcher can open your L2 client once the server is up, so a single start brings up the whole thing:

- **Point it at your client:** in the Control Panel, click **Set game client...** and pick the exe you
  normally run (usually `system\L2.exe`, or your own patcher/bootstrapper exe). That saves the path to
  `launcher\launcher.ini` (`[client] ClientExe=`) and ticks **Launch the game client with the server**.
  You can also set `ClientExe=` and `LaunchClient=true` by hand in the ini.
- **On start:** the launcher waits for the game server to bind its port, then opens the client from its
  own folder. **Stop-Server leaves the client open** (it just disconnects) — stopping only shuts down the
  server and database.
- **Note:** the launcher only *opens* the client. Making the client *connect* to your server is the
  client's own one-time setup (its server-list address in `l2.ini`), not something the launcher changes.

## Testing without the full pack (external DB path)

You can also run the launcher against your own MySQL/XAMPP without building a pack: copy `launcher\`,
`Start-Server.bat`, `Stop-Server.bat` into a `dist\` that has the built jars, leave `DataDir` blank,
set `MysqlBin`/credentials in `launcher.ini`, and run. This is the quick smoke test; the bundled pack
is the shippable end-user experience.

To reinstall the schema from scratch (empty DB only): delete `launcher\.db_installed`, drop the DB, run again.

## Known caveats / next

- The MariaDB **first-run init** is the least-tested step across MariaDB versions; the pack pins a
  version so behavior is known. If init ever misbehaves, check the DB console window.
- A real `.exe` wrapper for `Start-Server.bat` (icon, no console flash) via Launch4j is a nice polish
  step but not required — the `.bat` already gives the one-click experience.
- Linux/macOS `.sh` equivalents are a straightforward port if ever needed.
