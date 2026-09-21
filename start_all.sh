#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BRAIN_SCRIPT="$ROOT_DIR/brain/setup_brain.sh"
LAUNCHER_SCRIPT="$ROOT_DIR/launcher/start.sh"
BRAIN_URL="${BRAIN_URL:-http://127.0.0.1:5000}"
BRAIN_TIMEOUT="${BRAIN_TIMEOUT:-300}"

if [[ ! -f "$BRAIN_SCRIPT" ]]; then
    printf 'No se encontró el script del brain: %s\n' "$BRAIN_SCRIPT" >&2
    exit 1
fi

if [[ ! -f "$LAUNCHER_SCRIPT" ]]; then
    printf 'No se encontró el launcher: %s\n' "$LAUNCHER_SCRIPT" >&2
    exit 1
fi

printf 'Iniciando el brain...\n'
bash "$BRAIN_SCRIPT" &
brain_pid=$!

cleanup() {
    if kill -0 "$brain_pid" >/dev/null 2>&1; then
        printf '\nEl launcher terminó; el brain continúa ejecutándose (PID %s).\n' "$brain_pid"
    fi
}
trap cleanup EXIT

printf 'Esperando a que el brain responda en %s...\n' "$BRAIN_URL"
for ((elapsed=0; elapsed < BRAIN_TIMEOUT; elapsed++)); do
    if curl --silent --show-error --connect-timeout 1 "$BRAIN_URL/chat" >/dev/null 2>&1; then
        break
    fi

    if ! kill -0 "$brain_pid" >/dev/null 2>&1; then
        wait "$brain_pid"
        printf 'El setup del brain terminó antes de estar disponible.\n' >&2
        exit 1
    fi
    sleep 1
done

if ! curl --silent --show-error --connect-timeout 1 "$BRAIN_URL/chat" >/dev/null 2>&1; then
    printf 'El brain no respondió dentro de %s segundos.\n' "$BRAIN_TIMEOUT" >&2
    exit 1
fi

printf 'Brain disponible. Iniciando el launcher...\n'
exec bash "$LAUNCHER_SCRIPT"
