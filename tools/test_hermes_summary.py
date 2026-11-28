import http.client
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest
from http.server import HTTPServer

import hermes_summary as summary


class SummaryServerTest(unittest.TestCase):
    def test_publish_read_revoke_and_keep_last_good(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory) / "state"
            source = Path(directory) / "summary.html"
            body = "<h1>朝のまとめ {{date}}</h1>".encode()
            source.write_bytes(body)

            def run(*args):
                return subprocess.run(
                    [sys.executable, summary.__file__, "--state-dir", str(state), *args],
                    capture_output=True,
                )

            self.assertEqual(run("rotate-token").returncode, 0)
            token_file = state / "token"
            token = token_file.read_text().strip()
            self.assertEqual(os.stat(token_file).st_mode & 0o777, 0o600)

            with HTTPServer(("127.0.0.1", 0), summary.handler_for(state)) as server:
                worker = threading.Thread(target=server.serve_forever)
                worker.start()

                def request(auth=None, path=summary.ROUTE, method="GET"):
                    conn = http.client.HTTPConnection(*server.server_address, timeout=3)
                    try:
                        conn.request(method, path, headers={"Authorization": "Bearer " + auth} if auth else {})
                        response = conn.getresponse()
                        return response.status, response.read(), response.headers
                    finally:
                        conn.close()

                try:
                    self.assertEqual(request()[0], 401)
                    self.assertEqual(request("wrong")[0], 401)
                    self.assertEqual(request(token)[0], 404)
                    self.assertEqual(request(token, "/")[0], 404)
                    self.assertEqual(request(token, method="POST")[0], 501)
                    self.assertEqual(run("publish", str(source)).returncode, 0)
                    status, received, headers = request(token)
                    self.assertEqual((status, received), (200, body))
                    self.assertEqual(headers["Cache-Control"], "no-store")
                    self.assertEqual(headers["Content-Type"], "text/html; charset=utf-8")
                    for bad in (b"\xff", b" \n", b"x" * (summary.MAX_BYTES + 1)):
                        source.write_bytes(bad)
                        self.assertNotEqual(run("publish", str(source)).returncode, 0)
                        self.assertEqual(request(token)[1], body)
                    self.assertEqual(run("rotate-token").returncode, 0)
                    self.assertEqual(request(token)[0], 401)
                    self.assertEqual(request(token_file.read_text().strip())[1], body)
                    self.assertEqual(run("revoke-token").returncode, 0)
                    self.assertEqual(request(token)[0], 503)
                finally:
                    server.shutdown()
                    worker.join()


class BindAddressTest(unittest.TestCase):
    def test_accepts_loopback_and_tailscale_literals(self):
        accepted = {
            "127.0.0.1": "127.0.0.1",
            "127.1.2.3": "127.1.2.3",
            "::1": "::1",
            "[::1]": "::1",
            "100.64.0.1": "100.64.0.1",
            "100.127.255.254": "100.127.255.254",
            "fd7a:115c:a1e0::1": "fd7a:115c:a1e0::1",
            "[fd7a:115c:a1e0::1]": "fd7a:115c:a1e0::1",
        }
        for text, expected in accepted.items():
            with self.subTest(host=text):
                address = summary.parse_bind_address(text)
                self.assertEqual(str(address), expected)

    def test_tailscale_and_loopback_are_ipv4_or_ipv6(self):
        self.assertEqual(summary.parse_bind_address("100.64.0.1").version, 4)
        self.assertEqual(summary.parse_bind_address("127.0.0.1").version, 4)
        self.assertEqual(summary.parse_bind_address("::1").version, 6)

    def test_selects_ipv6_server_for_ipv6_literals(self):
        self.assertIs(summary.IPv6HTTPServer.address_family, summary.socket.AF_INET6)
        self.assertIsNot(summary.HTTPServer.address_family, summary.socket.AF_INET6)

    def test_rejects_public_lan_wildcard_and_hostnames(self):
        for text in (
            "0.0.0.0",
            "::",
            "192.168.1.10",
            "10.0.0.1",
            "172.16.0.1",
            "8.8.8.8",
            "2001:db8::1",
            "100.63.255.255",
            "100.128.0.0",
            "fd7a:115c:a1e1::1",
            "localhost",
            "example.com",
            "phone.tailnet.ts.net",
            "",
            " ",
            "[127.0.0.1",
            "100.64.0.999",
            "100.64.0.01",
        ):
            with self.subTest(host=text):
                with self.assertRaises(ValueError):
                    summary.parse_bind_address(text)

    def test_serve_rejects_exposed_host_before_binding(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory) / "state"
            state.mkdir(mode=0o700)
            result = subprocess.run(
                [
                    sys.executable,
                    summary.__file__,
                    "--state-dir",
                    str(state),
                    "serve",
                    "--host",
                    "0.0.0.0",
                    "--port",
                    "0",
                ],
                capture_output=True,
                timeout=30,
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertIn(b"not loopback or Tailscale", result.stderr)


if __name__ == "__main__":
    unittest.main()
