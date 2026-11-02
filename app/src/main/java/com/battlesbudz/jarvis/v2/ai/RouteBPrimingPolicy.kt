package com.battlesbudz.jarvis.v2.ai

/**
 * Route B (Option B) gating table and prompt-parity standard.
 *
 * Option B moves an eligible native-audio turn's generation from the sealed
 * Conversation route to a Session primed during capture: the turn's text
 * prefix is prefilled while the user is still speaking, the sealed encoder
 * rows are chunk-fed at finalization (the shape proven by the Route B spike),
 * and decoding happens on that same session. Only the TEXT prefill is hidden
 * under speech; the audio prefill still happens after the seal (hiding that
 * is Option A's provisional-rows bridge work).
 *
 * A turn shape is eligible only when the Session route would show the model
 * exactly what the Conversation route would show it. The parity unit is the
 * app-level decomposition — system instruction slot, user-turn text, tool
 * declarations, audio payload — because that is the level the app controls
 * and the level the shipped incremental-text path already treats as its
 * contract (one prompt string, fed incrementally to a Session or whole to a
 * Conversation, with a runtime fallback between the two). The audio payload
 * itself is not a variable: the spike's bridge entry builds each chunk
 * exactly as the Conversation route builds its sealed payload, and the spike
 * proved chunked feeding reconstructs the sealed rows exactly.
 *
 * Measured against that standard, the production direct-audio decomposition
 * NEVER matches a raw Session today: generation enables tools for every
 * direct-audio turn on a tool-capable model (see
 * ConversationGeneration's allowTools), and a Session cannot be given tool
 * declarations (SessionConfig carries only sampler/LoRA config), so the
 * declarations the Conversation route renders into the prompt would be
 * missing. The fixed-voice-preface decomposition used by early preparation
 * and speculative drafts does not match either: its instructions live in
 * the native system slot (Contents system instruction), which a raw
 * Session cannot be given. Both shapes therefore stay sealed, which on the
 * current catalog (every audio-capable Gemma is tool-capable) means the
 * priming machinery is wired but dormant: it arms the first day a
 * tools-disabled, preface-free audio configuration exists, and the parity
 * tests pin the exact boundary so that day is a policy flip, not a rewrite.
 */
internal object RouteBPrimingPolicy {

    /** Why a turn shape must stay on the sealed Conversation route. */
    enum class Reason {
        /** Not a native-audio (Gemma audio understanding) turn at all. */
        NOT_DIRECT_AUDIO,

        /** A diagnostics comparison trial; production routes only. */
        COMPARISON_TRIAL,

        /** No sealed encoder content: priming chunk-feeds sealed rows only. */
        NO_SEALED_AUDIO,

        /** The model has no incremental Session input path. */
        MODEL_NOT_INCREMENTAL,

        /**
         * Generation will run with tools enabled. The Conversation route
         * renders the tool declarations into the model's prompt; a raw
         * Session cannot receive them, so the prompts would differ.
         */
        TOOLS_ENABLED_FOR_TURN,

        /**
         * The turn runs tools-disabled but the engine's conversation is
         * still in its tools-enabled state: generation's tool-policy
         * transition resets the native conversation, which the checked
         * lifecycle refuses while a primed Session is held. The next
         * ordinary turn performs the transition; priming starts after it.
         */
        TOOL_POLICY_TRANSITION_PENDING,

        /** The turn carries a snapshot image; image turns keep their route. */
        SNAPSHOT_IMAGE_ATTACHED,

        /**
         * The native-speculation lane owns this turn's capture: it may run
         * Conversation-route drafts during pauses, and the checked native
         * lifecycle admits exactly one child (Session or Conversation) at
         * a time. Priming never contends with it in v1.
         */
        SPECULATION_LANE_PRESENT,

        /** No resident engine/session could be allocated for this turn. */
        SESSION_UNAVAILABLE,

        /** The shared prefill scheduler (thermal/backlog) declined. */
        SCHEDULER_NOT_ADMITTED
    }

    sealed interface Verdict {
        data object Eligible : Verdict
        data class Ineligible(val reason: Reason) : Verdict
    }

    /** The turn-shape facts the gate reads; filled at arming and re-checked at finalization. */
    data class Shape(
        val directAudioTurn: Boolean,
        val sealedAudioAvailable: Boolean,
        val comparisonActive: Boolean,
        val incrementalGemmaInput: Boolean,
        val toolsEnabledForTurn: Boolean,
        val toolPolicyTransitionPending: Boolean,
        val snapshotImageAttached: Boolean,
        val speculationLanePresent: Boolean,
        val sessionAvailable: Boolean,
        val schedulerAdmitted: Boolean
    )

    fun evaluate(shape: Shape): Verdict = when {
        !shape.directAudioTurn -> Verdict.Ineligible(Reason.NOT_DIRECT_AUDIO)
        shape.comparisonActive -> Verdict.Ineligible(Reason.COMPARISON_TRIAL)
        !shape.sealedAudioAvailable -> Verdict.Ineligible(Reason.NO_SEALED_AUDIO)
        !shape.incrementalGemmaInput -> Verdict.Ineligible(Reason.MODEL_NOT_INCREMENTAL)
        shape.toolsEnabledForTurn -> Verdict.Ineligible(Reason.TOOLS_ENABLED_FOR_TURN)
        shape.toolPolicyTransitionPending -> Verdict.Ineligible(Reason.TOOL_POLICY_TRANSITION_PENDING)
        shape.snapshotImageAttached -> Verdict.Ineligible(Reason.SNAPSHOT_IMAGE_ATTACHED)
        shape.speculationLanePresent -> Verdict.Ineligible(Reason.SPECULATION_LANE_PRESENT)
        !shape.sessionAvailable -> Verdict.Ineligible(Reason.SESSION_UNAVAILABLE)
        !shape.schedulerAdmitted -> Verdict.Ineligible(Reason.SCHEDULER_NOT_ADMITTED)
        else -> Verdict.Eligible
    }
}

/**
 * What the Conversation route would put in front of the model for one turn:
 * the decomposition the parity gate compares. [toolNames] stands for the
 * rendered tool declarations (their exact rendering is native-side; their
 * presence is the app-level fact).
 */
internal data class RouteBConversationShape(
    val systemInstruction: String?,
    val userText: String,
    val toolNames: List<String>,
    val audioTokenRows: Int
)

/** What a primed Session is fed for the same turn: one user slot, nothing else exists. */
internal data class RouteBSessionShape(
    val userText: String,
    val audioTokenRows: Int
)

/**
 * Prompt parity between the routes: identical user-turn text and audio, and
 * nothing in the Conversation decomposition that a Session cannot carry
 * (no system instruction slot, no tool declarations). This is the exact
 * condition the shipped incremental-text path runs under: its turns are
 * only ever routed to a Session when the Conversation route would also run
 * with tools disabled and no system instruction (action turns drop the
 * prepared session input in ConversationRouting).
 */
internal fun routeBPromptParity(conversation: RouteBConversationShape, session: RouteBSessionShape): Boolean =
    conversation.systemInstruction == null && conversation.toolNames.isEmpty() &&
        conversation.userText == session.userText &&
        conversation.audioTokenRows == session.audioTokenRows
