"""Source contracts for answer-only voice wiring, not native/acoustic execution.

The historical cue helpers remain for their isolated regressions; live production
callers must never reconnect them. JVM PCM/text tests cover pause preservation.
"""
from pathlib import Path
import re
import unittest

from check_architecture import source_tokens


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/java/com/battlesbudz/jarvis/v2"
RETIRED = {
    "voice/DelayedAcknowledgement.kt", "voice/FillerAudioCache.kt",
    "voice/FillerPcm.kt", "voice/FillerPhrases.kt", "voice/SentenceGapWaiter.kt",
}
CUE_DEPENDENCIES = re.compile(
    r"\b(?:DelayedAcknowledgement|FillerAudioCache|FillerPcm|FillerPhrases|"
    r"SentenceGapWaiter|playAcknowledgement|acknowledgeDelays|"
    r"acknowledgeConfirmedTurn|optionalFiller)\b"
)


def cue_dependencies(files):
    return [(path, match.group()) for path, source in files.items()
            if path not in RETIRED
            for match in CUE_DEPENDENCIES.finditer(source_tokens(source))]


class AnswerOnlyVoiceWiringTest(unittest.TestCase):
    def tokens(self, name):
        return source_tokens((SOURCE / name).read_text(encoding="utf-8"))

    def test_live_production_cannot_prepare_request_or_play_retired_cues(self):
        files = {path.relative_to(SOURCE).as_posix(): path.read_text(encoding="utf-8")
                 for path in SOURCE.rglob("*.kt")}
        self.assertTrue(files)
        self.assertEqual([], cue_dependencies(files))

    def test_guard_rejects_cues_reintroduced_in_normal_and_followup_turns(self):
        for path in ("runtime/VoiceTurnOutputFactory.kt", "runtime/turn/AcceptedVoiceFollowupStage.kt",
                     "voice/PiperVoiceOutput.kt", "conversation/ConversationRouting.kt",
                     "voice/VoiceCues.kt"):
            for code in ("PiperVoiceOutput(path, acknowledgeDelays = true)",
                         "output.acknowledgeConfirmedTurn()",
                         "VoiceCues.playAcknowledgement(cached)",
                         "val cue = FillerPhrases.RECOVERY",
                         "synthesizer.synthesize(text, optionalFiller = true)",
                         "import com.battlesbudz.jarvis.v2.voice.DelayedAcknowledgement as Cue"):
                with self.subTest(path=path, code=code):
                    self.assertTrue(cue_dependencies({path: code}))

    def test_historical_comments_and_nonverbal_tones_are_not_spoken_fillers(self):
        self.assertEqual([], cue_dependencies({"runtime/turn/VoiceTurnPreparation.kt":
            '// Removed acknowledgeDelays\nVoiceCues.play(VoiceCues.Cue.COMMAND_READY)'}))
        active_cue = "suspend fun play(cue: Cue) { playAcknowledgement(cached) }"
        self.assertTrue(cue_dependencies({"voice/VoiceCues.kt": active_cue}))

    def test_normal_and_followup_keep_their_existing_pcm_queue_bounds(self):
        output = self.tokens("voice/PiperVoiceOutput.kt")
        self.assertRegex(output, r"maxQueuedPassages\s*:\s*Int\s*=\s*2")
        self.assertRegex(output, r"NativeAudioQueue<SynthesizedPhrase>\(maxQueuedPassages\)")
        for path, capacity in (("runtime/VoiceTurnOutputFactory.kt", 8),
                               ("runtime/turn/AcceptedVoiceFollowupStage.kt", 2)):
            with self.subTest(path=path):
                source = self.tokens(path)
                self.assertEqual(1, source.count("PiperVoiceOutput("))
                self.assertRegex(source, rf"maxQueuedPassages\s*=\s*{capacity}\s*,")

    def test_answer_uses_whole_passage_generation_with_natural_silence_and_speed(self):
        output = self.tokens("voice/PiperVoiceOutput.kt")
        config = self.tokens("voice/SherpaTtsConfig.kt")
        synth = self.tokens("voice/PiperSpeechSynthesizer.kt")
        self.assertRegex(output, r"GenerationConfig\(silenceScale\s*=\s*1f,\s*sid\s*=\s*speakerId\)")
        self.assertRegex(output, r"val playbackSpeed\s*=\s*1f")
        self.assertEqual(2, len(re.findall(r"piperWholePassage\s*=\s*true", output)))
        self.assertRegex(config, r"maxNumSentences\s*=\s*if\s*\(piperWholePassage\)\s*0\s*else\s*1")
        self.assertRegex(config, r"silenceScale\s*=\s*1f")
        self.assertRegex(synth, r"val generated\s*=\s*tts.generateWithConfig\(text, generation\)")
        self.assertNotIn("generateWithConfigAndCallback", synth)
        self.assertRegex(synth, r"SynthesizedSpeechPcm.fromModel\(generated.samples, rate\)")

    def test_startup_drain_delivery_and_native_join_remain_owned_by_answer_output(self):
        output = self.tokens("voice/PiperVoiceOutput.kt")
        self.assertIn("PlaybackBufferPolicy.startupWaitMs(result.synthesisMs, frames * 1000 / rate)", output)
        self.assertIn("completed = drainAudioTrack(framesWritten, outputSampleRate, playbackSpeed)", output)
        self.assertIn("rememberPlayback(phrase.text)", output)
        self.assertIn("deliveryLedger?.advance", output)
        self.assertLess(output.index("audio.cancel()"), output.index("producer.cancelAndJoin()"))
        self.assertLess(output.index("audio.close(failure)"), output.index("engineLease?.finish("))
        self.assertIn("audioTrackFactory::releaseAfterWriterStops", output)


if __name__ == "__main__":
    unittest.main()
