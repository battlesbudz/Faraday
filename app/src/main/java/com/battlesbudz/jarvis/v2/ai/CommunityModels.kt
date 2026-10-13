package com.battlesbudz.jarvis.v2.ai

/** Portable bundles audited 2026-09-19. Sources and exclusions: docs/model-catalog-audit.json. */
internal object CommunityModels {
    val all = listOf(
        LocalModelSpec(
            id = "DeepSeek-R1-Distill-Qwen-1.5B", provider = "DeepSeek",
            fileName = "DeepSeek-R1-Distill-Qwen-1.5B.litertlm",
            expectedSha256 = "69b35f01759eed765641ab4af589bbe98131fd2825662a086d9037409b8c1295",
            recommendedGpu = true, supportsVision = false, reasoning = true,
            contextTokens = 4096, downloadBytes = 1833451520L,
            description = "Reasoning and problem solving · longer waits",
            downloadUrl = "https://huggingface.co/litert-community/DeepSeek-R1-Distill-Qwen-1.5B/resolve/2f8b8ee90d8f93b15305b699e8772b277d074a9a/DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "DeepSeek-R1-Distill-Qwen-7B", provider = "DeepSeek",
            fileName = "DeepSeek-R1-Distill-Qwen-7B.litertlm",
            expectedSha256 = "511d59c11704f7ab39b9cb3a0eef1a88f8c05673d0b74bcb4b19c15845ab4a7f",
            recommendedGpu = true, supportsVision = false, reasoning = true,
            contextTokens = 4096, downloadBytes = 4531978224L,
            description = "Reasoning and problem solving · longer waits",
            downloadUrl = "https://huggingface.co/litert-community/DeepSeek-R1-Distill-Qwen-7B/resolve/96991f3653cefd99ebb408255b227d4935dea1d1/DeepSeek-R1-Distill-Qwen-7B_q4_block32_ekv4096.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "Falcon-H1-0.5B-Instruct", provider = "TII · Falcon",
            fileName = "Falcon-H1-0.5B-Instruct.litertlm",
            expectedSha256 = "b8d1e5298bc3c8ed69b1f1f33a344fb9f992b033043f5438778b14477902fad8",
            recommendedGpu = true, supportsVision = false, reasoning = false,
            contextTokens = 1280, downloadBytes = 650163952L,
            description = "General conversation",
            downloadUrl = "https://huggingface.co/litert-community/Falcon-H1-0.5B-Instruct/resolve/b4a862c3fa996d8ef43cb8c6e5a23a95daf37a6c/Falcon-H1-0.5B-Instruct_int8.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "Falcon-H1-1.5B-Deep-Instruct", provider = "TII · Falcon",
            fileName = "Falcon-H1-1.5B-Deep-Instruct.litertlm",
            expectedSha256 = "759b3b361ccd9b6138767fe1771f35d3be64301308b9a178d3edd43819447526",
            recommendedGpu = true, supportsVision = false, reasoning = false,
            contextTokens = 1280, downloadBytes = 1833084896L,
            description = "General conversation",
            downloadUrl = "https://huggingface.co/litert-community/Falcon-H1-1.5B-Deep-Instruct/resolve/f91ae50b6adbb53cd36a7d462eb09b814c24a82e/Falcon-H1-1.5B-Deep-Instruct_int8.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "Falcon-H1-1.5B-Instruct", provider = "TII · Falcon",
            fileName = "Falcon-H1-1.5B-Instruct.litertlm",
            expectedSha256 = "f499623a4d6fb6df8c2cf6ce388a0d460bcb81f416fd5c69e4281906f48fb931",
            recommendedGpu = true, supportsVision = false, reasoning = false,
            contextTokens = 1280, downloadBytes = 1725449600L,
            description = "General conversation",
            downloadUrl = "https://huggingface.co/litert-community/Falcon-H1-1.5B-Instruct/resolve/972bf1777202caf538af00aeac605dde823f3c16/Falcon-H1-1.5B-Instruct_int8.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "Falcon-H1-3B-Instruct", provider = "TII · Falcon",
            fileName = "Falcon-H1-3B-Instruct.litertlm",
            expectedSha256 = "add075d24b309a68ba7312bb89410345c06ff1a6628e42785f45f57045795d2b",
            recommendedGpu = true, supportsVision = false, reasoning = false,
            contextTokens = 1280, downloadBytes = 3385302368L,
            description = "General conversation",
            downloadUrl = "https://huggingface.co/litert-community/Falcon-H1-3B-Instruct/resolve/182c11f09f2a91d75a9c72f6e1ccbbad10a7f3e4/Falcon-H1-3B-Instruct_int8.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "Falcon-H1-Tiny-R-0.6B", provider = "TII · Falcon",
            fileName = "Falcon-H1-Tiny-R-0.6B.litertlm",
            expectedSha256 = "66b9e6a5fa630d453d59516cc362e44fff3943eb7c63d07b116cf9a525a1bb94",
            recommendedGpu = true, supportsVision = false, reasoning = true,
            contextTokens = 1280, downloadBytes = 873254788L,
            description = "Reasoning and problem solving · longer waits",
            downloadUrl = "https://huggingface.co/litert-community/Falcon-H1-Tiny-R-0.6B/resolve/7978351a57b60003d61aa55350821aa2122074c7/Falcon-H1-Tiny-R-0.6B_int8.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "FastContext-1.0-4B-SFT", provider = "Microsoft",
            fileName = "FastContext-1.0-4B-SFT.litertlm",
            expectedSha256 = "3842a564a1f8db2ed53992ec6e2d5e2579aff72131dfac664c19cf2ec90542ce",
            recommendedGpu = true, supportsVision = false, reasoning = false,
            contextTokens = 4096, downloadBytes = 2474357680L,
            description = "Context-focused specialist · experimental conversation",
            downloadUrl = "https://huggingface.co/litert-community/FastContext-1.0-4B-SFT/resolve/87f983be62fa2eca43b0e4c7171a7fc61207583e/model_block128.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "FastVLM-0.5B", provider = "Apple",
            fileName = "FastVLM-0.5B.litertlm",
            expectedSha256 = "ccba1e8bfa0bab78345f5d009fdffd20bd8c907cf39b4bc632e391b0a96f3b18",
            recommendedGpu = true, supportsVision = true, reasoning = false,
            contextTokens = 1280, downloadBytes = 1156342768L,
            description = "General conversation",
            downloadUrl = "https://huggingface.co/litert-community/FastVLM-0.5B/resolve/460013246392191f8532e45e518576bb6513eace/FastVLM-0.5B.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "Gemma3-1B-IT", requiresAccess = true, provider = "Google",
            fileName = "Gemma3-1B-IT.litertlm",
            expectedSha256 = "1325ae366d31950f137c9c357b9fa89448b176d76998180c08ceaca78bba98be",
            recommendedGpu = true, supportsVision = false, reasoning = false,
            contextTokens = 4096, downloadBytes = 584417280L,
            description = "General conversation",
            downloadUrl = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/a6306a4e292016480083b73b8dc6f3f939ae04c3/Gemma3-1B-IT_multi-prefill-seq_q4_ekv4096.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "Hy-MT2-1.8B", provider = "Tencent",
            fileName = "Hy-MT2-1.8B.litertlm",
            expectedSha256 = "7bbd0b65d7e69c8d92b8ab3033e353536ef40c77f4dc5f4c32f2931417c13eda",
            recommendedGpu = true, supportsVision = false, reasoning = false,
            contextTokens = 1280, downloadBytes = 1815622960L,
            description = "Translation specialist · not a general assistant",
            downloadUrl = "https://huggingface.co/litert-community/Hy-MT2-1.8B/resolve/617f1ef202586cd6d4e9db718e17a53db655f1c4/Hy-MT2-1.8B_int8.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "InternVL3-1B", provider = "OpenGVLab",
            fileName = "InternVL3-1B.litertlm",
            expectedSha256 = "7cf87c35cf364d04bd2e6f957e3a0366830ad0b46eca3dd81e0000891fd1284a",
            recommendedGpu = true, supportsVision = true, reasoning = false,
            contextTokens = 2048, downloadBytes = 737314160L,
            description = "Image and text assistant",
            downloadUrl = "https://huggingface.co/litert-community/InternVL3-1B/resolve/1750633fd6d028d3f2d1cf733860f5a52926bb5f/InternVL3-1B.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "InternVL3-2B", provider = "OpenGVLab",
            fileName = "InternVL3-2B.litertlm",
            expectedSha256 = "cb7d63cbf2f5d9b3eb307012b54dc4fc4d68fef78dc3a5d4fbba41c59812f9b6",
            recommendedGpu = true, supportsVision = true, reasoning = false,
            contextTokens = 2048, downloadBytes = 1429640560L,
            description = "Image and text assistant",
            downloadUrl = "https://huggingface.co/litert-community/InternVL3-2B/resolve/6960f534f0c95569baa29c72cbd7b3238046a67c/InternVL3-2B.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "InternVL3_5-1B", provider = "OpenGVLab",
            fileName = "InternVL3_5-1B.litertlm",
            expectedSha256 = "df984928897c84aa2d6603651850a809dc1fbb2ec5f42c048b988b2d1bd1d456",
            recommendedGpu = true, supportsVision = true, reasoning = false,
            contextTokens = 2048, downloadBytes = 817517936L,
            description = "Image and text assistant",
            downloadUrl = "https://huggingface.co/litert-community/InternVL3_5-1B/resolve/d786967d292b7f76578b0c7a52a912b021436cf6/model.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "InternVL3_5-2B", provider = "OpenGVLab",
            fileName = "InternVL3_5-2B.litertlm",
            expectedSha256 = "0f288a1cca1674ca3c0c0ee1705aefd78e9bf163594d00f012c9a7817c3679e1",
            recommendedGpu = true, supportsVision = true, reasoning = false,
            contextTokens = 2048, downloadBytes = 1613223280L,
            description = "Image and text assistant",
            downloadUrl = "https://huggingface.co/litert-community/InternVL3_5-2B/resolve/4886a33c6439f3d1df00c9565cf472b7bc8211a1/model.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "InternVL3_5-4B", provider = "OpenGVLab",
            fileName = "InternVL3_5-4B.litertlm",
            expectedSha256 = "2721a08faaa12dfb3af5f336c76b56274093468c1e25c1b35edfddfdcc8cb649",
            recommendedGpu = true, supportsVision = true, reasoning = false,
            contextTokens = 2048, downloadBytes = 2992182640L,
            description = "Image and text assistant",
            downloadUrl = "https://huggingface.co/litert-community/InternVL3_5-4B/resolve/5acea476065132d64ed79f48b13c869f420f7af7/model.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "Jan-nano", provider = "Menlo",
            fileName = "Jan-nano.litertlm",
            expectedSha256 = "74712688d25de50a1b3ad5ed95e96be05d2445cbca8e684fb6490a4e446b7d40",
            recommendedGpu = true, supportsVision = false, reasoning = false,
            contextTokens = 4096, downloadBytes = 2662888368L,
            description = "General assistant · native tool use not connected",
            downloadUrl = "https://huggingface.co/litert-community/Jan-nano/resolve/db917094ba91c059653d1d684968ea22d35f56fe/model_block32.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "LFM2.5-1.2B-Instruct", provider = "Liquid AI",
            fileName = "LFM2.5-1.2B-Instruct.litertlm",
            expectedSha256 = "36f7f0221bcc42c75291da1d7e3422901024a5b06b9bfa3c02d7feface04f70a",
            recommendedGpu = true, supportsVision = false, reasoning = false,
            contextTokens = 4096, downloadBytes = 736220768L,
            description = "General conversation",
            downloadUrl = "https://huggingface.co/litert-community/LFM2.5-1.2B-Instruct/resolve/eb5e75a985a46b5d5707282539d985fdd34e2a10/LFM2.5-1.2B-Instruct_int4_gpu.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "LFM2.5-1.2B-JP", provider = "Liquid AI",
            fileName = "LFM2.5-1.2B-JP.litertlm",
            expectedSha256 = "2c826a9cdeefc614c9b7de1d1c5565c9a64f704e5f3291ad2cd5d7da396790d5",
            recommendedGpu = true, supportsVision = false, reasoning = false,
            contextTokens = 4096, downloadBytes = 736220768L,
            description = "Japanese and English conversation",
            downloadUrl = "https://huggingface.co/litert-community/LFM2.5-1.2B-JP/resolve/8bd3bb3c434e47b12d4789608e100bf8144d5ec5/LFM2.5-1.2B-JP_int4_gpu.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "LFM2.5-1.2B-Thinking", provider = "Liquid AI",
            fileName = "LFM2.5-1.2B-Thinking.litertlm",
            expectedSha256 = "75408d4047f15f054403e3d29826b2324c4b8709648c99221daab571f70a6bfd",
            recommendedGpu = true, supportsVision = false, reasoning = true,
            contextTokens = 4096, downloadBytes = 736220768L,
            description = "Reasoning and problem solving · longer waits",
            downloadUrl = "https://huggingface.co/litert-community/LFM2.5-1.2B-Thinking/resolve/b90f25aa2059cba530c4bf254d266a5f7c9cb330/LFM2.5-1.2B-Thinking_int4_gpu.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "LFM2.5-2.6B", provider = "Liquid AI",
            fileName = "LFM2.5-2.6B.litertlm",
            expectedSha256 = "f4797d5a16812231deb6d505b4f417dd835b9b3dc47bb74d9ec71facfd0c91a4",
            recommendedGpu = true, supportsVision = false, reasoning = false,
            contextTokens = 4096, downloadBytes = 1668151680L,
            description = "General conversation",
            downloadUrl = "https://huggingface.co/litert-community/LFM2.5-2.6B/resolve/9a25770a7517a24551917b5beabbd3c3e7cba214/LFM2.5-2.6B_int4.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "LFM2.5-230M", provider = "Liquid AI",
            fileName = "LFM2.5-230M.litertlm",
            expectedSha256 = "a4683cdafbf7b0ae526ba688fa905fd3db8546fc58221d10082443d4d0a6315c",
            recommendedGpu = true, supportsVision = false, reasoning = false,
            contextTokens = 1280, downloadBytes = 176756720L,
            description = "General conversation",
            downloadUrl = "https://huggingface.co/litert-community/LFM2.5-230M/resolve/bd9c98b7627e2c4b745bd77aa948a4094dcb382b/LFM2.5-230M_int4.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "LFM2.5-VL-1.6B", provider = "Liquid AI",
            fileName = "LFM2.5-VL-1.6B.litertlm",
            expectedSha256 = "79ca9db8af91e40486a5dfcaf26549180fd6c811ad85f826e3aef20ad0bd565c",
            recommendedGpu = true, supportsVision = true, reasoning = false,
            contextTokens = 4096, downloadBytes = 1298139472L,
            description = "Image and text assistant",
            downloadUrl = "https://huggingface.co/litert-community/LFM2.5-VL-1.6B/resolve/9f4ac757b88ffcd58510e590ef30c98603795c92/LFM2.5-VL-1.6B_int4_fixB.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "LFM2.5-VL-3B", provider = "Liquid AI",
            fileName = "LFM2.5-VL-3B.litertlm",
            expectedSha256 = "2393cfba9d78e930bca56e92a752b222bc8b0cb111356faf6c5f23d021a3fa5e",
            recommendedGpu = true, supportsVision = true, reasoning = false,
            contextTokens = 4096, downloadBytes = 2352023888L,
            description = "Image and text assistant",
            downloadUrl = "https://huggingface.co/litert-community/LFM2.5-VL-3B/resolve/3797245190aa4ae67f524481ed3d9657a3423e04/LFM2.5-VL-3B_int4_fixB.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "LFM2.5-VL-450M", provider = "Liquid AI",
            fileName = "LFM2.5-VL-450M.litertlm",
            expectedSha256 = "6854cd9677a34e680070b60076793e49aeb3224de7cb4101076b261cb9bf6033",
            recommendedGpu = true, supportsVision = true, reasoning = false,
            contextTokens = 4096, downloadBytes = 406817104L,
            description = "Image and text assistant",
            downloadUrl = "https://huggingface.co/litert-community/LFM2.5-VL-450M/resolve/be196f4fa88b2dfba8cdbbbff07b641df794f977/LFM2.5-VL-450M_int4_fixB.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "LLaVA-OneVision-0.5B", provider = "llava-hf",
            fileName = "LLaVA-OneVision-0.5B.litertlm",
            expectedSha256 = "7f7cd7ae3d2ae435a1f69651de02a9d51e916be60fdfef5cadc721725310971a",
            recommendedGpu = true, supportsVision = true, reasoning = false,
            contextTokens = 2048, downloadBytes = 828590400L,
            description = "Image and text assistant",
            downloadUrl = "https://huggingface.co/litert-community/LLaVA-OneVision-0.5B/resolve/681d5fea488c40a1dce3c6df52fc18c853e1dd39/LLaVA-OneVision-0.5B.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "Mage-VL", provider = "Microsoft",
            fileName = "Mage-VL.litertlm",
            expectedSha256 = "4231ea9f88ef2bd3aca92fd4554531f17717225418c4527ac858a8a9184dad72",
            recommendedGpu = true, supportsVision = true, reasoning = false,
            contextTokens = 2048, downloadBytes = 2811977104L,
            description = "Image and text assistant",
            downloadUrl = "https://huggingface.co/litert-community/Mage-VL/resolve/392b2a700f34bb6cc405764af62e494c858b3e87/Mage-VL.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "MiniCPM-V-4", provider = "OpenBMB",
            fileName = "MiniCPM-V-4.litertlm",
            expectedSha256 = "d45ef0cd8141597d731d002b72a152ab1a0f8b9cda8d9a0f5e1bc3a4689dcbd0",
            recommendedGpu = true, supportsVision = true, reasoning = false,
            contextTokens = 1280, downloadBytes = 4214021104L,
            description = "Image and text assistant",
            downloadUrl = "https://huggingface.co/litert-community/MiniCPM-V-4/resolve/2d05ab8722280bd1f5e654c6a1a5e39c0e2bfc8c/MiniCPM-V-4-int8.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "MiniCPM5-2B", provider = "OpenBMB",
            fileName = "MiniCPM5-2B.litertlm",
            expectedSha256 = "9858563beafbc6d5e0d25fcee3827541515296a9302ed3d088b16a58d4fbe7b8",
            recommendedGpu = true, supportsVision = false, reasoning = false,
            contextTokens = 4096, downloadBytes = 1553670064L,
            description = "General conversation",
            downloadUrl = "https://huggingface.co/litert-community/MiniCPM5-2B/resolve/f6dd64ef6e117fc6e8af6891ae2096caa3e85a4e/MiniCPM5-2B_int4.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "Ministral-3-3B-Instruct-2512", provider = "Mistral AI",
            fileName = "Ministral-3-3B-Instruct-2512.litertlm",
            expectedSha256 = "df5f84608e8db475edb74cac2549eb03727e4941b65980c1d95e310bad996ada",
            recommendedGpu = true, supportsVision = false, reasoning = false,
            contextTokens = 4096, downloadBytes = 2340982768L,
            description = "General conversation",
            downloadUrl = "https://huggingface.co/litert-community/Ministral-3-3B-Instruct-2512/resolve/35f7dc34f20ceecf72c58c880e6091ea817a6acc/Ministral-3-3B-Instruct-2512_q4_block32_ekv4096.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "Ministral-3-3B-Reasoning-2512", provider = "Mistral AI",
            fileName = "Ministral-3-3B-Reasoning-2512.litertlm",
            expectedSha256 = "f01c4ed43bbca0441c4a8d47925d4288e1e3cc8c6bf8ecc09c3b9f8cd5b92448",
            recommendedGpu = true, supportsVision = false, reasoning = true,
            contextTokens = 4096, downloadBytes = 2340982768L,
            description = "Reasoning and problem solving · longer waits",
            downloadUrl = "https://huggingface.co/litert-community/Ministral-3-3B-Reasoning-2512/resolve/2176ce9dd89f6d5e3808bc571fc5db0e494896b5/model.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "Nanbeige4.1-3B", provider = "Nanbeige",
            fileName = "Nanbeige4.1-3B.litertlm",
            expectedSha256 = "1a2b741bd39edb9de665d42e11cf137a586eb517e7f4e10eb1e802e80a68369e",
            recommendedGpu = true, supportsVision = false, reasoning = false,
            contextTokens = 4096, downloadBytes = 2409326576L,
            description = "General conversation",
            downloadUrl = "https://huggingface.co/litert-community/Nanbeige4.1-3B/resolve/d184d4a36d432612399470addd503c67fbca7af8/model.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "Nanbeige4.2-3B", provider = "Nanbeige",
            fileName = "Nanbeige4.2-3B.litertlm",
            expectedSha256 = "2a3388f958ad22faabc306856bfa026515b2352ca9564964f3e5e9129f22f289",
            recommendedGpu = true, supportsVision = false, reasoning = false,
            contextTokens = 4096, downloadBytes = 2579277808L,
            description = "General conversation",
            downloadUrl = "https://huggingface.co/litert-community/Nanbeige4.2-3B/resolve/d958ce354e27235a5540c62d17dc676772a69a9a/model_fp32act.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "Nemotron-3-Nano-4B", provider = "NVIDIA",
            fileName = "Nemotron-3-Nano-4B.litertlm",
            expectedSha256 = "ba73d2ad1d878bc2b796eb3a1d993eb1b3bd5105743cd85bd6fa9d86e58a013d",
            recommendedGpu = false, supportsVision = false, reasoning = true,
            contextTokens = 1280, downloadBytes = 4126697184L,
            description = "Reasoning and problem solving · longer waits",
            downloadUrl = "https://huggingface.co/litert-community/Nemotron-3-Nano-4B/resolve/c8febc96e163c4c008b60cde2f3c490a663517e1/Nemotron-3-Nano-4B_int8.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "Nemotron-H-4B-Instruct-128K", provider = "NVIDIA",
            fileName = "Nemotron-H-4B-Instruct-128K.litertlm",
            expectedSha256 = "30d3e92e8da419cd3d7c0fb7be00c13f716fa05ce6f7d1d2b9402f957bd723ba",
            recommendedGpu = false, supportsVision = false, reasoning = false,
            contextTokens = 1280, downloadBytes = 4806777392L,
            description = "General conversation",
            downloadUrl = "https://huggingface.co/litert-community/Nemotron-H-4B-Instruct-128K/resolve/ef6d699062d686c61a61d76cc7dc7eeed4dea9dc/Nemotron-H-4B-Instruct-128K_int8.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "North-Micro-Vision-Instruct", provider = "CohereLabs",
            fileName = "North-Micro-Vision-Instruct.litertlm",
            expectedSha256 = "098c7f7a3d20985136d7cc7311a2477943e5d00a51a6d8d1042cd903d48a9e45",
            recommendedGpu = true, supportsVision = true, reasoning = false,
            contextTokens = 4096, downloadBytes = 2190562720L,
            description = "Image and text assistant",
            downloadUrl = "https://huggingface.co/litert-community/North-Micro-Vision-Instruct/resolve/dcc265605b6195c333819439acc73b6d4652012a/North-Micro-Vision-Instruct_int4.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "OLMo-2-1B-Instruct", provider = "Allen AI",
            fileName = "OLMo-2-1B-Instruct.litertlm",
            expectedSha256 = "8d2457b54397731c5f451babb6bebfb9877545b4b070ac76914aab348fd954b0",
            recommendedGpu = true, supportsVision = false, reasoning = false,
            contextTokens = 4096, downloadBytes = 931241056L,
            description = "General conversation",
            downloadUrl = "https://huggingface.co/litert-community/OLMo-2-1B-Instruct/resolve/f9b3581cb9384080336422dc8b69edc92eba5e03/OLMo-2-1B-Instruct_q4_block32_ekv4096.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "Ovis2.5-2B", provider = "AIDC-AI",
            fileName = "Ovis2.5-2B.litertlm",
            expectedSha256 = "b8c5cb82e2a8b945e8f2b72576745acf22e61393074b345a2575245d21ab8e4b",
            recommendedGpu = true, supportsVision = true, reasoning = false,
            contextTokens = 2048, downloadBytes = 2148712880L,
            description = "Image and text assistant",
            downloadUrl = "https://huggingface.co/litert-community/Ovis2.5-2B/resolve/0e4c75b24c56f2b09ba6a359b09162805d8c7059/Ovis2.5-2B.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "Phi-4-mini-instruct", provider = "Microsoft",
            fileName = "Phi-4-mini-instruct.litertlm",
            expectedSha256 = "7764d4deb53800578307be33039476b38a6c370fff71bedb3c0552563e23ab02",
            recommendedGpu = true, supportsVision = false, reasoning = false,
            contextTokens = 4096, downloadBytes = 3910090752L,
            description = "General conversation",
            downloadUrl = "https://huggingface.co/litert-community/Phi-4-mini-instruct/resolve/8cd368be75fdb94d5a6f6f5b40f1ab22a6c2543e/Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "Phi-4-mini-reasoning", provider = "Microsoft",
            fileName = "Phi-4-mini-reasoning.litertlm",
            expectedSha256 = "ee46dec376e1aee8190c41bce5f29d3b891db3eaa0e92ce5a6a28e24f04067ba",
            recommendedGpu = true, supportsVision = false, reasoning = true,
            contextTokens = 4096, downloadBytes = 2783974384L,
            description = "Reasoning and problem solving · longer waits",
            downloadUrl = "https://huggingface.co/litert-community/Phi-4-mini-reasoning/resolve/23b4ae1472970a7672696d35c7007a93a33f0d4d/model.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "Polaris-4B-Preview", provider = "POLARIS-Project",
            fileName = "Polaris-4B-Preview.litertlm",
            expectedSha256 = "e1edad5042c8354bec1d6187c22cad9e460a38428da517fc7ef22b3aa828eb3b",
            recommendedGpu = true, supportsVision = false, reasoning = false,
            contextTokens = 4096, downloadBytes = 2474357680L,
            description = "General conversation",
            downloadUrl = "https://huggingface.co/litert-community/Polaris-4B-Preview/resolve/e7526d68fdfe0b5cf779c9d22a3b4f20d376bd13/model.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "SmolLM3-3B", provider = "Hugging Face",
            fileName = "SmolLM3-3B.litertlm",
            expectedSha256 = "2d989ddf434a25682e580eb45dcf65c2cbd6a57f76278b8f651a8cf195c2273e",
            recommendedGpu = true, supportsVision = false, reasoning = false,
            contextTokens = 4096, downloadBytes = 2002257840L,
            description = "General conversation",
            downloadUrl = "https://huggingface.co/litert-community/SmolLM3-3B/resolve/f14a1ad45627c6f11f9db53aa241870ebe2efebd/SmolLM3-3B_q4_block32_ekv4096.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "SmolVLM2-2.2B", provider = "Hugging Face",
            fileName = "SmolVLM2-2.2B.litertlm",
            expectedSha256 = "fab0ba947699ab405ff8fb9ea2de6c9af3cdfb598ea93f57631a268f3d631da2",
            recommendedGpu = true, supportsVision = true, reasoning = false,
            contextTokens = 2048, downloadBytes = 1511123888L,
            description = "Image and text assistant",
            downloadUrl = "https://huggingface.co/litert-community/SmolVLM2-2.2B/resolve/4a18e38c5bfde31eb25d015471c122d73bf7bcbe/SmolVLM2-2.2B.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "SmolVLM2-500M", provider = "Hugging Face",
            fileName = "SmolVLM2-500M.litertlm",
            expectedSha256 = "b808b328d845a600a33c5295f93d9217487317bd334dbc91b2a8d50e26e60ad0",
            recommendedGpu = true, supportsVision = true, reasoning = false,
            contextTokens = 2048, downloadBytes = 360822960L,
            description = "Image and text assistant",
            downloadUrl = "https://huggingface.co/litert-community/SmolVLM2-500M/resolve/dad030b6e56756201d670cfb4d042736a2ce3a5c/SmolVLM2-500M.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "Spark-X2.5-1.7B", provider = "XHToken",
            fileName = "Spark-X2.5-1.7B.litertlm",
            expectedSha256 = "72075d8d46c74457c7883d0c3e3a60a5bfe406d324fd1155ffbcb04dd81d24ea",
            recommendedGpu = true, supportsVision = false, reasoning = false,
            contextTokens = 1280, downloadBytes = 1263128496L,
            description = "General conversation",
            downloadUrl = "https://huggingface.co/litert-community/Spark-X2.5-1.7B/resolve/fc07edeb77f20cf5f717cb8527a1f271287714cc/Spark-X2.5-1.7B_int4.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "Spark-X2.5-4B", provider = "XHToken",
            fileName = "Spark-X2.5-4B.litertlm",
            expectedSha256 = "0b9ab47c1e65214995dc10f9833728941a73c05cfb219e3ddb05b0fe623b16a1",
            recommendedGpu = true, supportsVision = false, reasoning = false,
            contextTokens = 1280, downloadBytes = 2482196400L,
            description = "General conversation",
            downloadUrl = "https://huggingface.co/litert-community/Spark-X2.5-4B/resolve/453039c2968e01cbbb304fe871e6b4c541bf58d5/Spark-X2.5-4B_int4.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "VibeThinker-1.5B", provider = "Weibo",
            fileName = "VibeThinker-1.5B.litertlm",
            expectedSha256 = "2b954abf0df11deb7bea9c4ce779566918dd81f4e578d673e7f618b10799e412",
            recommendedGpu = true, supportsVision = false, reasoning = true,
            contextTokens = 1280, downloadBytes = 1567604736L,
            description = "Reasoning and problem solving · longer waits",
            downloadUrl = "https://huggingface.co/litert-community/VibeThinker-1.5B/resolve/24367f44117d086d513ad41f08fa145df8807fd4/VibeThinker-1.5B.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "VibeThinker-3B", provider = "Weibo",
            fileName = "VibeThinker-3B.litertlm",
            expectedSha256 = "df27fe62ddcec6ef8220bbd7d6998a65cff550719dbc4eeb7d26a5056f4f8c18",
            recommendedGpu = true, supportsVision = false, reasoning = true,
            contextTokens = 4096, downloadBytes = 2057106352L,
            description = "Reasoning and problem solving · longer waits",
            downloadUrl = "https://huggingface.co/litert-community/VibeThinker-3B/resolve/aa00b39df5b7ed9c93898c3b4fa410d14c7690d9/model.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "Zamba2-1.2B-instruct", provider = "Zyphra",
            fileName = "Zamba2-1.2B-instruct.litertlm",
            expectedSha256 = "af51316bd21f96c3766e81deba1ccc935ec0b1e7dedcfee2d23acc7686e49b0d",
            recommendedGpu = false, supportsVision = false, reasoning = false,
            contextTokens = 1280, downloadBytes = 1430400528L,
            description = "General conversation",
            downloadUrl = "https://huggingface.co/litert-community/Zamba2-1.2B-instruct/resolve/ab211f0c7d9532cedeae5e2d42c87db49029ae0a/Zamba2-1.2B-instruct_int8.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "Zamba2-2.7B-instruct", provider = "Zyphra",
            fileName = "Zamba2-2.7B-instruct.litertlm",
            expectedSha256 = "601e96057ddeb6fc1a7451f193e475419c7b121408d841c08cef391f051835f1",
            recommendedGpu = false, supportsVision = false, reasoning = false,
            contextTokens = 1280, downloadBytes = 2803107344L,
            description = "General conversation",
            downloadUrl = "https://huggingface.co/litert-community/Zamba2-2.7B-instruct/resolve/0fc6e8c871250de6c4bd4337e3923f6c6808f18d/Zamba2-2.7B-instruct_int8.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "codegemma-7b-it-int4-litertlm", provider = "Google",
            fileName = "codegemma-7b-it-int4-litertlm.litertlm",
            expectedSha256 = "895b4ef975512d67d3a9160305f1e9d7525bf87bbfa01ec33225325b35983f5b",
            recommendedGpu = true, supportsVision = false, reasoning = false,
            contextTokens = 1280, downloadBytes = 4724446544L,
            description = "Coding help · uses more memory",
            downloadUrl = "https://huggingface.co/litert-community/codegemma-7b-it-int4-litertlm/resolve/aac118626c8170a2dd617cfe9dc1995fed6f0d34/codegemma-7b-it-int4-litertlm.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "granite-4.0-h-1b", provider = "IBM",
            fileName = "granite-4.0-h-1b.litertlm",
            expectedSha256 = "060bae024d59546dfd2ed9fba856b5a8adcf8b83b16f919c2637a7fcd88840c5",
            recommendedGpu = false, supportsVision = false, reasoning = false,
            contextTokens = 1280, downloadBytes = 1683129664L,
            description = "General conversation",
            downloadUrl = "https://huggingface.co/litert-community/granite-4.0-h-1b/resolve/3feed8ef9a47a493c79b01b7e973a589359ab2a2/granite-4.0-h-1b_int8.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "granite-4.0-h-350m", provider = "IBM",
            fileName = "granite-4.0-h-350m.litertlm",
            expectedSha256 = "f85136cfd308676e8da5182ca4f29a5d4d63d9bb3d230d9c6e837d5b39a05086",
            recommendedGpu = true, supportsVision = false, reasoning = false,
            contextTokens = 1280, downloadBytes = 481218880L,
            description = "General conversation",
            downloadUrl = "https://huggingface.co/litert-community/granite-4.0-h-350m/resolve/3b1cb048cbf915abaebf4cd1ba21802ea00d79bb/granite-4.0-h-350m_int8_gpu.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "granite-4.1-3b", provider = "IBM",
            fileName = "granite-4.1-3b.litertlm",
            expectedSha256 = "ba60c191fd33b1ac9d1ab245d4a24fe81e1a520a0dd9720a435a9cc4773d80a9",
            recommendedGpu = true, supportsVision = false, reasoning = false,
            contextTokens = 1280, downloadBytes = 2191445936L,
            description = "General conversation",
            downloadUrl = "https://huggingface.co/litert-community/granite-4.1-3b/resolve/01f885d5ee82bdc4b5d43425784bb761cc2213b2/granite-4.1-3b_int4.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "granite-4.2-3b", provider = "IBM",
            fileName = "granite-4.2-3b.litertlm",
            expectedSha256 = "9859901f6050df5c4616e31462643b76d0aa2bd395a8863cb6162495c1ef517d",
            recommendedGpu = true, supportsVision = false, reasoning = false,
            contextTokens = 1280, downloadBytes = 2190708656L,
            description = "General conversation",
            downloadUrl = "https://huggingface.co/litert-community/granite-4.2-3b/resolve/3b7f1338ee26a06d016a72a7d5c64f3ed022a466/granite-4.2-3b_int4.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "sarashina2.2-0.5b-instruct-v0.1", provider = "sbintuitions",
            fileName = "sarashina2.2-0.5b-instruct-v0.1.litertlm",
            expectedSha256 = "e5e873fdc7d31e5040e218ecf1af71bf9d8a27779715af30436edb3b4cbc3b5f",
            recommendedGpu = true, supportsVision = false, reasoning = false,
            contextTokens = 1280, downloadBytes = 534488128L,
            description = "Japanese and English conversation",
            downloadUrl = "https://huggingface.co/litert-community/sarashina2.2-0.5b-instruct-v0.1/resolve/0ca987c8289fcfc67a771a210d5f427796dd60ff/sarashina2.2-0.5b-instruct-v0.1_int4.litertlm?download=true"
        ),
        LocalModelSpec(
            id = "sarashina2.2-1b-instruct-v0.1", provider = "sbintuitions",
            fileName = "sarashina2.2-1b-instruct-v0.1.litertlm",
            expectedSha256 = "7313d5f3442f0b412d975ba6fbc4567a5c2c076f6cd49038e55a3f82522f02b7",
            recommendedGpu = true, supportsVision = false, reasoning = false,
            contextTokens = 1280, downloadBytes = 903657808L,
            description = "Japanese and English conversation",
            downloadUrl = "https://huggingface.co/litert-community/sarashina2.2-1b-instruct-v0.1/resolve/32b71555d88e8be9663e94582a77500a6c5ef354/sarashina2.2-1b-instruct-v0.1_int4.litertlm?download=true"
        )
    )
}
