#!/usr/bin/env bash
#
# One-step setup + launch for the FPC brain - a local Ollama model or one of
# several cloud APIs (DeepSeek, OpenAI, Groq, OpenRouter, Mistral), your choice.
#
# What it does:
#   1. Asks which AI provider to use from a numbered menu: Ollama (local/offline)
#      or a cloud API. Asked every run - press Enter to keep whatever you picked
#      last time, or answer again to switch.
#   2. Lets you pick the chat model: a short suggestion list per provider plus a
#      "type your own ID" option (Ollama recommends gemma3:12b).
#      Ollama chosen: installs Ollama if missing, starts its server, pulls the
#      chosen model.
#      A cloud provider chosen: asks for (or reuses the saved) API key. Ollama is
#      never installed/started/pulled on this path.
#   3. Creates a Python virtualenv and installs requirements.
#   4. Writes the local .env file with the resolved settings (every provider key
#      you have entered is kept, so switching back and forth does not lose them).
#   5. Starts fpc_brain.py.
#
# Usage:
#   ./setup_brain.sh                 - if the brain is already set up (.env +
#                                       .venv exist) it starts straight away with
#                                       no provider question; otherwise it runs
#                                       the first-time setup.
#   ./setup_brain.sh --reconfigure   - always runs the provider/model questions
#                                       again, even when already set up, but
#                                       KEEPS the saved .env as the defaults
#                                       (Enter = keep a value; saved API keys are
#                                       reused). This is how you switch provider
#                                       or model without re-entering keys.
#   ./setup_brain.sh --reset         - wipes the saved .env first, so everything
#                                       is asked fresh again (including every API
#                                       key). Use this to start over from scratch.
#   ./setup_brain.sh --auto          - non-interactive launch used by a launcher:
#                                       if already configured (.env + .venv), just
#                                       start fpc_brain.py; otherwise print a note
#                                       and exit 2 without doing any install work.
#   OLLAMA_MODEL=gemma3 ./setup_brain.sh - override the Ollama model (skips the
#                                       model menu on the Ollama path)
#
set -euo pipefail

cd "$(dirname "$0")"

PYTHON="${PYTHON:-python3}"

say() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m[!] %s\033[0m\n' "$*"; }

ARG="${1:-}"

# --- 0a. --auto: non-interactive launch (never installs, never blocks) -----
if [[ "$ARG" == "--auto" ]]; then
    if [[ ! -f .env || ! -d .venv ]]; then
        say "Brain not configured yet - skipping auto-start."
        echo "    Run ./setup_brain.sh once (no arguments) to choose a provider and set it up."
        exit 2
    fi
    # shellcheck disable=SC1091
    source .venv/bin/activate
    say "Starting the FPC brain on http://127.0.0.1:5000 (auto) ..."
    exec "$PYTHON" fpc_brain.py
fi

# --- 0b. --reset wipes the saved config so everything is asked fresh -------
if [[ "$ARG" == "--reset" && -f .env ]]; then
    say "--reset: removing existing .env - you will be asked to reconfigure."
    rm -f .env
fi

# --- 0c. Already set up? Skip the questions and go straight to launch ------
# If an .env (chosen provider) and a .venv (built Python env) already exist,
# the brain was configured on a previous run, so a plain re-run starts it
# immediately - no provider question. --reconfigure forces the questions again
# (keeping .env values as defaults); --reset wipes .env above so it never skips
# here either.
if [[ "$ARG" != "--reconfigure" && -f .env && -f .venv/bin/activate ]]; then
    say "Brain already set up - starting it now."
    echo "    (To change provider or model, run: ./setup_brain.sh --reconfigure)"
    # shellcheck disable=SC1091
    source .venv/bin/activate
    say "Starting the FPC brain on http://127.0.0.1:5000 ..."
    exec "$PYTHON" fpc_brain.py
fi

# --- Load any existing configuration as defaults for the prompts below -----
EXIST_PROVIDER=""
EXIST_MODEL=""
LEGACY_OLLAMA_MODEL=""
KEY_DEEPSEEK=""
KEY_OPENAI=""
KEY_GROQ=""
KEY_OPENROUTER=""
KEY_MISTRAL=""
if [[ -f .env ]]; then
    while IFS='=' read -r k v; do
        case "$k" in
            PROVIDER) EXIST_PROVIDER="$v" ;;
            MODEL) EXIST_MODEL="$v" ;;
            OLLAMA_MODEL) LEGACY_OLLAMA_MODEL="$v" ;;
            DEEPSEEK_API_KEY) KEY_DEEPSEEK="$v" ;;
            OPENAI_API_KEY) KEY_OPENAI="$v" ;;
            GROQ_API_KEY) KEY_GROQ="$v" ;;
            OPENROUTER_API_KEY) KEY_OPENROUTER="$v" ;;
            MISTRAL_API_KEY) KEY_MISTRAL="$v" ;;
        esac
    done < .env
