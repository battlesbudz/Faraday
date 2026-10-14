<p align="center">
  <img src="artwork/launcher/faraday-wisp.svg" alt="" width="128" height="128">
</p>

<h1 align="center">Faraday</h1>

<p align="center"><strong>A private offline Android voice agent.</strong></p>

<p align="center">The conversation runs on your phone, including speech recognition, AI inference, and the spoken reply.</p>

<p align="center">
  <a href="https://github.com/battlesbudz/Faraday/releases"><img src="https://img.shields.io/github/v/release/battlesbudz/Faraday" alt="Latest release"></a>
  <a href="https://github.com/battlesbudz/Faraday/actions/workflows/android.yml"><img src="https://github.com/battlesbudz/Faraday/actions/workflows/android.yml/badge.svg" alt="Build status"></a>
  <a href="https://github.com/battlesbudz/Faraday/blob/main/LICENSE.md"><img src="https://img.shields.io/badge/license-PolyForm%20Noncommercial-blue" alt="License: PolyForm Noncommercial 1.0.0"></a>
  <a href="https://github.com/battlesbudz/Faraday/releases"><img src="https://img.shields.io/badge/platform-Android%2011%2B-green" alt="Platform: Android 11+"></a>
</p>

<p align="center">
  <sub>Built with</sub><br>
  <img src="https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin">
  <img src="https://img.shields.io/badge/Jetpack_Compose-4285F4?logo=jetpackcompose&logoColor=white" alt="Jetpack Compose">
  <img src="https://img.shields.io/badge/Gemma-1A73E8" alt="Gemma">
  <img src="https://img.shields.io/badge/LiteRT--LM-34A853" alt="LiteRT-LM">
  <img src="https://img.shields.io/badge/Whisper-000000?logo=openai&logoColor=white" alt="Whisper">
  <img src="https://img.shields.io/badge/Piper-FF6F00" alt="Piper">
</p>

<p align="center">
  <a href="https://github.com/battlesbudz/Faraday/releases">Download for Android</a> · <a href="https://cdn.jsdelivr.net/gh/battlesbudz/Faraday@main/demo/jarvis-voice-demo.mp4">Watch the demo</a>
</p>

<a href="https://cdn.jsdelivr.net/gh/battlesbudz/Faraday@main/demo/jarvis-voice-demo.mp4">
  <picture>
    <source media="(prefers-reduced-motion: reduce)" srcset="demo/demo-video-thumbnail.jpg">
    <img src="demo/faraday-demo-preview.gif" width="320" alt="Faraday demo preview: airplane mode and phone locked. Click to watch the full 53-second video with sound.">
  </picture>
</a>

*53 seconds: airplane mode, wake-word activation, and interrupting a spoken reply.*


<details>
<summary>Table of contents</summary>

