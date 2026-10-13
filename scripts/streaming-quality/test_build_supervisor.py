"""Small real-process compiler-supervisor tests; no Bazel or model execution."""
import contextlib
import io
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

import build_probes as b
from common import GateError, describe
import export_evidence

MEMORY = {'host_mem_available_bytes': 12*1024**3, 'cgroup_remaining_bytes': None,
          'effective_available_bytes': 12*1024**3}


class SupervisorTests(unittest.TestCase):
    def subreaper(self):
        value=b.ctypes.c_int()
        self.assertEqual(b.ctypes.CDLL(None).prctl(37,b.ctypes.byref(value),0,0,0),0)
        return value.value

    def run_small(self, code, *, wall=2, memory=None):
        directory = tempfile.TemporaryDirectory(); self.addCleanup(directory.cleanup)
        out=Path(directory.name)
        with patch.object(b, 'compile_memory', side_effect=memory or (lambda: dict(MEMORY))), contextlib.redirect_stdout(io.StringIO()) as log:
            report=b.run_compile([sys.executable,'-c',code],out,out,wall_seconds=wall,
                                 poll_seconds=.03,sample_seconds=.06,cleanup_seconds=.15)
        return report,out,log.getvalue()

    def assert_reaped(self, report, out):
        self.assertTrue(report['cleanup_verified'],report)
        self.assertEqual(report.get('surviving_process_count'),0)
        for name in ('parent.pid','child.pid'):
            if (out/name).exists():self.assertFalse(Path('/proc/'+(out/name).read_text()).exists(),name)

    def test_success_live_action_counter_and_tail(self):
        report,out,console=self.run_small("import time;print('[1,250 / 2,000] Compiling public.cc',flush=True);time.sleep(.15)")
        self.assertTrue(report['passed'],report)
        self.assertEqual(report['jobs'],4)
        self.assertEqual(report['diagnostic']['last_action_progress'],{'completed':1250,'total':2000})
        self.assertEqual(report['diagnostic']['sha256'],describe(out/'compile.log')['sha256'])
        self.assertIn('hosted_compile_progress',console)
        self.assertIn('1250',console)
        self.assertNotIn('Compiling public.cc',console)
        self.assert_reaped(report,out)

    def test_compile_exit_failure_is_not_timeout(self):
        report,out,_=self.run_small("print('ERROR: source compile failure',flush=True);raise SystemExit(7)")
        self.assertFalse(report['passed']);self.assertEqual(report['classification'],'build_compile_failure')
        self.assertEqual(report['exit_code'],7);self.assert_reaped(report,out)

    def test_actual_child_cpu_affinity_is_bounded(self):
        report,out,_=self.run_small("import os;print('observed_affinity='+str(len(os.sched_getaffinity(0))))")
        self.assertTrue(report['passed'],report)
        self.assertIn('observed_affinity=4',report['diagnostic']['tail'])
        self.assert_reaped(report,out)

    def test_timeout_reaps_term_ignoring_separate_session_grandchild(self):
        code='''import os,signal,subprocess,sys,time
from pathlib import Path
Path('parent.pid').write_text(str(os.getpid()))
subprocess.Popen([sys.executable,'-c',"import os,signal,time;from pathlib import Path;Path('child.pid').write_text(str(os.getpid()));signal.signal(signal.SIGTERM,signal.SIG_IGN);time.sleep(60)"],start_new_session=True)
signal.signal(signal.SIGTERM,signal.SIG_IGN)
time.sleep(60)
'''
        report,out,_=self.run_small(code,wall=.3)
        self.assertFalse(report['passed']);self.assertEqual(report['classification'],'build_timeout')
        self.assert_reaped(report,out)

    def test_success_with_surviving_descendant_is_failure(self):
        code='''import subprocess,sys,time
subprocess.Popen([sys.executable,'-c',"import os,time;from pathlib import Path;Path('child.pid').write_text(str(os.getpid()));time.sleep(60)"],start_new_session=True)
time.sleep(.1)
'''
        report,out,_=self.run_small(code)
        self.assertFalse(report['passed']);self.assertEqual(report['classification'],'build_supervision_failure')
        self.assert_reaped(report,out)

    def test_cancel_reaps_group_and_adopted_descendant(self):
        code='''import os,signal,subprocess,sys,time
subprocess.Popen([sys.executable,'-c',"import os,time;from pathlib import Path;Path('child.pid').write_text(str(os.getpid()));time.sleep(60)"],start_new_session=True)
time.sleep(.1)
os.kill(os.getppid(),signal.SIGTERM)
time.sleep(60)
'''
        old=signal.getsignal(signal.SIGTERM)
        report,out,_=self.run_small(code)
        self.assertFalse(report['passed']);self.assertEqual(report['classification'],'build_cancelled')
        self.assertEqual(report['signal'],signal.SIGTERM)
        self.assertEqual(signal.getsignal(signal.SIGTERM),old)
        self.assert_reaped(report,out)

    def test_ram_prerequisite_blocks_launch(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(b,'compile_memory',return_value={'effective_available_bytes':b.COMPILE_MIN_AVAILABLE-1}), patch.object(b.subprocess,'Popen') as launch:
            report=b.run_compile(['never'],tmp,tmp)
        launch.assert_not_called();self.assertFalse(report['started']);self.assertFalse(report['passed'])
        self.assertEqual(report['classification'],'build_resource_blocked')

    def test_system_reserve_failure_reaps(self):
        count=0
        def memory():
            nonlocal count
            count+=1
            return dict(MEMORY,effective_available_bytes=12*1024**3 if count==1 else 1024**3)
        report,out,_=self.run_small('import time;time.sleep(60)',memory=memory)
        self.assertEqual(report['classification'],'build_resource_failure')
        self.assertEqual(report['stop_reason'],'system_memory_reserve');self.assert_reaped(report,out)

    def test_process_tree_rss_limit_reaps(self):
        with patch.object(b,'COMPILE_TREE_RSS_LIMIT',1):
            report,out,_=self.run_small('import time;time.sleep(60)')
        self.assertEqual(report['stop_reason'],'process_tree_rss');self.assert_reaped(report,out)

    def test_tail_bounded_and_wrong_path_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);p=root/'compile.log';p.write_bytes(b'A'*40000+b'\n[99 / 100] Compiling public.cc\n')
            st=p.stat();identity=(st.st_dev,st.st_ino)
            d=b.compiler_diagnostic(root,identity)
            self.assertTrue(d['tail_truncated']);self.assertLessEqual(len(d['tail']),32768)
            self.assertEqual(d['total_bytes'],p.stat().st_size)
            p.rename(root/'real.log');p.symlink_to(root/'real.log')
            with self.assertRaises(GateError):b.compiler_diagnostic(root,identity)

    def test_log_limit_stops_and_reaps(self):
        with patch.object(b,'COMPILE_LOG_LIMIT',32):
            report,out,_=self.run_small("import time;print('A'*300,flush=True);time.sleep(60)")
        self.assertEqual(report['classification'],'build_output_limit');self.assert_reaped(report,out)

    def test_tail_export_uses_existing_exact_build_allowlist(self):
        report,out,_=self.run_small("print('compiler diagnostic')")
        import common
        common.write(out/'build-status.json',{'build_succeeded':True,'compile':report})
        selected=export_evidence.select(out,out/'not-a-run')
        self.assertEqual([name for name,_ in selected],['build/build-status.json'])
        self.assertIn('compiler diagnostic',selected[0][1]['compile']['diagnostic']['tail'])
        self.assertFalse(any(name.endswith('compile.log') for name,_ in selected))

    def test_unrelated_existing_child_is_not_adopted_or_killed(self):
        other=subprocess.Popen([sys.executable,'-c','import time;time.sleep(60)'])
        try:
            with tempfile.TemporaryDirectory() as tmp, patch.object(b,'compile_memory',return_value=dict(MEMORY)):
                with self.assertRaises(GateError):b.run_compile(['never'],tmp,tmp)
                self.assertIsNone(other.poll())
        finally:
            other.kill();other.wait()

    def test_reused_pid_cannot_become_owned(self):
        row={'state':'S','ppid':1,'group':900001,'start':102,'rss':1}
        known={900001:101}
        self.assertEqual(b.owned_tree({900001:row},900001,known),{})

    def test_three_cpus_block_launch(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(b,'compile_memory',return_value=dict(MEMORY)), patch.object(b.os,'sched_getaffinity',return_value={0,1,2}), patch.object(b.subprocess,'Popen') as launch:
            report=b.run_compile(['never'],tmp,tmp)
        launch.assert_not_called();self.assertEqual(report['classification'],'build_resource_blocked')

    def test_four_jobs_and_reviewed_compiler_budget(self):
        text=Path(b.__file__).read_text()
        self.assertIn("'--jobs=4'",text);self.assertIn("'--local_resources=cpu=4'",text)
        self.assertIn("'--repo_env=ANDROID_NDK_HOME='",text)
        self.assertEqual(b.COMPILE_WALL_SECONDS,3600)
        self.assertEqual(b.COMPILE_TREE_RSS_LIMIT,6*1024**3)
        self.assertEqual(b.COMPILE_SYSTEM_RESERVE,2*1024**3)

    def test_replacement_before_open_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);p=root/'compile.log';p.write_text('original compiler log')
            st=p.stat();identity=(st.st_dev,st.st_ino);real_open=os.open
            def raced_open(path,flags,*args):
                p.rename(root/'original.log');p.write_text('different replacement log')
                return real_open(path,flags,*args)
            with patch.object(b.os,'open',side_effect=raced_open):
                with self.assertRaises(GateError):b.compiler_diagnostic(root,identity)

    def test_replacement_after_open_cannot_change_hashed_bytes(self):
        import hashlib
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);p=root/'compile.log';original=b'original compiler log'
            p.write_bytes(original);st=p.stat();identity=(st.st_dev,st.st_ino);real_fstat=os.fstat;swapped=False
            def raced_fstat(fd):
                nonlocal swapped
                if not swapped:
                    swapped=True;p.rename(root/'original.log');p.write_text('different replacement log')
                return real_fstat(fd)
            with patch.object(b.os,'fstat',side_effect=raced_fstat):d=b.compiler_diagnostic(root,identity)
            self.assertEqual(d['tail'],original.decode())
            self.assertEqual(d['sha256'],hashlib.sha256(original).hexdigest())
            self.assertNotEqual(d['sha256'],describe(p)['sha256'])

    def test_cleanup_read_failure_still_restores_state_and_fails(self):
        real_table=b.process_table;raised=False;old=signal.getsignal(signal.SIGTERM);subreaper=self.subreaper()
        def fail_cleanup_read():
            nonlocal raised
            frame=sys._getframe();names=[]
            for _ in range(10):
                if frame is None:break
                names.append(frame.f_code.co_name);frame=frame.f_back
            if not raised and 'terminate_owned' in names:
                raised=True;raise OSError('injected cleanup proc read failure')
            return real_table()
        with patch.object(b,'process_table',side_effect=fail_cleanup_read):
            report,out,_=self.run_small('import time;time.sleep(60)',wall=.15)
        self.assertTrue(raised);self.assertFalse(report['passed'])
        self.assertEqual(report['classification'],'build_cleanup_failure')
        self.assertIn('injected cleanup proc read failure',' '.join(report['cleanup_errors']))
        self.assertEqual(signal.getsignal(signal.SIGTERM),old)
        self.assertEqual(self.subreaper(),subreaper)
        self.assert_reaped(report,out)

    def test_cleanup_kill_failure_still_restores_state_and_fails(self):
        real_killpg=os.killpg;raised=False;old=signal.getsignal(signal.SIGTERM);subreaper=self.subreaper()
        def fail_first_term(group,number):
            nonlocal raised
            if number==signal.SIGTERM and not raised:
                raised=True;raise PermissionError('injected cleanup kill failure')
            return real_killpg(group,number)
        with patch.object(b.os,'killpg',side_effect=fail_first_term):
            report,out,_=self.run_small('import time;time.sleep(60)',wall=.15)
        self.assertTrue(raised);self.assertFalse(report['passed'])
        self.assertEqual(report['classification'],'build_cleanup_failure')
        self.assertIn('injected cleanup kill failure',' '.join(report['cleanup_errors']))
        self.assertEqual(signal.getsignal(signal.SIGTERM),old)
        self.assertEqual(self.subreaper(),subreaper)
        self.assert_reaped(report,out)

    def test_no_timeout_expansion(self):
        with self.assertRaises(GateError):b.run_compile([],'.','.',wall_seconds=3601)

    def test_no_model_budget_change(self):
        import bounded_exec
        self.assertEqual(bounded_exec.BUDGET['wall_seconds'],240)
        self.assertEqual(bounded_exec.BUDGET['address_space_bytes'],4*1024**3)
        self.assertEqual(bounded_exec.BUDGET['rss_watchdog_bytes'],3584*1024**2)
        self.assertEqual(bounded_exec.BUDGET['minimum_mem_available_bytes'],5*1024**3)
        self.assertEqual(bounded_exec.BUDGET['cpu_threads'],1)

if __name__=='__main__':unittest.main()
