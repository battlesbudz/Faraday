"""Unit tests for scripts/select_unit_tests.py (per-package test selection).

Runs under `python3 scripts/dev.py check` (discover over scripts/), so the
selector's mapping and safety rules are themselves CI-tested. Builds a tiny
synthetic checkout in a temp dir; no Android SDK or network involved.
"""
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import select_unit_tests as sel

MAIN = "app/src/main/java/com/battlesbudz/jarvis/v2"
TEST = "app/src/test/java/com/battlesbudz/jarvis/v2"


def make_repo(root):
    root = Path(root)
    files = {
        f"{MAIN}/vision/PhoneVisionClient.kt": "package v\n",
        f"{MAIN}/ui/AssistantStatusBanner.kt": "package u\n",
        f"{MAIN}/assistant/JarvisInteractionService.kt": "package a\n",  # no tests
        f"{MAIN}/JarvisRuntime.kt": "package r\n",                       # root package
        f"{TEST}/vision/PhoneVisionClientTest.kt": "class PhoneVisionClientTest\n",
        f"{TEST}/vision/nested/DeepTest.kt": "class DeepTest\n",
        f"{TEST}/ui/BannerTest.kt": "class BannerTest\n",
        f"{TEST}/PrivateChatSecureFlagsTest.kt": "class PrivateChatSecureFlagsTest\n",
        "app/build.gradle.kts": "plugins {}\n",
        "build.gradle.kts": "plugins {}\n",
        "settings.gradle.kts": "rootProject.name = \"x\"\n",
        "gradle.properties": "org.gradle.caching=true\n",
        "scripts/verification/acoustic_scenarios.json": json.dumps({
            "required_jvm_tests": [
                {"classname": "com.battlesbudz.jarvis.v2.vision.PhoneVisionClientTest",
                 "name": "someMethod"},
            ]}),
    }
    for rel, text in files.items():
        path = root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
    return sel.Repo(root)


class SelectUnitTestsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = make_repo(self.temp.name)

    def decide(self, *paths):
        return sel.decide(list(paths), self.repo)

    def test_main_package_change_selects_that_package(self):
        decision = self.decide(f"{MAIN}/vision/PhoneVisionClient.kt")
        self.assertEqual("selected", decision["mode"])
        self.assertEqual({"vision"}, decision["packages"])

    def test_test_package_change_selects_its_own_package(self):
        decision = self.decide(f"{TEST}/ui/BannerTest.kt")
        self.assertEqual("selected", decision["mode"])
        self.assertEqual({"ui"}, decision["packages"])

    def test_two_packages_both_selected(self):
        decision = self.decide(f"{MAIN}/vision/PhoneVisionClient.kt",
                               f"{TEST}/ui/BannerTest.kt")
        self.assertEqual({"vision", "ui"}, decision["packages"])

    def test_root_v2_file_forces_full(self):
        self.assertEqual("full", self.decide(f"{MAIN}/JarvisRuntime.kt")["mode"])
        self.assertEqual("full", self.decide(f"{TEST}/PrivateChatSecureFlagsTest.kt")["mode"])

    def test_build_and_ci_files_force_full(self):
        for path in ("app/build.gradle.kts", "build.gradle.kts", "settings.gradle.kts",
                     "gradle.properties", "gradle/wrapper/gradle-wrapper.properties",
                     ".github/workflows/android.yml", "scripts/select_unit_tests.py",
                     "scripts/ci_gradle.sh", "app/src/main/AndroidManifest.xml",
                     "scripts/verification/acoustic_scenarios.json"):
            self.assertEqual("full", self.decide(path)["mode"], path)

    def test_untested_package_forces_full(self):
        decision = self.decide(f"{MAIN}/assistant/JarvisInteractionService.kt")
        self.assertEqual("full", decision["mode"])

    def test_unclassifiable_app_paths_force_full(self):
        for path in ("app/src/main/res/values/strings.xml",
                     "app/src/main/cpp/whisperqnn/decoder.cpp",
                     "app/src/androidTest/java/com/battlesbudz/jarvis/v2/Journey.kt",
                     "app/src/test/resources/recorded-audio/manifest.json",
                     "phoneinference/src/main/java/com/battlesbudz/phone/Vision.kt"):
            self.assertEqual("full", self.decide(path)["mode"], path)

    def test_docs_and_other_lanes_are_ignored(self):
        decision = self.decide("README.md", "docs/notes.md",
                               "scripts/check_page_sizes.py",
                               ".github/workflows/android-sandbox.yml")
        self.assertEqual("none", decision["mode"])

    def test_selected_filters_include_package_and_required_classes(self):
        decision = self.decide(f"{MAIN}/ui/AssistantStatusBanner.kt")
        args = sel.build_filters(decision, self.repo)
        self.assertIn("com.battlesbudz.jarvis.v2.ui.*", args)
        self.assertIn("com.battlesbudz.jarvis.v2.vision.PhoneVisionClientTest", args)

    def test_none_mode_runs_smoke_and_required_only(self):
        decision = self.decide("README.md")
        args = sel.build_filters(decision, self.repo)
        self.assertIn("com.battlesbudz.jarvis.v2.PrivateChatSecureFlagsTest", args)
        self.assertIn("com.battlesbudz.jarvis.v2.vision.PhoneVisionClientTest", args)
        self.assertNotIn("com.battlesbudz.jarvis.v2.ui.*", args)

    def test_full_mode_has_no_filters(self):
        decision = self.decide("app/build.gradle.kts")
        self.assertEqual([], sel.build_filters(decision, self.repo))

    def test_package_hash_changes_with_content(self):
        vision_before = sel.package_hash(self.repo, "vision")
        ui_before = sel.package_hash(self.repo, "ui")
        target = Path(self.temp.name) / MAIN / "vision/PhoneVisionClient.kt"
        target.write_text("package v // changed\n")
        self.assertNotEqual(vision_before, sel.package_hash(self.repo, "vision"))
        self.assertEqual(ui_before, sel.package_hash(self.repo, "ui"))


if __name__ == "__main__":
    unittest.main()
