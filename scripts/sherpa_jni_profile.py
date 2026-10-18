"""Limit the pinned Sherpa JNI wrapper to the interfaces Jarvis actually uses.

This changes build reachability, not inference implementations or model formats.
OnlineStream is needed by SpeakerEmbeddingExtractor even though online ASR is not.
"""
from pathlib import Path
import re
import shutil

APP_SOURCE_DIR = Path(__file__).resolve().parents[1] / 'app/src/main/cpp/smartturn'
APP_QNN_DIR = Path(__file__).resolve().parents[1] / 'app/src/main/cpp/whisperqnn'
APP_SOURCES = ('jarvis-smart-turn/smart_turn_jni.cc', 'jarvis-smart-turn/whisper_features.cc',
               'jarvis-whisper-qnn/whisper_qnn_jni.cc', 'jarvis-whisper-qnn/whisper_qnn_session.cc',
               'jarvis-whisper-qnn/whisper_features.cc')
APP_SYMBOLS = tuple('Java_com_battlesbudz_jarvis_v2_voice_smartturn_SmartTurnNative_' + name
                    for name in ('create', 'prepare', 'infer', 'cancel', 'close'))
QNN_SYMBOLS = tuple('Java_com_battlesbudz_jarvis_v2_voice_whisper_WhisperQnnNative_' + name
                    for name in ('create', 'decode', 'cancel', 'close'))
UPSTREAM_EXPORTS = '{\n global: Java_com_k2fsa_sherpa_onnx*;\n local: *;\n};\n'
APP_EXPORTS = '{\n global: Java_com_k2fsa_sherpa_onnx*;\n  ' + ';\n  '.join((*APP_SYMBOLS, *QNN_SYMBOLS)) + ';\n local: *;\n};\n'

JNI_SOURCES = (
    'common.cc', 'jni.cc', 'offline-recognizer.cc', 'offline-stream.cc',
    'online-stream.cc', 'speaker-embedding-extractor.cc', 'version.cc',
    'voice-activity-detector.cc',
)
UPSTREAM_SOURCES = set(JNI_SOURCES) | {
    'audio-tagging.cc', 'keyword-spotter.cc', 'offline-diacritization.cc',
    'offline-punctuation.cc', 'offline-speech-denoiser.cc',
    'online-speech-denoiser.cc', 'online-punctuation.cc', 'online-recognizer.cc',
    'speaker-embedding-manager.cc', 'speech-denoiser.cc',
    'spoken-language-identification.cc', 'wave-reader.cc', 'wave-writer.cc',
}
REQUIRED_CLASSES = ('OfflineRecognizer', 'OfflineStream', 'OnlineStream',
                    'OfflineTts', 'SpeakerEmbeddingExtractor', 'Vad')
REMOVED_CLASSES = ('AudioTagging', 'KeywordSpotter', 'OfflinePunctuation',
                   'OnlinePunctuation', 'OnlineRecognizer', 'OfflineSpeechDenoiser',
                   'OnlineSpeechDenoiser', 'SpeakerEmbeddingManager',
                   'SpokenLanguageIdentification')


def configure(source):
    path = Path(source) / 'sherpa-onnx/jni/CMakeLists.txt'
    text = path.read_text()
    match = re.search(r'set\(sources\s+(.*?)\n\)', text, re.S)
    if not match or set(match.group(1).split()) not in (UPSTREAM_SOURCES, set(JNI_SOURCES), set(JNI_SOURCES) | set(APP_SOURCES)):
        raise ValueError('Pinned Sherpa JNI source list changed; review the app profile')
    # TTS remains in the upstream conditional below this block.
    if 'offline-tts.cc' not in text:
        raise ValueError('Pinned Sherpa TTS wrapper is missing')
    for name in (*JNI_SOURCES, 'offline-tts.cc'):
        if not (path.parent / name).is_file():
            raise ValueError(f'Missing required JNI source: {name}')
    for name in ('smart_turn_jni.cc', 'smart_turn_session.h', 'whisper_features.cc', 'whisper_features.h'):
        if not (APP_SOURCE_DIR / name).is_file():
            raise ValueError(f'Missing app Smart Turn source: {name}')
    for name in ('whisper_qnn_jni.cc', 'whisper_qnn_session.cc', 'whisper_qnn_session.h',
                 'whisper_decode_state.h', 'whisper_run_state.h', 'whisper_features.cc', 'whisper_features.h'):
        if not (APP_QNN_DIR / name).is_file():
            raise ValueError(f'Missing app Whisper QNN source: {name}')
    exports = path.parent / 'sherpa-onnx-symbols.lds'
    if re.sub(r'\s', '', exports.read_text()) not in {re.sub(r'\s', '', value) for value in (UPSTREAM_EXPORTS, APP_EXPORTS)}:
        raise ValueError('Pinned Sherpa JNI export script changed; review the app profile')
    shutil.copytree(APP_SOURCE_DIR, path.parent / 'jarvis-smart-turn', dirs_exist_ok=True)
    shutil.copytree(APP_QNN_DIR, path.parent / 'jarvis-whisper-qnn', dirs_exist_ok=True)
    text = text[:match.start()] + 'set(sources\n  ' + '\n  '.join((*JNI_SOURCES, *APP_SOURCES)) + '\n)' + text[match.end():]
    path.write_text(text)
    exports.write_text(APP_EXPORTS)
