from pathlib import Path
import argparse,hashlib,json,subprocess,struct,re,importlib.util
parser=argparse.ArgumentParser(description='Independent exact-test classfile delta and existing release DEX boundary audit. Does not execute R8 or Android.')
for name in ['repo','baseline-evidence','repaired-evidence','build-evidence','dex-auditor','out']: parser.add_argument('--'+name,type=Path,required=True)
args=parser.parse_args()
repo,baseline,compiled,build,auditor,root=(getattr(args,k).resolve() for k in ['repo','baseline_evidence','repaired_evidence','build_evidence','dex_auditor','out'])
root.mkdir(parents=True,exist_ok=True)
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
source=repo/'app/src/androidTest/java/com/battlesbudz/jarvis/v2/verification/ReleaseJourneyTest.kt'
current=json.loads((compiled/'journey-receipt.json').read_text());old=json.loads((baseline/'journey-receipt.json').read_text())
assert current['exit']==0 and current['source_sha256']==sha(source)
assert sha(compiled/'Test45CompileFixture.kt')==current['fixture_sha256']
base_source=subprocess.check_output(['git','-C',str(repo),'show','13a62a8374610fefda9b93c3213c559a9f97bef7:'+str(source.relative_to(repo))])
assert hashlib.sha256(base_source).hexdigest()==old['source_sha256']
def pool(path):
 data=path.read_bytes(); count=struct.unpack_from('>H',data,8)[0];out=[None]*count;p=10;i=1
 while i<count:
  t=data[p];p+=1
  if t==1:
   n=struct.unpack_from('>H',data,p)[0];p+=2;v=data[p:p+n].decode('utf-8','replace');p+=n
  elif t in (3,4): v=data[p:p+4];p+=4
  elif t in (5,6): v=data[p:p+8];p+=8;i+=1
  elif t in (7,8,16,19,20):v=struct.unpack_from('>H',data,p)[0];p+=2
  elif t in (9,10,11,12,17,18):v=struct.unpack_from('>HH',data,p);p+=4
  elif t==15:v=struct.unpack_from('>BH',data,p);p+=3
  else:raise ValueError((path,t))
  out[i]=(t,v);i+=1
 return out
stem='com/battlesbudz/jarvis/v2/verification/Test45CompileFixture'
def collect(folder,label):
 pending=[stem];seen=set();refs=set();fields=set();paths=[];external_types=set()
 while pending:
  cls=pending.pop()
  if cls in seen:continue
  seen.add(cls);p=folder/(cls+'.class');assert p.exists(),p;paths.append(p)
  cp=pool(p)
  for x in cp:
   if not x:continue
   t,v=x
   if t==7:
    n=cp[v][1]
    if not n.startswith(stem):external_types.add(n)
    if n.startswith(stem) and (folder/(n+'.class')).exists():pending.append(n)
   if t in (9,10,11):
    owner=cp[cp[v[0]][1]][1];nt=cp[v[1]][1];name=cp[nt[0]][1];desc=cp[nt[1]][1]
    if not owner.startswith(stem): (fields if t==9 else refs).add((owner,name,desc))
 command=['java','--module','jdk.jdeps/com.sun.tools.javap.Main','-classpath',str(folder),'-p','-c',*[str(p.relative_to(folder))[:-6].replace('/','.') for p in paths]]
 output=subprocess.check_output(command,text=True);log=root/('independent-'+label+'-bytecode.txt');log.write_text(output)
 return refs,fields,{'referenced_external_types':sorted(external_types),'files':{str(p.relative_to(folder)):sha(p) for p in sorted(paths)},'javap':{'path':log.name,'sha256':sha(log)},'scope':'Reachable exact test45 fixture classes only, traversed through classfile constant pool; stale unreachable output classes excluded.'}
