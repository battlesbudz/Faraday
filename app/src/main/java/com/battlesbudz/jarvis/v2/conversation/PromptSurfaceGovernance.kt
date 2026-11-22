package com.battlesbudz.jarvis.v2.conversation

/** Small checks for prompt templates and inventories; prompt assembly remains with its owners. */
internal object PromptSurfaceGovernance {
    private val unresolvedPlaceholder = Regex("""\$\{[^{}]+}|\{\{[^{}]+}}""")

    fun requireNoUnfilledPlaceholders(template: String) {
        require(!unresolvedPlaceholder.containsMatchIn(template)) {
            "Prompt template contains an unfilled placeholder"
        }
    }

    fun requireSystemLayerHasNoToolNames(systemLayer: String, toolNames: Iterable<String>) {
        val namedTool = toolNames.firstOrNull { name ->
            name.isNotBlank() && Regex("(?i)(?<![A-Za-z0-9_])" + Regex.escape(name) + "(?![A-Za-z0-9_])")
                .containsMatchIn(systemLayer)
        }
        require(namedTool == null) { "System layer names a tool: $namedTool" }
    }

    fun requirePinnedSizes(pinned: Map<String, Int>, actual: Map<String, Int>) {
        require(pinned.isNotEmpty()) { "Prompt surface size inventory is empty" }
        val missing = pinned.keys - actual.keys
        val unpinned = actual.keys - pinned.keys
        val changed = (pinned.keys intersect actual.keys).filter { pinned[it] != actual[it] }
        require(missing.isEmpty() && unpinned.isEmpty() && changed.isEmpty()) {
            buildString {
                append("Prompt surface sizes differ from the golden inventory")
                if (missing.isNotEmpty()) append("; missing=").append(missing.sorted())
                if (unpinned.isNotEmpty()) append("; unpinned=").append(unpinned.sorted())
                if (changed.isNotEmpty()) append("; changed=").append(changed.sorted().associateWith { actual[it] })
            }
        }
    }
}
