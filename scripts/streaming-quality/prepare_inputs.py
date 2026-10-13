"""Derive fresh files only from hash-verified public source bytes."""
import importlib.metadata
from pathlib import Path
import struct
import wave
from common import *
from diagnostic_pair import validate_state_outputs


def derive_pcm(wav, directory):
    import numpy as np
    from scipy.signal import resample
    need(importlib.metadata.version('numpy') == '2.3.5' and
         importlib.metadata.version('scipy') == '1.17.0', 'Pinned PCM toolchain required')
    verify(wav, WAV)
    with wave.open(str(wav)) as w:
        need(w.getnchannels() == 1 and w.getsampwidth() == 2 and w.getframerate() == 48000,
             'Unexpected public WAV encoding')
        raw = np.frombuffer(w.readframes(w.getnframes()), dtype='<i2').astype(np.float32)/32768
        pcm = resample(raw, round(len(raw)*16000/w.getframerate())).astype('<f4')
    need(pcm.shape == (49221,) and np.isfinite(pcm).all(), 'Unexpected derived complete PCM')
    p = directory/'pcm.f32le'; p.write_bytes(pcm.tobytes())
    need(sha(p) == PCM_SHA, 'Exact complete PCM hash changed')
    fmt = struct.pack('<HHIIHHH', 3, 1, 16000, 64000, 4, 32, 0)
    body = b'WAVEfmt '+struct.pack('<I', len(fmt))+fmt
    body += b'fact'+struct.pack('<II', 4, len(pcm))
    body += b'data'+struct.pack('<I', pcm.nbytes)+pcm.tobytes()
    (directory/'matched.wav').write_bytes(b'RIFF'+struct.pack('<I', len(body))+body)
    need(sha(directory/'matched.wav') == '6438b41f257e31bfdd94148bd9d805a7845ed27a5dfd57d774f1c0ff3ef3cb7b', 'Matched float-WAV hash changed')
    return {'pcm': describe(p), 'wav': describe(directory/'matched.wav'),
            'numpy': '2.3.5', 'scipy': '1.17.0', 'pcm_samples': 49221,
            'derivation': 'Public PCM16 mono 48kHz -> scipy.signal.resample -> float32 mono 16kHz; float WAV copies identical PCM bits.'}


def extract_sections(bundle, directory):
    verify(bundle, BUNDLE)
    models = load(HERE/'model-structure.json')
    with Path(bundle).open('rb') as source:
        for name in ('static', 'adapter', 'eoa'):
            pin = models[name]
            need(0 <= pin['source_offset'] <= BUNDLE['bytes']-pin['bytes'], 'Section outside pinned bundle')
            source.seek(pin['source_offset'])
            left = pin['bytes']
            path = directory/(name+'.tflite')
            with path.open('xb') as output:
                while left:
                    block = source.read(min(left, 1024*1024))
                    need(bool(block), 'Incomplete source section')
                    output.write(block); left -= len(block)
            verify(path, pin)
    return {name: describe(directory/(name+'.tflite')) for name in ('static', 'adapter', 'eoa')}


def tsv(path, cases):
    lines = ['PINNED_ENCODER_FIXTURES_V1']
    for case in cases:
        lines.append(f'CASE {case["id"]} {case["signature"]} {case["state"]}')
        for kind in ('inputs', 'outputs'):
            for s in case[kind]:
                fields = [kind[:-1].upper(), str(s['index']), s['name'], s['dtype'],
                          str(len(s['shape'])), *map(str, s['shape']), str(s['bytes'])]
                fields += [s['file'], s.get('chain', '-')] if kind == 'inputs' else [s['label']]
                need(all(v and not any(c.isspace() for c in v) for v in fields), 'Invalid fixture token')
                lines.append(' '.join(fields))
        lines.append('END')
    path.write_text('\n'.join(lines)+'\n')


def encoder_cases(directory):
    """Structural schemas are reviewed metadata bound to exact model hashes.

    The C++ runner independently checks runtime signature names, dtypes, shapes,
    sizes and state chaining. Python checks finite compared outputs. No Python inference dependency.
    """
    import numpy as np
    need(sha(directory/'mel.f32le') == MEL_SHA, 'Native Mel output differs from pinned complete fixture', 'numerical_failure')
    mel = np.fromfile(directory/'mel.f32le', dtype='<f4').reshape(307, 128)
    models = load(HERE/'model-structure.json')
    stages = {}
    def save(name, value):
        (directory/name).write_bytes(value.tobytes()); return name
    initial = {'history_tokens': np.zeros(1, '<i4'), 'prev_mel': np.zeros((1,4,128), '<f4'),
               **{f'prev_layer_{i:02}': np.zeros((1,24,1024), '<f4') for i in range(12)}}
    files = {name: save('initial-'+name+'.bin', value) for name, value in initial.items()}
    chain = {'history_tokens': 'next_history_tokens', 'prev_mel': 'next_mel',
             **{f'prev_layer_{i:02}': f'next_layer_{i:02}' for i in range(12)}}
    step, eoa = models['stateful']['signatures']
    cases = []; offset = 0
    for i, count in enumerate([48]*6+[19]):
        block = np.zeros((1,48,128), '<f4'); block[0,:count] = mel[offset:offset+count]
        inputs = {**files, 'mel': save(f'chunk-{i}.bin', block),
                  'valid_mel_frames': save(f'count-{i}.bin', np.array([count], '<i4'))}
        cases.append(dict(id=f'{i:03}', signature=step['key'], state='CHAIN' if i else 'RESET',
            inputs=[dict(s, file=inputs[s['name']], chain=chain.get(s['name'], '-')) for s in step['inputs']],
            outputs=[dict(s, label=s['name']) for s in step['outputs']]))
        offset += count
    cases.append(dict(id='eoa', signature=eoa['key'], state='RESET', inputs=[],
                      outputs=[dict(s, label=s['name']) for s in eoa['outputs']]))
    stages['stateful'] = cases
    pad = np.zeros((1,1,816,128), '<f4'); pad[0,0,:307] = mel
    source = save('static-mel.bin', pad)
    mask = save('static-mask.bin', np.arange(816)[None,None,:] < 307)
    for stage in ('static', 'adapter', 'eoa'):
        sig = models[stage]['signatures'][0]
        ins = []
        for s in sig['inputs']:
            label = 'mask' if s['dtype'] == 'bool' else 'features'
            file = f'actual/static/pinned-encoder-000.out.{label}.bin' if stage == 'adapter' else (mask if s['dtype'] == 'bool' else source)
            ins.append(dict(s, file=file, chain='-'))
        outputs = [dict(s, label='soft_tokens' if stage == 'adapter' else 'eoa_embedding' if stage == 'eoa' else s['name']) for s in sig['outputs']]
        stages[stage] = [dict(id='000', signature=sig['key'], state='RESET', inputs=ins, outputs=outputs)]
    for stage, cases in stages.items(): tsv(directory/(stage+'.tsv'), cases)
    return stages


