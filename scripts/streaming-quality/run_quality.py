#!/usr/bin/env python3
"""Fresh official inputs, bounded native prerequisites, then two fresh lanes."""
import argparse
import base64
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import time
import unicodedata
from common import *
from bounded_exec import BUDGET, FULL_E2B_BUDGET, FULL_E2B_PROFILE, run, run_full_e2b, verify_cleanup
from build_probes import PACKAGE, TARGETS
from compare_native_pair import compare, lane_checks
from prepare_inputs import derive_pcm, extract_sections, encoder_cases, compare_encoder
from diagnostic_pair import (DiagnosticSession, HistoricalReferenceMismatch, KNOWN_ROWS_SHA,
    FAILURE_CODE, ERROR_CLASSES, stage_artifacts, validate_state_outputs, public_receipt)

REFERENCE = {'text': 'Roses are red, violets are blue.',
    'source_url': 'https://ai.google.dev/gemma/docs/capabilities/audio',
    'audio_url': WAV['url'], 'audio_sha256': WAV['sha256'],
    'type': 'Official documentation example output for this exact linked audio; not a new human listening annotation.'}


RESOURCE_PATTERN = r'\b(?:ENOMEM|std::bad_alloc|RESOURCE_EXHAUSTED)\b|(?:mmap|map|mapping|allocate|allocation).{0,100}(?:Cannot allocate memory|out of memory)'

def classify_process(process, result=None, diagnostic=''):
    """Only explicit resource evidence earns the resource classification."""
    if process.get('cleanup_verified') is False or process.get('stop_reason') == 'cleanup_failure':
        return 'model_cleanup_failure'
    if process.get('status') == 'resource_blocked': return 'resource_constrained'
    if process.get('stop_reason') in {'wall_timeout','rss_watchdog_limit','system_memory_reserve'}:
        return 'resource_constrained'
    if process.get('exit_code') in {-signal.SIGXCPU, -signal.SIGXFSZ}: return 'resource_constrained'
    error = str((result or {}).get('error', ''))+'\n'+diagnostic
    if re.search(RESOURCE_PATTERN, error, re.I):
        return 'resource_constrained'
    if any(message in error for message in (
            'Native audio-embedding callback differs from pinned reference',
            'Full SDK Mel bytes differ', 'Decoded PCM differs bitwise')):
        return 'numerical_failure'
    if process.get('status') != 'completed': return 'native_execution_failure' 
    if result is not None and result.get('execution_passed') is not True: return 'native_execution_failure'
    if process.get('cleanup_verified') is not True: return 'model_cleanup_failure'
    return 'passed'


def checked_process(command, directory, results=False, *, cleanup_root=None):
    process = run(command, directory, cleanup_root=cleanup_root)
    return checked_receipt(process, directory, results)


def checked_full_e2b(binary, request, directory, *, binary_identity, cleanup_root):
    process = run_full_e2b(binary, request, directory, binary_identity=binary_identity,
                           cleanup_root=cleanup_root)
    return checked_receipt(process, directory, results=True)


def checked_receipt(process, directory, results=False):
    need(process.get('cleanup_verified') is True,
         'Model process tree cleanup is not verified', 'model_cleanup_failure')
    result = load(directory/'result.json') if results and (directory/'result.json').is_file() else None
    diagnostic = (directory/'stderr.log').read_text(errors='replace') if (directory/'stderr.log').exists() else ''
    kind = classify_process(process, result, diagnostic)
    # No stderr dump is exported; keep the exact error string if native code
    # produced a structured result, plus process metrics and bounded reason.
    process['classification'] = kind
    if diagnostic:
        process['stderr_sha256'] = sha(directory/'stderr.log')
        # Retain only the exact bounded allocation evidence, never a log dump.
        matched = re.search(RESOURCE_PATTERN, str((result or {}).get('error', ''))+'\n'+diagnostic, re.I)
        if matched: process['explicit_resource_diagnostic'] = matched.group(0)
    write(directory/'process.json', process)
    need(kind == 'passed', f'{directory.name}: {kind}', kind)
    if results: need(result is not None, 'Native success omitted its result receipt', 'evidence_failure')
    return process, result


