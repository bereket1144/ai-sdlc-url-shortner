"""HTTP handler layer and response builder for the short link service.

Routes:
  POST /links   create a short link
  GET  /{code}  follow a short link (302) or 404

Stdlib only. The follow path performs a read-only lookup and never writes.
"""

import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

from code_generator import CodeGenerationError, CodeGenerator
from link_store import LinkStore
from url_validator import is_valid_long_url

MAX_BODY_BYTES = 64 * 1024


class ShortLinkService:
    """Wires validator, generator, store and response building together."""

    def __init__(self, store, generator=None):
        self.store = store
        self.generator = generator or CodeGenerator()

    def create_link(self, long_url):
        """Return the new code, or None when the URL is invalid."""
        if not is_valid_long_url(long_url):
            return None
        return self.generator.generate_for_insert(
            lambda code: self.store.insert(code, long_url)
        )

    def follow(self, code):
        """Return the stored long URL, or None when the code is unknown."""
        return self.store.lookup(code)


def build_short_url(host, code):
    """Return a copyable short URL path for the given code."""
    return "/" + code


def parse_long_url(body, content_type):
    """Extract the long URL from a JSON body or a form-encoded body."""
    if not body:
        return None
    text = body.decode("utf-8", errors="replace")
    if content_type and "application/json" in content_type.lower():
        try:
            payload = json.loads(text)
        except ValueError:
            return None
        if not isinstance(payload, dict):
            return None
        value = payload.get("url")
        return value if isinstance(value, str) else None
    fields = parse_qs(text, keep_blank_values=True)
    values = fields.get("url")
    if not values:
        return None
    return values[0]


def make_handler(service):
    """Build a request handler class bound to the given service."""

    class Handler(BaseHTTPRequestHandler):
        server_version = "ShortLink/1.0"

        def log_message(self, fmt, *args):
            return

        def _send(self, status, body=b"", headers=None):
            self.send_response(status)
            for name, value in (headers or {}).items():
                self.send_header(name, value)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            if body:
                self.wfile.write(body)

        def do_POST(self):
            path = urlsplit(self.path).path
            if path != "/links":
                self._send(404)
                return
            try:
                length = int(self.headers.get("Content-Length") or 0)
            except ValueError:
                self._send(400)
                return
            if length < 0 or length > MAX_BODY_BYTES:
                self._send(400)
                return
            body = self.rfile.read(length) if length else b""
            long_url = parse_long_url(body, self.headers.get("Content-Type"))
            try:
                code = service.create_link(long_url)
            except CodeGenerationError:
                self._send(500)
                return
            if code is None:
                self._send(400)
                return
            short_url = build_short_url(self.headers.get("Host"), code)
            payload = json.dumps({"code": code, "short_url": short_url}).encode(
                "utf-8"
            )
            self._send(
                200,
                payload,
                {"Content-Type": "application/json; charset=utf-8"},
            )

        def do_GET(self):
            path = urlsplit(self.path).path
            if path == "/links":
                self._send(404)
                return
            code = path.lstrip("/")
            if not code or "/" in code:
                self._send(404)
                return
            long_url = service.follow(code)
            if long_url is None:
                self._send(404)
                return
            self._send(302, b"", {"Location": long_url})

    return Handler


def build_server(host, port, db_path):
    """Create a server bound to a fresh LinkStore for the given file."""
    store = LinkStore(db_path)
    service = ShortLinkService(store)
    return ThreadingHTTPServer((host, port), make_handler(service)), store