a,af,ar=collect(baseline/'journey-out','baseline');b,bf,br=collect(compiled/'journey-out','repaired')
print('added refs',sorted(b-a));print('removed refs',sorted(a-b));print('added fields',sorted(bf-af))
spec=importlib.util.spec_from_file_location('dex',auditor);dex=importlib.util.module_from_spec(spec);spec.loader.exec_module(dex)
apps={key:dex.read_apk(build/'jarvis-os-v2-release-apk'/file) for key,file in [('normal','app-release.apk'),('compact','app-compact.apk')]};test=dex.read_apk(build/'jarvis-verification-test-apk/app-release-androidTest.apk')
mapping=build/'jarvis-os-v2-release-r8-reports/mapping.txt';mt=mapping.read_text();mapped=re.search(r'^androidx\.compose\.runtime\.EffectsKt -> (\S+):$',mt,re.M)[1];mapped_desc=dex.descriptor(mapped)
newrefs=[]
for owner,name,desc in sorted(b-a):
 item={'owner':owner,'name':name,'descriptor':desc}
 item['compiled_callsite_lines']=[line.strip() for line in (root/'independent-repaired-bytecode.txt').read_text().splitlines() if owner+'.'+name+':'+desc in line.replace(chr(34),'') and re.search(r'\binvoke(?:static|special|virtual|interface)\b',line)]
 assert item['compiled_callsite_lines'],item
 item['baseline_instrumentation_method_table_refs']=[m for m in test['methods'] if m['owner']=='L'+owner+';' and m['name']==name and m['descriptor']==desc]
 if owner=='androidx/compose/runtime/EffectsKt':
  item['actual1248_app_definitions']={key:[m for m in val['methods'] if m.get('definition') and m['owner']==mapped_desc and m['name']=='h'] for key,val in apps.items()}
  item['finding']='New direct test ABI; old R8 SideEffect dropped int parameter. Exact descriptor keep required. Fresh fixed R8 APK remains unverified.'
 elif owner=='androidx/compose/runtime/Composer':
  item['actual1248_app_definitions']={key:[m for m in val['methods'] if m.get('definition') and m['owner']=='L'+owner+';' and m['name']==name and m['descriptor']==desc] for key,val in apps.items()}
  assert all(item['actual1248_app_definitions'].values())
  item['finding']='Already protected by existing -keep class androidx.compose.runtime.Composer { *; }; exact definitions found in both1248 app variants.'
 elif owner.startswith('java/'):
  item['actual1248_app_method_table_refs']={key:[m for m in val['methods'] if m['owner']=='L'+owner+';' and m['name']==name and m['descriptor']==desc] for key,val in apps.items()}
  assert all(item['actual1248_app_method_table_refs'].values())
  item['finding']='Android boot-class AtomicInteger API; outside application R8 ownership. Constructor/get references already occur in baseline full test DEX; set is newly referenced by this acknowledgement.'
 else:
  item['finding']='Review required'
 newrefs.append(item)
