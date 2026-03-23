#!/bin/bash

PROJECT_DIR="$HOME/projects/traktor-streaming-proxy"
SERVER_DIR="$PROJECT_DIR/build/install/traktor-streaming-proxy"
CERT_DIR="$PROJECT_DIR/cert"

echo "🎧 Starting Traktor Streaming Proxy..."

# Enable DNS redirect to localhost
cd "$SERVER_DIR"
sudo pfctl -f pf.conf
sudo pfctl -e

# Start server in background
bin/traktor-streaming-proxy &
SERVER_PID=$!

# Wait for server to authenticate
sleep 5

# Start Traktor
echo "Starting Traktor..."
cd "$CERT_DIR"
DYLD_INSERT_LIBRARIES=./SecTrustEvaluateStub.dylib "/Applications/Native Instruments/Traktor Pro 4/Traktor Pro 4.app/Contents/MacOS/Traktor Pro 4" &

echo "✓ Traktor launched! Press Ctrl+C to stop proxy."
wait $SERVER_PID
