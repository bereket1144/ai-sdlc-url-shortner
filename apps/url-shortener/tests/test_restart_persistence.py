import json
import os
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer

from app import ShortLinkService, make_handler
from link_store import LinkStore


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class RestartPersistenceTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.db_path = os.path.join(self.tmp, "links.sqlite3")
        self.opener = urllib.request.build_opener(NoRedirect)

    def start_run(self):
        store = LinkStore(self.db_path)
        server = ThreadingHTTPServer(
            ("127.0.0.1", 0), make_handler(ShortLinkService(store))
        )
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        host, port = server.server_address
        return server, store, "http://%s:%d" % (host, port)

    def stop_run(self, server, store):
        server.shutdown()
        server.server_close()
        store.close()

    def test_links_created_in_run_one_redirect_in_run_two(self):
        url = "https://example.com/persisted"
        server, store, base = self.start_run()
        req = urllib.request.Request(
            base + "/links",
            data=json.dumps({"url": url}).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with self.opener.open(req) as resp:
            code = json.loads(resp.read().decode("utf-8"))["code"]
        self.stop_run(server, store)

        server, store, base = self.start_run()
        try:
            req = urllib.request.Request(base + "/" + code)
            try:
                with self.opener.open(req) as resp:
                    status = resp.status
                    location = resp.headers.get("Location")
            except urllib.error.HTTPError as exc:
                status = exc.code
                location = exc.headers.get("Location")
            self.assertEqual(status, 302)
            self.assertEqual(location, url)
        finally:
            self.stop_run(server, store)


if __name__ == "__main__":
    unittest.main()
