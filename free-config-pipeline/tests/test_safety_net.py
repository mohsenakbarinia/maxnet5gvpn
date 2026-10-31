"""Unit tests for the safety net rules preventing collapsed server pools from publishing."""

import unittest

from free_config_pipeline.config import FreePipelineConfig
from free_config_pipeline.manifest import check_safety_net, SafetyNetViolationError


class TestSafetyNet(unittest.TestCase):

    def setUp(self):
        self.config = FreePipelineConfig(min_servers=20, drop_threshold_percent=70.0)

    def test_fails_below_min_servers(self):
        with self.assertRaises(SafetyNetViolationError) as cm:
            check_safety_net(current_count=15, previous_count=None, config=self.config)
        self.assertIn("MIN_SERVERS", str(cm.exception))

    def test_fails_on_excessive_drop(self):
        # 100 -> 25 is a 75% drop, exceeding 70% threshold
        with self.assertRaises(SafetyNetViolationError) as cm:
            check_safety_net(current_count=25, previous_count=100, config=self.config)
        self.assertIn("dropped by 75.0%", str(cm.exception))

    def test_passes_when_healthy(self):
        # 100 -> 50 is a 50% drop, within 70% threshold and >= 20 servers
        try:
            check_safety_net(current_count=50, previous_count=100, config=self.config)
        except SafetyNetViolationError:
            self.fail("check_safety_net raised SafetyNetViolationError unexpectedly on healthy count")


if __name__ == "__main__":
    unittest.main()
