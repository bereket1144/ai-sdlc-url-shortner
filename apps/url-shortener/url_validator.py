"""Pure URL validation for the short link service.

No network access is performed; only stdlib parsing is used.
"""

from urllib.parse import urlsplit

ALLOWED_SCHEMES = ("http", "https")


def is_valid_long_url(value):
    """Return True when value is an absolute http/https URL.

    Rejects None, non-strings, empty/whitespace-only values, values without a
    scheme, values with a non-http(s) scheme, and values without a host.
    """
    if not isinstance(value, str):
        return False
    candidate = value.strip()
    if not candidate:
        return False
    try:
        parts = urlsplit(candidate)
    except ValueError:
        return False
    if parts.scheme.lower() not in ALLOWED_SCHEMES:
        return False
    if not parts.netloc:
        return False
    return True
