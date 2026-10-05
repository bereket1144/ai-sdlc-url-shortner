"""Service entrypoint: start the short link HTTP service."""

import argparse
import os

from app import build_server

DEFAULT_DB = os.environ.get("SHORTLINK_DB", "links.sqlite3")


def main(argv=None):
    parser = argparse.ArgumentParser(description="Short link service")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8000)
    parser.add_argument("--db", default=DEFAULT_DB)
    args = parser.parse_args(argv)
    server, store = build_server(args.host, args.port, args.db)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        store.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
