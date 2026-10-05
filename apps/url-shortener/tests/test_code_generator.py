import unittest

from code_generator import (
    ALPHABET,
    CODE_LENGTH,
    CodeCollision,
    CodeGenerationError,
    CodeGenerator,
)


class CodeGeneratorTests(unittest.TestCase):
    def test_codes_are_seven_base62_chars(self):
        gen = CodeGenerator()
        for _ in range(50):
            code = gen.new_code()
            self.assertEqual(len(code), CODE_LENGTH)
            self.assertTrue(all(ch in ALPHABET for ch in code))

    def test_forced_collision_yields_different_code(self):
        gen = CodeGenerator(retry_limit=5)
        seen = []

        def insert(code):
            seen.append(code)
            if len(seen) == 1:
                raise CodeCollision(code)
            return code

        result = gen.generate_for_insert(insert)
        self.assertEqual(len(seen), 2)
        self.assertNotEqual(seen[0], seen[1])
        self.assertEqual(result, seen[1])

    def test_retry_limit_exhaustion_raises(self):
        gen = CodeGenerator(retry_limit=3)
        calls = []

        def insert(code):
            calls.append(code)
            raise CodeCollision(code)

        with self.assertRaises(CodeGenerationError):
            gen.generate_for_insert(insert)
        self.assertEqual(len(calls), 3)


if __name__ == "__main__":
    unittest.main()
