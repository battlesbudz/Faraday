# Jarvis OS V2 — Capability Roadmap (V1 → V2 Port)

**Status:** planning document · **Branch:** `audio-pr2` (PR #6) · **Date:** 2026-09-22
**Sources:** [`battlesbudz/jarvis-os`](https://github.com/battlesbudz/jarvis-os) (V1, cloud-model agent OS),
`battlesbudz/Jarvis-OS-V2` (this repo), Gemma ecosystem research (Sept 2026).

## 1. North Star

Jarvis is a fully local, voice-first, autonomous agent for Android — the offline
Iron Man assistant: it converses, remembers, acts on the phone, operates apps,
manages communications, researches, builds, and improves itself, with no data
ever leaving the device for model inference.

Non-negotiable properties:

- **Models always local.** STT, LLM, TTS run on-device. No user data goes to a model vendor, ever.
- **Tools network-optional.** The agent may use the network for *tools* (email, web)
  when connectivity exists, but the core works in airplane mode.
- **Kotlin validates before execution.** Every tool call is validated in native code
  before it touches the phone. The model proposes; the OS disposes.
- **Approvals are voice-native.** Risky actions pause for a spoken yes/no.
- **Memory is a first-class OS layer**, not a prompt trick.

## 2. Competitive landscape (September 2026)

- Google ships **Gemini Intelligence**: on-device cross-app task automation via
  Gemini Nano 4, first on Galaxy Z Fold 8 / Flip 8 (July 2026). This is the direct
  competitor to "local agent acts on the phone." Requires 12GB+ RAM, flagship SoC.
- Apple Intelligence (AFM 3, ~20B on-device) exposes tool calling to developers via
  the Foundation Models framework; gated to iPhone 17 Pro-class hardware.
- Samsung folds Gemini Nano 4 into One UI 9; much of Galaxy AI still defaults to cloud.
- Meta is buying voice/agent capability aggressively (PlayAI, WaveForms, Manus $2B+).
- **The generic offline-assistant window is ~12–24 months** before OS-vendor defaults
  absorb the casual use case. The durable window is *vertical and offline-first*:
  mid-tier devices the platforms ignore, no-cloud-required environments
  (government, legal, health, finance), field work, accessibility.
- **Agent Skills** (Google AI Edge Gallery): an open `SKILL.md` convention —
  text/JS/native skill packs the model auto-invokes. Replicable; not Google's moat.
- **FunctionGemma 270M** (Dec 2025): 270M function-calling specialist, ~300MB,
  ~50 tok/s on Pixel 8 via LiteRT, fine-tunable (reported 58% → 85% on phone
  actions). License: **Gemma Terms of Use** (commercially permissive, *not*
  Apache 2.0 — flag for legal review before any acquisition).

**Implication:** Google will always have better models; that was never the moat.
The moat is the harness (validation, approvals, memory), vertical depth,
offline-first UX, and user trust. Adopt Google's model improvements; compete on
everything around the model.

## 3. Architecture decisions

### 3.1 Split inference pipeline

```
User voice → STT (Moonshine / Whisper, on-device)
          → Gemma E2B/E4B  (conversation, reasoning, decides WHAT to do)
          → FunctionGemma 270M (action router, parses the EXACT tool call)
          → Kotlin validator (validates, approval-gates, executes)
          → TTS (Piper, on-device) → user
```

- E2B: broad device support, default tier (runs on the mid-tier phones Google ignores).
- E4B: flagship tier (Fold 6-class), planning-heavy and code tasks.
- FunctionGemma 270M: dedicated action router, **fine-tuned on Jarvis's own tool set**.
  Offloads tool-call parsing from E2B — the biggest small-model weakness.
- Kotlin remains the trust boundary: validation, permission checks, execution.

### 3.2 Skills convention

Adopt the `SKILL.md` convention for tool packs: each pack is a folder with
frontmatter (name, description) + instructions injected into the system prompt.
Three pack types, mirroring the proven pattern:

1. **Prompt packs** — persona/instructions only (e.g., accessibility mode).
2. **Webview packs** — HTML/JS in a hidden webview for rich interactive results.
3. **Native packs** — map to Kotlin-validated tools (intents). New native tools
   require source changes + validation rules, by design.

This makes Jarvis community-extensible: third parties can author packs without
forking the app.

### 3.3 Tiered tool exposure

Small models cannot reliably select from 70 tools. Expose **one small pack per
context** (V1's `agents/crew/tools.json` already groups tools this way:
research, communications, planning, monitoring, creation, memory). The router
selects the pack; the model selects within the pack.

### 3.4 Screen understanding: accessibility tree first

V1's phone tools used screenshots + screen context. For on-device small models,
prefer the **Android accessibility node tree** (structured text) as the primary
screen representation; screenshot/MediaProjection is the fallback. Structured
text is what E2B-class models handle well.

### 3.5 Voice-native approvals

Port V1's risk-tiered approval policy (`TOOL_POLICY.md`, `approvalToolRisk`):
low-risk tools execute directly, high-risk tools (send email, delete data,
device actions, code changes) pause for spoken confirmation with the exact
action read back.

## 4. V1 capability inventory (what exists to port)

Source: `battlesbudz/jarvis-os` @ `d8018e4`.

### 4.1 Phone runtime tools (15) → Phase 2

| V1 tool | V2 mapping | Notes |
|---|---|---|
| `android_open_app_by_name` | exists (`open_app`) | extend with package hints |
| `android_capture_screen` | MediaProjection fallback | fallback only; tree is primary |
| `android_read_screen_context` | Accessibility node tree | **redesign:** tree-first |
| `android_tap_screen` | AccessibilityService tap | needs new tool + validation |
| `android_type_text` | AccessibilityService input | needs new tool + validation |
| `android_swipe_screen` | AccessibilityService swipe | needs new tool + validation |
| `android_press_phone_key` | AccessibilityService keys | needs new tool + validation |
| `android_wait_for_ui` | UI-state watcher | needs new tool |
| `android_read_notifications` | NotificationListenerService | high wedge value (accessibility) |
| `android_open_notification` | notification intents | needs new tool + validation |
| `android_search_in_app` | per-app search intents | pack-scoped |
| `android_youtube_search` | research pack | network-optional |
| `android_open_phone_url` | intent launcher | needs validation rules |
| `android_notify_user` | notifications | low risk |
| `android_return_to_jarvis_chat` | task navigation | internal |

### 4.2 Server agent tools (~70) → Phase 3, as tiered packs

- **Communications pack:** `fetch_emails`, `gmail_action`, `create_gmail_draft`, `send_email`, SMS/channel tools. Highest approval tier.
- **Research pack:** `webSearch`, `webFetch`, `xSearch`, `youtubeSearch`, `videoTranscript`, `websiteCrawler`. Network-optional.
- **Planning pack:** `fetch_calendar`, `create_calendar_event`, `manageTasks`, `scheduleJarvisTask`, `cronTools`.
- **Creation pack:** `documents`, `exportPdf`, `createPresentation`, `imageGenerate`, `googleDriveTools`.
- **System pack:** `weatherLookup`, `tts`, `connectedAccounts`, `sessionTools`.

### 4.3 Memory OS → Phase 1

V1 `server/memory/`: `memoryOs`, `vectorStore`, `extractor`, `retrieve`,
`writePipeline`, `decay`, `dream` (consolidation), `dreamPolicy`, `soul`,
`soulCuration`, `people`, `trust`, `autoReview`, `promptContext`,
`protectedEntities`. **Most portable subsystem** — largely a data pipeline.
On-device: swap vector store to a local embedding model; run dream
consolidation as a scheduled job while charging.

### 4.4 Safety & autonomy → port early, with Phase 1

V1 `agents/TOOL_POLICY.md`, `toolExecutionPolicy`, `approvalToolRisk`,
self-improvement loop (`observe → diagnose → propose → test → explain →
request approval → apply → monitor → roll back`). Port the *policy and loop*,
not just the tools. V1 rule to preserve: *"Jarvis is allowed to become more
capable. Jarvis is not allowed to become less accountable."*

### 4.5 Deferred to later phases

- **Phase 4:** crew/subagent orchestration (`spawnSubagent`, `assignAgentTask`,
  `delegateToCodex`), background jobs (`queueBackgroundJob`), monitoring loops.
- **Phase 5:** self-edit/code tools (`applyCodeChangeTool`, `selfEditTools`,
  `selfDiagnoseTool`, `selfHealTool`, `buildFeatureTool`, `runShellTool`,
  `codeExecution`, `deployApp`, `githubPrTools`) — **E4B-gated**, full
  propose→approve→apply→rollback loop.
- **Phase 6:** wearables (EYE VUE glasses bridge exists in V1), XR/spatial.

## 5. Phased plan

### Phase 0 — Voice loop (now, `audio-pr2` / PR #6)
Finish acceptance: recognition, interruptions/barge-in, latency, sustained use,
lifecycle, E4B on Fold 6. **Exit:** verification map in
`docs/verification/features.md` is green on real devices. Nothing else ships
until the voice loop is boring and reliable.

### Phase 1 — Memory OS + trust architecture
- Port memory pipeline (extract → store → retrieve → decay) with on-device embeddings.
- Dream consolidation as a scheduled charging-time job.
- Port V1 tool policy + approval tiers into the Kotlin validation layer.
- **Exit:** Jarvis remembers facts across sessions; risky tools approval-gate in voice.

### Phase 2 — Phone control (the jaw-drop demo + accessibility wedge)
- AccessibilityService: tap, swipe, type, key press, UI-state wait.
- Accessibility-tree screen understanding (primary); screenshot fallback.
- NotificationListenerService: read/open notifications.
- Voice-native approvals on every device action.
- **Exit:** "Do everything on my phone by voice, offline" works on 3+ real apps;
  ships as the accessibility wedge (see §7).

### Phase 3 — Tiered capability packs (skills)
- Adopt `SKILL.md` pack convention (§3.2); expose one pack per context (§3.3).
- Order: communications → research → planning → creation → system.
- Network-optional boundary: packs declare connectivity needs; core stays offline.
- **Exit per pack:** pack verified on-device with FunctionGemma router fine-tuned
  on that pack's tool set.

### Phase 4 — Agentic depth
- Kotlin-orchestrated multi-step plans (model decides single steps; Kotlin chains).
- Scheduled jobs, background monitoring, reviewable deliverables.
- **Exit:** multi-step tasks complete reliably without model free-chaining.

### Phase 5 — Self-improvement (E4B-gated)
- Code/diagnosis tools behind the strictest approval loop.
- **Exit:** propose→test→approve→apply→rollback demonstrated end-to-end.

### Phase 6 — Wearables
- EYE VUE bridge, HUD event schema (V1 has prior art). Hardware-dependent.

## 6. Small-model redesign notes (what can't just be ported)

1. **Tool selection:** 70 tools → tiered packs. Router picks pack, model picks tool.
2. **Planning horizon:** no free-chaining; Kotlin orchestrates validated sequences.
3. **Screen understanding:** accessibility tree (text) over screenshots (vision).
4. **Tool-call parsing:** FunctionGemma 270M as dedicated router, fine-tuned per pack.
5. **Approvals:** voice-native, risk-tiered, exact action read back before confirm.
6. **Memory embeddings:** on-device embedding model; consolidation offloaded to idle time.

## 7. Wedge strategy (go-to-market)

The full vision is the destination; wedges are the beachheads that fund and prove it.

1. **Offline voice companion for blind / low-vision / elderly users** (top pick).
   Voice IS the interface — monetizes the Phase 0/2 build directly. Works on cheap
   phones with no data plan. Real procurement channel (governments, nonprofits).
2. **No-cloud professional dictation/notes** (legal, medical admin, finance).
   Compliance *requires* local processing. B2B licensing.
3. **Hands-free field worker** (technicians, warehouse, delivery). Strongest
   offline story; needs Phase 2 + messaging tools.
4. **Kids' offline companion** (dark horse). Differentiated privacy story; heavy
   content-safety burden — park it.

Rule: the wedge dictates which tools get built next. Never build tools generically.

## 8. Licensing & commercial notes

- Gemma 4 (E2B/E4B): **Apache 2.0** — full commercial freedom. Audit all other
  models/deps (Whisper, Moonshine, Piper) before any commercial move.
- FunctionGemma 270M: **Gemma Terms of Use** — commercially permissive but NOT
  Apache 2.0. Get legal review before acquisition/funding diligence.
- Acquisition reality: code alone has ~no acquisition value. Buyers pay for
  traction (users/revenue/retention), talent, or IP they can't build. Bar for
  real conversations: reliable core + one deep wedge + engaged users + early revenue.

## 9. Open questions

- Exact offline boundary: airplane-mode-always vs. local-models + network tools?
  (Recommended: models always local; tools network-optional.)
- FunctionGemma fine-tune dataset: curate from V1 tool-call logs?
- Which wedge to commit to first — needed before Phase 2 tool prioritization.
- Business formation (LLC), privacy policy, Play Store listing, billing — before
  any public launch.
