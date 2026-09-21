#!/usr/bin/env bash
set -u

BASE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DIST_DIR="$(cd "$BASE_DIR/.." && pwd)"
INI_PATH="$BASE_DIR/launcher.ini"
PID_DIR="$BASE_DIR/.linux-pids"
DB_MARKER="$BASE_DIR/.db_installed"
LOG_DIR="$BASE_DIR/logs-linux"
RUNTIME_DIR="$DIST_DIR/runtime-linux"
JDK_DIR="$RUNTIME_DIR/jdk25"
MARIADB_DIR="$RUNTIME_DIR/mariadb"
DOWNLOAD_DIR="$RUNTIME_DIR/downloads"

JAVA_VERSION=25
MARIADB_VERSION="11.4.5"
JDK_URL="https://api.adoptium.net/v3/binary/latest/25/ga/linux/x64/jdk/hotspot/normal/eclipse"
MARIADB_URL="https://archive.mariadb.org/mariadb-${MARIADB_VERSION}/bintar-linux-systemd-x86_64/mariadb-${MARIADB_VERSION}-linux-systemd-x86_64.tar.gz"

mkdir -p "$PID_DIR" "$LOG_DIR" "$RUNTIME_DIR" "$DOWNLOAD_DIR"

ok()   { printf '  \033[1;32m[ OK ] %s\033[0m\n' "$*"; }
info() { printf '  \033[0;37m[info] %s\033[0m\n' "$*"; }
warn() { printf '  \033[1;33m[WARN] %s\033[0m\n' "$*"; }
fail() { printf '  \033[1;31m[FAIL] %s\033[0m\n' "$*"; exit 1; }

require_bootstrap_tools() {
    local missing=()
    for tool in curl tar awk find sort sed; do
        command -v "$tool" >/dev/null 2>&1 || missing+=("$tool")
    done
    if (( ${#missing[@]} > 0 )); then
        fail "SteamOS is missing required base tools: ${missing[*]}"
    fi
}

get_ini_val() {
    local section="$1" key="$2" default="$3"
    if [[ -f "$INI_PATH" ]]; then
        local val
        val=$(awk -F '=' -v section="[$section]" -v key="$key" '
            $0 == section { in_sec=1; next }
            /^\[/ { in_sec=0 }
            in_sec {
                lhs=$1
                gsub(/^[ \t]+|[ \t]+$/, "", lhs)
                if (lhs == key) {
                    $1=""
                    sub(/^=/, "", $0)
                    gsub(/^[ \t]+|[ \t]+$/, "", $0)
                    print $0
                }
            }
        ' "$INI_PATH" | tail -n 1)
        if [[ -n "$val" ]]; then
            printf '%s\n' "$val"
            return
        fi
    fi
    printf '%s\n' "$default"
}

is_true() {
    case "${1,,}" in
        true|1|yes|on) return 0 ;;
        *) return 1 ;;
    esac
}

test_port() {
    local host="$1" port="$2"
    (echo > "/dev/tcp/$host/$port") >/dev/null 2>&1
}

record_pid() {
    local role="$1" pid="$2" marker="$3"
    printf '%s\n%s\n' "$pid" "$marker" > "$PID_DIR/$role.pid"
}

pid_is_live() {
    local pid="$1"
    kill -0 "$pid" >/dev/null 2>&1
}

java_major_version() {
    local java_bin="$1"
    "$java_bin" -version 2>&1 | awk -F'[".]' '/version/ { print $2; exit }'
}

download_file() {
    local url="$1" output="$2" label="$3"
    info "Downloading $label..."
    rm -f "$output.part"
    if ! curl -fL --retry 3 --retry-delay 2 --connect-timeout 20 --progress-bar "$url" -o "$output.part"; then
        rm -f "$output.part"
        fail "Could not download $label from $url"
    fi
    mv "$output.part" "$output"
}

install_local_jdk() {
    local java_bin="$JDK_DIR/bin/java"
    local javac_bin="$JDK_DIR/bin/javac"

    if [[ -x "$java_bin" && -x "$javac_bin" ]]; then
        local major
        major="$(java_major_version "$java_bin")"
        if [[ "$major" =~ ^[0-9]+$ ]] && (( major >= JAVA_VERSION )); then
            return 0
        fi
        warn "Existing local JDK is not Java $JAVA_VERSION+. Replacing it."
        rm -rf "$JDK_DIR"
    fi

    local archive="$DOWNLOAD_DIR/temurin-jdk25-linux-x64.tar.gz"
    local staging="$RUNTIME_DIR/.jdk25-extract"
    download_file "$JDK_URL" "$archive" "Eclipse Temurin JDK 25 for Linux x64"

    info "Extracting local JDK 25..."
    rm -rf "$staging" "$JDK_DIR"
    mkdir -p "$staging" "$JDK_DIR"
    if ! tar -xzf "$archive" -C "$JDK_DIR" --strip-components=1; then
        rm -rf "$JDK_DIR" "$staging"
        fail "JDK extraction failed: $archive"
    fi
    rm -rf "$staging"

    [[ -x "$java_bin" && -x "$javac_bin" ]] || fail "Downloaded JDK is incomplete; bin/java or bin/javac is missing."
    rm -f "$archive"
    ok "Portable JDK 25 installed under runtime-linux/jdk25."
}

install_local_mariadb() {
    local server_bin="$MARIADB_DIR/bin/mariadbd"
    local client_bin="$MARIADB_DIR/bin/mariadb"
    local install_bin="$MARIADB_DIR/scripts/mariadb-install-db"

    if [[ -x "$server_bin" && -x "$client_bin" && -f "$install_bin" ]]; then
        return 0
    fi

    local archive="$DOWNLOAD_DIR/mariadb-${MARIADB_VERSION}-linux-x86_64.tar.gz"
    download_file "$MARIADB_URL" "$archive" "MariaDB $MARIADB_VERSION for Linux x64"

    info "Extracting portable MariaDB..."
    rm -rf "$MARIADB_DIR"
    mkdir -p "$MARIADB_DIR"
    if ! tar -xzf "$archive" -C "$MARIADB_DIR" --strip-components=1; then
        rm -rf "$MARIADB_DIR"
        fail "MariaDB extraction failed: $archive"
    fi

    [[ -x "$server_bin" ]] || fail "Downloaded MariaDB is incomplete; bin/mariadbd is missing."
    [[ -x "$client_bin" ]] || fail "Downloaded MariaDB is incomplete; bin/mariadb is missing."
    [[ -f "$install_bin" ]] || fail "Downloaded MariaDB is incomplete; scripts/mariadb-install-db is missing."
    chmod +x "$install_bin" 2>/dev/null || true
    rm -f "$archive"
    ok "Portable MariaDB installed under runtime-linux/mariadb."
}

printf '\033[1;35m########################################################\033[0m\n'
printf '\033[1;35m#   L2 Offline Living World - Steam Deck Launcher      #\033[0m\n'
printf '\033[1;35m########################################################\033[0m\n\n'

case "$(uname -s)" in
    Linux) ;;
    *) fail "This launcher is for Linux/SteamOS." ;;
esac
case "$(uname -m)" in
    x86_64|amd64) ;;
    *) fail "This bootstrap currently supports x86_64 Linux only. Detected: $(uname -m)" ;;
