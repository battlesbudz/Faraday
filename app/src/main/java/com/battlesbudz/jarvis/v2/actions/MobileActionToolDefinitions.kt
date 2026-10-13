package com.battlesbudz.jarvis.v2.actions

import com.google.ai.edge.litertlm.OpenApiTool

/**
 * The tool declarations supplied to the selected model. The model must select from
 * these names; Kotlin remains responsible for validation and execution.
 */
object MobileActionToolDefinitions {
    fun all(): List<OpenApiTool> = MobileToolCatalog.all().map(::CatalogTool)

    private class CatalogTool(private val tool: MobileToolCatalog.Tool) : OpenApiTool {
        override fun getToolDescriptionJsonString(): String = tool.schemaJson()

        /** Automatic LiteRT tool execution is disabled; validated runtime dispatch owns side effects. */
        override fun execute(paramsJsonString: String): String =
            "{\"error\":\"Native tool execution is disabled; Jarvis must validate and dispatch this request.\"}"
    }
}
