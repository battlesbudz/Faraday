package com.battlesbudz.jarvis.v2.ai

import java.util.Locale

/** Resource screening, not measured peak RAM, speed, thermal behavior or driver compatibility. */
data class PhoneProfile(
    val name: String, val model: String, val chip: String,
    val totalRamBytes: Long, val availableRamBytes: Long, val freeStorageBytes: Long,
    val arm64: Boolean
)
data class ModelFit(
    val label: String,
    val explanation: String,
    val workload: String,
    val workloadExplanation: String,
    val memoryRisk: Int,
    val deviceExperience: String? = null,
    val compatibilityNotice: String? = null
) {
    val displayLabel: String get() = "Memory: $label"
    // Color depends on this resource estimate only, never on experimental status.
    val memoryWarning: Boolean get() = memoryRisk >= 2 && label != "Memory needs unknown"
    val quickMemoryLabel: String get() = when {
        label == "Unsupported architecture" -> "Not supported on this phone"
        label == "Memory needs unknown" -> "Memory estimate unavailable"
        memoryRisk >= 2 -> "Memory estimate: May not fit"
        memoryRisk == 1 -> "Memory estimate: May be tight"
        else -> "Memory estimate: Likely enough room"
    }
    val startingOption: Boolean get() = memoryRisk <= 1 && compatibilityNotice == null
}

object ModelGuidance {
    fun gb(bytes: Long): String = "%.2f GB".format(Locale.US, bytes / 1_000_000_000.0)

    // No installation/test inputs: identical model + phone always gets identical guidance.
    fun assess(spec: LocalModelSpec, phone: PhoneProfile): ModelFit {
        val bytes = spec.downloadBytes?.takeIf { it > 0 }
        val ratio = if (bytes != null && phone.totalRamBytes > 0) bytes.toDouble() / phone.totalRamBytes else null
        val risk = when {
            !phone.arm64 -> 4
            ratio == null -> 3
            ratio >= .85 -> 3
            ratio >= .65 -> 2
            ratio >= .40 -> 1
            else -> 0
        }
        val label = when {
            !phone.arm64 -> "Unsupported architecture"
            ratio == null -> "Memory needs unknown"
            ratio >= 1 -> "Bundle exceeds total RAM"
            risk >= 2 -> "Tight headroom · may not fit"
            risk == 1 -> "Less memory headroom"
            else -> "Likely memory headroom"
        }
        val comparison = if (ratio == null) "Bundle size or total RAM is unavailable."
            else "${gb(bytes!!)} bundle / ${gb(phone.totalRamBytes)} total RAM (about ${(ratio * 100).toInt()}%)."
        val explanation = when {
            !phone.arm64 -> "This build needs a 64-bit ARM device."
            ratio == null -> "$comparison A device test is needed to assess loading."
            ratio >= 1 -> "$comparison Keeping this bundle resident would exceed physical RAM before Android and working memory. Loading may fail or run extremely slowly."
            risk >= 2 -> "$comparison Little room would remain for Android, speech and model working memory if the bundle were fully resident. Loading may fail; long waits or memory pressure are possible."
            risk == 1 -> "$comparison The weights may fit, but working memory and longer conversations can put pressure on RAM. Worth trying if you accept longer waits."
            else -> "$comparison This leaves room on paper for Android and working memory. Loading still depends on the bundle, context and driver."
        }
        val parameters = ModelGuide.parametersB(spec)
        val workload = when {
            parameters == null -> "Workload not measured"
            parameters > 8 -> "Very heavy workload"
            parameters > 4 -> "Heavy workload"
            parameters > 2 -> "Moderate workload"
            else -> "Light workload"
        }
        val workloadExplanation = buildString {
            append(when {
                parameters == null -> "No reliable compute-size comparison is available for this model."
                parameters > 8 -> "Expect substantial processing for each reply; better suited to patient text use than quick voice exchanges."
                parameters > 4 -> "More work per reply than small models. May be useful for harder tasks if you accept slower responses."
                parameters > 2 -> "A middle ground in compute size. More demanding than small models; not necessarily more accurate for your task."
                else -> "A lighter compute starting point for short requests. Very small models trade knowledge and reasoning for lower demand."
            })
            if (ModelGuide.canThink(spec)) append(" Thinking can add a long pause even with a small download.")
            if (!spec.recommendedGpu) append(" This bundle uses CPU in Jarvis; it may respond differently from GPU models of similar size.")
            if ((parameters ?: 0.0) > 4 || ModelGuide.canThink(spec))
                append(" Long or thinking-heavy runs can keep the processor busy longer, increasing battery and heat demand.")
            append(" Sustained generation can warm the phone and slow it down. RAM capacity alone cannot predict speed or overheating.")
        }
        val experience = if (phone.model.startsWith("SM-F956", true)) when (spec.id) {
            "Gemma-4-E2B-it" -> "Reported Fold6 experience: E2B responds well in voice calls. This is device experience, not a speed measurement for every task."
            "Gemma-4-E4B-it" -> "Reported Fold6 experience: E4B runs, but audible replies have taken around 30 seconds. It remains an option for patient text use."
            else -> null
        } else null
        val evidence = ModelCompatibility.assess(spec)
        val compatibilityNotice = evidence.summary.takeIf { evidence.status != ModelEvidenceStatus.TESTED }
        return ModelFit(label, explanation, workload, workloadExplanation, risk, experience, compatibilityNotice)
    }

    fun storageNotice(spec: LocalModelSpec, phone: PhoneProfile, installed: Boolean): String? {
        val bytes = spec.downloadBytes ?: return null
        return if (!installed && phone.freeStorageBytes >= 0 && phone.freeStorageBytes < bytes + 1_000_000_000L)
            "Download needs ${gb(bytes)} plus space for caches; ${gb(phone.freeStorageBytes)} is free. This is storage, not RAM." else null
    }
}
