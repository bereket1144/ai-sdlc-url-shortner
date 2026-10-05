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


class HttpEndpointTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.db_path = os.path.join(self.tmp, "links.sqlite3")
        self.store = LinkStore(self.db_path)
        self.service = ShortLinkService(self.store)
        self.server = ThreadingHTTPServer(
            ("127.0.0.1", 0), make_handler(self.service)
        )
        self.thread = threading.Thread(
            target=self.server.serve_forever, daemon=True
        )
        self.thread.start()
        host, port = self.server.server_address
        self.base = "http://%s:%d" % (host, port)
        self.opener = urllib.request.build_opener(NoRedirect)

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.store.close()

    def post(self, payload, content_type="application/json"):
        data = payload.encode("utf-8")
        req = urllib.request.Request(
            self.base + "/links",
            data=data,
            headers={"Content-Type": content_type},
            method="POST",
        )
        try:
            with self.opener.open(req) as resp:
                return resp.status, resp.read()
        except urllib.error.HTTPError as exc:
            return exc.code, exc.read()

    def get(self, path):
        req = urllib.request.Request(self.base + path)
        try:
            with self.opener.open(req) as resp:
                return resp.status, resp.headers, resp.read()
        except urllib.error.HTTPError as exc:
            return exc.code, exc.headers, exc.read()

    def test_create_returns_short_url(self):
        status, body = self.post(json.dumps({"url": "https://example.com/a"}))
        self.assertEqual(status, 200)
        payload = json.loads(body.decode("utf-8"))
        self.assertEqual(len(payload["code"]), 7)
        self.assertTrue(payload["short_url"].endswith(payload["code"]))

    def test_create_accepts_form_body(self):
        status, body = self.post(
            "url=https%3A%2F%2Fexample.com%2Fb",
            content_type="application/x-www-form-urlencoded",
        )
        self.assertEqual(status, 200)
        self.assertEqual(len(json.loads(body.decode("utf-8"))["code"]), 7)

    def test_missing_and_empty_url_return_400_without_row(self):
        before = self.store.count()
        self.assertEqual(self.post(json.dumps({}))[0], 400)
        self.assertEqual(self.post(json.dumps({"url": ""}))[0], 400)
        self.assertEqual(self.post("")[0], 400)
        self.assertEqual(self.store.count(), before)

    def test_invalid_urls_return_400_without_row(self):
        before = self.store.count()
        for value in ("example.com", "/relative", "ftp://example.com"):
            self.assertEqual(self.post(json.dumps({"url": value}))[0], 400)
        self.assertEqual(self.store.count(), before)

    def test_same_url_twice_yields_distinct_codes(self):
        url = "https://example.com/same"
        first = json.loads(
            self.post(json.dumps({"url": url}))[1].decode("utf-8")
        )["code"]
        second = json.loads(
            self.post(json.dumps({"url": url}))[1].decode("utf-8")
        )["code"]
        self.assertNotEqual(first, second)
        for code in (first, second):
            status, headers, _ = self.get("/" + code)
            self.assertEqual(status, 302)
            self.assertEqual(headers.get("Location"), url)

    def test_follow_known_code_redirects(self):
        url = "https://example.com/target"
        code = json.loads(
            self.post(json.dumps({"url": url}))[1].decode("utf-8")
        )["code"]
        status, headers, _ = self.get("/" + code)
        self.assertEqual(status, 302)
        self.assertEqual(headers.get("Location"), url)

    def test_follow_unknown_code_returns_404(self):
        status, headers, _ = self.get("/zzzzzzz")
        self.assertEqual(status, 404)
        self.assertIsNone(headers.get("Location"))

    def test_repeated_follow_still_redirects(self):
        url = "https://example.com/repeat"
        code = json.loads(
            self.post(json.dumps({"url": url}))[1].decode("utf-8")
        )["code"]
        for _ in range(3):
            status, headers, _ = self.get("/" + code)
            self.assertEqual(status, 302)
            self.assertEqual(headers.get("Location"), url)


if __name__ == "__main__":
    unittest.main()
