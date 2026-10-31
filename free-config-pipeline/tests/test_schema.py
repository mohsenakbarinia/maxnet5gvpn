"""Unit tests for manifest schema validation."""

import json
from pathlib import Path
import unittest

from free_config_pipeline.manifest_schema_validator import validate_manifest_dict


class TestManifestSchema(unittest.TestCase):

    def setUp(self):
        sample_path = Path(__file__).parent.parent / "testdata" / "sample" / "manifest.v1.json"
        self.sample_manifest = json.loads(sample_path.read_text(encoding="utf-8"))

    def test_valid_manifest_passes(self):
        is_valid, err = validate_manifest_dict(self.sample_manifest)
        self.assertTrue(is_valid, f"Expected valid manifest, got error: {err}")

    def test_missing_required_field_fails(self):
        bad_manifest = dict(self.sample_manifest)
        del bad_manifest["keyId"]
        is_valid, err = validate_manifest_dict(bad_manifest)
        self.assertFalse(is_valid)
        self.assertIn("keyId", str(err))

    def test_invalid_schema_version_fails(self):
        bad_manifest = dict(self.sample_manifest)
        bad_manifest["schema"] = 2
        is_valid, err = validate_manifest_dict(bad_manifest)
        self.assertFalse(is_valid)

    def test_invalid_sequence_fails(self):
        bad_manifest = dict(self.sample_manifest)
        bad_manifest["sequence"] = 0
        is_valid, err = validate_manifest_dict(bad_manifest)
        self.assertFalse(is_valid)

    def test_oversized_server_list_fails(self):
        bad_manifest = dict(self.sample_manifest)
        srv_item = self.sample_manifest["servers"][0]
        bad_manifest["servers"] = [srv_item] * 301
        is_valid, err = validate_manifest_dict(bad_manifest)
        self.assertFalse(is_valid)
        self.assertIn("300", str(err))

    def test_invalid_server_score_fails(self):
        bad_manifest = json.loads(json.dumps(self.sample_manifest))
        bad_manifest["servers"][0]["stats"]["score"] = 150.0
        is_valid, err = validate_manifest_dict(bad_manifest)
        self.assertFalse(is_valid)


if __name__ == "__main__":
    unittest.main()
