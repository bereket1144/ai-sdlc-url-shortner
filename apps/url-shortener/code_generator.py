"""Random 7-character base62 code generation with bounded collision retry."""

import secrets

ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
CODE_LENGTH = 7
DEFAULT_RETRY_LIMIT = 10


class CodeGenerationError(Exception):
    """Raised when no unused code could be produced within the retry limit."""


class CodeGenerator:
    """Generates codes and retries when the store reports a collision."""

    def __init__(self, retry_limit=DEFAULT_RETRY_LIMIT, randbelow=None):
        if retry_limit < 1:
            raise ValueError("retry_limit must be at least 1")
        self.retry_limit = retry_limit
        self._randbelow = randbelow or secrets.randbelow

    def new_code(self):
        """Return a fresh 7-character base62 code."""
        return "".join(
            ALPHABET[self._randbelow(len(ALPHABET))] for _ in range(CODE_LENGTH)
        )

    def generate_for_insert(self, insert):
        """Call insert(code) until it succeeds or the retry limit is exhausted.

        insert must raise CodeCollision on a uniqueness violation and return
        the accepted code on success. Raises CodeGenerationError when every
        attempt collides.
        """
        last_error = None
        for _ in range(self.retry_limit):
            code = self.new_code()
            try:
                return insert(code)
            except CodeCollision as exc:
                last_error = exc
        raise CodeGenerationError(
            "no unused code found after %d attempts" % self.retry_limit
        ) from last_error


class CodeCollision(Exception):
    """Signals that the store already holds the generated code."""