fi
# Older .env files only wrote OLLAMA_MODEL; use it as the saved-model default.
if [[ -z "$EXIST_MODEL" && -n "$LEGACY_OLLAMA_MODEL" ]]; then
    EXIST_MODEL="$LEGACY_OLLAMA_MODEL"
fi

# --- 1. Ask which provider to use, every run --------------------------------
echo "Choose an AI provider for the FPC brain:"
echo "  [1] Ollama     - local model, free, fully offline (needs a decent GPU/CPU)"
echo "  [2] DeepSeek   - cloud API, needs an API key, works on any PC"
echo "  [3] OpenAI     - cloud API, needs an API key"
echo "  [4] Groq       - cloud API, needs an API key"
echo "  [5] OpenRouter - cloud API, needs an API key"
echo "  [6] Mistral    - cloud API, needs an API key"
echo

if [[ -n "$EXIST_PROVIDER" ]]; then
    read -rp "Pick 1-6 (Enter = keep '$EXIST_PROVIDER'): " PROVIDER_CHOICE
else
    read -rp "Pick 1-6: " PROVIDER_CHOICE
fi

PROVIDER=""
if [[ -z "$PROVIDER_CHOICE" ]]; then
    if [[ -n "$EXIST_PROVIDER" ]]; then
        PROVIDER="$EXIST_PROVIDER"
    else
        warn "You must pick a provider on first setup."
        exit 1
    fi
fi
# Accept the menu numbers, plus the old O/D letters for muscle memory.
case "$PROVIDER_CHOICE" in
    1) PROVIDER="ollama" ;;
    2) PROVIDER="deepseek" ;;
    3) PROVIDER="openai" ;;
    4) PROVIDER="groq" ;;
    5) PROVIDER="openrouter" ;;
    6) PROVIDER="mistral" ;;
    [Oo]) PROVIDER="ollama" ;;
    [Dd]) PROVIDER="deepseek" ;;
esac

if [[ -z "$PROVIDER" ]]; then
    warn "Please pick a number 1-6."
    exit 1
fi

say "Provider: $PROVIDER"

# If the chosen provider differs from the one saved in .env, do not offer the
# old provider's model as the Enter-default in the model menu below.
if [[ -n "$EXIST_PROVIDER" && "$PROVIDER" != "$EXIST_PROVIDER" ]]; then
    EXIST_MODEL=""
fi

