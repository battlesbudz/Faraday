"""Dependency guard failure cases, independently exercised on small source fixtures."""
from pathlib import Path
import tempfile
import unittest

import check_architecture as architecture


class ArchitectureBoundaryTest(unittest.TestCase):
    def violations(self, files):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for name, source in files.items():
                path = root / architecture.SOURCE / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(source, encoding="utf-8")
            return architecture.find_violations(root)

    def test_entry_points_can_compose_runtime_and_launch_activity(self):
        self.assertEqual([], self.violations({
            "JarvisRuntime.kt": "internal class JarvisRuntime",
            "MainActivity.kt": "class MainActivity { val runtime = JarvisRuntime.get(context) }",
            "JarvisAppComposition.kt": "val store = JarvisRuntime.get(context).store",
            "voice/VoiceCallService.kt": "val runtime = JarvisRuntime.get(context); MainActivity::class.java",
            "assistant/JarvisInteractionSessionService.kt": "MainActivity::class.java",
        }))

    def test_video_notification_entry_point_cannot_compose_runtime(self):
        self.assertEqual([], self.violations({
            "voice/VideoCallService.kt": "MainActivity::class.java",
        }))
        self.assertTrue(self.violations({
            "voice/VideoCallService.kt": "JarvisRuntime.get(context)",
        }))
        self.assertTrue(self.violations({
            "voice/CameraXVideoBinder.kt": "MainActivity::class.java",
        }))

    def test_workflow_receiver_is_a_narrow_runtime_composition_entry_point(self):
        source = "import com.battlesbudz.jarvis.v2.JarvisRuntime\nJarvisRuntime.get(context)"
        self.assertEqual([], self.violations({"actions/WorkflowScheduleReceiver.kt": source}))
        for name in ("actions/WorkflowAlarmScheduler.kt", "actions/WorkflowEngine.kt",
                     "actions/OrdinaryAction.kt", "actions/nested/WorkflowScheduleReceiver.kt"):
            with self.subTest(name=name):
                self.assertTrue(self.violations({name: source}))
        # A receiver's runtime admission does not grant activity-launch authority.
        self.assertTrue(self.violations({
            "actions/WorkflowScheduleReceiver.kt": "MainActivity::class.java",
        }))

    def test_feature_import_qualified_access_and_extension_are_rejected(self):
        for source in (
            "import com.battlesbudz.jarvis.v2.JarvisRuntime as Runtime\nval runtime = Runtime.get(context)",
            "val runtime = com.battlesbudz.jarvis.v2.JarvisRuntime.get(context)",
            "internal fun JarvisRuntime.runTurn() = Unit",
            "import com.battlesbudz.jarvis.v2.*\nval runtime: JarvisRuntime",
        ):
            with self.subTest(source=source):
                self.assertTrue(self.violations({"conversation/Turn.kt": source}))
        self.assertTrue(self.violations({"ui/Screen.kt": "MainActivity::class.java"}))

    def test_strings_and_nested_comments_are_ignored_with_line_numbers_retained(self):
        source = ('// JarvisRuntime\n/* outer /* MainActivity */ JarvisRuntime */\n'
                  'val label = "JarvisRuntime \\\" example"\n'
                  'val raw = """MainActivity\nJarvisRuntime"""\n'
                  "val letter = 'x'\nval actual: JarvisRuntime\n")
        violations = self.violations({"voice/Stage.kt": source})
        self.assertEqual(1, len(violations))
        self.assertEqual(7, violations[0][1])

    def test_storage_cannot_read_conversation_implementation_admission(self):
        for source in (
            "import com.battlesbudz.jarvis.v2.conversation.ConversationWork as Work",
            "val busy = com.battlesbudz.jarvis.v2.conversation.ConversationWork.activeJobs.get()",
        ):
            with self.subTest(source=source):
                self.assertTrue(self.violations({"ai/ModelStore.kt": source}))
        self.assertEqual([], self.violations({
            "ai/ModelStore.kt": "import com.battlesbudz.jarvis.v2.work.ProcessConversationAdmission\n"
                                 "val busy = ProcessConversationAdmission.isActive()",
        }))

    def test_interpolation_keeps_executable_expressions_and_ignores_literal_text(self):
        for source in (
            'val label = "${com.battlesbudz.jarvis.v2.JarvisRuntime.get(context)}"',
            'val label = """text ${com.battlesbudz.jarvis.v2.JarvisRuntime.get(context)}"""',
            'val label = "${run { /* } */ com.battlesbudz.jarvis.v2.JarvisRuntime.get(context) }}"',
        ):
            with self.subTest(source=source):
                self.assertTrue(self.violations({"ui/Example.kt": source}))
        self.assertEqual([], self.violations({
            "ui/Example.kt": 'val literal = "\\${JarvisRuntime}"\n'
                             'val interpolation = "${call("JarvisRuntime")}"',
            "ai/Example.java": 'String literal = "${JarvisRuntime}";',
        }))

    def test_missing_source_tree_does_not_report_success(self):
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaises(FileNotFoundError):
                architecture.find_violations(Path(temporary))


if __name__ == "__main__":
    unittest.main()
