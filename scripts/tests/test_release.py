import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("release", Path(__file__).resolve().parents[1] / "release.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseTest(unittest.TestCase):
    def test_version_order_and_bounds(self):
        versions = ["1.0.1-rc.1", "1.0.1-rc.98", "1.0.1", "1.0.2-rc.1", "1.0.2", "1.1.0", "2.0.0"]
        codes = list(map(release.version_code, versions))
        self.assertTrue(all(a < b for a, b in zip(codes, codes[1:])))
        for invalid in ["01.0.0", "1.0", "2100.0.0", "1.100.0", "1.0.0-rc.99"]:
            with self.assertRaises(ValueError):
                release.version_code(invalid)

    def test_release_preparation_increments_versions_and_validates_app_copy(self):
        self.assertEqual("1.0.2", release.next_version("1.0.1", "patch"))
        self.assertEqual("1.1.0", release.next_version("1.0.1", "minor"))
        self.assertEqual("2.0.0", release.next_version("1.0.1", "major"))
        self.assertEqual("1.0.1-rc.2", release.next_version("1.0.1-rc.1", "rc"))
        self.assertEqual("1.0.1", release.next_version("1.0.1-rc.1", "patch"))
        self.assertEqual("1.0.2-rc.1", release.next_version("1.0.1", "rc"))
        self.assertGreater(release.version_code("1.0.2-rc.1"), release.version_code("1.0.1"))
        self.assertEqual("改进照片处理", release.validate_notes(" 改进照片处理 "))
        self.assertEqual(("1.0.2", "改进照片处理"),
                         release.prepare_version("1.0.1", "patch", " 改进照片处理 "))
        self.assertEqual(("1.0.2", "改进照片处理"),
                         release.validate_prepared_version("1.0.1", "1.0.2", " 改进照片处理 "))
        with self.assertRaises(ValueError):
            release.validate_prepared_version("1.0.1", "1.0.1", "改进照片处理")
        self.assertEqual(("1.0.1", "修订后的说明"),
                         release.validate_prepared_version(
                             "1.0.1", "1.0.1", "修订后的说明", allow_current=True))
        for notes in ["", "修复题目编号", "改进解答", "x" * 8001]:
            with self.assertRaises(ValueError):
                release.validate_notes(notes)

    def test_published_versions_cannot_be_reused_or_downgraded(self):
        with patch.object(release, "current_version", return_value="1.0.1"):
            for tag, old in [("v1.0.0", []), ("v1.0.1", ["v1.0.1"]), ("v1.0.1", ["v1.0.2"])]:
                with self.assertRaises(ValueError):
                    release.validate(tag, old)
            self.assertEqual(("1.0.1", 1000199), release.validate("v1.0.1", ["v1.0.1-rc.1"]))
            with self.assertRaises(ValueError):
                release.validate("v1.0.1", [], latest_stable="v1.0.1")
            self.assertEqual(("1.0.1", 1000199),
                             release.validate("v1.0.1", ["v1.0.1-rc.1"], latest_stable="v1.0.1-rc.1"))

    def test_assets_bind_installed_identity_to_exact_download(self):
        with tempfile.TemporaryDirectory() as temp, patch.object(release, "current_version", return_value="1.0.1"):
            root = Path(temp)
            apk, metadata, notes = root / "input.apk", root / "metadata.json", root / "notes.md"
            apk.write_bytes(b"test fixture APK bytes")
            metadata.write_text(json.dumps({"applicationId": "com.nic.photoanswer", "elements": [{"versionName": "1.0.1", "versionCode": 1000199}]}))
            notes.write_text("改进记录加载")
            release.package("v1.0.1", "macanine/camera", apk, metadata, notes, root / "out",
                            ":".join(["ab"] + ["cd"] * 31))
            result = json.loads((root / "out/update.json").read_text())
            self.assertEqual(release.hashlib.sha256(apk.read_bytes()).hexdigest(), result["apk"]["sha256"])
            self.assertEqual("ab" + "cd" * 31, result["signingCertificateSha256"])
            self.assertEqual("https://github.com/macanine/camera/releases/download/v1.0.1/camera-v1.0.1.apk", result["apk"]["url"])
            self.assertEqual(apk.stat().st_size, result["apk"]["size"])


if __name__ == "__main__":
    unittest.main()
