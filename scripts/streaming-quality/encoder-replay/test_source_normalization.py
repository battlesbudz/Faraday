from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from trace_compiler_inputs import source_record


class InRootParentPaths(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.execroot = self.root/'base/execroot/sdk'; self.execroot.mkdir(parents=True)
        self.sdk = self.root/'sdk'; self.sdk.mkdir()
        self.external = self.root/'base/external'; self.external.mkdir()
        (self.execroot/'external').mkdir()
        self.repo = self.external/'gemmlowp'; self.repo.mkdir()
        (self.repo/'public').mkdir(); (self.repo/'internal').mkdir()
        (self.repo/'internal/header.h').write_text('public header')
        (self.execroot/'external/gemmlowp').symlink_to(self.repo, target_is_directory=True)

    def test_relative_parent_within_named_repository_normalizes(self):
        value = source_record('external/gemmlowp/public/../internal/header.h', self.execroot, self.sdk)
        self.assertEqual('external/gemmlowp/internal/header.h', value['path'])
        self.assertEqual('gemmlowp', value['origin'])

    def test_absolute_repository_and_execroot_spellings_share_identity(self):
        expected = source_record('external/gemmlowp/internal/header.h', self.execroot, self.sdk)
        for name in [self.repo/'public/../internal/header.h',
                     self.execroot/'external/gemmlowp/public/../internal/header.h']:
            self.assertEqual(expected, source_record(name, self.execroot, self.sdk))

    def test_sdk_relative_parent_stays_within_owned_source(self):
        (self.sdk/'runtime').mkdir(); (self.sdk/'support').mkdir()
        (self.sdk/'support/header.h').write_text('public SDK header')
        (self.execroot/'runtime').symlink_to(self.sdk/'runtime', target_is_directory=True)
        result = source_record('runtime/../support/header.h', self.execroot, self.sdk)
        self.assertEqual('support/header.h', result['path'])

    def test_parent_cannot_switch_named_repository(self):
        other = self.external/'other'; other.mkdir(); (other/'header.h').write_text('other')
        with patch('trace_compiler_inputs.describe') as read:
            with self.assertRaisesRegex(ValueError, 'escaped'):
                source_record('external/gemmlowp/../other/header.h', self.execroot, self.sdk)
            read.assert_not_called()

    def test_absolute_parent_cannot_switch_named_repository(self):
        other = self.external/'other'; other.mkdir(); (other/'header.h').write_text('other')
        for name in [self.repo/'../other/header.h',
                     self.execroot/'external/gemmlowp/../other/header.h']:
            with self.subTest(name=name), patch('trace_compiler_inputs.describe') as read:
                with self.assertRaisesRegex(ValueError, 'escaped'):
                    source_record(name, self.execroot, self.sdk)
                read.assert_not_called()

    def test_absolute_owned_source_parent_cannot_resolve_to_private_file(self):
        private = self.root/'private.h'; private.write_text('private')
        for name in [self.sdk/'../private.h', self.execroot/'../../../private.h']:
            with self.subTest(name=name), patch('trace_compiler_inputs.describe') as read:
                with self.assertRaisesRegex(ValueError, 'outside reviewed'):
                    source_record(name, self.execroot, self.sdk)
                read.assert_not_called()

    def test_untrusted_relative_parent_and_unknown_absolute_path_are_rejected(self):
        for name in ['../private/header.h', self.root/'unknown/header.h']:
            with patch('trace_compiler_inputs.describe') as read, self.assertRaises(ValueError):
                source_record(name, self.execroot, self.sdk)
            read.assert_not_called()

    def test_repository_symlink_parent_escape_is_rejected_before_content_read(self):
        outside = self.root/'private'; outside.mkdir(); (outside/'header.h').write_text('private')
        (self.repo/'escape').symlink_to(outside, target_is_directory=True)
        with patch('trace_compiler_inputs.describe') as read:
            with self.assertRaisesRegex(ValueError, 'escaped'):
                source_record('external/gemmlowp/escape/header.h', self.execroot, self.sdk)
            read.assert_not_called()

    def test_existing_system_parent_spelling_is_canonicalized(self):
        source = Path('/usr/include/../include/stdio.h')
        self.assertTrue(source.is_file(), 'Linux compiler fixture requires public stdio.h')
        result = source_record(source, self.execroot, self.sdk)
        self.assertEqual('system_headers', result['origin'])
        self.assertEqual(str(source.resolve()), result['path'])

    def test_system_parent_cannot_escape_to_a_private_source(self):
        private = self.root/'private.h'; private.write_text('private')
        spelling = Path('/usr/..')/str(private).lstrip('/')
        with patch('trace_compiler_inputs.describe') as read:
            with self.assertRaisesRegex(ValueError, 'System source escaped'):
                source_record(spelling, self.execroot, self.sdk)
            read.assert_not_called()

    def test_relative_repository_name_cannot_be_parent(self):
        with self.assertRaises(ValueError):
            source_record('external/../anything/header.h', self.execroot, self.sdk)

    def test_known_virtual_include_resolves_only_its_named_repository(self):
        name = 'bazel-out/k8-opt/bin/external/gemmlowp/_virtual_includes/gemmlowp/header.h'
        link = self.execroot/name; link.parent.mkdir(parents=True)
        link.symlink_to(self.repo/'internal/header.h')
        expected = source_record('external/gemmlowp/internal/header.h', self.execroot, self.sdk)
        self.assertEqual(expected, source_record(name, self.execroot, self.sdk))
        self.assertEqual(expected, source_record(link, self.execroot, self.sdk))

    def test_virtual_include_cannot_switch_repository_or_target_private_file(self):
        other = self.external/'other'; other.mkdir(); (other/'header.h').write_text('other')
        private = self.root/'private.h'; private.write_text('private')
        for index, destination in enumerate([other/'header.h', private]):
            name = f'bazel-out/k8-opt/bin/external/gemmlowp/_virtual_includes/gemmlowp/header{index}.h'
            link = self.execroot/name; link.parent.mkdir(parents=True, exist_ok=True); link.symlink_to(destination)
            with patch('trace_compiler_inputs.describe') as read:
                with self.assertRaisesRegex(ValueError, 'escaped'):
                    source_record(name, self.execroot, self.sdk)
                read.assert_not_called()

    def test_virtual_include_in_nested_bazel_package_keeps_repository(self):
        name = 'bazel-out/k8-opt/bin/external/gemmlowp/src/nested/_virtual_includes/public/header.h'
        link = self.execroot/name; link.parent.mkdir(parents=True)
        link.symlink_to(self.repo/'internal/header.h')
        result = source_record(name, self.execroot, self.sdk)
        self.assertEqual('external/gemmlowp/internal/header.h', result['path'])
        self.assertEqual('gemmlowp', result['origin'])

    def test_virtual_include_can_reference_generated_header_in_same_repository(self):
        generated = self.execroot/'bazel-out/host-opt/bin/external/gemmlowp/generated/header.h'
        generated.parent.mkdir(parents=True); generated.write_text('generated public header')
        name = 'bazel-out/k8-opt/bin/external/gemmlowp/_virtual_includes/public/header.h'
        link = self.execroot/name; link.parent.mkdir(parents=True); link.symlink_to(generated)
        result = source_record(name, self.execroot, self.sdk)
        self.assertEqual(str(generated.relative_to(self.execroot)), result['path'])
        self.assertEqual('gemmlowp', result['origin'])

    def test_virtual_include_cannot_reference_generated_header_in_other_repository(self):
        generated = self.execroot/'bazel-out/k8-opt/bin/external/other/generated/header.h'
        generated.parent.mkdir(parents=True); generated.write_text('other repository')
        name = 'bazel-out/k8-opt/bin/external/gemmlowp/_virtual_includes/public/header.h'
        link = self.execroot/name; link.parent.mkdir(parents=True); link.symlink_to(generated)
        with patch('trace_compiler_inputs.describe') as read:
            with self.assertRaisesRegex(ValueError, 'escaped'):
                source_record(name, self.execroot, self.sdk)
            read.assert_not_called()


if __name__ == '__main__': unittest.main()
