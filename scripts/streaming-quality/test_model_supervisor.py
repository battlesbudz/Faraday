"""Tiny synthetic Linux processes only: no native/Java build, download, or model."""
import contextlib
import io
import json
import os
from pathlib import Path
import resource
import signal
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import bounded_exec as b
import build_probes
from common import GateError, describe, write
import export_evidence
import run_quality

MEMORY = {'host_mem_available_bytes': 12*b.GIB, 'cgroup_remaining_bytes': None,
          'effective_available_bytes': 12*b.GIB}


class ModelSupervisorTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.addCleanup(patch.stopall)
        patch.object(b, '_CLEANUP_UNCERTAIN', False).start()
        patch.object(b, '_CLEANUP_SECONDS', .15).start()
        patch.object(b, '_POLL_SECONDS', .02).start()
        patch.dict(os.environ, GEMMA_QUALITY_COMPUTE_SLOT='confirmed_by_owner').start()
        self.memory = patch.object(b, 'compile_memory', return_value=dict(MEMORY)).start()

    def run_small(self, code, *, wall=1, name='run'):
        out=self.root/name
        command=[sys.executable, '-c', 'import os;os.chdir('+repr(str(self.root))+');'+code]
        with patch.dict(b.BUDGET, wall_seconds=wall), contextlib.redirect_stdout(io.StringIO()) as console:
            report=b.run(command,out)
        return report,out,console.getvalue()

    def assert_reaped(self, report):
        self.assertTrue(report['cleanup_verified'],report)
        self.assertEqual(report['surviving_process_count'],0)
        self.assertEqual(report['unreaped_zombie_count'],0)
        self.assertTrue(report['all_children_reaped'])
        for name in ('parent.pid','child.pid','second.pid'):
            path=self.root/name
            if path.exists(): self.assertFalse(Path('/proc/'+path.read_text()).exists(),name)

    def state(self):
        value=build_probes.ctypes.c_int()
        self.assertEqual(build_probes.ctypes.CDLL(None).prctl(37,build_probes.ctypes.byref(value),0,0,0),0)
        return signal.getsignal(signal.SIGTERM),signal.getsignal(signal.SIGINT),value.value

    def make_control(self, code='print("synthetic control")', mode='projected_null'):
        binary=self.root/'native_conversation_quality_probe'
        binary.write_text('#!'+sys.executable+'\n'+code+'\n');binary.chmod(0o700)
        identity=describe(binary)
        request=self.root/'request.json'
        write(request,dict(native_binary_sha256=identity['sha256'], mode=mode, case='transcribe',
              context_tokens=640,max_output_tokens=64,audio_embedding_tap=True,resource_profile=b.FULL_E2B_PROFILE))
        return binary,request,identity

    def test_effective_memory_respects_finite_cgroup_ancestors(self):
        files={'/proc/meminfo':'MemAvailable: 12582912 kB\n', '/proc/self/cgroup':'0::/child\n',
               '/sys/fs/cgroup/child/memory.max':str(10*b.GIB),
               '/sys/fs/cgroup/child/memory.current':str(b.GIB),
               '/sys/fs/cgroup/memory.max':str(8*b.GIB),
               '/sys/fs/cgroup/memory.current':str(3*b.GIB)}
        with patch.object(Path,'read_text',lambda path,*args,**kwargs:files[str(path)]), \
             patch.object(Path,'exists',lambda path:str(path) in files), \
             patch.object(Path,'resolve',lambda path:path), patch.object(Path,'is_dir',return_value=True):
            value=build_probes.compile_memory()
        self.assertEqual(value,dict(host_mem_available_bytes=12*b.GIB,cgroup_remaining_bytes=5*b.GIB,
                                    effective_available_bytes=5*b.GIB))

    def test_address_space_failure_remains_resource_failure(self):
        with patch.dict(b.BUDGET,address_space_bytes=64*b.MIB):
            report,out,_=self.run_small('import mmap,sys\ntry: mmap.mmap(-1,128*1024**2)\nexcept OSError as error:\n print("mmap:",error,file=sys.stderr);raise')
        self.assertEqual(report['status'],'failed');self.assert_reaped(report)
        with self.assertRaises(GateError) as failure:run_quality.checked_receipt(report,out)
        self.assertEqual(failure.exception.classification,'resource_constrained')
        self.assertIn('Cannot allocate memory',(out/'stderr.log').read_text())
        self.assertEqual(json.loads((out/'process.json').read_text())['classification'],'resource_constrained')

    def test_fixed_full_control_and_legacy_prerequisite_budgets(self):
        self.assertEqual((b.BUDGET['address_space_bytes'],b.BUDGET['rss_watchdog_bytes'],
                          b.BUDGET['minimum_mem_available_bytes']),(4*b.GIB,3584*b.MIB,5*b.GIB))
        self.assertEqual((b.FULL_E2B_BUDGET['address_space_bytes'],b.FULL_E2B_BUDGET['rss_watchdog_bytes'],
                          b.FULL_E2B_BUDGET['minimum_mem_available_bytes']),(6*b.GIB,4*b.GIB,6*b.GIB))
        for budget in (b.BUDGET,b.FULL_E2B_BUDGET):
            self.assertEqual([budget[key] for key in ('wall_seconds','cpu_soft_limit_seconds','cpu_seconds',
                'maximum_regular_output_file_bytes','system_reserve_bytes','cpu_threads')],
                [240,239,240,128*b.MIB,b.GIB,1])

    def test_full_control_actual_inherited_rlimits_and_affinity(self):
        code='''import json,os,resource
print(json.dumps(dict(affinity=len(os.sched_getaffinity(0)),
  address=resource.getrlimit(resource.RLIMIT_AS),cpu=resource.getrlimit(resource.RLIMIT_CPU),
  core=resource.getrlimit(resource.RLIMIT_CORE),file=resource.getrlimit(resource.RLIMIT_FSIZE),
  threads=[os.environ[x] for x in ('OMP_NUM_THREADS','OPENBLAS_NUM_THREADS','MKL_NUM_THREADS')])))'''
        binary,request,identity=self.make_control(code)
        out=self.root/'projected_null'
        report=b.run_full_e2b(binary,request,out,binary_identity=identity,cleanup_root=self.root)
        actual=json.loads((out/'stdout.log').read_text())
        self.assertEqual(actual,dict(affinity=1,address=[6*b.GIB]*2,cpu=[239,240],core=[0,0],
                                    file=[128*b.MIB]*2,threads=['1']*3))
        self.assertEqual(report['resource_profile'],b.FULL_E2B_PROFILE)
        self.assertEqual(report['status'],'completed');self.assert_reaped(report)

    def test_profile_rejects_changed_binary_request_or_arbitrary_command(self):
        binary,request,identity=self.make_control()
        original=json.loads(request.read_text())
        for key,value in [('context_tokens',512),('max_output_tokens',65),('audio_embedding_tap',False),
                          ('mode','other'),('resource_profile','compiler'),('native_binary_sha256','0'*64)]:
            write(request,dict(original,**{key:value}))
            with self.assertRaises(GateError),patch.object(b.subprocess,'Popen') as launch:
                b.run_full_e2b(binary,request,self.root/'projected_null',binary_identity=identity,cleanup_root=self.root)
            launch.assert_not_called()
        write(request,original);binary.write_text('changed')
        with self.assertRaises(GateError):
            b.run_full_e2b(binary,request,self.root/'projected_null',binary_identity=identity,cleanup_root=self.root)
        with self.assertRaises(TypeError):b.run(['never'],self.root/'never',resource_profile=b.FULL_E2B_PROFILE)

    def test_full_control_admission_uses_six_gib_effective_headroom(self):
        binary,request,identity=self.make_control()
        for name,memory in [('host',dict(MEMORY,host_mem_available_bytes=6*b.GIB-1,effective_available_bytes=6*b.GIB-1)),
                            ('cgroup',dict(MEMORY,cgroup_remaining_bytes=6*b.GIB-1,effective_available_bytes=6*b.GIB-1))]:
            self.memory.return_value=memory
            parent=self.root/name;parent.mkdir()
            with patch.object(b.subprocess,'Popen') as launch:
                report=b.run_full_e2b(binary,request,parent/'projected_null',binary_identity=identity,cleanup_root=parent)
            launch.assert_not_called();self.assertEqual(report['status'],'resource_blocked')
            self.assertEqual(report['memory_before'],memory);self.assertTrue(report['cleanup_verified'])

    def test_separate_stdout_stderr_no_compiler_diagnostic_or_console_text(self):
        with patch.object(build_probes,'compiler_diagnostic',side_effect=AssertionError('must never collect model text')):
            report,out,console=self.run_small("import sys;print('MODEL_STDOUT');print('MODEL_STDERR',file=sys.stderr)")
        self.assertEqual(console,'');self.assertNotIn('MODEL_',json.dumps(report));self.assertNotIn('diagnostic',report)
        self.assertEqual((out/'stdout.log').read_text(),'MODEL_STDOUT\n')
        self.assertEqual((out/'stderr.log').read_text(),'MODEL_STDERR\n');self.assert_reaped(report)
        selected=export_evidence.select(self.root/'absent',self.root)
        self.assertFalse(any('MODEL_' in json.dumps(value) for _,value in selected))

    def test_timeout_reaps_term_ignoring_escaped_descendant(self):
        code='''import os,signal,subprocess,sys,time
from pathlib import Path
Path('parent.pid').write_text(str(os.getpid()))
subprocess.Popen([sys.executable,'-c',"import os,signal,time;from pathlib import Path;Path('child.pid').write_text(str(os.getpid()));signal.signal(signal.SIGTERM,signal.SIG_IGN);time.sleep(60)"],start_new_session=True)
signal.signal(signal.SIGTERM,signal.SIG_IGN);time.sleep(60)'''
        report,_,_=self.run_small(code,wall=.25)
        self.assertEqual(report['stop_reason'],'wall_timeout');self.assert_reaped(report)

    def test_exit_zero_with_adopted_descendant_fails_and_reaps(self):
        code='''import subprocess,sys,time
subprocess.Popen([sys.executable,'-c',"import os,time;from pathlib import Path;Path('child.pid').write_text(str(os.getpid()));time.sleep(60)"],start_new_session=True)
time.sleep(.1)'''
        report,_,_=self.run_small(code)
        self.assertEqual(report['exit_code'],0);self.assertEqual(report['status'],'failed')
        self.assertEqual(report['stop_reason'],'descendants_survived_probe');self.assert_reaped(report)

    def test_double_fork_new_sessions_and_zombies_are_all_reaped(self):
        code='''import os,signal,time
from pathlib import Path
Path('parent.pid').write_text(str(os.getpid()))
if os.fork()==0:
    os.setsid();Path('child.pid').write_text(str(os.getpid()))
    if os.fork()==0:
        os.setsid();Path('second.pid').write_text(str(os.getpid()))
        signal.signal(signal.SIGTERM,signal.SIG_IGN);time.sleep(60)
    os._exit(0)
time.sleep(60)'''
        report,_,_=self.run_small(code,wall=.25)
        self.assertEqual(report['stop_reason'],'wall_timeout');self.assert_reaped(report)

    def test_missing_echild_proof_blocks_even_when_proc_looks_empty(self):
        real=os.waitpid
        def unreaped(pid,flags):
            if pid==-1:return (0,0)
            return real(pid,flags)
        with patch.object(b.os,'waitpid',side_effect=unreaped):
            report,_,_=self.run_small('pass')
        self.assertEqual(report['surviving_process_count'],0)
        self.assertFalse(report['all_children_reaped']);self.assertFalse(report['cleanup_verified'])
        self.assertEqual(report['stop_reason'],'cleanup_failure');self.assertTrue(b._CLEANUP_UNCERTAIN)

    def test_live_observation_error_keeps_poison_after_cleanup(self):
        real=b.process_table;calls=0
        def fail_monitoring():
            nonlocal calls
            calls+=1
            if calls==2:raise OSError('injected live observation failure')
            return real()
        with patch.object(b,'process_table',side_effect=fail_monitoring):
            report,_,_=self.run_small('import time;time.sleep(60)')
        self.assertEqual(report['stop_reason'],'cleanup_failure')
        self.assertEqual(report['execution_stop_reason'],'supervision_failure')
        self.assertEqual(report['surviving_process_count'],0);self.assertEqual(report['unreaped_zombie_count'],0)
        self.assertFalse(report['cleanup_verified']);self.assertTrue(b._CLEANUP_UNCERTAIN)
        with self.assertRaises(GateError):b.verify_cleanup(self.root)

    def test_aggregate_rss_stops_multiple_individually_small_processes(self):
        code='''import subprocess,sys,time
for name in ('child.pid','second.pid'):
    subprocess.Popen([sys.executable,'-c',"import os,time;from pathlib import Path;Path('"+name+"').write_text(str(os.getpid()));data=bytearray(6*1024**2);time.sleep(60)"],start_new_session=True)
time.sleep(60)'''
        with patch.dict(b.BUDGET,rss_watchdog_bytes=32*b.MIB):report,_,_=self.run_small(code)
        self.assertEqual(report['stop_reason'],'rss_watchdog_limit');self.assertGreater(report['peak_process_count'],1)
        self.assertGreater(report['sampled_peak_rss_bytes'],32*b.MIB);self.assert_reaped(report)

    def test_live_effective_reserve_stops_and_reaps(self):
        self.memory.side_effect=[dict(MEMORY),dict(MEMORY,cgroup_remaining_bytes=b.GIB-1,effective_available_bytes=b.GIB-1)]
        report,_,_=self.run_small('import time;time.sleep(60)')
        self.assertEqual(report['stop_reason'],'system_memory_reserve');self.assert_reaped(report)

    def test_signal_cancels_tree_and_restores_handlers_and_subreaper(self):
        before=self.state()
        code='''import os,signal,subprocess,sys,time
subprocess.Popen([sys.executable,'-c',"import os,time;from pathlib import Path;Path('child.pid').write_text(str(os.getpid()));time.sleep(60)"],start_new_session=True)
time.sleep(.1);os.kill(os.getppid(),signal.SIGTERM);time.sleep(60)'''
        report,_,_=self.run_small(code)
        self.assertEqual(report['stop_reason'],'signal');self.assertEqual(report['signal'],signal.SIGTERM)
        self.assertEqual(self.state(),before);self.assert_reaped(report)

    def test_regular_file_limit_signal_is_failure(self):
        with patch.dict(b.BUDGET,maximum_regular_output_file_bytes=65536):
            report,out,_=self.run_small("import signal;signal.signal(signal.SIGXFSZ,signal.SIG_DFL);open('oversized','wb').write(b'x'*131072)")
        self.assertEqual(report['exit_code'],-signal.SIGXFSZ)
        self.assertEqual((self.root/'oversized').stat().st_size,65536)
        self.assertEqual(run_quality.classify_process(report),'resource_constrained');self.assert_reaped(report)

    def test_cpu_soft_limit_signal_is_failure(self):
        with patch.dict(b.BUDGET,cpu_soft_limit_seconds=1,cpu_seconds=2):
            report,_,_=self.run_small('exec("while True: pass")',wall=4)
        self.assertEqual(report['exit_code'],-signal.SIGXCPU)
        self.assertEqual(run_quality.classify_process(report),'resource_constrained');self.assert_reaped(report)

    def test_unrelated_existing_child_is_never_signalled(self):
        other=subprocess.Popen([sys.executable,'-c','import time;time.sleep(60)'])
        try:
            with self.assertRaises(GateError):self.run_small('pass')
            self.assertIsNone(other.poll())
        finally:other.kill();other.wait()

    def test_start_identity_rejects_reused_pid(self):
        row={'state':'S','ppid':1,'group':900001,'start':102,'rss':1}
        self.assertEqual(b.owned_tree({900001:row},900001,{900001:101}),{})

    def test_fast_exit_leader_identity_is_bound_before_pid_reuse(self):
        pid=900001
        original={'state':'Z','ppid':os.getpid(),'group':pid,'start':101,'rss':0}
        replacement={'state':'S','ppid':1,'group':pid,'start':102,'rss':1}
        known={}
        self.assertEqual(b._owned_rows({pid:original},pid,known),{pid:original})
        self.assertEqual(known[pid],101)
        self.assertEqual(b._owned_rows({pid:replacement},pid,known),{})
        # A missed first snapshot cannot make an unobserved identity authoritative.
        known={}
        self.assertEqual(b._owned_rows({},pid,known),{})
        self.assertEqual(b._owned_rows({pid:replacement},pid,known),{})

    def test_cleanup_inspection_error_retries_but_blocks_later_work(self):
        real=b.process_table;raised=False;before=self.state()
        def fail_cleanup():
            nonlocal raised
            frame=sys._getframe();names=[]
            while frame is not None:names.append(frame.f_code.co_name);frame=frame.f_back
            if not raised and 'terminate_owned' in names:
                raised=True;raise OSError('MODEL_TEXT_MUST_NOT_LEAK')
            return real()
        with patch.object(b,'process_table',side_effect=fail_cleanup):
            report,out,_=self.run_small('import time;time.sleep(60)',wall=.1)
        self.assertTrue(raised);self.assertFalse(report['cleanup_verified'])
        self.assertEqual(report['stop_reason'],'cleanup_failure');self.assertEqual(report['execution_stop_reason'],'wall_timeout')
        self.assertEqual(report['surviving_process_count'],0);self.assertEqual(self.state(),before)
        self.assertNotIn('MODEL_TEXT',json.dumps(report))
        self.assertEqual(run_quality.classify_process(report),'model_cleanup_failure')
        with patch.object(b.subprocess,'Popen') as launch:
            with self.assertRaises(GateError):b.run(['never'],self.root/'later')
            with self.assertRaises(GateError):b.run(['never'],self.root/'different'/'later')
        launch.assert_not_called()
        with patch.object(b,'_CLEANUP_UNCERTAIN',False):
            with self.assertRaises(GateError):b.verify_cleanup(self.root)

    def test_cleanup_signal_error_retries_and_poison_remains(self):
        real=signal.pidfd_send_signal;raised=False;before=self.state()
        def fail_signal(handle,number,*args):
            nonlocal raised
            if not raised and number==signal.SIGTERM:
                raised=True;raise PermissionError('injected')
            return real(handle,number,*args)
        with patch.object(b.signal,'pidfd_send_signal',side_effect=fail_signal):
            report,_,_=self.run_small('import time;time.sleep(60)',wall=.1)
        self.assertFalse(report['cleanup_verified']);self.assertEqual(report['surviving_process_count'],0)
        self.assertEqual(report['stop_reason'],'cleanup_failure');self.assertEqual(self.state(),before)
        self.assertTrue(b._CLEANUP_UNCERTAIN)

    def test_receipt_write_failure_leaves_pending_guard(self):
        real=b._save
        def fail_receipt(path,value):
            if path.name=='process.json':raise OSError('injected write failure')
            return real(path,value)
        with patch.object(b,'_save',side_effect=fail_receipt):
            with self.assertRaises(OSError):self.run_small('pass')
        self.assertTrue(b._CLEANUP_UNCERTAIN)
        self.assertFalse(json.loads((self.root/b.CLEANUP_LATCH).read_text())['cleanup_verified'])
        self.assertTrue((self.root/b.ACTIVE_LOCK).exists())

    def test_launch_failure_restores_state_and_returns_failure(self):
        before=self.state()
        with patch.object(b.subprocess,'Popen',side_effect=OSError('MODEL_TEXT_MUST_NOT_LEAK')):
            report,_,_=self.run_small('pass')
        self.assertEqual(report['status'],'failed');self.assertFalse(report['execution_started'])
        self.assertEqual(report['stop_reason'],'supervision_failure');self.assertEqual(self.state(),before)
        self.assertNotIn('MODEL_TEXT',json.dumps(report));self.assert_reaped(report)

    def test_pending_guard_blocks_gate_before_download_or_build_validation(self):
        build=self.root/'build';build.mkdir()
        write(build/b.CLEANUP_LATCH,{'schema_version':1,'cleanup_verified':False})
        args=type('Args',(),dict(out=self.root/'quality',build_dir=build))()
        with patch.object(run_quality,'verify_build') as verify,patch.object(run_quality,'download') as download:
            report=run_quality.run_gate(args)
        self.assertFalse(report['passed']);self.assertEqual(report['classification'],'model_cleanup_failure')
        verify.assert_not_called();download.assert_not_called()

    def test_success_cannot_hide_missing_cleanup(self):
        for process in ({'status':'completed'},{'status':'completed','cleanup_verified':False},
                        {'status':'resource_blocked','cleanup_verified':False}):
            with self.assertRaises(GateError):run_quality.checked_receipt(process,self.root)

    def test_completed_runs_release_guard_for_sequential_fresh_process(self):
        one,_,_=self.run_small('print("one")',name='one')
        two,_,_=self.run_small('print("two")',name='two')
        self.assert_reaped(one);self.assert_reaped(two)
        self.assertFalse((self.root/b.ACTIVE_LOCK).exists());b.verify_cleanup(self.root)


if __name__=='__main__':unittest.main()
