from pathlib import Path
import argparse, hashlib, json, subprocess, sys
parser = argparse.ArgumentParser(description="Offline exact-method compile and deterministic actual Compose lifecycle checks")
parser.add_argument("--repo", type=Path, required=True)
parser.add_argument("--deps", type=Path, required=True)
parser.add_argument("--out", type=Path, required=True)
args = parser.parse_args()
repo, deps, root = (p.resolve() for p in (args.repo, args.deps, args.out))
root.mkdir(parents=True, exist_ok=False)
harness = Path(__file__).resolve().parent
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
manifest = json.loads((deps / "manifest.json").read_text())
for path in sorted(deps.glob("*.jar")):
    entries = [e for e in manifest if e["file"] == path.name or e["file"].replace(".aar", ".jar") == path.name]
    assert len(entries) == 1, path
    entry = entries[0]
    assert sha(path) == (entry["sha256"] if entry["file"] == path.name else entry["classes_sha256"]), path
source=repo/'app/src/androidTest/java/com/battlesbudz/jarvis/v2/verification/ReleaseJourneyTest.kt'
s=source.read_text();start=s.index('    @Test fun test45_pipelineBenchmarksScoreOriginalAsrPersistAndExportRedactedEvidence');end=s.index('\n    @OptIn',start);method=s[start:end]
fixture='''package com.battlesbudz.jarvis.v2.verification
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.test.uiautomator.*
import com.battlesbudz.jarvis.v2.ui.PipelineBenchmarkScreen
import com.battlesbudz.jarvis.v2.diagnostics.*
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.Assert.*
class FixtureActivityHarness { fun onActivity(action: (ComponentActivity) -> Unit): Unit = TODO() }
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class Test45CompileFixture {
 lateinit var context: Context
 lateinit var activity: FixtureActivityHarness
 lateinit var device: UiDevice
 fun find(selector: BySelector): UiObject2 = TODO()
 fun benchmarkScrollTo(selector: BySelector, towardTop: Boolean = false, holdDiscovery: Boolean = false, sparseHeldDiscovery: Boolean = false): UiObject2 = TODO()
 fun benchmarkRevealText(text: String, holdDiscovery: Boolean = false): UiObject2 = TODO()
 fun benchmarkClickEnabled(selector: BySelector, towardTop: Boolean = false, inDialog: Boolean = false, holdDiscovery: Boolean = false, sparseHeldDiscovery: Boolean = false): Unit = TODO()
 fun captureEvidence(name: String): Unit = TODO()
 fun hideKeyboardWithoutNavigating(): Unit = TODO()
'''+method+'\n}\n'
p=root/'Test45CompileFixture.kt';p.write_text(fixture)
base=repo/'app/src/main/java/com/battlesbudz/jarvis/v2'
# Exact unchanged model declarations, extracted rather than substituting guessed stubs.
conversation=base/'chat/ConversationHistory.kt';c=conversation.read_text();c=c[c.index('data class ConversationMessage('):c.index('/** One app-owned thread')]
attachment=base/'chat/ChatAttachment.kt';a=attachment.read_text();a=a[a.index('@Keep enum class AttachmentKind'):a.index('object AttachmentPolicy')]
models=root/'ConversationModels.kt';models.write_text('package com.battlesbudz.jarvis.v2.chat\nimport androidx.annotation.Keep\nimport com.battlesbudz.jarvis.v2.diagnostics.ReplyMetrics\n'+a+c)
build=root/'BuildConfig.kt';build.write_text('package com.battlesbudz.jarvis.v2\nobject BuildConfig { const val VERSION_NAME="fixture"; const val VERSION_CODE=1; const val SOURCE_COMMIT="unavailable" }\n')
names=['PipelineBenchmark','PipelineBenchmarkAccuracy','PipelineBenchmarkReport','PipelineBenchmarkDefinitions','PipelineBenchmarkTextExport','PipelineBenchmarkArchive','PipelineBenchmarkJournal','PipelineBenchmarkSelection','AndroidPipelineBenchmarkStore','ConversationMetricsExport','ReplyMetrics','NativeAudioTimingMetrics']
sources=[base/'diagnostics'/(x+'.kt') for x in names]+[base/'ui'/(x+'.kt') for x in ['BenchmarkExportFiles','BenchmarkExportControls','PipelineBenchmarkScreen']]+[repo/'app/src/androidTest/java/com/battlesbudz/jarvis/v2/verification/BenchmarkExportJourneyProbe.kt']
jars=sorted(deps.glob('*.jar'));cp=':'.join(map(str,jars));cmd=['java','-Xmx1500m','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-Xplugin='+str(deps/'kotlin-compose-compiler-plugin-embeddable-2.3.21.jar'),'-classpath',cp,'-d',str(root/'journey-out'),*map(str,sources+[models,build,p])]
r=subprocess.run(cmd,capture_output=True,text=True);(root/'journey-compile.log').write_text(r.stdout+r.stderr);print(r.stdout+r.stderr)
receipt={'exit':r.returncode,'base_commit':subprocess.check_output(['git','-C',str(repo),'rev-parse','HEAD'],text=True).strip(),'scope':'Fresh compilation of exact changed test45 method against real Android14 framework, UiAutomator2.3.0, Compose1.7.6/material3 1.3.1/activity1.10.0 APIs and actual production benchmark/UI/probe sources. Existing activity/navigation/evidence helpers are signature-only TODO seams; BuildConfig generated constants are stubbed; unchanged conversation data declarations are extracted verbatim from source. NOT complete ReleaseJourneyTest compilation, instrumentation/device execution, R8 or APK verification. No prior compilation result reused.','source_sha256':sha(source),'exact_method_sha256':hashlib.sha256(method.encode()).hexdigest(),'fixture_sha256':sha(p),'model_declaration_origins':{str(x.relative_to(repo)):sha(x) for x in [conversation,attachment]},'sources':{str(x.relative_to(repo)):sha(x) for x in sources},'generated_sources':{x.name:sha(x) for x in [models,build,p]},'dependencies':{x.name:sha(x) for x in jars},'command':cmd}
(root/'journey-receipt.json').write_text(json.dumps(receipt,indent=2))
assert r.returncode == 0
records = []
def run(name, command):
    result = subprocess.run(command,capture_output=True,text=True,timeout=90)
    log = root / (name + ".log")
    log.write_text(result.stdout + result.stderr)
    records.append({"name": name, "command": command, "exit": result.returncode, "log_sha256": sha(log)})
    print(log.read_text())
    assert result.returncode == 0, name
    return log.read_text()
