#!/usr/bin/env python3
"""Publish a morning-summary HTML file locally and serve it via a private HTTPS proxy."""

import argparse
import hmac
import os
from pathlib import Path
import re
import secrets
import sys
import tempfile
from http.server import BaseHTTPRequestHandler, HTTPServer

ROUTE = "/v1/morning-summary"
MAX_BYTES = 256 * 1024
TOKEN = re.compile(rb"[A-Za-z0-9_-]{43}")


def replace_private(path, data):
    fd, tmp = tempfile.mkstemp(dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(tmp, path)
    finally:
        Path(tmp).unlink(missing_ok=True)


def read_html(path):
    with path.open("rb") as stream:
        body = stream.read(MAX_BYTES + 1)
    if len(body) > MAX_BYTES or not body.decode("utf-8").strip():
        raise ValueError("HTML must be nonblank UTF-8 and no larger than 256 KiB")
    return body


def handler_for(state):
    class Handler(BaseHTTPRequestHandler):
        def setup(self):
            self.request.settimeout(5)
            super().setup()

        def log_message(self, *_):
            pass  # Do not log URLs, credentials, or any client-supplied text.

        def reply(self, status, body=b""):
            self.send_response(status)
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(body)))
            if status == 200:
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.send_header("X-Content-Type-Options", "nosniff")
                self.send_header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; img-src data:; sandbox")
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            if self.path != ROUTE:
                return self.reply(404)
            try:
                with (state / "token").open("rb") as stream:
                    token = stream.read(45).removesuffix(b"\n")
            except OSError:
                return self.reply(503)
            if not TOKEN.fullmatch(token):
                return self.reply(503)
            auth = self.headers.get_all("Authorization", [])
            if len(auth) != 1 or not hmac.compare_digest(auth[0].encode("latin-1"), b"Bearer " + token):
                return self.reply(401)
            try:
                body = read_html(state / "latest.html")
            except (OSError, ValueError):
                return self.reply(404)
            return self.reply(200, body)

    return Handler


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state-dir", type=Path, default=Path.home() / ".local/state/morning-alarm-summary")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("rotate-token", help="create/replace the read token (does not print it)")
    commands.add_parser("revoke-token", help="remove the current read token")
    publish = commands.add_parser("publish", help="atomically replace the latest HTML")
    publish.add_argument("file", type=Path)
    serve = commands.add_parser("serve", help="serve only on 127.0.0.1; use a private HTTPS proxy")
    serve.add_argument("--port", type=int, default=3647)
    args = parser.parse_args()
    state = args.state_dir.expanduser()
    try:
        state.mkdir(mode=0o700, parents=True, exist_ok=True)
        if not state.is_dir() or state.stat().st_uid != os.getuid() or state.stat().st_mode & 0o077:
            parser.error("state directory must belong to the current user and be private (0700)")
        if args.command == "rotate-token":
            replace_private(state / "token", (secrets.token_urlsafe(32) + "\n").encode("ascii"))
        elif args.command == "revoke-token":
            (state / "token").unlink(missing_ok=True)
        elif args.command == "publish":
            replace_private(state / "latest.html", read_html(args.file))
            print("Published on Hermes host; Android sync has not been confirmed.")
        else:
            with HTTPServer(("127.0.0.1", args.port), handler_for(state)) as server:
                server.serve_forever()
    except KeyboardInterrupt:
        return 0
    except UnicodeError:
        print("Error: HTML must be valid UTF-8", file=sys.stderr)
        return 1
    except (OSError, ValueError) as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
