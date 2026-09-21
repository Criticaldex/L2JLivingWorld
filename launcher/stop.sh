#!/usr/bin/env bash
set -u

BASE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DIST_DIR="$(cd "$BASE_DIR/.." && pwd)"
PID_DIR="$BASE_DIR/.linux-pids"
LOG_DIR="$BASE_DIR/logs-linux"
RUNTIME_DIR="$DIST_DIR/runtime-linux"
MARIADB_DIR="$RUNTIME_DIR/mariadb"
HAD_FAILURE=0

ok()   { printf '  \033[1;32m[ OK ] %s\033[0m\n' "$*"; }
info() { printf '  \033[0;37m[info] %s\033[0m\n' "$*"; }
warn() { HAD_FAILURE=1; printf '  \033[1;31m[FAIL] %s\033[0m\n' "$*"; }

stop_pid_file() {
    local role="$1"
    local file="$PID_DIR/$role.pid"
    [[ -f "$file" ]] || { info "$role: no launcher PID file; nothing to stop."; return; }

    local pid marker cmdline
    pid="$(sed -n '1p' "$file")"
    marker="$(sed -n '2p' "$file")"

    if [[ ! "$pid" =~ ^[0-9]+$ ]]; then
        warn "$role PID file is invalid: $file"
        return
    fi

    if ! kill -0 "$pid" >/dev/null 2>&1; then
        info "$role is already stopped (stale PID $pid)."
        rm -f "$file"
        return
    fi

    cmdline="$(tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null || true)"
    if [[ -n "$marker" && "$cmdline" != *"$marker"* ]]; then
        warn "$role PID $pid no longer matches its recorded command marker; not killing it."
        return
    fi

    kill -TERM "$pid" >/dev/null 2>&1 || true
    for _ in $(seq 1 10); do
        kill -0 "$pid" >/dev/null 2>&1 || break
        sleep 1
    done

    if kill -0 "$pid" >/dev/null 2>&1; then
        kill -KILL "$pid" >/dev/null 2>&1 || true
        sleep 1
    fi

    if kill -0 "$pid" >/dev/null 2>&1; then
        warn "Could not stop $role (PID $pid)."
    else
        ok "$role stopped (PID $pid)."
        rm -f "$file"
    fi
}

printf '\033[1;36m==== L2 Living World shutdown ====\033[0m\n'

# Dependants first, then infrastructure started by this launcher.
stop_pid_file game
stop_pid_file login
stop_pid_file brain
stop_pid_file ollama
stop_pid_file mariadb

if [[ ! -f "$PID_DIR/mariadb.pid" ]]; then
    rm -f "$RUNTIME_DIR/mariadb.pid" "$RUNTIME_DIR/mariadb.sock"
fi

printf '\n'
if (( HAD_FAILURE )); then
    printf '\033[1;31mShutdown completed with errors. Check %s and the messages above.\033[0m\n' "$LOG_DIR"
    exit 1
fi

printf '\033[1;32mShutdown completed successfully.\033[0m\n'
