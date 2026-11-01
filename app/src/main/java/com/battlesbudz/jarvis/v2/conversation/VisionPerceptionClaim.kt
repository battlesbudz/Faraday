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
        // A bare scene description as the answer's opening ("A room with
        // a window and a desk.") asserts perception without ever saying
        // "I see"; only the first sentence can open the answer this way.
        val opening = sentences.firstOrNull()
        if (opening != null && !isHonestOrAnchored(opening) &&
            sceneOpening.containsMatchIn(opening) && sceneContinuation.containsMatchIn(opening)
        ) {
            return true
        }
        return sentences.any { sentence ->
            !isHonestOrAnchored(sentence) && claimPatterns.any { it.containsMatchIn(sentence) }
        }
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
