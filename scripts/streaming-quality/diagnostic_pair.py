"""Same-invocation diagnostic continuation. Serialized receipts grant no authority."""
import math
from pathlib import Path
import re
import struct

from common import GateError, HERE, LITERT_PIN, ROWS_SHA, canonical, describe, digest, load, need

KNOWN_ROWS_SHA = '33bf34b9953094d2b897b2422091cbae089beda0453709d1af16b18a98478e0e'
FAILURE_CODE = 'historical_projected_reference_mismatch'
PREREQUISITES = ('reassembly', 'frontend', 'encoder-stateful', 'encoder-static', 'encoder-adapter', 'encoder-eoa')
ERROR_CLASSES = {'identity_failure', 'evidence_failure', 'numerical_failure', 'model_cleanup_failure',
                 'model_supervision_failure', 'resource_constrained', 'native_execution_failure',
                 'orchestration_failure', 'numerical_or_output_parity_failure', 'semantic_reference_mismatch'}


def fingerprint(path):
    path = Path(path)
    need(path.is_file() and not path.is_symlink(), 'Diagnostic file is missing or unsafe', 'evidence_failure')
    before = path.stat()
    value = describe(path)
    after = path.stat()
    stamp = lambda s: (s.st_dev, s.st_ino, s.st_size, s.st_mtime_ns, s.st_ctime_ns)
    need(stamp(before) == stamp(after), 'Diagnostic file changed while reading', 'evidence_failure')
    return (stamp(after), value)


class HistoricalReferenceMismatch(GateError):
    """Only the exact known final hash mismatch has this structured identity."""
    def __init__(self):
        super().__init__('numerical_failure', 'Complete pinned projected-row hash mismatch')
        self.code = FAILURE_CODE
        self.ticket = None
        self.receipt_fingerprint = None

    def bind_original(self, path, ticket):
        self.ticket = ticket
        self.receipt_fingerprint = fingerprint(path)


