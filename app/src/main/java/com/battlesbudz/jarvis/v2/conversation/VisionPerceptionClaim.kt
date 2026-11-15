package com.battlesbudz.jarvis.v2.conversation

/**
 * The no-vision honesty guard's detector, mirroring [AudioAccessRefusal].
 *
 * On 31 October 2026 a device test on a build with no vision input of any
 * kind (voice call e91d949b, build 1632) showed the failure this guards
 * against. Asked "Can you tell me what you see?", the model answered "I see
 * a room with a window and a desk. This is the primary visual data I am
 * currently processing." — a scene confabulated from nothing, stated as
 * live perception. Nothing in the pipeline objected. Asked again, it
 * regenerated the identical text and the repetition guard shipped its
 * generic fallback instead.
 *
 * The guard acts only when all three hold: the user's turn is
 * vision-directed (it asks about current visual perception), the turn had
 * NO visual input (no attached image and no injected visual-context
 * block), and the generated answer asserts perception anyway. A turn with
 * an image or a visual-context block is never guarded, even if the answer
 * is wrong — bad recognition is a different problem. An answer that is
 * honest about not seeing passes untouched.
 *
 * Both detectors are deliberately narrow and enumerated, in the
 * [AudioAccessRefusal] style. The question detector's primary failure
 * mode is metaphorical "see" ("I see your point", "let's see what
 * happens", "what do you see in this argument", "can you see why"), so
 * those shapes are excluded first and pinned as negatives in tests. The
 * answer detector needs a first-person (or camera/image-subject)
 * perception verb with a concrete object, a negated or honestly-denied
 * seeing never matches by construction, and a sentence anchored to an
 * image the user actually supplied ("in the photo you sent, I can see…")
 * is a legitimate reference to an earlier turn, not a fresh perception
 * claim.
 *
 * Direct-audio turns are the one place the question detector cannot
 * run: the invocation prompt on those turns is the fixed audio
 * instruction boilerplate (the user's words exist only as audio, and
 * the Gemma caption is produced by the call layer afterwards, for the
 * history — it is never the invocation text and it finalizes after the
 * answer is generated). A build-1697 device call (443f75e8) proved the
 * gap: asked aloud "Can you tell me what you see?" with no visual
 * input, the model answered "I see a picture of a very large, brightly
 * coloured, somewhat abstract shape…" and the guard never fired,
 * because the only question text it could read was the boilerplate.
 * So on a direct-audio turn the claim side carries the guard instead:
 * a concrete visual claim — a pictured, shaped or scened thing stated
 * as seen — is sufficient on its own, because on a turn with no visual
 * input such a claim is false whatever the spoken question was. The
 * concrete-claim detector is narrower than the general claim detector
 * on purpose: metaphorical seeing ("I see what you mean", "I see the
 * point") carries no visual content noun and never fires it.
 *
 * A build-1771 device call (c9a59a9f) exposed a pure pattern gap in
 * that claim side — the wiring already ran on the native-audio path,
 * but the answer "I see your phone screen. It is currently displaying
 * a settings screen." matched nothing and left no guard line at all.
 * Both sentences evaded the enumeration: the "I see" object set
 * deliberately excludes possessives (which protects "I see your
 * point"), exempting a possessive-led concrete object with them; and
 * the display patterns anchor on named subjects ("the camera", "the
 * image"), so a pronoun-subject "It is currently displaying…" walked
 * past. The two patterns added for that specimen require the concrete
 * scene noun within two words of the possessive or the displayed
 * article, so "I see your point about the budget" and "I see your
 * message about the screen repair" still never match.
 */
internal object VisionPerceptionClaim {
    const val FALLBACK = "I can't see anything on this turn, sir — no camera image came through."

    const val CORRECTION =
        "\nThe previous draft wrongly claimed to see something. No image or camera feed was " +
            "provided on this turn, so you cannot see anything right now. Answer honestly " +
            "that you can't see on this turn. Do not describe any scene, room or object as " +
            "if it were visible, and do not call a tool."

    // ---- question side: is this turn asking about current perception? ----

    /** "What do you see in this argument/essay/data…" asks for interpretation, not eyes. */
    private val documentInterpretation = Regex(
        "(?i)\\bsee in (this|that|the) (argument|essay|article|text|passage|plan|proposal|" +
            "speech|letter|e-?mail|message|report|document|story|book|data|point|idea)\\b"
    )

    /** "Can you see why / my point / the problem" is metaphorical seeing. */
    private val metaphoricalSee = Regex(
        "(?i)\\bsee (why|my point|the point|your point|the problem|the issue|my reasoning)\\b"
    )

    /** "What do you see this as" asks how something is regarded, not what is visible. */
    private val regardSee = Regex("(?i)\\bsee (it|this|that|them|him|her|us) as\\b")

    private val mediaWord = Regex("(?i)\\b(photo|picture|image|pic|screenshot|photograph)\\b")

    private val suppliedMarker = Regex(
        "(?i)\\b(sent|shared|uploaded|posted|provided|gave|took|earlier|previously)\\b"
    )

