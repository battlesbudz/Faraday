#!/usr/bin/env python3
"""Capture an encoder-only diagnostic after this run's guarded successful build.

No acceptance predicate is changed. Model files are never accepted as inputs.
Call begin_guard before the existing build, then collect after successful build.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import resource
import shutil
import signal
import subprocess
import tarfile
import time
import sys
import traceback

from capsule_writer import canonical, digest, file_identity, need, validate_bindings, write_capsule
from replay_artifact_inventory import parse_listing, elf_metadata, artifact_origin, SYSTEM_NAMES, SYSTEM_ROOTS
from trace_compiler_inputs import trace
from hash_bazel_action_metadata import action_metadata
from diagnostic_status import Status, Parser, build_dir_from_argv, validate_context, instance_from_argv

GIB=1024**3
SDK_PIN='924e79c91542761242244e4f1651851f822e4cbb'
LITERT_PIN='0ff28117f1cb5556d0e015bf80b773f74e2bee51'
BAZEL_SHA='ac6249d1192aea9feaf49dfee2ab50c38cee2454b00cf29bbec985a11795c025'
ENCODER_TARGET='//experimental/hosted_audio_quality_20261006:pinned_encoder_probe'


def save(path,value):Path(path).write_text(json.dumps(value,indent=2,sort_keys=True,allow_nan=False)+'\n')


def github_context():
    event=json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text())
    repository=os.environ['GITHUB_REPOSITORY'];source=os.environ['GITHUB_SHA']
    pr=event.get('pull_request')
    if pr:
        need(pr['head']['repo']['full_name']==repository,'Fork diagnostics are outside the approved publication scope')
        head=pr['head']['sha']
    else:head=source
    return {'repository':repository,'run_id':int(os.environ['GITHUB_RUN_ID']),
            'run_attempt':int(os.environ['GITHUB_RUN_ATTEMPT']),'head_sha':head,'source_commit':source}


def checked_identity(path,expected):
    actual=file_identity(path);need(actual==expected,'File identity mismatch: '+str(path));return actual


def sdk_snapshot(sdk):
    head=subprocess.check_output(['git','-C',str(sdk),'rev-parse','HEAD'],text=True).strip()
    need(head==SDK_PIN,'Unexpected SDK HEAD')
    need(LITERT_PIN in (sdk/'WORKSPACE').read_text(),'Unexpected LiteRT pin')
    paths=subprocess.check_output(['git','-C',str(sdk),'ls-files','--cached','--others','--exclude-standard','-z']).decode().split('\0')
    files={p:file_identity(sdk/p)['sha256'] for p in sorted(set(paths)) if p and (sdk/p).is_file()
           and not (p.startswith('prebuilt/') and p.endswith('.so'))}
    return {'sdk_commit':head,'litert_workspace_pin':LITERT_PIN,'files':files,'workspace_sha256':file_identity(sdk/'WORKSPACE')['sha256']}


def begin_guard(sdk,build_command,context,policy,guard_path):
    """Called before the already authorized build. Does not run Bazel."""
    sdk=Path(sdk).resolve(strict=True);guard_path=Path(guard_path)
    need(not guard_path.exists(),'Fresh compile guard required')
    need(build_command.count('build')==1 and ENCODER_TARGET in build_command,'Exact encoder build command required')
    need('--remote_executor=' in build_command and '--remote_cache=' in build_command and
         '--noremote_upload_local_results' in build_command,'Remote execution/cache must remain disabled')
    need(file_identity(build_command[0])['sha256']==BAZEL_SHA,'Unexpected Bazel executable')
    tools={};tool_libraries={}
    candidates={shutil.which(name) for name in ('clang','clang++','ar','llvm-ar','ld','ld.lld','as')}
    candidates|={x.split('=',2)[2] for x in build_command if x.startswith(('--repo_env=CC=','--repo_env=CXX='))}
    for path in sorted(x for x in candidates if x):
        p=Path(path).resolve(strict=True)
        with p.open('rb') as stream:need(stream.read(4)==b'\x7fELF','Compiler/tool wrapper is unsupported')
        tools[str(p)]=file_identity(p)
        dynamic=subprocess.check_output(['readelf','-d',str(p)],text=True,timeout=10)
        if '(NEEDED)' in dynamic:
            env=dict(os.environ)
            for key in ('LD_PRELOAD','LD_AUDIT','LD_LIBRARY_PATH','LD_DEBUG','LD_DEBUG_OUTPUT'):env.pop(key,None)
            listing=subprocess.check_output(['ldd',str(p)],text=True,env=env,timeout=10)
            for _,library in parse_listing(listing).items():
                if str(library) not in tool_libraries:tool_libraries[str(library)]=file_identity(library)
    need(tools,'Compiler/tool identities missing')
    snapshot=sdk_snapshot(sdk)
    guard={'schema_version':1,'epoch':'before_same_run_build','context':context,
           'policy_sha256':digest(policy),'build_command':build_command,'build_command_sha256':digest(build_command),
           'bazel_sha256':BAZEL_SHA,'source_snapshot_sha256':digest(snapshot),'compiler_tools':tools,
           'compiler_tool_libraries':tool_libraries,
           'sdk':str(sdk),'created_ns':time.time_ns()}
    save(guard_path,guard);return guard


def derive_query_command(build_command):
    need(build_command.count('build')==1,'One build subcommand required')
    index=build_command.index('build')
    startup=[x for x in build_command[:index] if not x.startswith(('--host_jvm_args=-Xmx','--host_jvm_args=-XX:ActiveProcessorCount='))]
    need('--batch' in startup,'Metadata query requires batch mode')
    args=[x for x in build_command[index+1:] if not x.startswith('//')]
    return startup+['--host_jvm_args=-Xmx768m','--host_jvm_args=-XX:ActiveProcessorCount=2',
                    'aquery','deps('+ENCODER_TARGET+')',*args,'--nofetch','--output=jsonproto',
                    '--include_commandline','--include_artifacts','--include_param_files']


def available():
    return int(next(x for x in Path('/proc/meminfo').read_text().splitlines() if x.startswith('MemAvailable:')).split()[1])*1024


def process_tree(root):
    rows={}
    for path in Path('/proc').iterdir():
        if not path.name.isdigit():continue
        try:
            status={x.split(':',1)[0]:x.split(':',1)[1].strip() for x in (path/'status').read_text().splitlines() if ':' in x}
            rows[int(path.name)]=(int(status['PPid']),int(status.get('VmRSS','0 kB').split()[0])*1024)
        except (OSError,KeyError,ValueError):continue
    children={root}
    for _ in range(20):
        old=set(children);children|={pid for pid,(parent,_) in rows.items() if parent in children}
        if children==old:break
    return children,sum(rows.get(pid,(0,0))[1] for pid in children)


def query_metadata(command,sdk,out):
    """Sixty-second query; diagnostic_guard additionally owns its full tree."""
    out=Path(out);need(not out.exists(),'Fresh metadata directory required');out.mkdir()
    need(available()>=5*GIB,'Metadata query needs existing five-GiB headroom')
    def limits():
        resource.setrlimit(resource.RLIMIT_CPU,(59,60));resource.setrlimit(resource.RLIMIT_CORE,(0,0))
        resource.setrlimit(resource.RLIMIT_FSIZE,(64*1024**2,64*1024**2))
        os.sched_setaffinity(0,set(sorted(os.sched_getaffinity(0))[:2]))
    env=dict(os.environ)
    for key in ('LD_PRELOAD','LD_AUDIT','LD_DEBUG','LD_DEBUG_OUTPUT','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS'):
        env.pop(key,None)
    start=time.monotonic();peak=0;stop=None
    with (out/'aquery.json').open('xb') as stdout,(out/'aquery.stderr.log').open('xb') as stderr:
        child=subprocess.Popen(command,cwd=sdk,env=env,stdout=stdout,stderr=stderr,stdin=subprocess.DEVNULL,
                               start_new_session=True,preexec_fn=limits)
        try:
            while child.poll() is None:
                _,rss=process_tree(child.pid);peak=max(peak,rss)
                if time.monotonic()-start>=60:stop='wall_limit'
                elif rss>2*GIB:stop='rss_limit'
                elif available()<GIB:stop='memory_reserve'
                if stop:os.killpg(child.pid,signal.SIGKILL);break
                time.sleep(.1)
            code=child.wait(timeout=5)
        finally:
            if child.poll() is None:os.killpg(child.pid,signal.SIGKILL);child.wait()
    try:os.killpg(child.pid,0);alive=True
    except ProcessLookupError:alive=False
    if alive:
        os.killpg(child.pid,signal.SIGKILL);stop=stop or 'surviving_process_group'
    result={'exit_code':code,'wall_seconds':time.monotonic()-start,'peak_tree_rss_bytes':peak,'stop_reason':stop,
            'command_sha256':digest(command),'graph':file_identity(out/'aquery.json'),'cpu_count':2,'java_heap_mib':768,
            'wall_limit_seconds':60,'rss_limit_bytes':2*GIB,'system_reserve_bytes':GIB,
            'process_group_empty':not alive}
    save(out/'query-receipt.json',result)
    need(code==0 and stop is None and not alive,'Bounded metadata query failed')
    return result


def policy_path(record,roots):
    relative=Path(record['path'])
    need(not relative.is_absolute() and '..' not in relative.parts,'Unsafe policy source path')
    root=roots[record['root']].resolve(strict=True);path=(root/relative).resolve(strict=True)
    need(path.is_relative_to(root),'Policy source escaped its declared root')
    checked_identity(path,record['identity']);return path


def verify_original_source(archive,origin_root):
    files=0
    with tarfile.open(archive,'r:*') as source:
        for item in source.getmembers():
            parts=Path(item.name).parts
            need(not item.name.startswith('/') and '..' not in parts,'Unsafe original-source archive')
            need(item.isdir() or item.isfile(),'Unexpected link/special member in original source')
            if not item.isfile():continue
            path=origin_root.joinpath(*parts[1:]);need(path.is_file(),'Original corresponding source is missing')
            data=source.extractfile(item).read();need(hashlib.sha256(data).hexdigest()==file_identity(path)['sha256'],
                                                     'Corresponding source changed and requires a reviewed patch')
            files+=1
    return files


def public_graph(graph):
    sources=[]
    for item in graph['sources']:
        if item['origin'] in ('system_headers','compiler_headers'):
            # No private absolute filesystem paths enter the capsule.
            sources.append({'origin':item['origin'],'basename':Path(item['path']).name,'bytes':item['bytes'],'sha256':item['sha256']})
        else:
            path = Path(item['path'])
            need(not path.is_absolute() and '..' not in path.parts and '\\' not in str(path),
                 'Unrecognized private source path cannot enter public metadata')
            sources.append({key:item[key] for key in ('path','origin','bytes','sha256')})
    return {'source_inputs':sources,'object_count':graph['object_count'],'origin_counts':graph['origin_counts'],
            'source_graph_sha256':digest(graph),'link_response_files':graph['response_file_sha256'],
            'compiler_depfiles':graph['dependency_file_sha256'],'required_action_outputs':graph['required_action_outputs']}


def collect(sdk,execroot,build_dir,guard_path,context,policy,expected_policy_sha,out,repository_cache, *, phase=lambda value: None):
    """Fresh hosted collection and archive writing, with no inference."""
    phase('guarded_metadata')
    sdk=Path(sdk).resolve(strict=True);execroot=Path(execroot).resolve(strict=True)
    build_dir=Path(build_dir);out=Path(out);need(not out.exists(),'Fresh diagnostic directory required');out.mkdir()
    need(digest(policy)==expected_policy_sha,'Reviewed source/license policy changed')
    guard=json.loads(Path(guard_path).read_text());build_path=build_dir/'build-status.json';build=json.loads(build_path.read_text())
    need(guard['epoch']=='before_same_run_build' and guard['context']==context,'Missing same-run guard binding')
    need(guard['policy_sha256']==expected_policy_sha,'Guard/policy mismatch')
    need(build.get('build_succeeded') is True and build.get('compilation_exited_before_quality') is True,'Successful completed compile required')
    need(build.get('diagnostic_compile_guard_sha256')==digest(guard),'Build did not bind this precompile guard')
    need(build.get('build_command_sha256')==guard['build_command_sha256'],'Actual build command differs from guard')
    snapshot=sdk_snapshot(sdk);need(digest(snapshot)==build['source_snapshot_sha256']==guard['source_snapshot_sha256'],'SDK source changed across build')
    for path,pin in guard['compiler_tools'].items():checked_identity(path,pin)
    for path,pin in guard['compiler_tool_libraries'].items():checked_identity(path,pin)
    probe=(sdk/'bazel-bin/experimental/hosted_audio_quality_20261006/pinned_encoder_probe').resolve(strict=True)
    checked_identity(probe,build['binaries']['pinned_encoder_probe'])
    # execroot/external consists of repository symlinks. Resolve policy paths
    # against Bazel's actual repository root so legitimate aliases stay inside
    # one checked root without accepting arbitrary symlink escapes.
    phase('dependencies')
    roots={'sdk':sdk,'external':execroot.parent.parent/'external','repository_cache':Path(repository_cache)}
    env=dict(os.environ)
    for key in ('LD_PRELOAD','LD_AUDIT','LD_LIBRARY_PATH','LD_DEBUG','LD_DEBUG_OUTPUT'):env.pop(key,None)
    listing=subprocess.check_output(['ldd',str(probe)],text=True,env=env,timeout=10)
    (out/'encoder-dependencies.private.txt').write_text(listing)
    dependencies=parse_listing(listing);pins={str(Path(p).resolve(strict=True)):v for p,v in build['dynamic_libraries'].items()}
    entries=[];aliases={};system=[];used={'sdk'}
    for alias,path in sorted(dependencies.items()):
        need(str(path) in pins,'Dependency is absent from exact build receipt')
        pin=checked_identity(path,pins[str(path)]);metadata=elf_metadata(path)
        if SYSTEM_NAMES.fullmatch(alias):
            need(any(path.is_relative_to(root.resolve()) for root in SYSTEM_ROOTS if root.exists()),'Unexpected system-library location')
            system.append({'alias':alias,'identity':pin,'elf':metadata,'disposition':'excluded'});continue
        origin=artifact_origin(path,execroot);need(origin in policy['origins'],'Unknown runtime origin');used.add(origin)
        need('prebuilt' not in path.parts,'Unreviewed prebuilt runtime dependency')
        aliases[alias]={'origin':origin,'identity':pin,'elf':metadata}
        entries.append({'capsule_path':'lib/'+alias,'source_path':str(path),'identity':pin,'origin':origin,'kind':'runtime_elf'})
    for metadata in [elf_metadata(probe)]+[v['elf'] for v in aliases.values()]:
        for alias in metadata['needed']:need(alias in dependencies,'Incomplete per-probe dependency closure')
    phase('source_graph')
    graph=trace(probe,execroot,listing,sdk);need(not graph['unresolved'],'Unresolved source/object/link graph')
    for origin in graph['origin_counts']:
        if origin in ('system_headers','compiler_headers'):continue
        origin='sdk' if origin=='sdk_or_generated' else origin
        need(origin in policy['origins'],'Unknown compiled/header origin: '+origin);used.add(origin)
    phase('query')
    command=derive_query_command(guard['build_command']);query=query_metadata(command,sdk,out/'query-private')
    phase('action_binding')
    raw_graph=json.loads((out/'query-private/aquery.json').read_text())
    actions=action_metadata(raw_graph,execroot,graph['required_action_outputs'],strict=True,
                            approved_tool_paths=set(guard['compiler_tools']))
    approved_tools={digest(pin) for pin in guard['compiler_tools'].values()}
    need(all(digest(x['tool_executable']) in approved_tools for x in actions['actions']),'Compiler/link tool was not guarded before compilation')
    need(digest(sdk_snapshot(sdk))==digest(snapshot),'SDK source changed during diagnostic query')
    for path,pin in guard['compiler_tools'].items():checked_identity(path,pin)
    for path,pin in guard['compiler_tool_libraries'].items():checked_identity(path,pin)
    for relative,pin in graph['required_action_outputs'].items():checked_identity(execroot/relative,pin)
    for item in graph['sources']:
        source=Path(item['resolved_path'])
        checked_identity(source,{k:item[k] for k in ('bytes','sha256')})
    entries.append({'capsule_path':'bin/pinned_encoder_probe','source_path':str(probe),
                    'identity':build['binaries']['pinned_encoder_probe'],'origin':'sdk','kind':'probe'})
    phase('licenses')
    for origin in sorted(used):
        record=policy['origins'][origin]
        policy_path(record['declaration'],roots)
        for patch in record.get('patches',[]):policy_path(patch,roots)
        for destination,notice in record['licenses'].items():
            path=policy_path(notice,roots)
            entries.append({'capsule_path':destination,'source_path':str(path),'identity':notice['identity'],'origin':origin,'kind':'license'})
    for destination,item in policy['source_archives'].items():
        path=policy_path(item,roots);need(item['origin'] in used,'Unexpected corresponding-source origin')
        count=verify_original_source(path,roots['external']/item['origin'])
        need(count==item['regular_files'],'Unexpected original-source file count')
        entries.append({'capsule_path':destination,'source_path':str(path),'identity':item['identity'],'origin':item['origin'],'kind':'source_archive'})
    phase('packaging')
    bindings={**context,'build_receipt_sha256':file_identity(build_path)['sha256'],
              **{k:build[k] for k in ('source_snapshot_sha256','recipe_manifest_sha256','android_source_receipt_sha256','reviewed_patch_sha256')},
              'build_command_sha256':guard['build_command_sha256'],'bazel_sha256':BAZEL_SHA,'policy_sha256':expected_policy_sha}
    validate_bindings(bindings)
    cpu_block=Path('/proc/cpuinfo').read_text().split('\n\n')[0]
    cpu={k.strip():v.strip() for k,v in (x.split(':',1) for x in cpu_block.splitlines() if ':' in x)
         if k.strip() in ('vendor_id','cpu family','model','model name','stepping','flags')}
    capture={'schema_version':1,'provenance_epoch':'same_run_post_build','complete':True,'blockers':[],
             'bindings':bindings,'files':entries,'runtime_aliases':aliases,'system_libraries':system,
             'used_origins':sorted(used),'probe_identity':build['binaries']['pinned_encoder_probe'],
             'compiler_actions':actions,'header_input_graph':public_graph(graph),'host_cpu':cpu,
             'proof':{key:True for key in ('source_unchanged','build_completed','no_compilation_during_query',
                 'compiler_actions_complete','dependency_closure_complete','licenses_complete','system_libraries_excluded','link_arguments_match_actions')}}
    capture['proof']['query_graph_sha256']=query['graph']['sha256']
    capture['compiler_actions']['tool_library_identities']=[{'basename':Path(path).name,'identity':pin}
        for path,pin in sorted(guard['compiler_tool_libraries'].items())]
    save(out/'capture.private.json',capture)
    archive=out/('encoder-runtime-'+str(context['run_id'])+'-'+str(context['run_attempt'])+'.tar.gz')
    return write_capsule(capture,policy,expected_policy_sha,archive)


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    status = None
    try:
        build_dir = build_dir_from_argv(argv)
        try:
            context = validate_context(github_context())
        except Exception:
            status = Status(build_dir, None)
            raise
        # Status starts before full argument parsing and every former preflight.
        status = Status(build_dir, context, instance_from_argv(argv))
        p = Parser(description=__doc__)
        for name in ('build-dir','policy','guard','out','repository-cache'):
            p.add_argument('--'+name,type=Path,required=True)
        p.add_argument('--expected-policy-sha256',required=True)
        p.add_argument('--status-instance',help=argparse.SUPPRESS)
        a = p.parse_args(argv)
        need(a.status_instance is None or a.status_instance == status.invocation_id,
             'Matching diagnostic status instance required')
        status.phase('preflight')
        need(not a.out.exists(),'Fresh diagnostic output required')
        status.phase('guarded_metadata')
        build=json.loads((a.build_dir/'build-status.json').read_text())
        sdk=Path(build['sdk']).resolve(strict=True)
        execroot=(sdk/'bazel-bin').resolve(strict=True).parents[2]
        result=collect(sdk,execroot,a.build_dir,a.guard,context,json.loads(a.policy.read_text()),
                       a.expected_policy_sha256,a.out,a.repository_cache,phase=status.phase)
        status.passed()
        print(json.dumps({'complete':True,'archive':result['archive'],'bindings':result['bindings']},indent=2))
        return 0
    except Exception as error:
        # Tracebacks are retained only in the guard's existing private log.
        # Standalone callers also receive only the bounded public projection.
        if status is not None:
            if status.value['context'] is not None: status.failed(error)
            if '--status-instance' in argv: traceback.print_exc()
            print(json.dumps(status.value, sort_keys=True))
        else:
            print(json.dumps({'complete':False,'error_code':'invalid_receipt'}))
        return 2


if __name__=='__main__':raise SystemExit(main())