class DiagnosticSession:
    """Same-run immutable witnesses for normal and legacy paths; no resume API."""
    def __init__(self, work):
        self.work = Path(work).resolve()
        self.root_inode = (self.work.stat().st_dev, self.work.stat().st_ino)
        self.pins = {}
        self.completed = []
        self._ticket = object()
        self._issued = False
        self._used = False

    def watch(self, paths):
        for path in paths:
            path = Path(path)
            need(path.resolve().is_relative_to(self.work) and
                 all(not p.is_symlink() for p in (path, *path.parents) if p.is_relative_to(self.work)),
                 'Diagnostic file escaped its fresh run', 'evidence_failure')
            pin = fingerprint(path)
            if path in self.pins:
                need(self.pins[path] == pin, 'Same-run prerequisite changed', 'evidence_failure')
            self.pins[path] = pin

    def process_completed(self, name, process, artifacts=()):
        need(len(self.completed) < len(PREREQUISITES) and name == PREREQUISITES[len(self.completed)],
             'Diagnostic prerequisite order changed', 'evidence_failure')
        need(all(type(process.get(k)) is type(v) and process[k] == v for k, v in {
            'classification': 'passed', 'status': 'completed', 'exit_code': 0,
            'execution_started': True, 'cleanup_verified': True}.items()),
            'Diagnostic prerequisite did not execute and clean up', 'evidence_failure')
        receipt = self.work/name/'process.json'
        need(canonical(load(receipt)) == canonical(process), 'Live prerequisite receipt differs', 'evidence_failure')
        self.watch([receipt, *artifacts])
        self.completed.append(name)

    def comparison_ticket(self):
        need(tuple(self.completed) == PREREQUISITES and not self._issued,
             'Fresh complete prerequisites required', 'evidence_failure')
        self.verify_unchanged()
        self._issued = True
        return self._ticket

    def authorize(self, error):
        need(type(error) is HistoricalReferenceMismatch and error.ticket is self._ticket and
             self._issued and not self._used, 'No live diagnostic continuation ticket', 'evidence_failure')
        path = self.work/'encoder-oracle.json'
        need(error.receipt_fingerprint is not None and fingerprint(path) == error.receipt_fingerprint,
             'Original failed oracle was copied or edited', 'evidence_failure')
        receipt = load(path)
        need(all(receipt.get(k) is v for k, v in {
            'passed': False, 'post_adapter_valid_rows_bitwise': True, 'eoa_bitwise': True,
            'complete_reference_hash_match': False, 'cache_state_all_layers_checked': False,
            'complete_pinned_native_receipts': True, 'stateful_counts_and_masks_match': True,
            'static_prefix_mask_match': True}.items()) and
            receipt.get('classification') == 'numerical_failure' and receipt.get('failure_code') == FAILURE_CODE and
            receipt.get('projected_rows') == {'bytes': 473088, 'sha256': KNOWN_ROWS_SHA} and
            receipt.get('expected_projected_rows') == {'bytes': 473088, 'sha256': ROWS_SHA},
            'Failed oracle is not the known isolated reference mismatch', 'evidence_failure')
        self.watch([path, self.work/'inputs/projected.f32le'])
        need(describe(self.work/'inputs/projected.f32le') == receipt['projected_rows'],
             'Diagnostic rows differ from original failed oracle', 'identity_failure')
        self.verify_unchanged()
        self._used = True
        return receipt

    def verify_unchanged(self):
        need((self.work.stat().st_dev, self.work.stat().st_ino) == self.root_inode,
             'Diagnostic run directory replaced', 'evidence_failure')
        for path, pin in self.pins.items():
            need(path.resolve().is_relative_to(self.work) and
                 all(not p.is_symlink() for p in (path, *path.parents) if p.is_relative_to(self.work)),
                 'Diagnostic input path changed', 'evidence_failure')
            need(fingerprint(path) == pin, 'Same-run prerequisite changed', 'evidence_failure')

    def public_binding(self):
        return digest({str(path.relative_to(self.work)): pin[1] for path, pin in sorted(self.pins.items())})


def stage_artifacts(inputs, stage, cases):
    return [inputs/(stage+'.tsv'), inputs/'actual'/stage/'pinned-encoder-run.tsv',
            *[inputs/'actual'/stage/f'pinned-encoder-{case["id"]}.out.{spec["label"]}.bin'
              for case in cases for spec in case['outputs']]]


def validate_state_outputs(inputs, stages):
    """Fresh fixture/inventory plus value validity; never static-reference parity."""
    validate_fixture_contract(inputs, stages)
    for stage, cases in stages.items():
        lines = (inputs/'actual'/stage/'pinned-encoder-run.tsv').read_text().splitlines()
        cursor = 2
        need(lines[:2] == [f'PIN\t{LITERT_PIN}', 'CPU_THREADS\t1'],
             'Native receipt header changed', 'evidence_failure')
        for case in cases:
            output_rows = [f'OUTPUT\t{case["id"]}\t{s["label"]}\t{s["bytes"]}\tpinned-encoder-{case["id"]}.out.{s["label"]}.bin'
                           for s in case['outputs']]
            actual_rows = lines[cursor:cursor+len(output_rows)]
            need(len(set(actual_rows)) == len(output_rows) and set(actual_rows) == set(output_rows),
                 'Native output receipt inventory changed', 'evidence_failure')
            cursor += len(output_rows)
            fields = lines[cursor].split('\t') if cursor < len(lines) else []
            need(len(fields) == 4 and fields[:3] == ['CASE', case['id'], case['signature']],
                 'Native case receipt changed', 'evidence_failure')
            try: elapsed = float(fields[3])
            except ValueError: elapsed = float('nan')
            need(math.isfinite(elapsed) and elapsed >= 0, 'Invalid native elapsed receipt', 'evidence_failure')
            cursor += 1
        need(lines[cursor:] == [f'COMPLETE\t{len(cases)}'], 'Native receipt was incomplete', 'evidence_failure')
    return validate_state_values(inputs, stages)