    private val whatYouSee = Regex(
        "(?i)(\\bwhat (do|can|could) you (see|seeing|looking at)\\b|\\bwhat you (see|are seeing)\\b)"
    )

    private val canYouSee = Regex(
        "(?i)(\\bcan you (see|look at)\\b|" +
            "\\bdo you see (anything|something|anyone|anybody|me|us|him|her|them|it)\\b)"
    )

    private val lookAtThis = Regex("(?i)\\blook at (this|that|it|me)\\b")

    private val inFrontOf = Regex(
        "(?i)\\b(what('s| is)|describe|tell me)\\b[^.?!]*\\bin front of (me|you|the camera|us)\\b"
    )

    private val whatsAround = Regex("(?i)\\bwhat('s| is) around (you|me|us)\\b")

    /**
     * True when [text] asks about what is currently visible: what the
     * assistant sees, is looking at, or what is in front of the user or
     * the camera. Conservative by construction — a follow-up referring to
     * an image the user supplied on an earlier turn ("the photo I sent
     * you") is not a request for fresh perception and returns false.
     */
    fun isVisionDirectedQuestion(text: String): Boolean {
        val question = text.trim()
        if (question.isEmpty()) return false
        if (documentInterpretation.containsMatchIn(question)) return false
        if (metaphoricalSee.containsMatchIn(question)) return false
        if (regardSee.containsMatchIn(question)) return false
        if (mediaWord.containsMatchIn(question) && suppliedMarker.containsMatchIn(question)) {
            return false
        }
        return whatYouSee.containsMatchIn(question) ||
            canYouSee.containsMatchIn(question) ||
            lookAtThis.containsMatchIn(question) ||
            inFrontOf.containsMatchIn(question) ||
            whatsAround.containsMatchIn(question)
    }

    // ---- answer side: does this text assert current perception? ----

    private val sentenceSplit = Regex("[.!?\\n]+")

    /**
     * A sentence that honestly denies seeing is never a perception claim:
     * "I can't see…", "I don't see…", "no camera image came through". The
     * negation must sit next to the seeing verb, so a claim elsewhere in
     * the same answer is still caught sentence by sentence.
     */
    private val honestDenial = Regex(
        "(?i)(\\b(can't|cannot|can not|don't|do not|unable to|not able to)" +
            "\\s+(\\w+\\s+){0,2}?(see|seeing|view)\\b|" +
            "\\bno (camera |live |visual )?(image|camera|photo|picture|feed|visual input|video)\\b|" +
            "\\bwithout (an? |any )?(image|camera|photo|picture|feed)\\b)"
    )

    private val suppliedVerb = Regex(
        "(?i)\\b(sent|shared|uploaded|posted|provided|gave|took)\\b"
    )

    private val mediaAboveBelow = Regex("(?i)\\b(photo|picture|image) (above|below|here)\\b")

    /** Scene nouns an opening bare description can name; enumerated on purpose. */
    private val sceneNoun =
        "(room|office|kitchen|bedroom|living room|bathroom|street|garden|yard|desk|table|" +
            "window|door|wall|floor|ceiling|chair|sofa|couch|bed|lamp|screen|monitor|" +
            "television|tv|car|person|man|woman|child|dog|cat|building|house|tree|plant|fan|map)"

    private val sceneOpening = Regex("(?i)^\\s*(a|an|the) $sceneNoun\\b")
    private val sceneContinuation = Regex(
        "(?i)\\b(with|in front of|behind|beside|next to|near|against)\\b"
    )

    private val objectAfterSee =
        "(a|an|the|some|one|two|three|four|five|six|seven|eight|nine|ten|\\d+|" +
            "you|it|this|them|him|her)"

    private val claimPatterns = listOf(
        // "I see a room…" — the object set deliberately excludes bare
        // "that" and possessives, so "I see that works" and "I see your
        // point" never match.
        Regex("(?i)\\bi see $objectAfterSee\\b"),
        Regex("(?i)\\bi can see $objectAfterSee\\b"),
        // The build-1771 specimen's first sentence: "I see your phone
        // screen." A possessive-led object is a perception claim when
        // the thing seen is concrete — a scene noun within two words of
        // the possessive. "I see your point about the budget" names no
        // scene noun there, and "I see your message about the screen
        // repair" keeps its scene noun three words out; both stay safe.
        Regex("(?i)\\bi (see|can see) your (\\w+ ){0,2}?$sceneNoun\\b"),
        // The specimen's second sentence: "It is currently displaying
        // a settings screen." A pronoun subject presenting a concrete
        // thing as currently displayed is the named-subject display
        // claim below with the subject worn down to "it"/"this".
        Regex("(?i)\\b(it|this)('s| is) (currently |now )?(displaying|showing) " +
            "(a|an|the) (\\w+ ){0,2}?$sceneNoun\\b"),
        Regex("(?i)\\b(it|this) (displays|shows) (a|an|the) (\\w+ ){0,2}?$sceneNoun\\b"),
        Regex("(?i)\\bi('m| am) (seeing|looking at)\\b"),
        Regex("(?i)\\bwhat i (see|can see|am seeing|'m seeing) is\\b"),
        Regex("(?i)\\bthe camera (shows|sees|is showing|displays)\\b"),
        Regex("(?i)\\bthe (image|picture|photo) (shows|depicts|displays|contains)\\b"),
        // The device answer's second sentence: "This is the primary
        // visual data I am currently processing."
        Regex("(?i)\\bvisual data\\b"),
        Regex("(?i)\\byou('re| are) looking at (a|an|the) $sceneNoun\\b")
    )

