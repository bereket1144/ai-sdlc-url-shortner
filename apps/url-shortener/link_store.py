"""SQLite-backed code -> long_url store.

Schema creation is idempotent so the service can start repeatedly against the
same file. The UNIQUE constraint on code is the collision authority.
"""

import sqlite3

from code_generator import CodeCollision

SCHEMA = (
    "CREATE TABLE IF NOT EXISTS links ("
    "code TEXT PRIMARY KEY NOT NULL UNIQUE, "
    "long_url TEXT NOT NULL"
    ")"
)


class LinkStore:
    """Persists short code to long URL mappings in a SQLite file."""

    def __init__(self, db_path):
        self.db_path = db_path
        self._conn = sqlite3.connect(db_path)
        self._conn.execute("PRAGMA journal_mode=WAL")
        self._conn.execute(SCHEMA)
        self._conn.commit()

    def insert(self, code, long_url):
        """Insert a mapping; raise CodeCollision when code already exists."""
        try:
            with self._conn:
                self._conn.execute(
                    "INSERT INTO links (code, long_url) VALUES (?, ?)",
                    (code, long_url),
                )
        except sqlite3.IntegrityError as exc:
            raise CodeCollision(code) from exc
        return code

    def lookup(self, code):
        """Return the stored long URL, or None when the code is unknown."""
        row = self._conn.execute(
            "SELECT long_url FROM links WHERE code = ?", (code,)
        ).fetchone()
        if row is None:
            return None
        return row[0]

    def count(self):
        """Return the number of stored links (used by tests)."""
        return self._conn.execute("SELECT COUNT(*) FROM links").fetchone()[0]

    def close(self):
        self._conn.close()