def validate_state_values(inputs, stages):
    """Pure byte checks. Alone, these confer no freshness or continuation authority."""
    def output(stage, case, label):
        return (inputs/'actual'/stage/f'pinned-encoder-{case}.out.{label}.bin').read_bytes()
    total = 0
    for stage, cases in stages.items():
        for case in cases:
            for spec in case['outputs']:
                data = output(stage, case['id'], spec['label'])
                size = 1 if spec['dtype'] == 'bool' else 4
                need(spec['dtype'] in ('bool', 'int32', 'float32') and
                     math.prod(spec['shape']) * size == spec['bytes'] == len(data),
                     'Invalid emitted output dimensions or byte count', 'numerical_failure')
                if spec['dtype'] == 'float32':
                    need(all(math.isfinite(v[0]) for v in struct.iter_unpack('<f', data)),
                         'Nonfinite emitted encoder output', 'numerical_failure')
                elif spec['dtype'] == 'bool':
                    need(all(v in (0, 1) for v in data), 'Invalid emitted boolean', 'numerical_failure')
                total += 1
    history = 0
    previous = [bytes(24*1024*4) for _ in range(12)]
    for index, valid in enumerate([48]*6+[19]):
        case = f'{index:03}'; count = (valid+3)//4
        need(output('stateful', case, 'token_count') == struct.pack('<i', count),
             'Diagnostic state token count differs', 'numerical_failure')
        next_history = min(history+count, 24)
        need(output('stateful', case, 'next_history_tokens') == struct.pack('<i', next_history),
             'Diagnostic state history differs', 'numerical_failure')
        mel = (inputs/f'chunk-{index}.bin').read_bytes()
        need(len(mel) == 48*128*4 and output('stateful', case, 'next_mel') == mel[(valid-4)*128*4:valid*128*4],
             'Diagnostic Mel history differs', 'numerical_failure')
        start = max(history+count-24, 0); retained = history-start
        for layer in range(12):
            data = output('stateful', case, f'next_layer_{layer:02}')
            need(len(data) == 24*1024*4 and
                 data[:retained*1024*4] == previous[layer][start*1024*4:history*1024*4] and
                 data[next_history*1024*4:] == bytes((24-next_history)*1024*4),
                 'Diagnostic layer history transition differs', 'numerical_failure')
            previous[layer] = data
        history = next_history
    return {'all_emitted_outputs_valid': True, 'state_transition_invariants_valid': True,
            'emitted_output_count': total, 'cache_state_reference_equivalence_proven': False}


