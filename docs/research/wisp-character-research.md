# Assistant character research for Wisp

**Research reviewed:** 6 October 2026. **Repository intake:** 6 October 2026.
**Status:** dated research and design recommendations; not a release or verification record.

This preserves the full research delivered as *Wisp Assistant Character Research*.
The [implementation queue](../plans/wisp-transparency-improvements.md) maps its
recommendations to the existing application, distinguishes verified behavior from
unreleased implementation, and sequences the remaining work after the current
`audio-pr2` integration. The [feature map](../verification/features.md) and exact
build receipts remain authoritative for application acceptance.

Primary company and platform links below reflect the research date. Product
availability, branding and undocumented animation details can change. Recheck
relevant sources before implementing a new integration; the comparisons are not
claims of access to competitors' private internals or a measured UX benefit.
Proposed Wisp examples do not imply that Jarvis currently supports those tools.

The user's product requirement is to keep Wisp present and hide the activity line
when idle. An unresolved approval or task outcome stays discoverable in task
controls. A persistent storage warning must not freeze the live activity display
or create permanent idle text; see the queue's current-behavior reconciliation.

---

Research brief for Wisp and the Android Jarvis experience

Reviewed 6 October 2026

## Main finding

The closest reference for Wisp is Muse. Meta explicitly documents a short description of current work underneath its avatar, with the full activity log and permissions available when the avatar is tapped. Wisp can combine that transparency with the immediate listening and speaking feedback of voice assistants.

My recommendation is a small, persistent character with a task-specific activity sentence while work is happening. The sentence changes when the underlying work changes, disappears when genuinely idle, and opens a task view with evidence and controls. Motion communicates broad state; text explains the actual operation.

This is a representative comparison, not a catalogue of every assistant character. Product facts below come from primary company documentation. Wisp recommendations are design proposals, not claims about proprietary implementations. Public documentation does not specify every animation state, timing rule or status-generation method.

## The closest references

### Meta Muse

