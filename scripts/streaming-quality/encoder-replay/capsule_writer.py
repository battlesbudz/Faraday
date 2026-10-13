#!/usr/bin/env python3
"""Write only a fully verified, explicitly allowlisted weight-free diagnostic capsule."""
import argparse
import gzip
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat as statmod
import tarfile

LIMIT = 512 * 1024**2
HEX64 = re.compile(r'^[0-9a-f]{64}$')
HEX40 = re.compile(r'^[0-9a-f]{40}$')
ALIAS = re.compile(r'^[A-Za-z0-9_+.-]+$')
FORBIDDEN_SYSTEM = re.compile(r'^(?:ld-linux.*|lib(?:c|m|dl|pthread|rt|stdc\+\+|gcc_s)\.so(?:\..*)?)$')
SOURCE_ARCHIVES = {
    'sources/OouraFFT-v1.0-original.tar.gz': '5f4dabc2ae21e1f537425d58a49cdca1c49ea11db0d6271e2a4b27e9697548eb',
    'sources/eigen-ea13a98-original.tar.gz': '35c6126e246585d9cf6600b65471582c2701aae64b784a6fd19168a90cfc841e',
}


def canonical(value):
    return json.dumps(value,sort_keys=True,separators=(',',':'),ensure_ascii=False,allow_nan=False).encode()


def digest(value):return hashlib.sha256(canonical(value)).hexdigest()


def file_identity(path):
    h=hashlib.sha256();size=0
    with Path(path).open('rb') as stream:
        for block in iter(lambda:stream.read(1024**2),b''):
            h.update(block);size+=len(block)
            if size>LIMIT:raise ValueError('File exceeds capsule budget')
    return {'bytes':size,'sha256':h.hexdigest()}


def need(condition,message):
    if not condition:raise ValueError(message)


def safe_destination(name):
    p=PurePosixPath(name)
    need(len(name)<=512 and all(len(part)<=255 for part in p.parts) and
         not p.is_absolute() and '\\' not in name and str(p)==name and
         all(part not in ('','..','.') and ALIAS.fullmatch(part) for part in p.parts),
         'Unsafe capsule member path')
    return p


def validate_bindings(bindings):
    expected={'repository','run_id','run_attempt','head_sha','source_commit',
              'build_receipt_sha256','source_snapshot_sha256','recipe_manifest_sha256',
              'android_source_receipt_sha256','reviewed_patch_sha256','build_command_sha256',
              'bazel_sha256','policy_sha256'}
    need(set(bindings)==expected,'Incomplete or unexpected run/build/source bindings')
    need(bindings['repository']=='battlesbudz/Faraday','Unexpected repository')
    for key in ('run_id','run_attempt'):
        need(isinstance(bindings[key],int) and not isinstance(bindings[key],bool) and bindings[key]>0,'Invalid run identity')
    for key in ('head_sha','source_commit'):need(bool(HEX40.fullmatch(bindings[key])),'Invalid commit binding')
    for key in expected-{'repository','run_id','run_attempt','head_sha','source_commit'}:
        need(bool(HEX64.fullmatch(bindings[key])),'Invalid digest binding '+key)