def validate_fixture_contract(inputs, stages):
    """Bind runtime schema/chain checks to the exact generated seven-chunk fixture."""
    models = load(HERE/'model-structure.json')
    expected = {}; cases = []
    step, eoa = models['stateful']['signatures']
    chain = {'history_tokens': 'next_history_tokens', 'prev_mel': 'next_mel',
             **{f'prev_layer_{i:02}': f'next_layer_{i:02}' for i in range(12)}}
    mel = (inputs/'mel.f32le').read_bytes()
    need(len(mel) == 307*128*4, 'Invalid complete Mel dimensions', 'numerical_failure')
    offset = 0
    for i, count in enumerate([48]*6+[19]):
        files = {name: 'initial-'+name+'.bin' for name in chain}
        files.update(mel=f'chunk-{i}.bin', valid_mel_frames=f'count-{i}.bin')
        cases.append(dict(id=f'{i:03}', signature=step['key'], state='CHAIN' if i else 'RESET',
            inputs=[dict(s, file=files[s['name']], chain=chain.get(s['name'], '-')) for s in step['inputs']],
            outputs=[dict(s, label=s['name']) for s in step['outputs']]))
        need((inputs/f'count-{i}.bin').read_bytes() == struct.pack('<i', count) and
             (inputs/f'chunk-{i}.bin').read_bytes() == mel[offset*128*4:(offset+count)*128*4]+bytes((48-count)*128*4),
             'Diagnostic Mel chunk or valid count changed', 'numerical_failure')
        offset += count
    for spec in step['inputs']:
        if spec['name'] in chain:
            need((inputs/('initial-'+spec['name']+'.bin')).read_bytes() == bytes(spec['bytes']),
                 'Diagnostic initial state changed', 'numerical_failure')
    need((inputs/'static-mel.bin').read_bytes() == mel+bytes((816-307)*128*4) and
         (inputs/'static-mask.bin').read_bytes() == b'\1'*307+b'\0'*(816-307),
         'Diagnostic static fixture changed', 'numerical_failure')
    cases.append(dict(id='eoa', signature=eoa['key'], state='RESET', inputs=[],
                      outputs=[dict(s, label=s['name']) for s in eoa['outputs']]))
    expected['stateful'] = cases
    for stage in ('static', 'adapter', 'eoa'):
        sig = models[stage]['signatures'][0]
        ins = [dict(s, file=(f'actual/static/pinned-encoder-000.out.{"mask" if s["dtype"] == "bool" else "features"}.bin'
                    if stage == 'adapter' else 'static-mask.bin' if s['dtype'] == 'bool' else 'static-mel.bin'), chain='-')
               for s in sig['inputs']]
        expected[stage] = [dict(id='000', signature=sig['key'], state='RESET', inputs=ins,
            outputs=[dict(s, label='soft_tokens' if stage == 'adapter' else 'eoa_embedding' if stage == 'eoa' else s['name'])
                     for s in sig['outputs']])]
    need(stages == expected, 'Diagnostic schema or chain mapping changed', 'evidence_failure')
    for stage, cases in stages.items():
        lines = ['PINNED_ENCODER_FIXTURES_V1']
        for case in cases:
            lines.append(f'CASE {case["id"]} {case["signature"]} {case["state"]}')
            for kind in ('inputs', 'outputs'):
                for spec in case[kind]:
                    fields = [kind[:-1].upper(), str(spec['index']), spec['name'], spec['dtype'],
                              str(len(spec['shape'])), *map(str, spec['shape']), str(spec['bytes'])]
                    fields += [spec['file'], spec['chain']] if kind == 'inputs' else [spec['label']]
                    lines.append(' '.join(fields))
            lines.append('END')
        need((inputs/(stage+'.tsv')).read_text() == '\n'.join(lines)+'\n',
             'Executed fixture manifest differs from verified schema', 'evidence_failure')


