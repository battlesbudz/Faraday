package com.battlesbudz.jarvis.v2.ai

/** Presentation metadata; never changes a bundle's runtime capabilities. */
data class ModelPurpose(val tags: List<String>, val description: String, val caveat: String = "")

object ModelGuide {
    fun family(spec: LocalModelSpec): String {
        val id = spec.id.lowercase()
        return when {
            id.contains("gemma") -> "Gemma"
            id.startsWith("deepseek") -> "DeepSeek"
            id.startsWith("qwen") -> "Qwen"
            id.startsWith("llama-") -> "Llama"
            id.startsWith("ministral") -> "Mistral"
            id.startsWith("lfm") -> "Liquid · LFM"
            id.startsWith("smolvlm") -> "SmolVLM"
            id.startsWith("smollm") -> "SmolLM"
            id.startsWith("phi-") -> "Phi"
            id.startsWith("fastcontext") -> "FastContext"
            id.startsWith("mage-") -> "Mage"
            id.startsWith("fastvlm") -> "FastVLM"
            id.startsWith("falcon") -> "Falcon"
            id.startsWith("internvl") -> "InternVL"
            id.startsWith("minicpm") -> "MiniCPM"
            id.startsWith("granite") -> "Granite"
            id.startsWith("nemotron") -> "Nemotron"
            id.startsWith("ternary-bonsai") -> "Bonsai"
            id.startsWith("hy-mt") -> "Hunyuan Translation"
            id.startsWith("north-") -> "North"
            id.startsWith("llava") -> "LLaVA"
            id.startsWith("jan-") -> "Jan"
            id.startsWith("nanbeige") -> "Nanbeige"
            id.startsWith("olmo") -> "OLMo"
            id.startsWith("ovis") -> "Ovis"
            id.startsWith("polaris") -> "Polaris"
            id.startsWith("spark") -> "Spark"
            id.startsWith("tinyswallow") -> "TinySwallow"
            id.startsWith("vibethinker") -> "VibeThinker"
            id.startsWith("zamba") -> "Zamba"
            id.startsWith("sarashina") -> "Sarashina"
            else -> spec.provider
        }
    }

    /** Size order is independent of selection, installation, test results and available RAM. */
    fun families(models: List<LocalModelSpec> = ModelCatalog.all, query: String = ""): Map<String, List<LocalModelSpec>> =
        models.filter { spec ->
            val purpose = purpose(spec)
            query.isBlank() || "${family(spec)} ${spec.provider} ${spec.id} ${purpose.tags.joinToString(" ")} ${purpose.description}"
                .contains(query.trim(), ignoreCase = true)
        }.groupBy(::family).toSortedMap().mapValues { (_, specs) ->
            specs.sortedWith(compareBy<LocalModelSpec> { it.downloadBytes ?: Long.MAX_VALUE }.thenBy { it.id })
        }

    fun parametersB(spec: LocalModelSpec): Double? {
        val id = spec.id.lowercase()
        // Gemma E sizes are effective compute sizes, not total resident weights.
        if (id == "gemma-4-e2b-it") return 2.0
        if (id == "gemma-4-e4b-it") return 4.0
        if (id.contains("26b-a4b")) return 4.0
        if (id.startsWith("phi-4-mini")) return 3.8
        if (id == "jan-nano") return 4.0
        if (id == "minicpm-v-4") return 4.0
        val match = Regex("(?:^|[-_])([0-9]+(?:\\.[0-9]+)?)([bm])(?:[-_]|$)").find(id) ?: return null
        return match.groupValues[1].toDouble() / if (match.groupValues[2] == "m") 1000 else 1
    }

    fun canThink(spec: LocalModelSpec): Boolean = spec.reasoning ||
        (spec.id.startsWith("Qwen3-") && !spec.id.contains("Instruct")) || spec.id == "SmolLM3-3B"