def validate_capture(capture,policy,expected_policy_sha):
    need(digest(policy)==expected_policy_sha,'Reviewed policy identity mismatch')
    need(capture.get('schema_version')==1,'Unsupported capture schema')
    need(capture.get('provenance_epoch')=='same_run_post_build','Historical/current-query-only proof cannot authorize a capsule')
    need(capture.get('complete') is True and capture.get('blockers')==[],'Incomplete capture evidence')
    validate_bindings(capture['bindings'])
    need(capture['bindings']['policy_sha256']==expected_policy_sha,'Capture/policy binding mismatch')
    proof=capture['proof']
    for key in ('source_unchanged','build_completed','no_compilation_during_query',
                'compiler_actions_complete','dependency_closure_complete','licenses_complete',
                'system_libraries_excluded','link_arguments_match_actions'):
        need(proof.get(key) is True,'Missing proof: '+key)
    action_proof=capture['compiler_actions'];required=capture['header_input_graph']['required_action_outputs']
    need(action_proof.get('complete') is True and action_proof.get('blockers')==[], 'Incomplete compiler action metadata')
    need(isinstance(required,dict) and required and action_proof.get('required_output_count')==len(required),
         'Incomplete required-output action graph')
    actual_outputs={}
    for action in action_proof['actions']:
        need(action.get('mnemonic') in ('CppCompile','CppLink','CppArchive'),'Unreviewed action kind')
        need(bool(HEX64.fullmatch(action['arguments_sha256'])) and bool(HEX64.fullmatch(action['tool_executable']['sha256'])),
             'Missing compiler/expanded-argument hashes')
        for path,pin in action['outputs'].items():
            need(path not in actual_outputs,'Duplicate producer in action evidence');actual_outputs[path]=pin
    need(all(actual_outputs.get(path)==pin for path,pin in required.items()),'Required action-output identity missing')
    output_identities={digest(x) for x in actual_outputs.values()}
    entries=capture['files'];need(0<len(entries)<=2048,'Invalid payload file count')
    names=set();probe_count=0;runtime_aliases=set();total=0
    for item in entries:
        name=item['capsule_path'];p=safe_destination(name)
        need(name not in names,'Duplicate capsule alias');names.add(name)
        kind=item['kind'];pin=item['identity'];origin=item['origin']
        need(origin in policy['origins'],'Unknown source origin')
        need(origin in capture['used_origins'],'Payload origin omitted from compiled/header evidence')
        need(isinstance(pin['bytes'],int) and 0<pin['bytes']<=LIMIT and bool(HEX64.fullmatch(pin['sha256'])),'Invalid payload identity')
        source=Path(item['source_path'])
        need(source.is_absolute() and source.is_file() and not source.is_symlink() and source.resolve()==source,
             'Payload source must be an explicit regular physical file')
        if kind=='probe':
            need(name=='bin/pinned_encoder_probe' and origin=='sdk','Unexpected probe destination/origin')
            probe_count+=1
            need(pin==capture['probe_identity'],'Probe/build identity mismatch')
            need(digest(pin) in output_identities,'Probe is not bound to compiler action output')
        elif kind=='runtime_elf':
            need(len(p.parts)==2 and p.parts[0]=='lib' and not FORBIDDEN_SYSTEM.fullmatch(p.name),'System or unsafe runtime payload')
            need(p.name in capture['runtime_aliases'],'Unknown runtime alias')
            need(pin==capture['runtime_aliases'][p.name]['identity'] and origin==capture['runtime_aliases'][p.name]['origin'],
                 'Runtime mapping identity mismatch')
            runtime_aliases.add(p.name)
            need(digest(pin) in output_identities,'Runtime ELF is not bound to compiler action output')
        elif kind=='license':
            allowed=policy['origins'][origin]['licenses']
            need(name in allowed and pin==allowed[name]['identity'],'Unreviewed notice payload')
        elif kind=='source_archive':
            need(name in SOURCE_ARCHIVES and pin['sha256']==SOURCE_ARCHIVES[name],'Unreviewed original source archive')
            need(name in policy['source_archives'] and pin==policy['source_archives'][name]['identity'],'Source archive policy mismatch')
            need(origin==policy['source_archives'][name]['origin'],'Source archive origin mismatch')
        else:raise ValueError('Unexpected payload kind')
        total+=pin['bytes'];need(total<=LIMIT,'Capsule exceeds 512 MiB')
    need(probe_count==1,'Exactly one encoder probe required')
    need(runtime_aliases==set(capture['runtime_aliases']),'Incomplete runtime alias payload')
    required_notices={path for origin in capture['used_origins'] for path in policy['origins'][origin]['licenses']}
    need(required_notices<=names,'Required dependency notices omitted')
    need(set(SOURCE_ARCHIVES)<=names,'Required Eigen/Ooura corresponding source omitted')
    need(set(capture['used_origins'])<=set(policy['origins']),'Unmapped compiled/header origin')
    return total


class HashingReader:
    def __init__(self,stream):self.stream=stream;self.hash=hashlib.sha256();self.bytes=0;self.prefix=b''
    def read(self,n=-1):
        block=self.stream.read(n);self.hash.update(block);self.bytes+=len(block)
        if len(self.prefix)<4:self.prefix=(self.prefix+block)[:4]
        return block


def add_bytes(tar,name,data):
    info=tarfile.TarInfo(name);info.size=len(data);info.mode=0o644;info.mtime=0
    info.uid=info.gid=0;info.uname=info.gname='';tar.addfile(info,io.BytesIO(data))