esac
require_bootstrap_tools

printf '\033[1;36m==== 1/6 Portable runtime ====\033[0m\n'
install_local_jdk
install_local_mariadb

JAVA_BIN="$JDK_DIR/bin/java"
JAVAC_BIN="$JDK_DIR/bin/javac"
JAVA_MAJOR="$(java_major_version "$JAVA_BIN")"
[[ "$JAVA_MAJOR" =~ ^[0-9]+$ ]] || fail "Could not determine bundled Java version."
(( JAVA_MAJOR >= JAVA_VERSION )) || fail "Bundled Java is $JAVA_MAJOR; Java $JAVA_VERSION+ is required."
ok "Java $JAVA_MAJOR: $JAVA_BIN"

export JAVA_HOME="$JDK_DIR"
export PATH="$JDK_DIR/bin:$MARIADB_DIR/bin:$PATH"

printf '\n\033[1;36m==== 2/6 MariaDB ====\033[0m\n'
DB_HOST="$(get_ini_val database Host 127.0.0.1)"
[[ "$DB_HOST" == "localhost" ]] && DB_HOST="127.0.0.1"
DB_PORT="$(get_ini_val database Port 3306)"
DB_USER="$(get_ini_val database User root)"
DB_PASS="$(get_ini_val database Password '')"
DB_NAME="$(get_ini_val database Database l2jmobiusinterlude)"

DB_SERVER="$MARIADB_DIR/bin/mariadbd"
DB_CLIENT="$MARIADB_DIR/bin/mariadb"
DB_INSTALL="$MARIADB_DIR/scripts/mariadb-install-db"
DB_ADMIN="$MARIADB_DIR/bin/mariadb-admin"
DB_DATA="$RUNTIME_DIR/mariadb-data"
DB_SOCKET="$RUNTIME_DIR/mariadb.sock"
DB_PID_FILE="$RUNTIME_DIR/mariadb.pid"

