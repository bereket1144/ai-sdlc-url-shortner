import os
import tempfile
import unittest

from code_generator import CodeCollision
from link_store import LinkStore


class LinkStoreTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.db_path = os.path.join(self.tmp, "links.sqlite3")

    def test_insert_and_lookup_hit(self):
        store = LinkStore(self.db_path)
        store.insert("abc1234", "https://example.com/a")
        self.assertEqual(store.lookup("abc1234"), "https://example.com/a")
        store.close()

    def test_lookup_miss_returns_none(self):
        store = LinkStore(self.db_path)
        self.assertIsNone(store.lookup("missing"))
        store.close()

    def test_duplicate_insert_raises_collision(self):
        store = LinkStore(self.db_path)
        store.insert("abc1234", "https://example.com/a")
        with self.assertRaises(CodeCollision):
            store.insert("abc1234", "https://example.com/b")
        self.assertEqual(store.lookup("abc1234"), "https://example.com/a")
        store.close()

    def test_schema_creation_is_idempotent(self):
        LinkStore(self.db_path).close()
        LinkStore(self.db_path).close()

    def test_data_survives_restart(self):
        store = LinkStore(self.db_path)
        store.insert("abc1234", "https://example.com/a")
        store.close()
        reopened = LinkStore(self.db_path)
        self.assertEqual(reopened.lookup("abc1234"), "https://example.com/a")
        reopened.close()


if __name__ == "__main__":
    unittest.main()
