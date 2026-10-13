#!/usr/bin/env python3
"""Hash saved Bazel aquery action arguments and actual compiler/linker binaries.

Consumes already captured jsonproto output; never runs Bazel or a compiler.
The aquery must use the exact successful build's configuration and source snapshot.
No raw arguments or environment-variable values are exported.
"""
import argparse
import hashlib
import json
from pathlib import Path
from replay_artifact_inventory import describe


def action_metadata(graph, execroot, required_outputs=None, strict=True, approved_tool_paths=None):
    fragments={str(x['id']):x for x in graph.get('pathFragments',[])}
    cache={}
    def fragment(key,stack=()):
        key=str(key)
        if key in cache:return cache[key]
        if key in stack:raise ValueError('Cyclic Bazel path fragments')
        item=fragments[key]
        parent=str(item.get('parentId','0'))
        result=(fragment(parent,stack+(key,))+'/' if parent!='0' else '')+item['label']
        if Path(result).is_absolute() or '..' in Path(result).parts:raise ValueError('Unsafe action output path')
        cache[key]=result;return result
    artifacts={str(x['id']):x.get('execPath') or fragment(x['pathFragmentId']) for x in graph['artifacts']}
    for path in artifacts.values():
        if Path(path).is_absolute() or '..' in Path(path).parts:raise ValueError('Unsafe action artifact path')
    rows=[];seen=set();blockers=[];link_param_matches=[]
    for action in graph['actions']:
        if action['mnemonic'] not in ('CppCompile','CppLink','CppArchive'):continue
        paths=[artifacts[str(x)] for x in action.get('outputIds',[])]
        if required_outputs is not None and not set(paths)&set(required_outputs):continue
        arguments=action.get('arguments')
        if not arguments or not action.get('outputIds'):raise ValueError('Missing action arguments/outputs')
        param_files=action.get('paramFiles',[])
        if any(x.startswith('@') for x in arguments) and not param_files:
            raise ValueError('Expanded response-file arguments must be captured with aquery')
        all_arguments=arguments+[arg for item in param_files for arg in item['arguments']]
        forbidden={'-static','--static','-Bstatic','-static-pie','-static-libgcc','-static-libstdc++'}
        if any(forbidden & set(arg.split(',')) for arg in all_arguments):
            raise ValueError('Static system-runtime linking requires separate obligations review')
        executable=Path(arguments[0])
        if not executable.is_absolute():executable=execroot/executable
        if approved_tool_paths is not None and str(executable.resolve()) not in approved_tool_paths:
            raise ValueError('Compiler/link tool is outside the precompile allowlist')
        executable_pin=describe(executable)
        with executable.open('rb') as stream:
            if stream.read(4)!=b'\x7fELF':blockers.append('Compiler wrapper rejected: '+str(executable))
        outputs={}
        for key in action['outputIds']:
            path=artifacts[str(key)]
            if path in seen:raise ValueError('Duplicate producer for output '+path)
            if not (execroot/path).resolve().is_relative_to(execroot.resolve()):
                raise ValueError('Action output symlink escaped build root')
            if not (execroot/path).is_file():
                if required_outputs is not None and path not in required_outputs:continue
                raise ValueError('Required built output is absent: '+path)
            seen.add(path);outputs[path]=describe(execroot/path)
            if required_outputs is not None and path in required_outputs and outputs[path]!=required_outputs[path]:
                raise ValueError('Required output changed since source/input capture')
        for param in param_files:
            param_path=Path(param['execPath'])
            if param_path.is_absolute() or '..' in param_path.parts:raise ValueError('Unsafe response-file path')
            actual=(execroot/param_path).read_text().splitlines()
            if actual!=param['arguments']:raise ValueError('Aquery arguments differ from existing link/archive response file')
            link_param_matches.append(str(param_path))
        rows.append({'mnemonic':action['mnemonic'],'action_key':action.get('actionKey'),
                     'configuration_id':str(action.get('configurationId','')),
                     'arguments_sha256':hashlib.sha256(json.dumps({'argv':arguments,'param_files':param_files},sort_keys=True,separators=(',',':'),ensure_ascii=False).encode()).hexdigest(),
                     'action_environment_sha256':hashlib.sha256(json.dumps(action.get('environmentVariables',[]),sort_keys=True,separators=(',',':'),ensure_ascii=False).encode()).hexdigest(),
                     'argument_count':len(arguments),'tool_executable':executable_pin,
                     'tool_execroot_relative_path':str(executable.relative_to(execroot)) if executable.is_relative_to(execroot) else executable.name,
                     'outputs':outputs})
    if not rows:raise ValueError('No compiler/linker actions found')
    if required_outputs is not None and set(required_outputs)-seen:
        raise ValueError('Missing action producers for required output(s): '+str(sorted(set(required_outputs)-seen)[:5]))
    if strict and blockers:raise ValueError('; '.join(sorted(set(blockers))))
    return {'schema_version':1,'source':'Saved exact-config Bazel aquery jsonproto',
            'scope':'Read-only hashes; no executable execution or uploaded command/environment strings.',
            'complete':not blockers,'blockers':sorted(set(blockers)),
            'required_output_count':len(required_outputs) if required_outputs is not None else len(seen),
            'verified_response_files':sorted(set(link_param_matches)),'actions':rows}


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('aquery','execroot','out'):p.add_argument('--'+name,type=Path,required=True)
    args=p.parse_args()
    if args.out.exists():raise ValueError('Fresh output required')
    if args.aquery.stat().st_size>64*1024**2:raise ValueError('Oversized aquery input')
    result=action_metadata(json.loads(args.aquery.read_text()),args.execroot.resolve(strict=True))
    result['aquery_sha256']=describe(args.aquery)['sha256']
    args.out.write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps({'actions':len(result['actions']),'aquery_sha256':result['aquery_sha256']}))


if __name__=='__main__':main()