def public_receipt(value):
    """Exact bounded public metadata; no arbitrary strings, paths or native outputs."""
    from common import canonical
    keys = {'schema_version', 'purpose', 'strict_gate_passed', 'strict_exit_code', 'strict_failure_code',
            'strict_oracle_sha256', 'prerequisite_binding_sha256', 'actual_rows_sha256', 'historical_rows_sha256',
            'input_identity_sha256', 'resource_profile', 'state', 'failure_class', 'checks', 'lanes',
            'native_pair_passed', 'documentation_example_match', 'cache_state_reference_equivalence_proven'}
    need(type(value) is dict and set(value) == keys and len(canonical(value)) <= 8192,
         'Invalid diagnostic receipt schema', 'evidence_failure')
    constants = {'schema_version': 1, 'purpose': 'historical_reference_mismatch_diagnostic_only',
                 'strict_gate_passed': False, 'strict_exit_code': 2, 'strict_failure_code': FAILURE_CODE,
                 'actual_rows_sha256': KNOWN_ROWS_SHA, 'historical_rows_sha256': ROWS_SHA,
                 'resource_profile': 'hosted_full_e2b_context640_control',
                 'cache_state_reference_equivalence_proven': False}
    need(all(type(value.get(k)) is type(v) and value[k] == v for k, v in constants.items()),
         'Diagnostic receipt changed strict contract', 'evidence_failure')
    for key in ('strict_oracle_sha256', 'prerequisite_binding_sha256', 'input_identity_sha256'):
        need(value[key] is None or (type(value[key]) is str and re.fullmatch('[0-9a-f]{64}', value[key])),
             'Invalid diagnostic digest', 'evidence_failure')
    need(value['state'] in ('blocked', 'running', 'completed') and
         (value['failure_class'] is None or value['failure_class'] in ERROR_CLASSES),
         'Invalid diagnostic result state', 'evidence_failure')
    for key in ('native_pair_passed', 'documentation_example_match'):
        need(value[key] is None or type(value[key]) is bool, 'Invalid diagnostic check', 'evidence_failure')
    need(type(value['checks']) is dict and set(value['checks']) <= {
        'all_emitted_outputs_valid', 'state_transition_invariants_valid', 'emitted_output_count',
        'cache_state_reference_equivalence_proven'}, 'Unexpected diagnostic checks', 'evidence_failure')
    for key, child in value['checks'].items():
        need((type(child) is int and 0 < child <= 256) if key == 'emitted_output_count' else type(child) is bool,
             'Invalid diagnostic check value', 'evidence_failure')
    need(value['checks'].get('cache_state_reference_equivalence_proven', False) is False,
         'Unsupported state equivalence claim', 'evidence_failure')
    need(type(value['lanes']) is dict and set(value['lanes']) <= {'projected_null', 'raw'},
         'Unexpected diagnostic lane', 'evidence_failure')
    for lane in value['lanes'].values():
        need(type(lane) is dict and set(lane) == {'request_sha256', 'result_sha256', 'process_sha256'} and
             all(type(v) is str and re.fullmatch('[0-9a-f]{64}', v) for v in lane.values()),
             'Invalid diagnostic lane metadata', 'evidence_failure')
    complete_checks = {'all_emitted_outputs_valid': True, 'state_transition_invariants_valid': True,
                       'emitted_output_count': 131, 'cache_state_reference_equivalence_proven': False}
    lane_names = set(value['lanes'])
    need(lane_names in (set(), {'projected_null'}, {'projected_null', 'raw'}),
         'Diagnostic lanes are out of order', 'evidence_failure')
    need(value['checks'] in ({}, complete_checks), 'Incomplete diagnostic prerequisite checks', 'evidence_failure')
    if value['state'] in ('running', 'completed') or lane_names:
        need(value['checks'] == complete_checks and all(value[k] is not None for k in
             ('strict_oracle_sha256', 'prerequisite_binding_sha256', 'input_identity_sha256')),
             'Diagnostic progress lacks complete prerequisite bindings', 'evidence_failure')
    if value['state'] == 'completed':
        need(lane_names == {'projected_null', 'raw'} and
             type(value['native_pair_passed']) is bool and type(value['documentation_example_match']) is bool,
             'Completed diagnostic lacks both lanes or outcomes', 'evidence_failure')
        expected_failure = ('numerical_or_output_parity_failure' if not value['native_pair_passed'] else
                            'semantic_reference_mismatch' if not value['documentation_example_match'] else None)
        need(value['failure_class'] == expected_failure,
             'Diagnostic failure class disagrees with outcomes', 'evidence_failure')
    else:
        need(value['native_pair_passed'] is None and value['documentation_example_match'] is None,
             'Unfinished diagnostic cannot claim pair outcomes', 'evidence_failure')
        need((value['failure_class'] is None) == (value['state'] == 'running'),
             'Diagnostic progress and failure class disagree', 'evidence_failure')
    return value


def read_public(path):
    need(Path(path).stat().st_size <= 8192, 'Oversized diagnostic receipt', 'evidence_failure')
    return public_receipt(load(path))
