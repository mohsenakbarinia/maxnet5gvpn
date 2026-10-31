"""Test runner discovering and executing all test suites under tests/."""

from pathlib import Path
import sys
import unittest

pkg_root = Path(__file__).resolve().parent
src_dir = pkg_root / "src"
tests_dir = pkg_root / "tests"

if str(src_dir) not in sys.path:
    sys.path.insert(0, str(src_dir))
if str(pkg_root) not in sys.path:
    sys.path.insert(0, str(pkg_root))

if __name__ == "__main__":
    loader = unittest.TestLoader()
    suite = loader.discover(start_dir=str(tests_dir), pattern="test_*.py")
    runner = unittest.TextTestRunner(verbosity=2)
    result = runner.run(suite)
    sys.exit(0 if result.wasSuccessful() else 1)