def write_capsule(capture,policy,expected_policy_sha,out):
    total=validate_capture(capture,policy,expected_policy_sha)
    out=Path(out);need(not out.exists(),'Fresh capsule path required')
    need(out.parent.is_dir() and not out.parent.is_symlink(),'Explicit regular output directory required')
    partial=out.with_name(out.name+'.partial');need(not partial.exists(),'Stale partial capsule exists')
    public_files={item['capsule_path']:{'identity':item['identity'],'kind':item['kind'],'origin':item['origin']} for item in capture['files']}
    public={'schema_version':1,'purpose':'Encoder-only compiled-runtime diagnostic, not a quality acceptance artifact',
            'bindings':capture['bindings'],'proof':capture['proof'],'files':public_files,
            'system_libraries':capture['system_libraries'],'runtime_aliases':capture['runtime_aliases'],
            'source_origins':{x:policy['origins'][x]['public'] for x in capture['used_origins']},
            'compiler_actions':capture['compiler_actions'],'header_input_graph':capture['header_input_graph'],
            'capture_evidence_sha256':digest({k:v for k,v in capture.items() if k!='files'}),
            'capsule_source_manifest_sha256':digest(public_files),
            'host_cpu':capture['host_cpu'],'local_replay_performed':False,
            'system_environment_note':'System binaries are excluded; a different machine is not an exact hosted-environment reproduction.'}
    public_data=json.dumps(public,indent=2,sort_keys=True,allow_nan=False).encode()+b'\n'
    notice=policy['source_notice'].encode()
    need(total+len(public_data)+len(notice)+2048*(len(capture['files'])+2)<=LIMIT,'Archive metadata exceeds 512 MiB budget')
    try:
        with partial.open('xb') as raw:
            with gzip.GzipFile(filename='',mode='wb',fileobj=raw,mtime=0) as compressed:
                with tarfile.open(fileobj=compressed,mode='w|',format=tarfile.PAX_FORMAT) as tar:
                    for item in sorted(capture['files'],key=lambda x:x['capsule_path']):
                        fd=os.open(item['source_path'],os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK)
                        with os.fdopen(fd,'rb') as source:
                            stat=os.fstat(source.fileno())
                            need(statmod.S_ISREG(stat.st_mode),'Special payload file rejected')
                            need(stat.st_size==item['identity']['bytes'],'Source size changed before copy')
                            reader=HashingReader(source)
                            info=tarfile.TarInfo(item['capsule_path']);info.size=stat.st_size;info.mode=0o644;info.mtime=0
                            info.uid=info.gid=0;info.uname=info.gname='';tar.addfile(info,reader)
                            need(reader.bytes==stat.st_size and reader.hash.hexdigest()==item['identity']['sha256'],'Source bytes changed during copy')
                            if item['kind'] in ('probe','runtime_elf'):need(reader.prefix==b'\x7fELF','Non-ELF executable payload')
                    add_bytes(tar,'CAPSULE-RECEIPT.json',public_data)
                    add_bytes(tar,'SOURCE-NOTICE.txt',notice)
        need(partial.stat().st_size<=LIMIT,'Compressed capsule exceeds 512 MiB')
        os.replace(partial,out)
        receipt={'archive':file_identity(out),'bindings':capture['bindings'],
                 'capsule_source_manifest_sha256':digest(public_files),'capsule_receipt_sha256':hashlib.sha256(public_data).hexdigest(),
                 'uncompressed_payload_bytes':total+len(public_data)+len(notice),'regular_files':len(public_files)+2,
                 'uploaded':False,'replay_performed':False}
        out.with_suffix(out.suffix+'.json').write_text(json.dumps(receipt,indent=2)+'\n')
        return receipt
    except BaseException:
        # Preserve failed evidence but never promote a partial archive.
        raise


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for key in ('capture','policy','out'):p.add_argument('--'+key,type=Path,required=True)
    p.add_argument('--expected-policy-sha256',required=True);p.add_argument('--expected-capture-sha256',required=True)
    a=p.parse_args();capture=json.loads(a.capture.read_text());policy=json.loads(a.policy.read_text())
    need(digest(capture)==a.expected_capture_sha256,'Capture identity mismatch')
    print(json.dumps(write_capsule(capture,policy,a.expected_policy_sha256,a.out),indent=2))


if __name__=='__main__':main()
