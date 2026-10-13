"""Synthetic nonzero tensors test the declared contract, never model quality."""
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest import mock

import prepare_inputs
from common import ENCODER_ACCEPTANCE_CONTRACT, GateError
from export_evidence import validate_json
from test_diagnostic_pair import fixtures, emit, SYNTHETIC_ROWS_SHA


class EncoderReceiptTest(unittest.TestCase):
    def setUp(self):
        temporary=tempfile.TemporaryDirectory(); self.addCleanup(temporary.cleanup)
        self.root=Path(temporary.name); self.receipt=self.root/'oracle.json'
        self.stages,payloads=fixtures(self.root)
        for stage,cases in self.stages.items(): emit(self.root,stage,cases,payloads[stage])

    def compare(self):
        return prepare_inputs.compare_encoder(self.root,self.stages,self.receipt)

    def output(self,stage,case,label):
        return self.root/'actual'/stage/f'pinned-encoder-{case}.out.{label}.bin'

    def test_unknown_historical_fingerprint_passes_only_same_host_equivalence(self):
        result=self.compare()
        self.assertTrue(result['passed'])
        self.assertEqual(result['acceptance_contract'],ENCODER_ACCEPTANCE_CONTRACT)
        self.assertTrue(result['post_adapter_valid_rows_bitwise'])
        self.assertTrue(result['eoa_bitwise'])
        self.assertFalse(result['complete_reference_hash_match'])
        self.assertEqual(result['historical_reference_role'],'fingerprint_diagnostic_only')
        self.assertEqual(result['projected_rows'],{'bytes':473088,'sha256':SYNTHETIC_ROWS_SHA})
        self.assertEqual(result['state_output_checks']['emitted_output_count'],131)
        self.assertEqual(result['state_output_count'],98)
        self.assertFalse(result['cache_state_all_layers_checked'])
        self.assertFalse(result['state_output_checks']['cache_state_reference_equivalence_proven'])
        self.assertEqual(result,json.loads(self.receipt.read_text()))
        validate_json(result)

    def test_historical_match_is_factual_and_does_not_change_acceptance(self):
        with mock.patch.object(prepare_inputs,'ROWS_SHA',SYNTHETIC_ROWS_SHA): result=self.compare()
        self.assertTrue(result['passed']); self.assertTrue(result['complete_reference_hash_match'])

    def test_last_scalar_and_both_sides_of_every_chunk_seam_compare_exactly(self):
        # One float32 ULP at a time; no tolerance or prefix-only check.
        scalars={77*1536-1}
        for row in (12,24,36,48,60,72): scalars.update((row*1536-1,row*1536))
        path=self.output('adapter','000','soft_tokens'); original=path.read_bytes()
        for scalar in sorted(scalars):
            with self.subTest(scalar=scalar):
                changed=bytearray(original); bits=struct.unpack_from('<I',changed,scalar*4)[0]
                struct.pack_into('<I',changed,scalar*4,bits+1); path.write_bytes(changed)
                with self.assertRaisesRegex(GateError,'Streamed/static post-adapter rows differ'): self.compare()
                result=json.loads(self.receipt.read_text())
                self.assertFalse(result['post_adapter_valid_rows_bitwise'])
                self.assertIsNone(result['complete_reference_hash_match'])
                self.assertNotIn('projected_rows',result)
        path.write_bytes(original)

    def test_chunk_reordering_dropping_or_duplication_fails(self):
        path=self.output('stateful','001','soft_tokens'); original=path.read_bytes(); width=1536*4
        mutations=[self.output('stateful','000','soft_tokens').read_bytes(),
                   original[width:]+original[:width], original[:width]+original[:-width]]
        for data in mutations:
            path.write_bytes(data)
            with self.assertRaisesRegex(GateError,'Streamed/static post-adapter rows differ'): self.compare()
        path.write_bytes(original)

    def test_padded_rows_are_outside_observable_equivalence_but_must_be_finite(self):
        for stage,case,valid in (('stateful','006',5),('adapter','000',77)):
            path=self.output(stage,case,'soft_tokens'); original=path.read_bytes()
            changed=bytearray(original); struct.pack_into('<f',changed,valid*1536*4,123.5)
            path.write_bytes(changed); self.assertTrue(self.compare()['passed'])
            struct.pack_into('<f',changed,valid*1536*4,float('nan')); path.write_bytes(changed)
            with self.assertRaisesRegex(GateError,'Nonfinite emitted encoder output'): self.compare()
            path.write_bytes(original)

    def test_masks_counts_eoa_state_and_inventory_are_required(self):
        changes=[('stateful','006','token_count',struct.pack('<i',4)),
            ('stateful','006','token_mask',b'\1'*4+b'\0'*8),
            ('static','000','mask',b'\1'*76+b'\0'*128),
            ('eoa','000','eoa_embedding',bytes(1536*4)),
            ('stateful','006','next_history_tokens',struct.pack('<i',23)),
            ('stateful','006','next_mel',bytes(2048)),
            ('stateful','006','next_layer_11',bytes(24*1024*4))]
        for stage,case,label,data in changes:
            path=self.output(stage,case,label); original=path.read_bytes(); path.write_bytes(data)
            with self.subTest(label=label),self.assertRaises(GateError): self.compare()
            self.assertFalse(json.loads(self.receipt.read_text())['passed']); path.write_bytes(original)
        path=self.root/'actual/stateful/pinned-encoder-run.tsv'
        path.write_text(path.read_text().replace('OUTPUT\t000\tnext_layer_11','OUTPUT\t000\tnext_layer_10'))
        with self.assertRaisesRegex(GateError,'inventory'): self.compare()


if __name__ == '__main__': unittest.main()
