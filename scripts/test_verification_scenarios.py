"""Keep named release phases synchronized with the instrumentation source set."""
import json
from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]
CONTRACTS = ROOT / "scripts/verification"
SOURCES = ROOT / "app/src/androidTest/java"
TEST_METHOD = re.compile(r"@Test\s+fun\s+(test\w+)\s*\(")


def methods(class_name):
    path = SOURCES / (class_name.replace(".", "/") + ".kt")
    return TEST_METHOD.findall(path.read_text())


class VerificationScenariosTest(unittest.TestCase):
    def assert_named_contract(self, class_name, expected):
        self.assertTrue(expected, "A required instrumentation class cannot have an empty contract")
        self.assertEqual(len(expected), len(set(expected)), "Duplicate contracted method")
        actual = methods(class_name)
        self.assertEqual(len(actual), len(set(actual)), "Duplicate instrumentation method")
        self.assertCountEqual(expected, actual, "Every named phase must exist and every test must run")

    def test_main_contract_covers_unique_wisp_and_muse_journeys(self):
        contract = json.loads((CONTRACTS / "scenarios.json").read_text())
        self.assert_named_contract(contract["class"], contract["tests"])
        numbers = [int(re.fullmatch(r"test(\d+)_\w+", name)[1]) for name in contract["tests"]]
        self.assertEqual(len(numbers), len(set(numbers)), "Journey numbers must remain distinct")
        self.assertTrue(set(range(1, 81)) | {90} <= set(numbers),
                        "The combined audio/Wisp/Muse journeys must all remain required")
        self.assertEqual(sorted(contract["tests"]), contract["tests"],
                         "The contract must match NAME_ASCENDING execution order")

    def test_new_tool_function_facades_and_launch_enum_keep_the_release_abi(self):
        rules = (ROOT / "app/proguard-rules.pro").read_text().splitlines()
        for owner in ("BackgroundLaunchKt", "BackgroundLaunchRoute", "ScreenControlKt",
                      "ScreenControlServiceKt"):
            with self.subTest(owner=owner):
                self.assertIn(f"-keep class com.battlesbudz.jarvis.v2.actions.{owner} {{ *; }}", rules)

    def test_layout_contract_covers_all_required_methods(self):
        contract = json.loads((CONTRACTS / "layout_scenarios.json").read_text())
        self.assert_named_contract(contract["class"], contract["tests"])

    def test_external_lifecycle_contract_covers_all_required_methods(self):
        contract = json.loads((CONTRACTS / "lifecycle_scenarios.json").read_text())
        self.assert_named_contract(contract["class"],
                                   [phase["test"] for phase in contract["phases"].values()])
        for phase in contract["upgrade"].values():
            self.assert_named_contract(phase["class"], [phase["test"]])


if __name__ == "__main__":
    unittest.main()