# --- Model suggestions per provider ------------------------------------------
# Fills MODREC (the recommended/Enter default) and SUGG1..SUGG5 (empty = absent)
# for the given provider. Suggestions only - the menu always lets the user type
# any model ID, so the lists can be short and don't have to be exhaustive.
set_model_suggestions() {
    SUGG1=""; SUGG2=""; SUGG3=""; SUGG4=""; SUGG5=""
    case "$PROVIDER" in
        ollama)
            MODREC="gemma3:12b"
            SUGG1="gemma3:12b"; SUGG2="llama3.1"; SUGG3="qwen2.5:14b"; SUGG4="phi3"
            ;;
        deepseek)
            MODREC="deepseek-chat"
            SUGG1="deepseek-chat"; SUGG2="deepseek-reasoner"
            ;;
        openai)
            MODREC="gpt-4o-mini"
            SUGG1="gpt-4o-mini"; SUGG2="gpt-4o"; SUGG3="gpt-4.1-mini"
            ;;
        groq)
            MODREC="openai/gpt-oss-120b"
            SUGG1="openai/gpt-oss-120b"; SUGG2="openai/gpt-oss-20b"
            ;;
        openrouter)
            MODREC="deepseek/deepseek-chat"
            SUGG1="deepseek/deepseek-chat"; SUGG2="meta-llama/llama-3.3-70b-instruct"
            ;;
        mistral)
            MODREC="mistral-small-latest"
            SUGG1="mistral-small-latest"; SUGG2="mistral-large-latest"
            ;;
    esac
}

# Shows the suggestion menu and sets MODEL. The Enter default is the model
# saved in .env for this provider (EXIST_MODEL) if any, otherwise the
# recommended one (MODREC). "c" lets the user type any model ID. Requires
# set_model_suggestions to have run first.
pick_model() {
    local enterdef="$MODREC"
    [[ -n "$EXIST_MODEL" ]] && enterdef="$EXIST_MODEL"
    echo
    echo "Choose a model for $PROVIDER (recommended: $MODREC):"
    [[ -n "$SUGG1" ]] && echo "  [1] $SUGG1"
    [[ -n "$SUGG2" ]] && echo "  [2] $SUGG2"
    [[ -n "$SUGG3" ]] && echo "  [3] $SUGG3"
    [[ -n "$SUGG4" ]] && echo "  [4] $SUGG4"
    [[ -n "$SUGG5" ]] && echo "  [5] $SUGG5"
    echo "  [c] Type a custom model ID"
    echo
    local choice
    read -rp "Pick a number, c for a custom ID, or Enter to keep '$enterdef': " choice

    if [[ -z "$choice" ]]; then
        MODEL="$enterdef"
        return
    fi
    if [[ "$choice" == [Cc] ]]; then
        local custom
        read -rp "Enter the exact model ID: " custom
        MODEL="${custom:-$enterdef}"
        return
    fi
    case "$choice" in
        1) MODEL="$SUGG1" ;;
        2) MODEL="$SUGG2" ;;
        3) MODEL="$SUGG3" ;;
        4) MODEL="$SUGG4" ;;
        5) MODEL="$SUGG5" ;;
        *) MODEL="" ;;
    esac
    if [[ -z "$MODEL" ]]; then
        echo "Not a valid choice - keeping '$enterdef'."
        MODEL="$enterdef"
    fi
}

if [[ "$PROVIDER" == "ollama" ]]; then
    # --- 2a. Ollama: pick a model, install if missing, start server, pull ---
    if [[ -n "${OLLAMA_MODEL:-}" ]]; then
        # Explicit override:  OLLAMA_MODEL=... ./setup_brain.sh - honor it, no menu.
        MODEL="$OLLAMA_MODEL"
        say "Using OLLAMA_MODEL override: $MODEL"
    else
        set_model_suggestions
        pick_model
    fi

    say "Ollama model: $MODEL"

    if ! command -v ollama >/dev/null 2>&1; then
        say "Ollama not found. Installing..."
        if [[ "$(uname)" == "Darwin" ]]; then
            if command -v brew >/dev/null 2>&1; then
                brew install ollama
            else
                warn "Homebrew not found. Please install Ollama from https://ollama.com/download and re-run."
                exit 1
            fi
        else
            curl -fsSL https://ollama.com/install.sh | sh
        fi
    else
        say "Ollama already installed: $(ollama --version 2>/dev/null | head -1)"
    fi

    if ! curl -fsS http://127.0.0.1:11434/api/tags >/dev/null 2>&1; then
        say "Starting Ollama server in the background..."
        nohup ollama serve >/tmp/ollama-serve.log 2>&1 &
        for i in $(seq 1 30); do
            if curl -fsS http://127.0.0.1:11434/api/tags >/dev/null 2>&1; then break; fi
            sleep 1
        done
    fi
    if ! curl -fsS http://127.0.0.1:11434/api/tags >/dev/null 2>&1; then
        warn "Ollama server did not come up. Check /tmp/ollama-serve.log"
        exit 1
    fi
    say "Ollama server is up."

    say "Pulling model '$MODEL' (first run downloads several GB, this can take a while)..."
    ollama pull "$MODEL"
