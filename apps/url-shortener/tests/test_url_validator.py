import unittest

from url_validator import is_valid_long_url


class UrlValidatorTests(unittest.TestCase):
    def test_accepts_absolute_http_and_https(self):
        self.assertTrue(is_valid_long_url("http://example.com/a"))
        self.assertTrue(is_valid_long_url("https://example.com"))
        self.assertTrue(is_valid_long_url("https://example.com:8443/x?y=1"))

    def test_rejects_missing_and_empty(self):
        self.assertFalse(is_valid_long_url(None))
        self.assertFalse(is_valid_long_url(""))
        self.assertFalse(is_valid_long_url("   "))

    def test_rejects_non_absolute(self):
        self.assertFalse(is_valid_long_url("example.com/a"))
        self.assertFalse(is_valid_long_url("/relative/path"))

    def test_rejects_other_schemes(self):
        self.assertFalse(is_valid_long_url("ftp://example.com"))
        self.assertFalse(is_valid_long_url("javascript:alert(1)"))
        self.assertFalse(is_valid_long_url("mailto:a@example.com"))


if __name__ == "__main__":
    unittest.main()
