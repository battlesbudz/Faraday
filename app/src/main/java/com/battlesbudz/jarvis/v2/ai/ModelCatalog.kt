package com.battlesbudz.jarvis.v2.ai

data class LocalModelSpec(
    val id: String,
    val fileName: String,
    val expectedSha256: String? = null,
    val recommendedGpu: Boolean,
    val downloadUrl: String? = null,
    val supportsVision: Boolean = false,
    val supportsAudio: Boolean = false,
    /** Expose Jarvis tools through the bundle's own LiteRT template; reliability is model-dependent. */
    val supportsTools: Boolean = true,
    val incrementalGemmaInput: Boolean = false,
    val contextTokens: Int? = null,
    val downloadBytes: Long? = null,
    val description: String = "",
    val provider: String = if (id.startsWith("Qwen")) "Alibaba · Qwen" else "Google",
    val reasoning: Boolean = id.contains("Thinking"),
    val experimental: Boolean = true,
    val requiresAccess: Boolean = false
)

/** Publisher filenames often differ from Jarvis's installed filename. */
internal fun LocalModelSpec.importFileNames(): Set<String> = buildSet {
    add(fileName)
    downloadUrl?.let { url ->
        val publishedName = java.net.URI(url).path.substringAfterLast('/')
        if (publishedName.endsWith(".litertlm")) add(publishedName)
    }
}

object ModelCatalog {
    val gemma4E2b = LocalModelSpec(
        id = "Gemma-4-E2B-it",
        fileName = "gemma-4-E2B-it.litertlm",
        expectedSha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        recommendedGpu = true, supportsVision = true, supportsAudio = true,
        experimental = false,
        supportsTools = true, incrementalGemmaInput = true,
        downloadBytes = 2588147712L, description = "Everyday conversation and voice · balanced size",
        // Pin the download to the exact Hugging Face revision used by PR1.
        // Updating the model requires an intentional catalog change.
        downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/6e5c4f1/gemma-4-E2B-it.litertlm?download=true"
    )

    val gemma4E4b = LocalModelSpec(
        id = "Gemma-4-E4B-it",
        fileName = "gemma-4-E4B-it.litertlm",
        expectedSha256 = "f335f2bfd1b758dc6476db16c0f41854bd6237e2658d604cbe566bcefd00a7bc",
        recommendedGpu = true, supportsVision = true, supportsAudio = true,
        experimental = false,
        supportsTools = true, incrementalGemmaInput = true,
        downloadBytes = 3659530240L, description = "Larger assistant · more memory and longer waits",
        downloadUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/1fc8912676889ed3aeec478c92c1e239bed08928/gemma-4-E4B-it.litertlm?download=true"
    )

