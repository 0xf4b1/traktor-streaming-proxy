#!/bin/bash
# Double-clickable launcher: starts the streaming proxy (if needed), then Traktor
# with SecTrustEvaluateStub.dylib preloaded. Stops the proxy when Traktor quits
# (only if this script started it).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
PROXY_DIR="$ROOT/build/install/traktor-streaming-proxy"
PROXY_BIN="$PROXY_DIR/bin/traktor-streaming-proxy"
DYLIB="$ROOT/cert/SecTrustEvaluateStub.dylib"
TRAKTOR="${TRAKTOR_BIN:-/Applications/Native Instruments/Traktor Pro 4/Traktor Pro 4.app/Contents/MacOS/Traktor Pro 4}"
LOG="${TRAKTOR_PROXY_LOG:-$HOME/Library/Logs/traktor-streaming-proxy.log}"

STARTED_PROXY=0
PROXY_PID=""

if [[ ! -x "$PROXY_BIN" ]]; then
  echo "Proxy binary not found: $PROXY_BIN"
  echo "Build it first: ./gradlew installDist"
  read -r -p "Press Enter to close..."
  exit 1
fi

if [[ ! -f "$DYLIB" ]]; then
  echo "Stub library not found: $DYLIB"
  echo "Build it with: (cd cert && make)"
  read -r -p "Press Enter to close..."
  exit 1
fi

if [[ ! -x "$TRAKTOR" ]]; then
  echo "Traktor binary not found: $TRAKTOR"
  echo "Set TRAKTOR_BIN to the correct path if needed."
  read -r -p "Press Enter to close..."
  exit 1
fi

# Probe only the local proxy process on :8443.
# Use localhost (often ::1): on some macOS/Java setups 127.0.0.1:8443 times out while
# the IPv6 listener works. Never fall back to system DNS for api.beatport.com — that can
# hit the real Beatport API and look "ready" even when this proxy is down.
proxy_ready() {
  local body
  body="$(curl --noproxy '*' -sk --fail --connect-timeout 1 --max-time 2 \
    "https://localhost:8443/v4/catalog/genres/" 2>/dev/null)" || return 1
  printf '%s' "$body" | grep -Eq '"results"[[:space:]]*:[[:space:]]*\['
}

stop_proxy() {
  if [[ "$STARTED_PROXY" -ne 1 ]]; then
    return 0
  fi
  echo "Stopping traktor-streaming-proxy..."
  if [[ -n "$PROXY_PID" ]] && kill -0 "$PROXY_PID" 2>/dev/null; then
    kill "$PROXY_PID" 2>/dev/null || true
    # Wait briefly, then force if still alive
    for _ in $(seq 1 20); do
      if ! kill -0 "$PROXY_PID" 2>/dev/null; then
        break
      fi
      sleep 0.25
    done
    if kill -0 "$PROXY_PID" 2>/dev/null; then
      kill -9 "$PROXY_PID" 2>/dev/null || true
    fi
  fi
  # Fallback: anything still matching the installed binary path
  pkill -f "$PROXY_BIN" 2>/dev/null || true
  STARTED_PROXY=0
  echo "Proxy stopped."
}

trap stop_proxy EXIT

# Config.readConfig() loads ./config.properties from the process CWD.
# Use the repo-root config, not the installDist template.
CONFIG_FILE="$ROOT/config.properties"
if [[ ! -f "$CONFIG_FILE" ]]; then
  echo "config.properties not found in $ROOT"
  read -r -p "Press Enter to close..."
  exit 1
fi

if ! proxy_ready; then
  echo "Starting traktor-streaming-proxy..."
  echo "Config: $CONFIG_FILE"
  echo "Log: $LOG"
  cd "$ROOT"
  nohup "$PROXY_BIN" >>"$LOG" 2>&1 &
  PROXY_PID=$!
  STARTED_PROXY=1

  ready=0
  for _ in $(seq 1 60); do
    if proxy_ready; then
      ready=1
      break
    fi
    sleep 0.5
  done

  if [[ "$ready" -ne 1 ]]; then
    echo "Proxy did not become ready in time on https://localhost:8443. Check $LOG"
    echo "Traktor still needs pf redirect + /etc/hosts for api.beatport.com:443."
    read -r -p "Press Enter to close..."
    exit 1
  fi
  echo "Proxy is ready (pid $PROXY_PID)."
else
  echo "Proxy already running (will not stop it on exit)."
fi

echo "Launching Traktor..."
export DYLD_INSERT_LIBRARIES="$DYLIB"
# Do not exec: keep this shell alive so trap can stop the proxy after Traktor quits.
"$TRAKTOR"
echo "Traktor closed."