- [Performance](#performance)
- [Get started](#get-started)
- [What you can do](#what-you-can-do)
- [Capability comparison](#capability-comparison)
- [Roadmap](#roadmap)
- [Memory](#memory)
- [Privacy](#privacy)
- [Documentation and contributing](#documentation-and-contributing)
- [FAQ](#faq)
- [License](#license)

</details>


## Performance

Measured median response time<br>
**2.5 seconds from speech end to playback**<br>
Samsung Galaxy Z Fold 6 · Gemma E2B

## Get started

You’ll need a phone running Android 11 or later and an internet connection for the initial downloads.

- **Storage:** Gemma E2B is about 2.6 GB and E4B about 3.7 GB, plus roughly 280 MB for speech models. Sizes vary by model and version. Installation also needs additional temporary space.
- **RAM:** We recommend 12 GB, matching the Samsung Galaxy Z Fold 6 used for testing.

<p align="center">
  <a href="https://cdn.jsdelivr.net/gh/battlesbudz/Faraday@main/demo/faraday-install-walkthrough.mp4">
    <img src="demo/install-video-thumbnail.png" width="200" alt="Play: 35-second install walkthrough video.">
  </a>
</p>

<p align="center"><em>▶ Video (35 sec): from the releases page to tapping “Download selected model.” Click to watch.</em></p>

1. Download and install the APK from the [releases page](https://github.com/battlesbudz/Faraday/releases).

<p align="center">
  <img src="demo/setup-step1-releases.png" width="200" alt="Faraday releases page on GitHub.">
</p>

2. Open the app, choose a Gemma model, and select **“Download selected model and voice setup.”**

<p align="center">
  <img src="demo/setup-step2-model-picker.png" width="200" alt="Model picker with Gemma-4-E2B-it selected.">
</p>

3. When the downloads finish, select **“Check selected model”** and allow microphone access when prompted.

<p align="center">
  <img src="demo/setup-step3-model-installed.png" width="200" alt="Model installed confirmation screen.">
</p>

4. Start a voice call. When wake-word listening is active, say **“Hey Jarvis”** and ask a question.

> **Note:** “Hey Jarvis” is the default wake phrase for legacy reasons. Making it customizable — or updating it to “Hey Faraday” — is planned for an upcoming release.

For phone actions and natural speech interruption, select **Speech recognition** in voice settings.

## What you can do

- **Voice and text chat.** Talk to Faraday hands-free or type your messages in the chat.

<p align="center">
  <img src="demo/faraday-voice-call.jpg" width="280" alt="Faraday voice call: spoken transcript with response timings.">
</p>

- **Keep talking hands-free.** Wake-word listening works with the screen off or another app open while the voice service is active.
- **Full-duplex audio and barge-in.** Faraday listens while it speaks, so you can interrupt a reply and redirect the conversation.
- **Use phone actions.** Check battery level, adjust media volume, open installed apps, and control media playback with play, pause, next, and previous.
- **Use automatic memory.** Faraday saves relevant information from your conversations automatically. Review, correct, or erase what it remembers.
- **Manage your conversations.** Return to saved chats, delete conversations, or start a private chat.
- **See response timings.** Open a reply’s measurements and export conversation metrics as JSON or CSV.
- **Ask about photos.** Send Faraday a picture and get a detailed on-device description.

<p align="center">
  <img src="demo/faraday-vision.jpg" width="280" alt="Faraday describing a photo of a city street scene.">
</p>


## Capability comparison

| Capability | Faraday | PocketPal AI | Off Grid AI Mobile | Google AI Edge Gallery |
|---|---|---|---|---|
| Local AI and model choice | Yes | Yes | Yes | Yes |
| Offline spoken conversation | Speech input and output | Spoken output; no built-in speech input found | Local speech input; Pro adds spoken output | Local audio input; no spoken-response loop found |
| Wake word while locked or in the background | Yes, while listening is active | Not found | Not found; background speech pauses | Not found |
| Speak over a reply to interrupt | Yes | Tap-to-stop speech | Spoken interruption unverified | Not found |
| Phone and media controls | Battery, volume, app launch, play/pause/next/previous | No phone/media controls found | Battery and device information | Flashlight, contacts, maps, calendar and settings actions |
| Automatic personal memory across chats | Yes, with an editable local memory wiki | Not found; saved chats and personas | Pro advertises persona memory; automatic capture unverified | Not found; saved sessions |
| Saved conversation history | Yes | Yes | Yes | Yes |
| Performance measurements | Voice-pipeline timings and exports | Generation timings and benchmarks | Generation timings | Inference metrics and benchmark CSV |

“Not found” means absent from the Android implementation reviewed, rather than an assumption based on the marketing page. This is a source-code comparison, not a new side-by-side device test.

<details>
<summary>Comparison sources and scope</summary>

Reviewed October 13, 2026. Faraday entries cover main and the audited working branches, including the automatic-memory additions in the tools branch. Source branches can contain capabilities newer than a published APK.

- **Faraday:** [wake-word listening](https://github.com/battlesbudz/Faraday/blob/2921c2f91e7f5d39d36c9c69ce350e7e2505e3b7/app/src/main/java/com/battlesbudz/jarvis/v2/voice/PassiveWakeListener.kt), [interruption during playback](https://github.com/battlesbudz/Faraday/blob/2921c2f91e7f5d39d36c9c69ce350e7e2505e3b7/app/src/main/java/com/battlesbudz/jarvis/v2/voice/ReplyVoiceCapture.kt), [phone and media actions](https://github.com/battlesbudz/Faraday/blob/68e6426a940b9b42b4f9fe7500fdfa93e0c62fd4/app/src/main/java/com/battlesbudz/jarvis/v2/actions/AndroidMobileActionExecutor.kt), [automatic memory](https://github.com/battlesbudz/Faraday/blob/7465c6e43c03ee3d4454fa348b5d63019a6c63e4/app/src/main/java/com/battlesbudz/jarvis/v2/memory/AutomaticMemory.kt), [editable memory wiki](https://github.com/battlesbudz/Faraday/blob/7465c6e43c03ee3d4454fa348b5d63019a6c63e4/app/src/main/java/com/battlesbudz/jarvis/v2/ui/MemoryScreen.kt), [benchmark exports](https://github.com/battlesbudz/Faraday/blob/2921c2f91e7f5d39d36c9c69ce350e7e2505e3b7/app/src/main/java/com/battlesbudz/jarvis/v2/ui/PipelineBenchmarkScreen.kt).
- **PocketPal AI:** [project overview](https://github.com/a-ghorbani/pocketpal-ai/blob/951ad1bc562bd4c383fc638e6a3a63fef12358dd/README.md), [Android manifest](https://github.com/a-ghorbani/pocketpal-ai/blob/951ad1bc562bd4c383fc638e6a3a63fef12358dd/android/app/src/main/AndroidManifest.xml), [speech output](https://github.com/a-ghorbani/pocketpal-ai/blob/951ad1bc562bd4c383fc638e6a3a63fef12358dd/src/store/TTSStore.ts), [tool registry](https://github.com/a-ghorbani/pocketpal-ai/blob/951ad1bc562bd4c383fc638e6a3a63fef12358dd/src/services/talents/index.ts), [chat context](https://github.com/a-ghorbani/pocketpal-ai/blob/951ad1bc562bd4c383fc638e6a3a63fef12358dd/src/hooks/useChatSession.ts), [metrics](https://github.com/a-ghorbani/pocketpal-ai/blob/951ad1bc562bd4c383fc638e6a3a63fef12358dd/src/components/AssistantTurnFooter/AssistantTurnFooter.tsx).
- **Off Grid AI Mobile:** [product and Pro features](https://getoffgridai.co/mobile/), [project overview](https://github.com/off-grid-ai/OGAM/blob/0ecd4be7d50fc2c73025e7bb6bacfcf0e9cd9a12/README.md), [voice settings](https://github.com/off-grid-ai/OGAM/blob/0ecd4be7d50fc2c73025e7bb6bacfcf0e9cd9a12/src/components/settings/voiceSections.tsx), [listening/speaking behavior](https://github.com/off-grid-ai/OGAM/blob/0ecd4be7d50fc2c73025e7bb6bacfcf0e9cd9a12/src/components/ChatInput/useSilenceEndpoint.ts), [background behavior](https://github.com/off-grid-ai/OGAM/blob/0ecd4be7d50fc2c73025e7bb6bacfcf0e9cd9a12/src/bootstrap/hookRegistry.ts), [tool registry](https://github.com/off-grid-ai/OGAM/blob/0ecd4be7d50fc2c73025e7bb6bacfcf0e9cd9a12/src/services/tools/registry.ts), [metrics](https://github.com/off-grid-ai/OGAM/blob/0ecd4be7d50fc2c73025e7bb6bacfcf0e9cd9a12/src/components/ChatMessage/components/GenerationMeta.tsx). Pro advertises persona memory; automatic mobile capture was not verified from the available public implementation.
- **Google AI Edge Gallery:** [project overview](https://github.com/google-ai-edge/gallery/blob/4928788ef07cdf2194fc435a0f446cf3b997bd5d/README.md), [audio recording](https://github.com/google-ai-edge/gallery/blob/4928788ef07cdf2194fc435a0f446cf3b997bd5d/Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/chat/AudioRecorderPanel.kt), [Android manifest](https://github.com/google-ai-edge/gallery/blob/4928788ef07cdf2194fc435a0f446cf3b997bd5d/Android/src/app/src/main/AndroidManifest.xml), [mobile actions](https://github.com/google-ai-edge/gallery/blob/4928788ef07cdf2194fc435a0f446cf3b997bd5d/Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/mobileactions/MobileActionsViewModel.kt), [saved sessions](https://github.com/google-ai-edge/gallery/blob/4928788ef07cdf2194fc435a0f446cf3b997bd5d/Android/src/app/src/main/java/com/google/ai/edge/gallery/agent/sessions/DefaultLlmSessionManager.kt), [live metrics](https://github.com/google-ai-edge/gallery/blob/4928788ef07cdf2194fc435a0f446cf3b997bd5d/Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/chat/LiveMetricsBar.kt), [benchmark CSV](https://github.com/google-ai-edge/gallery/blob/4928788ef07cdf2194fc435a0f446cf3b997bd5d/Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/benchmark/BenchmarkResultsViewer.kt).

</details>

## Roadmap

**In testing now** — working in development branches, heading for a release:

- **On-device vision.** Ask about photos, get descriptions and object counts.
- **Automatic memory.** Faraday remembers across conversations without being asked.
- **Private chats and chat deletion.** Keep a conversation off the record or remove one entirely.
- **Audio-native tool calls.** Use phone actions directly in Gemma voice mode: battery, schedule, notifications, app launch.

**Coming next:**

- **Reminders and schedule management.** Create reminders, view scheduled tasks, and receive notifications.
- **Screen interaction.** Let Faraday read screen content, tap controls, scroll, and enter text with the required permissions.
- **Web browsing.** Open and read pages, follow links, fill forms, and take over when you need to.
- **Workflows.** Schedule routines, review approvals, track progress, and cancel tasks.
- **Service connections.** Set up MCP connections, discover available tools, and manage their permissions.

## Memory

Faraday can remember information across conversations automatically, without you having to ask it to save each detail. You can review what it remembers, make corrections, or erase it.

## Privacy

Faraday runs entirely on your phone. Your voice, AI processing, conversations, and memories stay on the device during offline use. Once the models are downloaded, you can talk to Faraday in airplane mode with no internet connection.

When online, Faraday can look things up through Wikipedia or Wikidata, or open a website link you provide. Those requests send the search query or request to the relevant website. Any external services you choose to connect also receive the information needed to carry out your requests.

## Documentation and contributing

The app uses Kotlin and Jetpack Compose, with Gemma through LiteRT-LM, Whisper for speech recognition, and Piper for speech output.

- [Setup and build instructions](https://github.com/battlesbudz/Faraday/blob/audio-pr2/docs/architecture/development.md)
- [Architecture](https://github.com/battlesbudz/Faraday/blob/audio-pr2/docs/architecture/README.md)
- [Measurements and benchmarks](https://github.com/battlesbudz/Faraday/blob/audio-pr2/docs/verification/pipeline-benchmarks.md)
- [Contribution guide](https://github.com/battlesbudz/Faraday/blob/audio-pr2/CONTRIBUTING.md)

Found a problem or have an idea? [Open an issue](https://github.com/battlesbudz/Faraday/issues). For bugs, include your phone, Android version, app version, selected model, and steps to reproduce. Remove private conversation content before sharing logs.

## FAQ

**Why does Faraday need so much storage?**
The AI models run entirely on your phone instead of a server. That's the tradeoff for offline privacy — the models live on your device.

**Can I use a different wake phrase?**
Not yet. "Hey Jarvis" is the default for legacy reasons; customizable wake phrases are planned.

**Why does the app need microphone access?**
For wake-word listening and voice input. All audio processing happens on-device.

**Does always-on listening drain the battery?**
Wake-word listening uses the phone's low-power audio path. Heavy use — long voice calls, frequent model inference — will use more battery, like any intensive app.

**What happens to my data if I uninstall?**
Everything is stored locally on your phone. Uninstalling removes your conversations, memories, and downloaded models. There's no cloud copy.

## License

Faraday is source-available under the [PolyForm Noncommercial License 1.0.0](https://github.com/battlesbudz/Faraday/blob/audio-pr2/LICENSE.md). Commercial use requires permission. Model and dependency licenses apply separately.
