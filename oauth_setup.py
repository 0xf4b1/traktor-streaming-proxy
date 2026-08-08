#!/usr/bin/env python3
"""Create a refreshable OAuth properties file for YouTube or SoundCloud."""

import argparse
import base64
import getpass
import hashlib
import json
import os
import secrets
import time
import urllib.parse
import urllib.request
import webbrowser
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path


PROVIDERS = {
    "youtube": {
        "authorize": "https://accounts.google.com/o/oauth2/v2/auth",
        "token": "https://oauth2.googleapis.com/token",
        "scope": "https://www.googleapis.com/auth/youtube.readonly",
    },
    "soundcloud": {
        "authorize": "https://secure.soundcloud.com/authorize",
        "token": "https://secure.soundcloud.com/oauth/token",
        "scope": None,
    },
}
REDIRECT_URI = "http://127.0.0.1:8765/callback"


def exchange_code(provider, client_id, client_secret, code, verifier):
    data = {
        "grant_type": "authorization_code",
        "client_id": client_id,
        "client_secret": client_secret,
        "redirect_uri": REDIRECT_URI,
        "code": code,
        "code_verifier": verifier,
    }
    request = urllib.request.Request(
        PROVIDERS[provider]["token"],
        data=urllib.parse.urlencode(data).encode(),
        headers={"Accept": "application/json", "Content-Type": "application/x-www-form-urlencoded"},
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def receive_code(expected_state, authorization_url):
    result = {}

    class CallbackHandler(BaseHTTPRequestHandler):
        def do_GET(self):
            query = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            if query.get("state", [None])[0] != expected_state:
                self.send_error(400, "Invalid OAuth state")
                return
            result["code"] = query.get("code", [None])[0]
            result["error"] = query.get("error", [None])[0]
            body = b"Authentication complete. You can close this window."
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, _format, *_args):
            return

    server = HTTPServer(("127.0.0.1", 8765), CallbackHandler)
    server.timeout = 300
    webbrowser.open(authorization_url)
    server.handle_request()
    if result.get("error"):
        raise RuntimeError(f"Provider rejected authorization: {result['error']}")
    if not result.get("code"):
        raise RuntimeError("No authorization code received within five minutes")
    return result["code"]


def write_credentials(path, client_id, client_secret, token):
    refresh_token = token.get("refresh_token")
    if not refresh_token:
        raise RuntimeError("Provider returned no refresh token; revoke the old grant and try again")
    expires_at = int(time.time()) + int(token.get("expires_in", 3600))
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        "# OAuth credentials - keep private\n"
        f"clientId={client_id}\n"
        f"clientSecret={client_secret}\n"
        f"refreshToken={refresh_token}\n"
        f"accessToken={token.get('access_token', '')}\n"
        f"expiresAt={expires_at}\n",
        encoding="utf-8",
    )
    if os.name != "nt":
        path.chmod(0o600)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("provider", choices=PROVIDERS)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    provider = PROVIDERS[args.provider]
    output = args.output or Path("secrets") / f"{args.provider}-oauth.properties"
    print(f"Register this redirect URI at the provider first: {REDIRECT_URI}")
    client_id = input("OAuth client ID: ").strip()
    client_secret = getpass.getpass("OAuth client secret: ").strip()
    verifier = secrets.token_urlsafe(64)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    state = secrets.token_urlsafe(24)
    parameters = {
        "client_id": client_id,
        "redirect_uri": REDIRECT_URI,
        "response_type": "code",
        "code_challenge": challenge,
        "code_challenge_method": "S256",
        "state": state,
    }
    if provider["scope"]:
        parameters.update({"scope": provider["scope"], "access_type": "offline", "prompt": "consent"})
    url = provider["authorize"] + "?" + urllib.parse.urlencode(parameters)
    print("Open this URL if the browser does not start automatically:\n" + url)
    token = exchange_code(
        args.provider,
        client_id,
        client_secret,
        receive_code(state, url),
        verifier,
    )
    write_credentials(output, client_id, client_secret, token)
    print(f"OAuth credentials saved to {output}. Do not commit this file.")


if __name__ == "__main__":
    main()