Meta's design team says users can create an avatar, give it a name and choose a style. That identity accompanies an ongoing conversation and multiple tasks. Under the avatar, Muse displays a snippet describing current work. Tapping it opens the full activity log and approved permissions. A separate Goals view covers longer-running commitments. [Meta's design explanation](https://introducing.muse.ai/)

This gives the character a practical job: a recognizable entry point to activity and control. Meta also describes structured approval controls for consequential actions. [Meta's product introduction](https://about.fb.com/news/2026/09/introducing-muse-personal-ai-agent/)

What Wisp should borrow: the under-character activity sentence and tap-for-details pattern. Keep personality in appearance and wording while making the operation understandable.

What remains unknown: the sources do not establish Muse's complete motion vocabulary or whether every status sentence is model-generated, templated or composed another way.

Muse Charm is a separate pocket-device announcement for interacting with Muse using real-time voice. Meta said more details would follow later in the year. It is not a sufficient source for a complete character-animation specification. [Meta Connect announcement](https://about.fb.com/news/2026/09/the-biggest-news-from-connect-2026/)

### OpenAI dots

OpenAI lets users name a dot and choose a character or pet, including a generated pet. Its profile provides activity organized into In progress, Scheduled and Completed, plus access to the dot's computer. [OpenAI setup guide](https://help.openai.com/en/articles/20001530-getting-started-with-your-dot)

OpenAI describes dots as persistent agents that can handle several projects and remain reachable while work continues. Users can inspect their computer, with access and approval controls around actions. [OpenAI product introduction](https://openai.com/index/introducing-dots/)

The character's documented role is identity and continuity. One familiar face can represent several tasks, provided users can inspect those tasks separately. Animation alone cannot explain concurrency.

What Wisp should borrow: stable identity, task continuity and an activity view. With concurrent work, a sentence such as “Comparing train routes · 2 other tasks” can help, with the count derived from real task state.

Limit: these sources do not document the exact mapping from dot animation to executor state. An avatar movement should not be treated as proof that a tool is running.

### Microsoft Mico

Microsoft documents Mico as an animated character that visually reacts during voice conversations. Its stated purpose was exploring warmth and expressiveness. Current support documentation says Mico is moving from general Copilot Voice into Learn Live, where it continues as a tutor with real-time animation and voice. Voice itself remains available without the character. [Microsoft's current Mico guidance](https://support.microsoft.com/en-us/microsoft-365-copilot/frequently-asked-questions-about-retired-copilot-features)

This is a character primarily serving conversational presence. A reaction can make spoken exchange feel responsive, but it does not establish that a search, purchase or file operation happened.

What Wisp should borrow: subtle listening reactions and restrained expressions when an outcome arrives. Make motion optional. Never make an emotional pose the only distinction between failure and success.

The product change matters: Mico should not be presented as an unchanged, universally available part of today's general Copilot Voice.

## Voice assistants show a different kind of presence

### Apple Siri

Apple uses an abstract visual identity rather than a personalized cartoon face. Its current Siri page presents a wavy, gradient orb alongside voice and typed interaction. An earlier Apple Intelligence design explicitly used a glow around the screen while Siri was active. These are different generations, not one universal current animation. [Current Siri presentation](https://www.apple.com/apple-intelligence/) · [Apple's 2024 design announcement](https://images.apple.com/uk/newsroom/2024/06/introducing-apple-intelligence-for-iphone-ipad-and-mac/)

The design lesson is ambient presence: acknowledge the assistant while keeping the user's content central. Apple also frames Siri as a route to app actions from across the system. [Apple's Siri design guidance](https://developer.apple.com/design/human-interface-guidelines/siri/)

For Wisp: keep the character small enough that it does not cover the work. Use text such as “Reading the selected article” only when that operation actually begins. An orb alone cannot explain a long-running task.

### Google Gemini Live

The most transferable documented pattern is conversation control. Gemini Live can be interrupted by speech, with tapping available if spoken interruption is disabled. Google documents captions, screen or camera sharing, and a route back into active background conversation. A Live session starts while unlocked; users can end an ongoing session from its notification. [Gemini Live on Android](https://support.google.com/gemini/answer/15274899?co=GENIE.Platform%3DAndroid&hl=en)

For Wisp: microphone state needs immediate, unambiguous feedback. Mute and stop should remain available while tasks run. Live capture, a queued task and a network search are separate facts; one generic “working” animation should not blur them together.

This comparison uses documented controls rather than asserting a permanent waveform layout. Live interfaces change across versions.

### Amazon Alexa

Amazon provides an explicit visual state vocabulary for Echo devices: directional blue during listening, alternating blue between an utterance and response, a response indicator, and distinct indications for microphone mute, notifications and errors. [Amazon's light-ring guidance](https://developer.amazon.com/en-US/alexa/branding/echo-guidelines/identity-guidelines/light-ring)

Alexa demonstrates the value of a small, learnable set of immediate interaction signals. That vocabulary does not communicate the details of arbitrary multi-step work.

For Wisp: a finite set of broad motion states is sensible. The text should stay open-ended and specific, such as “Checking the opening hours for three museums.” Avoid relying on colors or copying Amazon's exact visual branding.

### ChatGPT Voice and agent work

OpenAI documents several voice presentations, including integrated chat and a separate blue-orb screen, with microphone and exit controls. [ChatGPT Voice FAQ](https://help.openai.com/en/articles/8400625-voice-mode-faq)

Longer-running ChatGPT agent work supports interruption, steering, pausing, stopping and partial results. Deep research separately provides progress visibility, sources and activity history. [ChatGPT agent introduction](https://openai.com/index/introducing-chatgpt-agent/) · [Deep research guide](https://help.openai.com/en/articles/10500283-research-faq)

For Wisp: connect voice presence and task evidence while preserving their different meanings. Speaking animation reflects audio playback; a timeline explains what was attempted, what succeeded and what still needs attention.

## What this means for Wisp

Characters serve three different purposes: recognizable identity, moment-to-moment conversational feedback, and access to ongoing work. Wisp should support all three without asking one animation to carry every meaning.

- The character preserves identity and shows broad interaction state.
- A short, freely worded sentence explains current work or a blocker.
- A tap opens activity, tasks, results and controls.

This is a design synthesis. The sources do not establish that a mascot or status sentence produces a particular measured reduction in perceived latency. The practical goal is to remove uncertainty: did Wisp hear me, has work begun, is it waiting, and can I stop it?

## Recommended first release

### 1 Truthful task specific activity text

Use actual task, microphone, network and tool events. The visible text should be an ordinary string, not an enum restricted to “Thinking,” “Researching” and “Working.” Internal lifecycle states can still be finite.

These are proposed Wisp examples, not quotes from competitors:

- “Listening” when the microphone genuinely captures input
- “Searching for quieter cafés near the station” after search begins
- “Reading the café's opening hours” after the page-read operation begins
- “Waiting for your approval to send the message” at an approval gate
- “The connection dropped. Tap to retry” after confirmed failure

Start with safe contextual composition from actual events and approved display metadata. A separate model call is unnecessary merely to phrase every update. If natural-language generation is added later, constrain it to verified facts and discard late summaries of superseded steps.

### 2 Immediate listening and speaking feedback

Drive animation from actual capture and playback. Keep mute and stop reachable. Stopping speech and cancelling a task are different actions; distinguish their controls.

### 3 Honest completion and recovery

Confirm completion only after verified success. Preserve partial results. Show an actionable error rather than leaving the last productive sentence running indefinitely. Reconnecting must not masquerade as continued research.

Show a cancellation request first, then confirm cancellation when acknowledged. If an external action completed or its outcome is uncertain, explain that instead of claiming it was undone.

### 4 Hide the line only at true idle

Remove the activity line when no work or unresolved attention item remains. An approval, failure or disconnected task is not idle and should remain discoverable. Required Android microphone and service indicators stay available independently of decorative text.

### 5 Accessible and private from the beginning

Support larger text, contrast and non-color cues. Motion is optional. Important changes should be available to TalkBack without announcing every intermediate event.

## Next capabilities

1. Tap-to-open details with timestamped operations, useful source links, verified results and explicit controls.
2. Concurrent tasks with an accurate count and separate rows; keep approvals and failures visible.
3. Durable recovery after app recreation, including reconnection to genuinely active work and accurate interrupted states.
4. Optional screen context with user-controlled sharing and obvious start and stop indicators.
5. Proactive goals and reminders after reliable scheduling, permissions, quiet-time rules and delivery exist.
6. Richer character expression, voice-linked motion and customization after operational states are trustworthy.

For a local Android Jarvis, cross-app automation, continuous wake-word listening and cloud-style always-on work are separate projects. An animated character or foreground service does not grant system access or guarantee uninterrupted execution. Android places permission, scheduling and background-start restrictions on these capabilities. [Foreground services](https://developer.android.com/develop/background-work/services/fgs) · [Background-start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start) · [Long-running work](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running)

## Implementation principles

### Keep facts separate from presentation

Public activity events should identify task, run, sequence, timestamp, lifecycle state and safe display text. Associate events with real operations. Ignore stale or duplicate updates; a late event must not revive a finished task.

Distinguish requested, queued, started and finished work. “Preparing to search” and “Searching” imply different facts. Show counts only when measured. Percentages need meaningful denominators; remaining time needs a defensible estimate. Never advance stages simply because a timer elapsed.

### Summarize actions rather than private reasoning

Describe observable activity: the source being checked, the file being prepared, the pending approval and the verified result. Do not expose private chain-of-thought, raw model streams, prompts, credentials or hidden execution notes. “Comparing the two train options” is enough.

### Protect details before formatting text

Use a per-tool allowlist for display metadata. Do not copy raw arguments, full URLs with query parameters, message bodies, private filenames, account identifiers or authentication data. Use neutral app or source names when subjects are sensitive.

Use private notifications with a deliberately redacted public version on the lock screen. “Wisp has an update” is safer than exposing a medical, financial or relationship task subject. Android supports private and secret visibility and replacement public content. [Notification visibility](https://developer.android.com/reference/android/app/Notification)

### Preserve task identity during concurrency

Keep per-task state even with one character. Microphone capture needs an immediate indicator. Preserve a separate attention indicator for approvals and failures. The sentence can follow the viewed task while a list shows other work. Cancellation targets the intended task unless the user stops all work.

### Control motion and announcements

Use a stable one- or two-line area without marquees or constant layout jumps. Coalesce noisy events, but do not delay meaningful results to finish an animation.

Compose supports polite live regions and state descriptions; Android warns that frequent live-region changes overwhelm users. Use one meaningful status node, avoid duplicate descriptions on decorative art, and expose accessible Stop, Retry and Details controls. Remove hidden idle text from the accessibility tree instead of only making it transparent. [Compose semantics](https://developer.android.com/develop/ui/compose/accessibility/semantics)

Honor system motion scaling and provide a static presentation with equivalent information. [Compose motion scaling](https://developer.android.com/reference/kotlin/androidx/compose/ui/MotionDurationScale) · [W3C guidance on nonessential interaction animation](https://www.w3.org/WAI/WCAG22/Understanding/animation-from-interactions)

## Acceptance checks

- Microphone failure never leaves Wisp claiming to listen.
- A queued operation does not claim a source has already opened.
- Switching between tasks preserves each task's actual state.
- A cancelled run cannot overwrite a newer run.
- Connection loss produces an accurate waiting or error state.
- Approval requests identify the action and remain available until resolved.
- An already completed external action is not described as undone after Stop.
- Idle text and its accessibility node are absent at genuine idle.
- Large text, TalkBack and reduced motion remain usable.
- Lock-screen previews reveal no sensitive task subject.

Wisp should feel present because its work is understandable and controllable.