start_local_mariadb() {
    local pid

    if [[ ! -d "$DB_DATA/mysql" ]]; then
        info "Initializing private MariaDB data directory..."
        mkdir -p "$DB_DATA"
        if ! "$DB_INSTALL" \
            --no-defaults \
            --basedir="$MARIADB_DIR" \
            --datadir="$DB_DATA" \
            --auth-root-authentication-method=normal \
            --skip-test-db >"$LOG_DIR/mariadb-init.log" 2>&1; then
            fail "MariaDB initialization failed. See $LOG_DIR/mariadb-init.log"
        fi
    fi

    info "Starting private MariaDB on $DB_HOST:$DB_PORT..."
    "$DB_SERVER" --no-defaults \
        --basedir="$MARIADB_DIR" \
        --datadir="$DB_DATA" \
        --bind-address="$DB_HOST" \
        --port="$DB_PORT" \
        --socket="$DB_SOCKET" \
        --pid-file="$DB_PID_FILE" \
        --log-error="$LOG_DIR/mariadb.log" \
        --skip-networking=0 >/dev/null 2>&1 &
    pid=$!
    record_pid mariadb "$pid" "$DB_SERVER"

    for _ in $(seq 1 30); do
        if test_port "$DB_HOST" "$DB_PORT"; then
            ok "Private MariaDB started (PID $pid)."
            return 0
        fi
        if ! pid_is_live "$pid"; then
            break
        fi
        sleep 1
    done

    rm -f "$PID_DIR/mariadb.pid"
    warn "Portable MariaDB exited during startup."
    if command -v ldd >/dev/null 2>&1; then
        if ldd "$DB_SERVER" 2>/dev/null | grep -q 'not found'; then
            printf '\nMissing shared libraries reported by MariaDB:\n'
            ldd "$DB_SERVER" 2>/dev/null | grep 'not found' || true
        fi
    fi
    fail "Could not start MariaDB. See $LOG_DIR/mariadb.log"
}

if test_port "$DB_HOST" "$DB_PORT"; then
    ok "A database is already available on $DB_HOST:$DB_PORT; leaving it untouched."
else
    start_local_mariadb
fi

[[ -x "$DB_CLIENT" ]] || fail "Portable MariaDB client is missing: $DB_CLIENT"

db_exec() {
    if [[ -n "$DB_PASS" ]]; then
        MYSQL_PWD="$DB_PASS" "$DB_CLIENT" --protocol=TCP -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" "$@"
    else
        "$DB_CLIENT" --protocol=TCP -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" "$@"
    fi
}

printf '\n\033[1;36m==== 3/6 Database schema ====\033[0m\n'
if [[ -f "$DB_MARKER" ]]; then
    if db_exec "$DB_NAME" -e 'SELECT 1;' >/dev/null 2>&1; then
        ok "Database schema is already installed."
    else
        warn "Schema marker exists but database '$DB_NAME' is not accessible; re-importing."
        rm -f "$DB_MARKER"
    fi
fi

if [[ ! -f "$DB_MARKER" ]]; then
    info "Creating database '$DB_NAME' and importing SQL files..."
    db_exec -e "CREATE DATABASE IF NOT EXISTS \`$DB_NAME\` CHARACTER SET utf8 COLLATE utf8_unicode_ci;" || fail "Could not create database '$DB_NAME'."

    SQL_ROOT="$DIST_DIR/db_installer/sql"
    [[ -d "$SQL_ROOT" ]] || fail "SQL folder not found at $SQL_ROOT"

    imported=0
    for group in login game; do
        SQL_DIR="$SQL_ROOT/$group"
        [[ -d "$SQL_DIR" ]] || continue
        info "Importing $group SQL files..."
        while IFS= read -r -d '' f; do
            db_exec "$DB_NAME" < "$f" || fail "SQL import failed: $f"
            imported=$((imported + 1))
        done < <(find "$SQL_DIR" -maxdepth 1 -type f -name '*.sql' -print0 | sort -z)
    done

    (( imported > 0 )) || fail "No SQL files were found under $SQL_ROOT"
    date > "$DB_MARKER"
    ok "Database schema installed ($imported SQL files)."
fi

