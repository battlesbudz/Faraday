#!/usr/bin/env python3
"""Compare completed native-only receipts. No process/model execution."""
import argparse,json
from pathlib import Path
import sys

def lane_checks(value):
    """Required evidence for either independently executed full-model lane."""
    tap = value.get('audio_embedding_tap', {})
    if type(tap) is not dict: tap = {}
    counts = all(type(value.get(k)) is int for k in
                 ('initial_tokens', 'final_tokens', 'prefill_tokens', 'decode_tokens'))
    return {
      'native_interface': value.get('interface') == 'native_cpp_conversation',
      'executed': value.get('execution_passed') is True,
      'fresh_checked_lifetime': all(value.get(k) is True for k in
          ('fresh_process', 'fresh_conversation', 'checked_drain_delete')),
      'no_dispatch': type(value.get('tool_dispatch_count')) is int and value['tool_dispatch_count'] == 0 and
          value.get('automatic_tool_calling') is False and value.get('tool_calls') == [],
      'full_bundle_verified': value.get('full_bundle_hash_reverified_before_launch') is True,
      'positive_untruncated_counts': counts and value['initial_tokens'] >= 0 and
          value['final_tokens'] > value['initial_tokens'] and value['prefill_tokens'] >= 78 and
          0 < value['decode_tokens'] < 64 and value.get('decode_at_budget') is False,
      'text_response': type(value.get('text')) is str and bool(value['text']) and
          type(value.get('response')) is dict,
      'embedding_tap_exact': all(type(tap.get(k)) is int for k in ('calls','valid_tokens','bytes_compared')) and
          tap.get('bitwise_equal') is True and tap.get('calls') == 1 and
          tap.get('valid_tokens') == 77 and tap.get('bytes_compared') == 473088 and 'error' not in tap,
    }

def compare(raw,projected):
    identities=['case','sdk_commit','litert_workspace_pin','bundle_sha256','producer_sha256',
                'manifest_sha256','pcm_sha256','projected_tokens_sha256','native_binary_sha256',
                'native_source_snapshot_sha256']
    checks={
      'independent_lane_checks': all(all(lane_checks(x).values()) for x in (raw, projected)),
      'native_interfaces':all(x.get('interface')=='native_cpp_conversation' for x in [raw,projected]),
      'correct_lanes':raw.get('mode')=='raw' and projected.get('mode')=='projected_null',
      'same_identities':all(k in raw and k in projected and raw[k]==projected[k] for k in identities),
      'fixed_control_config':all(x.get('context_tokens') == 640 and x.get('max_output_tokens') == 64 and
          x.get('resource_profile') == 'hosted_full_e2b_context640_control' for x in [raw,projected]),
      'both_executed':all(x.get('execution_passed') is True for x in [raw,projected]),
      'fresh_checked_lifetimes':all(x.get('fresh_process') and x.get('fresh_conversation') and x.get('checked_drain_delete') for x in [raw,projected]),
      'no_dispatch':all(x.get('tool_dispatch_count')==0 and x.get('automatic_tool_calling') is False for x in [raw,projected]),
      'full_bundle_verified':all(x.get('full_bundle_hash_reverified_before_launch') is True for x in [raw,projected]),
      'prefill_count':raw.get('prefill_tokens')==projected.get('prefill_tokens') and raw.get('prefill_tokens',0)>=78,
      'token_counts':all(k in raw and k in projected and raw[k]==projected[k] for k in ['initial_tokens','final_tokens','decode_tokens']),
      'full_response':all(k in raw and k in projected and raw[k]==projected[k] for k in ['response','text','tool_calls']),
      'not_truncated':all(x.get('decode_at_budget') is False for x in [raw,projected]),
      'embedding_tap_exact':all(x.get('audio_embedding_tap',{}).get('bitwise_equal') is True and
         x['audio_embedding_tap'].get('calls')==1 and x['audio_embedding_tap'].get('valid_tokens')==77 and
         x['audio_embedding_tap'].get('bytes_compared')==473088 and 'error' not in x['audio_embedding_tap']
         for x in [raw,projected]),
    }
    return {'checks':checks,'native_pair_passed':all(checks.values()),'full_jni_positive_consumption_passed':False,
            'semantic_quality':'not_scored_without_independent_transcript',
            'scope':'Matched native C++ Conversation transcription and exact post-adapter callback rows. No JNI or Android quality claim.'}

def main():
    p=argparse.ArgumentParser();p.add_argument('raw',type=Path);p.add_argument('projected',type=Path);p.add_argument('--output',type=Path,required=True)
    a=p.parse_args();r=compare(json.loads(a.raw.read_text()),json.loads(a.projected.read_text()))
    a.output.write_text(json.dumps(r,indent=2)+'\n');print(json.dumps(r,indent=2));return 0 if r['native_pair_passed'] else 2
if __name__=='__main__':sys.exit(main())