original=base_source.decode();now=source.read_text();method=lambda s:s[s.index('    @Test fun test45_pipelineBenchmarksScoreOriginalAsrPersistAndExportRedactedEvidence'):s.index('\n    @OptIn',s.index('    @Test fun test45_pipelineBenchmarksScoreOriginalAsrPersistAndExportRedactedEvidence'))]
assertion=lambda s:[x.strip() for x in method(s).splitlines() if re.search(r'\bassert\w*\(',x)]
assert assertion(original)==assertion(now)
keep_path=repo/'app/proguard-rules.pro';keep=keep_path.read_text();rule='-keep class androidx.compose.runtime.EffectsKt {\n    public static void SideEffect(kotlin.jvm.functions.Function0, androidx.compose.runtime.Composer, int);\n}'
assert keep.count(rule)==1
assert {r[0] for r in b-a if r[0].startswith('androidx/compose/')}=={'androidx/compose/runtime/EffectsKt','androidx/compose/runtime/Composer'}
assert not any('DisposableEffect' in x[0] or 'DisposableEffect' in x[1] for x in b-a)
report={'schema':1,'review_passed':True,'baseline_commit':'13a62a8374610fefda9b93c3213c559a9f97bef7','current_source_sha256':sha(source),'baseline_source_sha256':hashlib.sha256(base_source).hexdigest(),'exact_compile_receipt':{'path':str((compiled/'journey-receipt.json').resolve()),'sha256':sha(compiled/'journey-receipt.json'),'exit':current['exit'],'source_bound':True},'original_compile_receipt':{'path':str((baseline/'journey-receipt.json').resolve()),'sha256':sha(baseline/'journey-receipt.json'),'original_different_commit_same_exact_source_verified':True},'classfile_audit':{'baseline':ar,'repaired':br,'new_external_method_references':newrefs,'removed_external_method_references':[{'owner':o,'name':n,'descriptor':d} for o,n,d in sorted(a-b)],'new_external_field_references':[{'owner':o,'name':n,'descriptor':d} for o,n,d in sorted(bf-af)]},'actual1248':{'apps':{key:{k:val[k] for k in ['path','sha256','dex_sha256']} for key,val in apps.items()},'test':{k:test[k] for k in ['path','sha256','dex_sha256']},'mapping':{'path':str(mapping.resolve()),'sha256':sha(mapping),'EffectsKt':mapped,'sideeffect_residual_signature':'(Lkotlin/jvm/functions/Function0;Landroidx/compose/runtime/Composer;)V'},'baseline_test_effects_refs':[m for m in test['methods'] if m['owner']==mapped_desc]},'keep_rule':{'path':str(keep_path.relative_to(repo)),'sha256':sha(keep_path),'exact_text':rule,'validated':'Retains class and only public static SideEffect(Function0, Composer, int), with optimization disabled for that method; existing keep rules retain Function0 and Composer. No broad Effects/runtime keep added.'},'preserved_assertion_lines':len(assertion(original)),'limits':['Static classfile/DEX/mapping audit, not new R8 execution.','Existing1248 APKs prove the previous descriptor transformation; they do not verify repaired APK bytes.','Method-table entries are encoded references, not dynamic call counts.','Fixture compiles exact test45 method and production benchmark sources, with activity/navigation TODO seams; not full ReleaseJourneyTest compilation or Android instrumentation.']}
report['classfile_audit']['new_external_type_references']=sorted(set(br.pop('referenced_external_types'))-set(ar.pop('referenced_external_types')))
host_path=compiled/'host-receipt.json'
host=json.loads(host_path.read_text())
assert host['api_compile_receipt_sha256']==sha(compiled/'journey-receipt.json')
harness=repo/'docs/verification/evidence/benchmark-export-recreation-2026-10-09/harness'
for name,digest in host['harness_sha256'].items(): assert sha(harness/name)==digest,(name,'stale host source receipt')
assert all(sha(repo/name)==digest for name,digest in host['source_sha256'].items())
assert all(record.get('exit',0)==0 for record in host['records'])
report['runtime_review']={'host_receipt':{'path':str(host_path),'sha256':sha(host_path)},'harness_sha256':host['harness_sha256'],'logs':{name:sha(compiled/name) for name in ['runtime-tests.log','focused-unit-tests.log','compile-runtime.log','journey-compile.log']},'result':'Six lifecycle cases and eleven existing focused JVM tests pass. Current harness/source hashes match reviewed-host receipt.','case_review':['The two negative cases deliberately admit callback before replacement commit and control transport truncation/write timing. They reproduce both observed failure states but do not prove unique device schedules.','Eight replacement iterations deliver a real old raw registry result while a fresh same-scope payload is pending; zero old URI opens, untouched fresh busy/payload state and exact fresh output are checked.','Pre-coroutine-start disposal verifies zero provider opens.','Queued IO case occupies both configured IO workers, snapshots nonempty owner job children, disposes the composition, releases workers, awaits owner completion and joins workers plus owner jobs before asserting zero opens. No FIFO-dispatch assumption remains.','Picker cancellation releases the gate; a new save frozen at epoch1 survives same-owner recomposition to epoch99 and writes exactly its original payload.'],'limits':['Host invokes actual Compose1.7.6, Activity1.10.0 registry and exact production composable/transport classes.','ContentResolver is a controlled stream adapter; Trace and Build.VERSION are explicit host replacements.','Manual coroutine dispatcher, frame clock and blocking latches impose legal schedules; no real Android ContentResolver/FileProvider IPC, Activity.setContent UI scheduling, accessibility, or external picker executes.','Fresh R8 APK, full release instrumentation and five-profile matrix remain required.']}
report['audit_script_sha256']=sha(Path(__file__))
report['dex_parser_sha256']=sha(auditor)
report['new_reference_count']=len(newrefs)
report['actual1248']['missing_original_three_argument_sideeffect']=all(not any(m.get('definition') and m['owner']==mapped_desc and m['descriptor']=='(Lkotlin/jvm/functions/Function0;Landroidx/compose/runtime/Composer;I)V' for m in val['methods']) for val in apps.values())
(root/'independent-review.json').write_text(json.dumps(report,indent=2)+'\n')
print('wrote',root/'independent-review.json')