    /**
     * True when [text] asserts current visual perception: claims to see,
     * describes a scene as currently visible, or presents "visual data"
     * as being processed. Honest denials, references anchored to an image
     * the user supplied, and metaphorical seeing never match.
     */
    fun assertsPerception(text: String): Boolean {
        val display = text.trim()
        if (display.isEmpty()) return false
        val sentences = display.split(sentenceSplit).map { it.trim() }.filter { it.isNotEmpty() }
        if (opensWithBareSceneDescription(sentences)) return true
        return sentences.any { sentence ->
            !isHonestOrAnchored(sentence) && claimPatterns.any { it.containsMatchIn(sentence) }
        }
    }

    // A bare scene description as the answer's opening ("A room with
    // a window and a desk.") asserts perception without ever saying
    // "I see"; only the first sentence can open the answer this way.
    private fun opensWithBareSceneDescription(sentences: List<String>): Boolean {
        val opening = sentences.firstOrNull() ?: return false
        return !isHonestOrAnchored(opening) &&
            sceneOpening.containsMatchIn(opening) && sceneContinuation.containsMatchIn(opening)
    }

    /** Concrete visual content a claimed perception can be about. */
    private val visualContent = Regex(
        "(?i)\\b(picture|image|photo|photograph|scene|shape|silhouette|landscape|" +
            "painting|drawing|camera|hallway|corridor|view|$sceneNoun)\\b"
    )

    /**
     * True when [text] asserts perception of a concrete visual thing: a
     * pictured, shaped or scened object stated as seen, or a bare scene
     * description opening the answer. This is the claim-side trigger for
     * direct-audio turns, where the spoken question is not available as
     * text: it is deliberately narrower than [assertsPerception], so a
     * metaphorical "I see the point" or a bare "I see you" — claims with
     * no visual content — never fire it, while the build-1697 specimen
     * ("I see a picture of a very large, brightly coloured, somewhat
     * abstract shape") always does. Honest denials and references
     * anchored to a supplied image are excluded exactly as in
     * [assertsPerception].
     */
    fun assertsConcretePerception(text: String): Boolean {
        val display = text.trim()
        if (display.isEmpty()) return false
        val sentences = display.split(sentenceSplit).map { it.trim() }.filter { it.isNotEmpty() }
        if (opensWithBareSceneDescription(sentences)) return true
        return sentences.any { sentence ->
            !isHonestOrAnchored(sentence) &&
                claimPatterns.any { it.containsMatchIn(sentence) } &&
                visualContent.containsMatchIn(sentence)
        }
    }

    /** Which evidence admitted the guard on a no-visual-input turn. */
    enum class Trigger { QUESTION, DIRECT_AUDIO_CLAIM }

    /**
     * The guard's admission test, shared by the generation stage and the
     * recovery backstop so the two can never drift apart. Returns the
     * trigger when the turn must not be answered with [draftText] as-is:
     * either the question text is vision-directed and the draft asserts
     * perception, or the turn is direct audio — where the question
     * exists only as sound, so the boilerplate prompt can never be
     * vision-directed — and the draft asserts a concrete visual
     * perception. A turn whose question is known and not vision-directed
     * is never admitted by the claim side; that boundary is pinned.
     */
    fun triggerFor(prompt: String, directAudio: Boolean, draftText: String): Trigger? = when {
        isVisionDirectedQuestion(prompt) && assertsPerception(draftText) -> Trigger.QUESTION
        directAudio && assertsConcretePerception(draftText) -> Trigger.DIRECT_AUDIO_CLAIM
        else -> null
    }

    /** Diagnostics spelling of a [Trigger], for the guard outcome lines. */
    fun triggerName(trigger: Trigger): String = when (trigger) {
        Trigger.QUESTION -> "question"
        Trigger.DIRECT_AUDIO_CLAIM -> "direct_audio_claim"
    }

    private fun isHonestOrAnchored(sentence: String): Boolean {
        if (honestDenial.containsMatchIn(sentence)) return true
        // "In the photo you sent, I can see a desk" describes an image the
        // user actually supplied (on this or an earlier turn) — a
        // legitimate reference, not an invented live perception.
        if (mediaAboveBelow.containsMatchIn(sentence)) return true
        return mediaWord.containsMatchIn(sentence) && suppliedVerb.containsMatchIn(sentence)
    }
}
