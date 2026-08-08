#!/bin/bash
# Stop traktor-streaming-proxy if it is running.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
PROXY_BIN="$ROOT/build/install/traktor-streaming-proxy/bin/traktor-streaming-proxy"

stopped=0

if [[ -x "$PROXY_BIN" ]] && pgrep -f "$PROXY_BIN" >/dev/null 2>&1; then
  pkill -f "$PROXY_BIN" 2>/dev/null || true
  stopped=1
fi

# Also match java processes started from the install dist (Gradle wrapper may exec java)
if pgrep -f "traktor-streaming-proxy.jar" >/dev/null 2>&1; then
  pkill -f "traktor-streaming-proxy.jar" 2>/dev/null || true
  stopped=1
fi

# Brief wait + force leftover listeners on 8443 owned by our app name
sleep 0.5
if pgrep -f "traktor-streaming-proxy" >/dev/null 2>&1; then
  pkill -9 -f "traktor-streaming-proxy" 2>/dev/null || true
  stopped=1
fi

if [[ "$stopped" -eq 1 ]]; then
  echo "traktor-streaming-proxy stopped."
else
  echo "traktor-streaming-proxy is not running."
fi