def verify_build(build_dir):
    receipt = load(build_dir/'build-status.json')
    need(receipt.get('build_succeeded') is True and receipt.get('compilation_exited_before_quality') is True,
         'Host compilation has not completed successfully')
    # Capture/export may fail, but uncertain process ownership must never
    # overlap the model workload. SIGKILL leaves a pending same-run latch.
    need(receipt.get('diagnostic_cleanup_required') is True, 'Diagnostic cleanup admission contract missing')
    sys.path.insert(0, str(HERE/'encoder-replay'))
    from diagnostic_guard import verify_cleanup
    from hosted_capture import github_context
    verify_cleanup(build_dir, github_context())
    need(receipt['recipe_manifest_sha256'] == verify_recipe_sources(), 'Hosted recipe changed after compile')
    sdk = Path(receipt['sdk'])
    need(digest(sdk_snapshot(sdk)) == receipt['source_snapshot_sha256'], 'SDK source changed after host compilation')
    for target in TARGETS: verify(sdk/'bazel-bin'/PACKAGE/target, receipt['binaries'][target])
    for path, pin in receipt['host_prebuilts'].items(): verify(sdk/path, pin)
    for path, pin in receipt.get('dynamic_libraries', {}).items(): verify(Path(path), pin)
    return receipt, sdk


def lane_identity(mode, build, identity):
    return dict(mode=mode, case='transcribe', sdk_commit=SDK_PIN, litert_workspace_pin=LITERT_PIN,
        bundle_sha256=BUNDLE['sha256'], producer_sha256=PRODUCER,
        manifest_sha256=digest(identity), pcm_sha256=PCM_SHA,
        projected_tokens_sha256=identity['projected']['sha256'],
        native_binary_sha256=build['binaries']['native_conversation_quality_probe']['sha256'],
        native_source_snapshot_sha256=build['source_snapshot_sha256'],
        full_bundle_hash_reverified_before_launch=True, context_tokens=640,
        max_output_tokens=64, resource_profile=FULL_E2B_PROFILE)


def validate_lane_result(result, expected):
    need(all(type(result.get(k)) is type(v) and result[k] == v for k, v in expected.items()),
         'Native result identity differs from this lane request', 'identity_failure')
    need(all(result.get(k) is True for k in ('fresh_process','fresh_conversation','checked_drain_delete')),
         'Native lifecycle is not verified', 'model_cleanup_failure')
    need(all(lane_checks(result).values()), 'Native lane checks failed', 'numerical_failure')


def lane_request(mode, work, build, identity):
    projected = (work/'inputs/projected.f32le').read_bytes()
    audio = {'type': 'audio', 'blob': base64.b64encode(projected if mode == 'projected_null' else
                                                   (work/'inputs/matched.wav').read_bytes()).decode()}
    if mode == 'projected_null':
        audio['projected_audio'] = dict(schema_version=1, dtype='float32_le', projection='audio_adapter',
            end_marker='runtime', complete=True, pcm_samples=49221, token_count=77, embedding_width=1536,
            seal_token='public-complete-float-pcm-49221-v1', producer_sha256=PRODUCER)
    return dict(lane_identity(mode, build, identity),
        model_path=str(work/'inputs/full.litertlm'), projected_tokens_path=str(work/'inputs/projected.f32le'),
        wav_path=str(work/'inputs/matched.wav'), audio_embedding_tap=True,
        message={'role': 'user', 'content': [{'type': 'text', 'text': 'Transcribe the spoken words in this audio. Return only the transcription.'}, audio]})


def normalize(text):
    return ' '.join(''.join(' ' if unicodedata.category(c).startswith('P') else c
                            for c in text.casefold()).split())