def compare_encoder(directory, stages, receipt_path=None):
    """Exact same-host observable equivalence; historical hashes are diagnostics."""
    receipt = {'passed': False, 'post_adapter_valid_rows_bitwise': None,
               'eoa_bitwise': None, 'complete_reference_hash_match': None,
               'acceptance_contract': ENCODER_ACCEPTANCE_CONTRACT,
               'historical_reference_role': 'fingerprint_diagnostic_only',
               'cache_state_all_layers_checked': False,
               'oracle': 'Same SDK-pinned CPU runtime original encoder+adapter vs explicit-state encoder; full complete public PCM'}
    try:
        _compare_encoder(directory, stages, receipt)
        receipt['passed'] = True
        return receipt
    except Exception as error:
        receipt['error'] = str(error)
        receipt['classification'] = error.classification if isinstance(error, GateError) else 'orchestration_failure'
        raise
    finally:
        if receipt_path is not None:
            write(receipt_path, receipt)


def _compare_encoder(directory, stages, receipt):
    import numpy as np
    receipt['state_output_checks'] = validate_state_outputs(directory, stages)
    receipt['state_output_count'] = sum(s['name'].startswith('next_')
        for case in stages['stateful'] for s in case['outputs'])
    need(receipt['state_output_checks']['emitted_output_count'] == 131 and
         receipt['state_output_count'] == 98, 'Incomplete encoder output coverage', 'evidence_failure')
    def output(stage, case, label):
        spec = next(s for c in stages[stage] if c['id'] == case for s in c['outputs'] if s['label'] == label)
        p = directory/f'actual/{stage}/pinned-encoder-{case}.out.{label}.bin'
        data = p.read_bytes()
        need(len(data) == spec['bytes'], 'Oracle output size changed', 'numerical_failure')
        if spec['dtype'] == 'float32':
            need(np.isfinite(np.frombuffer(data, dtype='<f4')).all(), 'Nonfinite oracle output', 'numerical_failure')
        return data
    for stage in stages:
        native_receipt = (directory/f'actual/{stage}/pinned-encoder-run.tsv').read_text()
        need(f'PIN\t{LITERT_PIN}\n' in native_receipt and 'CPU_THREADS\t1\n' in native_receipt and native_receipt.endswith(f'COMPLETE\t{len(stages[stage])}\n'),
             'Missing fresh complete C++ oracle receipt', 'evidence_failure')
    receipt['complete_pinned_native_receipts'] = True
    chunks = []
    for i, count in enumerate([12]*6+[5]):
        need(output('stateful', f'{i:03}', 'token_count') == struct.pack('<i', count), 'Stateful token count mismatch', 'numerical_failure')
        need(output('stateful', f'{i:03}', 'token_mask') == b'\1'*count+b'\0'*(12-count), 'Stateful mask mismatch', 'numerical_failure')
        chunks.append(output('stateful', f'{i:03}', 'soft_tokens')[:count*1536*4])
    rows = b''.join(chunks)
    receipt['stateful_counts_and_masks_match'] = True
    need(output('static', '000', 'mask') == b'\1'*77+b'\0'*127, 'Static prefix mask mismatch', 'numerical_failure')
    receipt['static_prefix_mask_match'] = True
    receipt['post_adapter_valid_rows_bitwise'] = output('adapter', '000', 'soft_tokens')[:len(rows)] == rows
    need(receipt['post_adapter_valid_rows_bitwise'], 'Streamed/static post-adapter rows differ', 'numerical_failure')
    receipt['eoa_bitwise'] = output('stateful', 'eoa', 'eoa_embedding') == output('eoa', '000', 'eoa_embedding')
    need(receipt['eoa_bitwise'], 'Learned EOA differs', 'numerical_failure')
    p = directory/'projected.f32le'; p.write_bytes(rows)
    receipt.update(pcm_samples=49221, mel_frames=307, audio_rows=77, embedding_width=1536,
                   projected_rows=describe(p), historical_projected_rows={'bytes': 473088, 'sha256': ROWS_SHA})
    receipt['complete_reference_hash_match'] = receipt['projected_rows'] == receipt['historical_projected_rows']
