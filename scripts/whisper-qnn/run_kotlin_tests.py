#!/usr/bin/env python3
"""No downloads. Compile production Whisper adapters and run focused host tests.

Two immutable DTOs are extracted from their production owner files; no behavioral
code is replaced. Requires a verified Kotlin2.3.21/JUnit4.13.2/coroutines1.9.0,
Android API/Sherpa1.13.7/commons-compress1.27.1 compiler dependency cache.
Full Gradle/R8/Android verification remains required.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--dependency-cache', required=True, type=Path)
parser.add_argument('--output', required=True, type=Path)
args = parser.parse_args()
repo = Path(__file__).resolve().parents[2]; cache = args.dependency_cache.resolve()
output = args.output.resolve(); output.mkdir(parents=True, exist_ok=True)
cp = ':'.join(str(p) for p in sorted(cache.glob('*.jar')))
voice = repo/'app/src/main/java/com/battlesbudz/jarvis/v2/voice'
test = repo/'app/src/test/java/com/battlesbudz/jarvis/v2/voice'
sources = list((voice/'whisper').glob('*.kt')) + [voice/n for n in (
    'AsyncWhisperSession.kt','StreamingTranscriber.kt','AsrRecognitionWorkMetrics.kt','RollingAudioBuffer.kt',
    'ExternalSpeechGate.kt','WhisperTranscriber.kt','AsrEngine.kt','VoiceModelSession.kt','CallModelSlot.kt',
    'VoiceWorkScheduler.kt','RecognitionAudioEvidence.kt','RecognitionModelStore.kt')]
types = output/'VoiceTypes.kt'
cue = next(line for line in (voice/'NativePauseEndpointPolicy.kt').read_text().splitlines() if line.startswith('data class CompletedEndpointCue'))
fmt = (voice/'AudioInput.kt').read_text().split('data class AudioFormat(',1)[1]
types.write_text('package com.battlesbudz.jarvis.v2.voice\n' + cue + '\ndata class AudioFormat(' + fmt)
sources.append(types)
sources += [test/n for n in ('AsyncWhisperSessionTest.kt','whisper/WhisperDecoderTest.kt','whisper/WhisperTokenizerTest.kt','WhisperCancellationTest.kt')]
compile_command = ['java','-Xmx900m','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect',
                   '-jvm-target','17','-classpath',cp,'-d',str(output/'tests.jar'),*map(str,sources)]
test_command = ['java','-cp',str(output/'tests.jar')+':'+cp,'org.junit.runner.JUnitCore',*[f'com.battlesbudz.jarvis.v2.voice.{name}' for name in ('AsyncWhisperSessionTest','whisper.WhisperDecoderTest','whisper.WhisperTokenizerTest','WhisperCancellationTest')]]
for name, command in [('compile',compile_command),('junit',test_command)]:
    result = subprocess.run(command, text=True, capture_output=True)
    (output/(name+'.log')).write_text(result.stdout+result.stderr); print(result.stdout+result.stderr); result.check_returncode()
receipt = dict(status='passed', scope='focused_jvm_and_android_sherpa_api_compile', dto_extraction=['CompletedEndpointCue','AudioFormat'],
               compile_command=compile_command, test_command=test_command,
               sources_sha256={str(p.relative_to(repo)) if p.is_relative_to(repo) else p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in sources},
               dependencies_sha256={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in cache.glob('*.jar')},
               not_covered=['Android loading','HTP inference','physical acoustic accuracy','device latency'])
(output/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