    fun purpose(spec: LocalModelSpec): ModelPurpose {
        val id = spec.id.lowercase()
        return when {
            id.contains("codegemma") || id.contains("coder") -> ModelPurpose(
                listOf("Coding", "Debugging"), "Write and explain code snippets, suggest fixes and help understand errors.",
                "Code suggestions need review. This picker does not give the model access to your repositories.")
            id.contains("medgemma") -> ModelPurpose(listOf("Medical research", "Specialist"),
                "Explore medical terminology and research material.", "Research use; not a clinician or a basis for diagnosis. Attach one image per message.")
            id.startsWith("fastcontext") -> ModelPurpose(listOf("Code exploration", "Specialist"),
                "Designed to find relevant code and gather repository evidence for a coding agent.",
                "Its file-search tools are not connected in Jarvis; this is an experimental text-only use of that specialist.")
            id.startsWith("hy-mt") -> ModelPurpose(listOf("Translation", "Languages"),
                "Translate text between languages while preserving meaning and phrasing.", "A translation specialist, not the first choice for general conversation.")
            id.startsWith("llama-") -> ModelPurpose(listOf("Text completion", "Base model"),
                "Continue a passage or experiment with a language-model foundation.",
                "These catalog bundles are base models, not instruction-tuned chat assistants; they may not follow requests reliably.")
            spec.supportsVision && !id.contains("gemma-4") || id.startsWith("fastvlm") -> ModelPurpose(
                listOf("Images", "Text & image model"), "Model family designed for questions about pictures and visual content.",
                "Attach one image per message. This does not enable screen control or live video calls.")
            spec.reasoning -> ModelPurpose(listOf("Reasoning", "Math", "Problem solving"),
                "Work through multi-step questions, calculations and problems where waiting for extra thinking may be worthwhile.",
                "Can spend longer thinking before the answer. Extra thinking is not a guarantee of correctness.")
            id.contains("-jp") || id.startsWith("tinyswallow") || id.startsWith("sarashina") -> ModelPurpose(
                listOf("Japanese", "English", "Writing"), "Japanese and English conversation, rewriting and language practice.")
            id.startsWith("jan-") -> ModelPurpose(listOf("Chat", "Tool-oriented"),
                "General assistance with training aimed at tool-driven tasks.", "Jarvis provides battery, volume and app-opening tools; other tools are not installed.")
            (parametersB(spec) ?: 99.0) < 0.5 -> ModelPurpose(listOf("Simple requests", "Short writing"),
                "Try short rewrites, basic questions and simple instructions with a very small model.", "Limited knowledge and reasoning; a larger model may handle difficult requests better.")
            id.contains("gemma-4") -> ModelPurpose(listOf("Chat", "Writing", "Reasoning"),
                "Everyday questions, drafting, summarizing and working through problems.",
                if (spec.supportsTools) "Jarvis tools are connected for this model. Attach one image per message."
                else "Attach one image per message.")
            id == "phi-4-mini-instruct" -> ModelPurpose(listOf("Math", "Code help", "Languages"),
                "Explain math steps, help with code and answer questions in different languages.",
                "These are publisher-described uses, not a measured advantage over other models in Jarvis.")
            id.startsWith("qwen3.5") -> ModelPurpose(listOf("Chat", "Writing", "Code help"),
                "Try general questions, summaries, drafting and code explanations.", "This bundle is a text-only conversion; it does not add image input.")
            canThink(spec) -> ModelPurpose(listOf("Chat", "Reasoning", "Code help"),
                "General assistance plus multi-step problem solving and code explanations.", "May think before answering; response speed depends on the prompt and runtime behavior.")
            id.startsWith("ternary-bonsai") -> ModelPurpose(listOf("Chat", "Compression experiment"),
                "Try general conversation using highly compressed weights.", "A small download does not guarantee fast processing or the quality of less-compressed models.")
            else -> ModelPurpose(listOf("Chat", "Writing", "Summaries"),
                "Try everyday questions, drafting, rewriting and summarizing text.", "General-purpose model; these uses are not a measured quality ranking in Jarvis.")
        }
    }

    /** Short task descriptions, not quality rankings or phone speed promises. */
    fun quickUse(spec: LocalModelSpec): String = when (spec.id) {
        "Gemma-4-E2B-it" -> "Everyday chat & voice assistance"
        "Gemma-4-E4B-it" -> "Writing & longer problem-solving tasks"
        "Gemma3-1B-IT" -> "Short questions & simple rewrites"
        "Phi-4-mini-instruct" -> "Math, code help & multilingual questions"
        else -> purpose(spec).tags.take(2).joinToString(" · ")
    }

    fun inputsLabel(spec: LocalModelSpec): String = listOfNotNull(
        "Text", "Images".takeIf { spec.supportsVision }, "Audio clips".takeIf { spec.supportsAudio }
    ).joinToString(" · ")

    /** Limitations that could change a choice stay visible even with Details closed. */
    fun visibleLimitation(spec: LocalModelSpec): String? = when {
        spec.id.startsWith("Llama-", true) -> "Base model: may not follow instructions reliably."
        spec.id.startsWith("FastContext", true) -> "Code-search tools are not connected in Jarvis."
        spec.id.startsWith("MedGemma", true) -> "Research only; not medical advice."
        else -> null
    }

    /** A lower-load general chat candidate, not a quality or benchmark winner. */
    fun startingPoint(specs: List<LocalModelSpec>, phone: PhoneProfile): LocalModelSpec? {
        val candidates = specs.filter {
            val p = purpose(it)
            ModelGuidance.assess(it, phone).startingOption &&
                "Chat" in p.tags && !it.reasoning && (parametersB(it) ?: 99.0) in 0.5..4.0
        }
        return candidates.firstOrNull { it.id == "Gemma-4-E2B-it" }
            ?: candidates.firstOrNull { it.id == "Qwen3-1.7B" }
            ?: candidates.minWithOrNull(compareBy<LocalModelSpec> { kotlin.math.abs((parametersB(it) ?: 99.0) - 1.5) }
                .thenBy { it.downloadBytes ?: Long.MAX_VALUE }.thenBy { it.id })
    }
}