else
    # --- 2b. Cloud provider: reuse or ask for the API key, then pick a model -
    # Every cloud provider here is OpenAI-compatible, so the only per-provider
    # difference is the API key (stored as <PROVIDER>_API_KEY) and the model.
    EXIST_CLOUD_KEY=""
    case "$PROVIDER" in
        deepseek) EXIST_CLOUD_KEY="$KEY_DEEPSEEK" ;;
        openai) EXIST_CLOUD_KEY="$KEY_OPENAI" ;;
        groq) EXIST_CLOUD_KEY="$KEY_GROQ" ;;
        openrouter) EXIST_CLOUD_KEY="$KEY_OPENROUTER" ;;
        mistral) EXIST_CLOUD_KEY="$KEY_MISTRAL" ;;
    esac

    CLOUD_KEY=""
    if [[ -n "$EXIST_CLOUD_KEY" ]]; then
        read -rp "Use saved $PROVIDER API key ending '...${EXIST_CLOUD_KEY: -4}'? [Y/n]: " key_choice
        if [[ ! "$key_choice" =~ ^[Nn] ]]; then
            CLOUD_KEY="$EXIST_CLOUD_KEY"
        fi
    fi
    if [[ -z "$CLOUD_KEY" ]]; then
        read -rp "Enter your $PROVIDER API key: " CLOUD_KEY
    fi
    if [[ -z "$CLOUD_KEY" ]]; then
        warn "An API key is required for $PROVIDER."
        exit 1
    fi

    # Store the key back in its own slot so .env keeps every provider's key.
    case "$PROVIDER" in
        deepseek) KEY_DEEPSEEK="$CLOUD_KEY" ;;
        openai) KEY_OPENAI="$CLOUD_KEY" ;;
        groq) KEY_GROQ="$CLOUD_KEY" ;;
        openrouter) KEY_OPENROUTER="$CLOUD_KEY" ;;
        mistral) KEY_MISTRAL="$CLOUD_KEY" ;;
    esac
    say "$PROVIDER key configured."

    set_model_suggestions
    pick_model
    say "Model: $MODEL"
fi

# --- 3. Python env + deps ---------------------------------------------------
if [[ ! -d .venv ]]; then
    say "Creating Python virtualenv (.venv)..."
    "$PYTHON" -m venv .venv
fi
# shellcheck disable=SC1091
source .venv/bin/activate
say "Installing Python requirements..."
"$PYTHON" -m pip install --quiet --upgrade pip
"$PYTHON" -m pip install --quiet -r requirements.txt

# --- 4. Write the resolved .env (always overwritten with this run's choice) -
say "Writing .env..."
{
    echo "PROVIDER=$PROVIDER"
    echo "MODEL=$MODEL"
    [[ -n "$KEY_DEEPSEEK" ]] && echo "DEEPSEEK_API_KEY=$KEY_DEEPSEEK"
    [[ -n "$KEY_OPENAI" ]] && echo "OPENAI_API_KEY=$KEY_OPENAI"
    [[ -n "$KEY_GROQ" ]] && echo "GROQ_API_KEY=$KEY_GROQ"
    [[ -n "$KEY_OPENROUTER" ]] && echo "OPENROUTER_API_KEY=$KEY_OPENROUTER"
    [[ -n "$KEY_MISTRAL" ]] && echo "MISTRAL_API_KEY=$KEY_MISTRAL"
} > .env

# --- 5. Launch ---------------------------------------------------------------
say "Starting the FPC brain on http://127.0.0.1:5000 ..."
exec "$PYTHON" fpc_brain.py