production = root / "journey-out"
compiler = cmd[:cmd.index("-classpath")]
host_sources = [harness / n for n in ["Trace.kt", "Build.kt", "ContentResolver.kt", "RuntimeProbe.kt"]]
unit_sources = [repo / "app/src/test/java/com/battlesbudz/jarvis/v2" / p for p in
                ["ui/BenchmarkExportStateTest.kt", "diagnostics/PipelineBenchmarkTextExportTest.kt"]]
run("compile-runtime", compiler + ["-classpath", str(production) + ":" + cp,
    "-Xfriend-paths=" + str(production), "-d", str(root / "runtime-out"), *map(str,host_sources + unit_sources)])
host_cp = str(root / "runtime-out") + ":" + str(production) + ":" + cp
runtime_log = run("runtime-tests", ["java", "-ea", "-Dkotlinx.coroutines.io.parallelism=2", "-cp", host_cp,
    "com.battlesbudz.jarvis.v2.ui.RuntimeProbeKt"])
assert "6 deterministic lifecycle cases passed" in runtime_log
run("focused-unit-tests", ["java", "-ea", "-cp", host_cp, "org.junit.runner.JUnitCore",
    "com.battlesbudz.jarvis.v2.ui.BenchmarkExportStateTest",
    "com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkTextExportTest"])
# Check retained test assertions/waits against the exact failed source.
relative = str(source.relative_to(repo))
baseline = subprocess.check_output(["git", "-C", str(repo), "show", "13a62a8374610fefda9b93c3213c559a9f97bef7:" + relative],text=True)
bstart = baseline.index('    @Test fun test45_pipelineBenchmarksScoreOriginalAsrPersistAndExportRedactedEvidence')
bmethod = baseline[bstart:baseline.index('\n    @OptIn', bstart)]
assertions = lambda value: [line.strip() for line in value.splitlines() if "assert" in line]
waits = lambda value: [line.strip() for line in value.splitlines() if "wait(" in line or "awaitObserved(" in line or "waitForIdle(" in line or "withTimeout(" in line]
assert assertions(bmethod) == assertions(method)
assert all(line in waits(method) for line in waits(bmethod))
records.append({"name": "retained-test45-contract", "assertion_lines": len(assertions(method)),
                "existing_wait_lines": len(waits(bmethod)), "new_wait": "exact generation, existing5s probe bound"})
receipt = {"schema": 1, "base_commit": subprocess.check_output(["git", "-C", str(repo), "rev-parse", "HEAD"],text=True).strip(),
    "source_sha256": {str(p.relative_to(repo)):sha(p) for p in sources + unit_sources + [source,repo / "app/proguard-rules.pro"]},
    "harness_sha256": {p.name:sha(p) for p in host_sources + [Path(__file__)]},
    "api_compile_receipt_sha256": sha(root / "journey-receipt.json"), "records": records,
    "scope": "Exact production composable/transport with real Compose1.7.6 and Activity1.10.0 on host Android14 bytecode. Explicit ContentResolver/Trace/Build.VERSION host seams, manual dispatcher/frame clock and bounded latch barriers. Compile fixture substitutes only existing helper bodies, BuildConfig and exact extracted DTO declarations. Not Android provider/IPC/device, R8/APK/full Gradle, native/model/audio performance proof. The exact integrated revision requires fresh full release gate."}
(root / "host-receipt.json").write_text(json.dumps(receipt,indent=2) + "\n")