printf '\n\033[1;36m==== 4/6 Optional FPC Brain ====\033[0m\n'
START_BRAIN="$(get_ini_val servers StartBrain false)"
if is_true "$START_BRAIN"; then
    BRAIN_DIR="$DIST_DIR/brain"
    [[ -f "$BRAIN_DIR/fpc_brain.py" ]] || fail "StartBrain=true, but $BRAIN_DIR/fpc_brain.py was not found."
    [[ -f "$BRAIN_DIR/setup_brain.sh" ]] || fail "StartBrain=true, but setup_brain.sh was not found."

    if [[ ! -f "$BRAIN_DIR/.env" || ! -d "$BRAIN_DIR/.venv" ]]; then
        warn "Brain is not configured yet. Run '$BRAIN_DIR/setup_brain.sh' once; auto-start is being skipped."
    else
        if ! curl -fsS http://127.0.0.1:11434/api/tags >/dev/null 2>&1; then
            OLLAMA_BIN="$(command -v ollama 2>/dev/null || true)"
            [[ -n "$OLLAMA_BIN" ]] || fail "StartBrain=true but Ollama is not configured. Run dist/brain/setup_brain.sh once first."
            info "Starting Ollama locally..."
            nohup "$OLLAMA_BIN" serve >"$LOG_DIR/ollama.log" 2>&1 &
            OLLAMA_PID=$!
            record_pid ollama "$OLLAMA_PID" "$OLLAMA_BIN"
            for _ in $(seq 1 30); do
                curl -fsS http://127.0.0.1:11434/api/tags >/dev/null 2>&1 && break
                sleep 1
            done
        fi

        curl -fsS http://127.0.0.1:11434/api/tags >/dev/null 2>&1 || fail "Ollama did not become ready. See $LOG_DIR/ollama.log"
        ok "Ollama API is ready."

        info "Starting FPC brain..."
        (cd "$BRAIN_DIR" && nohup bash ./setup_brain.sh --auto >"$LOG_DIR/brain.log" 2>&1 & echo $! > "$PID_DIR/brain.raw")
        BRAIN_PID="$(cat "$PID_DIR/brain.raw")"
        rm -f "$PID_DIR/brain.raw"
        record_pid brain "$BRAIN_PID" "fpc_brain.py"
        sleep 2
        if pid_is_live "$BRAIN_PID"; then
            ok "FPC brain started (PID $BRAIN_PID)."
        else
            warn "FPC brain exited early. See $LOG_DIR/brain.log"
            rm -f "$PID_DIR/brain.pid"
        fi
    fi
else
    info "StartBrain=false in launcher.ini; skipping Ollama and FPC brain."
fi

printf '\n\033[1;36m==== 5/6 Server files ====\033[0m\n'
LOGIN_JAR="$DIST_DIR/libs/LoginServer.jar"
GAME_JAR="$DIST_DIR/libs/GameServer.jar"
LOGIN_DIR="$DIST_DIR/login"
GAME_DIR="$DIST_DIR/game"

[[ -f "$LOGIN_JAR" ]] || fail "LoginServer.jar not found at $LOGIN_JAR"
[[ -f "$GAME_JAR" ]] || fail "GameServer.jar not found at $GAME_JAR"
[[ -d "$LOGIN_DIR" ]] || fail "Login working directory not found at $LOGIN_DIR"
[[ -d "$GAME_DIR" ]] || fail "Game working directory not found at $GAME_DIR"
ok "Server files found."

printf '\n\033[1;36m==== 6/6 LoginServer + GameServer ====\033[0m\n'
start_java_server() {
    local role="$1"
    local work_dir="$2"
    local jar="$3"
    local xms="$4"
    local xmx="$5"
    local log="$6"
    local pid_file="$PID_DIR/$role.pid"
    if [[ -f "$pid_file" ]]; then
        local old_pid
        old_pid="$(sed -n '1p' "$pid_file")"
        if [[ "$old_pid" =~ ^[0-9]+$ ]] && pid_is_live "$old_pid"; then
            info "$role is already running (PID $old_pid)."
            return
        fi
        rm -f "$pid_file"
    fi

    info "Starting $role..."
    (cd "$work_dir" && nohup "$JAVA_BIN" -Xms"$xms" -Xmx"$xmx" -jar "$jar" >"$log" 2>&1 & echo $! > "$PID_DIR/$role.raw")
    local pid
    pid="$(cat "$PID_DIR/$role.raw")"
    rm -f "$PID_DIR/$role.raw"
    record_pid "$role" "$pid" "$jar"
    sleep 2
    if pid_is_live "$pid"; then
        ok "$role started (PID $pid). Log: $log"
    else
        rm -f "$pid_file"
        fail "$role exited during startup. Check $log"
    fi
}

START_LOGIN="$(get_ini_val servers StartLogin true)"
START_GAME="$(get_ini_val servers StartGame true)"

if is_true "$START_LOGIN"; then
    start_java_server login "$LOGIN_DIR" "$LOGIN_JAR" 512m 1024m "$LOG_DIR/login.log"
else
    info "StartLogin=false; skipping LoginServer."
fi

if is_true "$START_GAME"; then
    start_java_server game "$GAME_DIR" "$GAME_JAR" 1024m 2048m "$LOG_DIR/game.log"
else
    info "StartGame=false; skipping GameServer."
fi

printf '\n\033[1;32mServer startup sequence completed.\033[0m\n'
printf 'Portable runtime: %s\n' "$RUNTIME_DIR"
printf 'Logs: %s\n' "$LOG_DIR"
printf 'Stop with: %s/stop.sh\n' "$BASE_DIR"
