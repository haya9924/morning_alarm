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


if __name__ == "__main__":
    unittest.main()
