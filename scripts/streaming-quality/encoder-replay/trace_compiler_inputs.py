#!/usr/bin/env python3
"""Read link/archive response files and depfiles; never runs the probe or compiler."""
import argparse
import collections
import hashlib
import json
from pathlib import Path
from replay_artifact_inventory import describe, parse_listing, SYSTEM_NAMES


def source_record(name, execroot, sdk):
    """Normalize only verified source roots; physical locations stay private."""
    path = Path(name)
    if '\\' in str(path) or (not path.is_absolute() and path.parts[:1] == ('..',)):
        raise ValueError('Source input escapes reviewed roots')
    execroot, sdk = Path(execroot).resolve(strict=True), Path(sdk).resolve(strict=True)
    # Bazel's execroot/external is a directory of per-repository symlinks,
    # not necessarily a symlink itself. Use the same canonical repository root
    # that hosted_capture verifies against the reviewed declaration policy.
    external = (execroot.parent.parent/'external').resolve()
    # Compiler depfiles legitimately spell in-root parents (GCC's absolute
    # include path and gemmlowp/public/../internal are common examples). Select
    # the trusted namespace from the original spelling, then require the fully
    # resolved file to remain in that namespace before reading its contents.
    system_roots = (Path('/usr'), Path('/lib'))
    lexical = path
    system = False
    if path.is_absolute():
        if path.is_relative_to(execroot): lexical = path.relative_to(execroot)
        elif path.is_relative_to(sdk): lexical = path.relative_to(sdk)
        elif path.is_relative_to(external): lexical = Path('external')/path.relative_to(external)
        elif any(path.is_relative_to(root) for root in system_roots): system = True
        else: raise ValueError('Source input is outside reviewed source/system roots')
    if lexical.parts[:1] == ('external',) and (len(lexical.parts) < 3 or lexical.parts[1] in ('.', '..')):
        raise ValueError('External source repository path is incomplete')
    # Bazel creates a symlink forest for a repository's public include prefix.
    # Only this named form may resolve from generated outputs back into that
    # same external repository. It may not impersonate a different repository.
    virtual_origin = None
    if (len(lexical.parts) >= 7 and lexical.parts[0] == 'bazel-out'
            and lexical.parts[2:4] == ('bin', 'external')
            and '_virtual_includes' in lexical.parts[5:-1]):
        marker = lexical.parts.index('_virtual_includes', 5)
        if '..' in lexical.parts[:marker] or len(lexical.parts) < marker + 3:
            raise ValueError('Virtual source repository path is incomplete')
        virtual_origin = lexical.parts[4]
        if virtual_origin in ('.', '..'):
            raise ValueError('Virtual source repository path is incomplete')
    physical = (path if path.is_absolute() else execroot/path).resolve(strict=True)
    def external_repository(origin):
        try:
            expected = (external/origin).resolve(strict=True)
            alias = (execroot/'external'/origin).resolve(strict=True)
        except OSError:
            raise ValueError('External source repository alias is unavailable') from None
        if not expected.is_relative_to(external) or alias != expected:
            raise ValueError('External source repository alias escaped its verified root')
        return expected
    def external_source(origin):
        expected = external_repository(origin)
        if not physical.is_relative_to(expected):
            raise ValueError('External source repository alias escaped its verified root')
        return 'external/'+origin+'/'+str(physical.relative_to(expected))
    if system:
        if not any(physical.is_relative_to(root.resolve()) for root in system_roots):
            raise ValueError('System source escaped its verified root')
        return dict(path=str(physical), resolved_path=str(physical), origin='system_headers',
                    **describe(physical))
    if virtual_origin is not None:
        expected = external_repository(virtual_origin)
        if physical.is_relative_to(expected):
            public = 'external/'+virtual_origin+'/'+str(physical.relative_to(expected))
        else:
            relative = physical.relative_to(execroot) if physical.is_relative_to(execroot) else None
            parts = relative.parts if relative is not None else ()
            if (len(parts) < 6 or parts[0] != 'bazel-out' or parts[2:4] != ('bin', 'external')
                    or parts[4] != virtual_origin):
                raise ValueError('Virtual source repository alias escaped its verified root')
            public = str(relative)
    elif lexical.parts[:1] == ('external',):
        public = external_source(lexical.parts[1])
    elif physical.is_relative_to(execroot):
        public = str(physical.relative_to(execroot))
    elif physical.is_relative_to(sdk):
        public = str(physical.relative_to(sdk))
    else:
        # Do not put an unrecognized private pathname in a public failure log.
        raise ValueError('Source input is outside reviewed source/system roots')
    if public.startswith('external/'):
        origin = public.split('/')[1]
    elif public.startswith('bazel-out/') and '/external/' in public:
        origin = public.split('/external/')[1].split('/')[0]
    else:
        origin = 'sdk_or_generated'
    return dict(path=public, resolved_path=str(physical), origin=origin, **describe(physical))


def trace(probe, execroot, listing, sdk):
    execroot=execroot.resolve(strict=True)
    seen=set();objects=set();responses={};depfiles={};unresolved=[]

    def local(path):
        path=Path(path)
        return path if path.is_absolute() else execroot/path

    def walk(path):
        path=local(path)
        if path in seen:return
        seen.add(path)
        if len(seen)>4096:raise ValueError('Link graph exceeds expected size')
        response=Path(str(path)+'-2.params')
        if not response.is_file():unresolved.append(str(response));return
        responses[str(response.relative_to(execroot))]=describe(response)['sha256']
        for argument in response.read_text().splitlines():
            if argument.startswith('@'):raise ValueError('Nested response file must be captured explicitly')
            if not argument.endswith(('.a','.o')):continue
            item=local(argument)
            if item==path:continue
            if item.suffix=='.a':walk(item)
            else:objects.add(item)

    for alias,path in parse_listing(listing).items():
        if not SYSTEM_NAMES.fullmatch(alias):walk(path)
    walk(probe)
    headers=set()
    for obj in sorted(objects):
        depfile=obj.with_suffix('.d')
        if not depfile.is_file():unresolved.append(str(depfile));continue
        depfiles[str(depfile.relative_to(execroot))]=describe(depfile)['sha256']
        text=depfile.read_text().replace('\\\n',' ')
        if ': ' not in text:raise ValueError('Unsupported depfile grammar')
        headers.update(text.split(': ',1)[1].split())
        if len(headers)>20000:raise ValueError('Source graph exceeds expected size')
    origins=collections.Counter();sources=[]
    for name in sorted(headers):
        item = source_record(name, execroot, sdk)
        origins[item['origin']]+=1
        sources.append(item)
    return {'scope':'Recursive exact link/archive and compiler-depfile inventory; per-action compiler flags require separate action evidence.',
            'response_file_sha256':responses,'dependency_file_sha256':depfiles,
            'object_count':len(objects),'sources':sources,'origin_counts':dict(origins),'unresolved':unresolved,
            'required_action_outputs':{str(path.relative_to(execroot)):describe(path) for path in sorted(seen|objects)}}


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('probe','execroot','sdk','dependency-listing','out'):p.add_argument('--'+name,type=Path,required=True)
    args=p.parse_args()
    if args.out.exists():raise ValueError('Fresh output required')
    result=trace(args.probe,args.execroot,args.dependency_listing.read_text(),args.sdk)
    args.out.write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps({'objects':result['object_count'],'origins':result['origin_counts'],'unresolved':result['unresolved']},indent=2))
    return 2 if result['unresolved'] else 0


if __name__=='__main__':raise SystemExit(main())