    // Curated portable bundles; no MediaTek-specific NPU files or legacy .task files.
    val qwen = listOf(
        LocalModelSpec(
            id = "Qwen2.5-Coder-1.5B-Instruct", fileName = "Qwen2.5-Coder-1.5B-Instruct_int4.litertlm",
            expectedSha256 = "273ecc7771ba2dd5fe1bb6d4d4726ad0353102f04ad094082ccf59bca9f21213",
            recommendedGpu = false, supportsVision = false,
            contextTokens = 4096, downloadBytes = 1117385648L,
            description = "Coding specialist · INT4",
            downloadUrl = "https://huggingface.co/litert-community/Qwen2.5-Coder-1.5B-Instruct/resolve/ddb8ab66e162dd89328da9bc144d1d626d3f2c9f/Qwen2.5-Coder-1.5B-Instruct_int4.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "Qwen2.5-Coder-3B-Instruct", fileName = "Qwen2.5_Coder_3B_It.litertlm",
            expectedSha256 = "78d23da074383f52f852b945b8090870e6c9dded02a842f535ee3ccb9e2874f3",
            recommendedGpu = true, supportsVision = false,
            contextTokens = 4096, downloadBytes = 3433083824L,
            description = "Coding specialist · INT8",
            downloadUrl = "https://huggingface.co/litert-community/Qwen2.5-Coder-3B-Instruct/resolve/f02778f1b0bb06315051c1e212abd19a389bff34/Qwen2.5_Coder_3B_It.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "Qwen3-0.6B", fileName = "Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm",
            expectedSha256 = "e3e290109da4388d65a17510a0c66af91c8039f52d2c465868dbc43c09a776cf",
            recommendedGpu = true, supportsVision = false,
            contextTokens = 2048, downloadBytes = 344437808L,
            description = "Small general assistant · INT4",
            downloadUrl = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/8414150f2e9dcc82449bcc9c5abc404b399a4d06/Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "Qwen3-1.7B", fileName = "Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm",
            expectedSha256 = "2eeffef7b51bc3e1225ea69fe7aa5f417397934b56a5b6c20cc068d6fd2c918b",
            recommendedGpu = true, supportsVision = false,
            contextTokens = 2048, downloadBytes = 977184032L,
            description = "General assistant · INT4",
            downloadUrl = "https://huggingface.co/litert-community/Qwen3-1.7B/resolve/73fbc3fe8271c162a603ee66f6e7ed25b6211195/Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "Qwen3-4B", fileName = "qwen3_4b_mixed_int4.litertlm",
            expectedSha256 = "f0794bc77efeaaf4f7af815f04c483b19b8f2ae4a102cef1b7b760a25848a18e",
            recommendedGpu = true, supportsVision = false,
            contextTokens = 2048, downloadBytes = 2659057664L,
            description = "General assistant · INT4",
            downloadUrl = "https://huggingface.co/litert-community/Qwen3-4B/resolve/84cc5a35c9c65cd18fcd65bb1f3a7d77a4acfe6e/qwen3_4b_mixed_int4.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "Qwen3-4B-Instruct-2507", fileName = "qwen3_4b_instruct_2507_mixed_int4.litertlm",
            expectedSha256 = "9e48b165836256f5344d9d044930607b9c47f6ef34e27f82e96881664f3ba2fd",
            recommendedGpu = true, supportsVision = false,
            contextTokens = 2048, downloadBytes = 2659057664L,
            description = "Non-thinking assistant · INT4",
            downloadUrl = "https://huggingface.co/litert-community/Qwen3-4B-Instruct-2507/resolve/4d409771b414d86b270ca54d27ffc45032a81142/qwen3_4b_instruct_2507_mixed_int4.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "Qwen3-4B-Thinking-2507", fileName = "Qwen3-4B-Thinking-2507.litertlm",
            expectedSha256 = "356b2d7778d27d55fbb0d2e0c5e8801e9de136db59849c41c882bd97333840d5",
            recommendedGpu = true, supportsVision = false,
            contextTokens = 4096, downloadBytes = 2474357680L,
            description = "Reasoning model · longer silent thinking · INT4",
            downloadUrl = "https://huggingface.co/litert-community/Qwen3-4B-Thinking-2507/resolve/92751be3c03156438ba11699fffbd314002ade5d/model.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "Qwen3.5-0.8B", fileName = "Qwen3.5-0.8B_int8.litertlm",
            expectedSha256 = "684d4d34adf7176eb47f6026ff65c33d42584737254e5524a8d1ad62edc21b98",
            recommendedGpu = false, supportsVision = false,
            contextTokens = 4096, downloadBytes = 963184864L,
            description = "Text-only conversion · INT8",
            downloadUrl = "https://huggingface.co/litert-community/Qwen3.5-0.8B/resolve/c23b16e43ada6ead533b12593fe500bbe268014f/Qwen3.5-0.8B_int8.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "Qwen3.5-2B", fileName = "Qwen3.5-2B_int8.litertlm",
            expectedSha256 = "86c97baba6d3fb4109588562f0b9411e502c883be7fc2479f93ce3a6ea3efb6b",
            recommendedGpu = false, supportsVision = false,
            contextTokens = 4096, downloadBytes = 2116592816L,
            description = "Text-only conversion · INT8",
            downloadUrl = "https://huggingface.co/litert-community/Qwen3.5-2B/resolve/0d50110e55d80ac7be6233d047e0d5f0fb8394c4/Qwen3.5-2B_int8.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "Qwen3.5-4B", fileName = "Qwen3.5-4B_mixed_int4.litertlm",
            expectedSha256 = "176b5a2b6c20bde7739fba98e653a04f5d5b4a753a4b9dd0ff529aba7c35be99",
            recommendedGpu = false, supportsVision = false,
            contextTokens = 4096, downloadBytes = 2754365536L,
            description = "Text-only conversion · INT4",
            downloadUrl = "https://huggingface.co/litert-community/Qwen3.5-4B/resolve/f06d1275eb219ff140d11d08b586cf9bb55b8fd6/Qwen3.5-4B_mixed_int4.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "Qwen2.5-1.5B-Instruct", fileName = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            expectedSha256 = "faa60663b333290c1496c499828b21d3e3254a788cacd8cce917ce0f761a2dc9",
            recommendedGpu = true, supportsVision = false,
            contextTokens = 4096, downloadBytes = 1597931520L,
            description = "General assistant · INT8",
            downloadUrl = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/19edb84c69a0212f29a6ef17ba0d6f278b6a1614/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "Qwen2-0.5B-Instruct", fileName = "Qwen2_0.5B_Instruct.litertlm",
            expectedSha256 = "0f01cc004b8eb62b92ba6be85ed05a248ba0d2f78af94c4949b313eccfb4c157",
            recommendedGpu = true, supportsVision = false,
            contextTokens = 4096, downloadBytes = 647377840L,
            description = "Older small assistant",
            downloadUrl = "https://huggingface.co/litert-community/Qwen2-0.5B-Instruct/resolve/13aab3e522828d85fa178d885716ab858a715149/Qwen2_0.5B_Instruct.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "Qwen2-VL-2B", fileName = "Qwen2-VL-2B.litertlm",
            expectedSha256 = "cf481776bcb16fe539a38c924a67fc213bb5ee55e6fa600729f060e64849066b",
            recommendedGpu = true, supportsVision = true,
            contextTokens = 4096, downloadBytes = 1783424544L,
            description = "Image and text assistant · one image per turn",
            downloadUrl = "https://huggingface.co/litert-community/Qwen2-VL-2B/resolve/5a03c8597ec02ec3c84cde3f1fd043688396bde5/Qwen2-VL-2B.litertlm?download=true"
        )
    )

    val all = listOf(gemma4E2b, gemma4E4b) + qwen + CommunityModels.all

    fun find(id: String?): LocalModelSpec? = all.firstOrNull { it.id == id }

    // Preserve existing installations and recover safely from a removed catalog entry.
    fun resolve(id: String?): LocalModelSpec = find(id) ?: gemma4E2b
}