def final_comparison(raw, projected):
    pair = compare(raw, projected)
    pair['acceptance_contract'] = ENCODER_ACCEPTANCE_CONTRACT
    pair['semantic_reference'] = REFERENCE
    pair['documentation_example_match'] = all(normalize(r.get('text', '')) == normalize(REFERENCE['text'])
                                              for r in (raw, projected))
    pair['semantic_quality'] = 'single_public_documentation_example_match_only; not independently human-annotated'
    pair['passed'] = pair['native_pair_passed'] and pair['documentation_example_match']
    pair['classification'] = ('passed' if pair['passed'] else 'numerical_or_output_parity_failure'
                              if not pair['native_pair_passed'] else 'semantic_reference_mismatch')
    return pair


def run_diagnostic_pair(a, work, build, sdk, identity, stages, session, error):
    """Never return a release pass or alter the strict oracle, summary or exit."""
    receipt = dict(schema_version=1, purpose='historical_reference_mismatch_diagnostic_only',
        strict_gate_passed=False, strict_exit_code=2, strict_failure_code=FAILURE_CODE,
        strict_oracle_sha256=error.receipt_fingerprint[1]['sha256'] if error.receipt_fingerprint else None,
        prerequisite_binding_sha256=None, actual_rows_sha256=KNOWN_ROWS_SHA, historical_rows_sha256=ROWS_SHA,
        input_identity_sha256=None, resource_profile=FULL_E2B_PROFILE, state='blocked', failure_class=None,
        checks={}, lanes={}, native_pair_passed=None, documentation_example_match=None,
        cache_state_reference_equivalence_proven=False)
    target = work/'diagnostic-pair.json'
    try:
        oracle = session.authorize(error)
        inputs = work/'inputs'
        receipt['checks'] = validate_state_outputs(inputs, stages)
        receipt['prerequisite_binding_sha256'] = session.public_binding()
        diagnostic = work/'diagnostic-pair'; diagnostic.mkdir()
        identity = dict(identity, pcm=describe(inputs/'pcm.f32le'), mel=describe(inputs/'mel.f32le'),
            wav=describe(inputs/'matched.wav'), projected=oracle['projected_rows'],
            frontend_receipt_sha256=sha(work/'frontend.json'),
            oracle_receipt_sha256=receipt['strict_oracle_sha256'],
            diagnostic_only=True, prerequisite_binding_sha256=receipt['prerequisite_binding_sha256'])
        write(diagnostic/'input-identity.json', identity)
        session.watch([diagnostic/'input-identity.json'])
        receipt.update(input_identity_sha256=digest(identity), state='running')
        write(target, public_receipt(receipt))
        for mode in ('projected_null', 'raw'):
            verify_cleanup(a.build_dir)
            current_build, current_sdk = verify_build(a.build_dir)
            need(current_build == build and current_sdk == sdk, 'Diagnostic build identity changed')
            verify(inputs/'full.litertlm', BUNDLE)
            verify(inputs/'public.wav', WAV)
            for name, pin in load(HERE/'model-structure.json').items():
                verify(inputs/(name+'.tflite'), pin)
            for name, file in [('pcm','pcm.f32le'), ('mel','mel.f32le'), ('wav','matched.wav'), ('projected','projected.f32le')]:
                verify(inputs/file, identity[name])
            session.verify_unchanged()
            request = diagnostic/(mode+'-request.json')
            write(request, lane_request(mode, work, build, identity))
            session.watch([request])
            process, result = checked_full_e2b(sdk/'bazel-bin'/PACKAGE/'native_conversation_quality_probe',
                request, diagnostic/mode, binary_identity=build['binaries']['native_conversation_quality_probe'],
                cleanup_root=a.build_dir)
            # A native echo cannot substitute the historical hash or another input identity.
            expected = load(request)
            need(all(result.get(key) == expected[key] for key in (
                'mode', 'case', 'sdk_commit', 'litert_workspace_pin', 'bundle_sha256', 'producer_sha256',
                'manifest_sha256', 'pcm_sha256', 'projected_tokens_sha256', 'native_binary_sha256',
                'native_source_snapshot_sha256', 'context_tokens', 'max_output_tokens', 'resource_profile')),
                'Diagnostic native result identity differs', 'identity_failure')
            # Check the first lane before starting another model; no pair is
            # claimed until both independently executed results are compared.
            need(all(result.get(k) is True for k in ('fresh_process','fresh_conversation','checked_drain_delete')),
                 'Diagnostic native lifecycle is not verified', 'model_cleanup_failure')
            tap = result.get('audio_embedding_tap', {})
            need(tap.get('bitwise_equal') is True and tap.get('calls') == 1 and
                 tap.get('valid_tokens') == 77 and tap.get('bytes_compared') == 473088 and 'error' not in tap,
                 'Diagnostic embedding tap failed', 'numerical_failure')
            session.watch([diagnostic/mode/'result.json', diagnostic/mode/'process.json'])
            session.verify_unchanged()
            receipt['lanes'][mode] = dict(request_sha256=sha(request),
                result_sha256=sha(diagnostic/mode/'result.json'), process_sha256=sha(diagnostic/mode/'process.json'))
            write(target, public_receipt(receipt))
        pair = final_comparison(load(diagnostic/'raw/result.json'), load(diagnostic/'projected_null/result.json'))
        receipt.update(state='completed', native_pair_passed=pair['native_pair_passed'],
            documentation_example_match=pair['documentation_example_match'],
            failure_class=None if pair['passed'] else pair['classification'])
    except Exception as failure:
        kind = failure.classification if isinstance(failure, GateError) else 'orchestration_failure'
        receipt.update(state='blocked', failure_class=kind if kind in ERROR_CLASSES else 'orchestration_failure')
    write(target, public_receipt(receipt))
    return receipt


def run_gate(a):
    work = a.out.resolve()
    need(not work.exists(), 'Use a fresh quality run directory; stale receipts are forbidden')
    work.mkdir(parents=True)
    session = DiagnosticSession(work)
    summary = {'schema_version': 1, 'classification': 'pending', 'passed': False,
               'acceptance_contract': ENCODER_ACCEPTANCE_CONTRACT,
               'android_full_model_proven': False, 'jni_full_model_proven': False,
               'budget': FULL_E2B_BUDGET, 'resource_profile': FULL_E2B_PROFILE,
               'prerequisite_budget': BUDGET, 'stage': 'verify_build', 'lanes': {},
               'scope': 'Hosted native C++ Conversation quality prerequisite only; no Android/JNI full-model quality or device latency claim.'}
    write(work/'summary.json', summary)
    start = time.monotonic()
    try:
        verify_cleanup(a.build_dir)
        build, sdk = verify_build(a.build_dir)
        need(os.environ.get('GEMMA_QUALITY_COMPUTE_SLOT') == 'confirmed_by_owner', 'Post-compilation compute slot required')
        inputs = work/'inputs'; inputs.mkdir()
        summary['source_snapshot_sha256'] = build['source_snapshot_sha256']
        summary['reviewed_patch_sha256'] = build['reviewed_patch_sha256']
        summary['recipe_manifest_sha256'] = build['recipe_manifest_sha256']
        summary['stage'] = 'official_downloads'; write(work/'summary.json', summary)
        download(BUNDLE, inputs/'full.litertlm')
        download(WAV, inputs/'public.wav', seconds=90)
        identity = {'bundle': BUNDLE, 'public_wav': WAV, 'models': extract_sections(inputs/'full.litertlm', inputs),
                    'pcm_derivation': derive_pcm(inputs/'public.wav', inputs),
                    'source_snapshot_sha256': build['source_snapshot_sha256']}
        session.watch([inputs/'full.litertlm', inputs/'public.wav', inputs/'pcm.f32le', inputs/'matched.wav',
                       *[inputs/(stage+'.tflite') for stage in ('static', 'adapter', 'eoa')]])
        summary['stage'] = 'weightless_reassembly'; write(work/'summary.json', summary)
        java = Path(os.environ['JAVA_HOME'])/'bin/java'
        process, _ = checked_process([java, '-Xms16m', '-Xmx128m', '-XX:+UseSerialGC', '-XX:ActiveProcessorCount=1',
            '-XX:CompressedClassSpaceSize=32m', '-XX:MaxMetaspaceSize=128m', '-XX:ReservedCodeCacheSize=64m',
            '-Xss512k', HERE/'recipe/WeightlessEncoderRecipe.java', inputs/'full.litertlm',
            HERE/'recipe/source-copy-recipe.bin', HERE/'recipe/structural-literals.bin.gz', inputs/'stateful.tflite'], work/'reassembly', cleanup_root=a.build_dir)
        verify(inputs/'stateful.tflite', load(HERE/'model-structure.json')['stateful'])
        session.process_completed('reassembly', process, [inputs/'stateful.tflite'])
        identity['models']['stateful'] = describe(inputs/'stateful.tflite')
        summary['stage'] = 'native_frontend'; write(work/'summary.json', summary)
        process, _ = checked_process([sdk/'bazel-bin'/PACKAGE/'native_frontend_quality_probe', inputs/'matched.wav',
                         inputs/'pcm.f32le', inputs/'mel.f32le'], work/'frontend', cleanup_root=a.build_dir)
        frontend = json.loads((work/'frontend/stdout.log').read_text().splitlines()[-1])
        need(frontend.get('passed') is True and frontend.get('decode_pcm_bitwise') is True and
             frontend.get('encoded_and_pcm_mel_bitwise') is True, 'Native frontend parity failed', 'numerical_failure')
        need(sha(inputs/'mel.f32le') == MEL_SHA, 'Native full-SDK Mel hash mismatch', 'numerical_failure')
        frontend.update(mel_fixture_bitwise=True, pcm=describe(inputs/'pcm.f32le'),
                        mel=describe(inputs/'mel.f32le'), wav=describe(inputs/'matched.wav'),
                        binary=build['binaries']['native_frontend_quality_probe'])
        write(work/'frontend.json', frontend)
        session.process_completed('frontend', process, [work/'frontend.json',
            inputs/'pcm.f32le', inputs/'mel.f32le', inputs/'matched.wav'])
        stages = encoder_cases(inputs)
        session.watch({inputs/s['file'] for cases in stages.values() for case in cases
            for s in case['inputs'] if not s['file'].startswith('actual/')})
        session.watch([inputs/(stage+'.tsv') for stage in stages])
        summary['stage'] = 'native_encoder_oracle'; write(work/'summary.json', summary)
        (inputs/'actual').mkdir()
        for stage in ('stateful', 'static', 'adapter', 'eoa'):
            session.verify_unchanged()
            target = inputs/'actual'/stage; target.mkdir()
            process, _ = checked_process([sdk/'bazel-bin'/PACKAGE/'pinned_encoder_probe', inputs/(stage+'.tflite'),
                             inputs/(stage+'.tsv'), inputs, target], work/('encoder-'+stage), cleanup_root=a.build_dir)
            session.process_completed('encoder-'+stage, process, stage_artifacts(inputs, stage, stages[stage]))
        session.comparison_ticket()
        try:
            oracle = compare_encoder(inputs, stages, work/'encoder-oracle.json')
        except HistoricalReferenceMismatch as error:
            if getattr(a, 'diagnostic_on_known_reference_mismatch', False):
                # Compatibility with the legacy failure producer only. The v1
                # same-host oracle never raises a historical-fingerprint error.
                # The strict failure is unconditionally re-raised, even after successful diagnostics.
                try:
                    run_diagnostic_pair(a, work, build, sdk, identity, stages, session, error)
                finally:
                    raise error
            raise
        need(canonical(load(work/'encoder-oracle.json')) == canonical(oracle),
             'Live encoder oracle receipt differs', 'evidence_failure')
        oracle['binary'] = build['binaries']['pinned_encoder_probe']
        write(work/'encoder-oracle.json', oracle)
        session.watch([work/'encoder-oracle.json', inputs/'projected.f32le'])
        # Bind the identity actually adopted by watch, closing the gap between
        # comparison and initial observation of the freshly written rows.
        need(session.pins[inputs/'projected.f32le'][1] == oracle['projected_rows'],
             'Projected rows changed after encoder comparison', 'identity_failure')
        identity.update(acceptance_contract=ENCODER_ACCEPTANCE_CONTRACT,
                        prerequisite_binding_sha256=session.public_binding(),
                        pcm=describe(inputs/'pcm.f32le'), mel=describe(inputs/'mel.f32le'),
                        wav=describe(inputs/'matched.wav'), projected=describe(inputs/'projected.f32le'),
                        frontend_receipt_sha256=sha(work/'frontend.json'), oracle_receipt_sha256=sha(work/'encoder-oracle.json'))
        write(work/'input-identity.json', identity)
        session.watch([work/'input-identity.json'])
        # Each lane has a new OS process, engine, Conversation and lifecycle.
        # Neither receives history/state/output from the other lane.
        for mode in ('projected_null', 'raw'):
            summary['stage'] = mode; write(work/'summary.json', summary)
            verify_cleanup(a.build_dir)
            current_build, current_sdk = verify_build(a.build_dir)
            need(current_build == build and current_sdk == sdk, 'Build identity changed')
            verify(inputs/'full.litertlm', BUNDLE)  # Whole 2.59GB hash again, each lane.
            verify(inputs/'public.wav', WAV)
            for name, pin in load(HERE/'model-structure.json').items():
                verify(inputs/(name+'.tflite'), pin)
            for name, file in [('pcm','pcm.f32le'), ('mel','mel.f32le'), ('wav','matched.wav'), ('projected','projected.f32le')]:
                verify(inputs/file, identity[name])
            session.verify_unchanged()
            request = work/(mode+'-request.json')
            write(request, lane_request(mode, work, build, identity))
            session.watch([request])
            process, result = checked_full_e2b(sdk/'bazel-bin'/PACKAGE/'native_conversation_quality_probe',
                request, work/mode, binary_identity=build['binaries']['native_conversation_quality_probe'],
                cleanup_root=a.build_dir)
            need(canonical(load(work/mode/'process.json')) == canonical(process) and
                 canonical(load(work/mode/'result.json')) == canonical(result),
                 'Live native receipts differ', 'evidence_failure')
            need(all(type(process.get(k)) is type(v) and process[k] == v for k, v in {
                'classification': 'passed', 'status': 'completed', 'exit_code': 0,
                'execution_started': True, 'cleanup_verified': True}.items()),
                'Native process did not execute and clean up', 'model_cleanup_failure')
            validate_lane_result(result, lane_identity(mode, build, identity))
            session.watch([work/mode/'result.json', work/mode/'process.json'])
            session.verify_unchanged()
            summary['lanes'][mode] = {'classification': 'passed', 'result_sha256': sha(work/mode/'result.json'),
                                     'process_sha256': sha(work/mode/'process.json')}
            write(work/'summary.json', summary)
        pair = final_comparison(load(work/'raw/result.json'), load(work/'projected_null/result.json'))
        write(work/'comparison.json', pair)
        summary.update(classification=pair['classification'], passed=pair['passed'], stage='finished')
        summary['semantic_reference_type'] = REFERENCE['type']
    except Exception as e:
        summary.update(classification=e.classification if isinstance(e, GateError) else 'orchestration_failure',
                       passed=False, error=str(e))
    summary['wall_seconds'] = time.monotonic()-start
    write(work/'summary.json', summary)
    return summary


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--build-dir', type=Path, required=True); p.add_argument('--out', type=Path, required=True)
    p.add_argument('--diagnostic-on-known-reference-mismatch', action='store_true',
        help='Legacy failure-producer compatibility only; v1 same-host acceptance treats historical fingerprints as diagnostics.')
    a = p.parse_args()
    result = run_gate(a); print(json.dumps(result, indent=2))
    return 0 if result['passed'] else 3 if result['classification'] == 'resource_constrained' else 2
if __name__ == '__main__': sys.exit(main())
