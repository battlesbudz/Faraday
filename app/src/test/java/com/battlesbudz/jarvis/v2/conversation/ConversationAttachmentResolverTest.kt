package com.battlesbudz.jarvis.v2.conversation

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class ConversationAttachmentResolverTest {
    @Test fun unavailableOrThrowingPrimaryUsesAssetDescriptorStream() {
        val primaryAttempts: List<() -> InputStream?> = listOf({ null }, { throw IOException("provider_requires_asset") })
        for (primary in primaryAttempts) {
            val asset = ByteArrayInputStream(byteArrayOf(17, 42))
            var fallbackCalls = 0
            val resolved = openConversationAttachment(primary) { fallbackCalls++; asset }
            assertSame(asset, resolved)
            assertEquals(1, fallbackCalls)
            assertEquals(17, resolved!!.read())
            assertEquals(42, resolved.read())
        }
    }

    @Test fun primaryStreamIsUsedWithoutInvokingAssetDescriptorFallback() {
        val primary = ByteArrayInputStream(byteArrayOf(5))
        var fallbackCalls = 0
        val resolved = openConversationAttachment({ primary }) { fallbackCalls++; error("fallback_must_not_run") }
        assertSame(primary, resolved)
        assertEquals(0, fallbackCalls)
        assertEquals(5, resolved!!.read())
    }

    @Test fun bothUnavailableResolverPathsReturnNoStreamWithoutLeakingTheirErrors() {
        val unavailable: List<() -> InputStream?> = listOf({ null }, { throw IOException("provider_unavailable") })
        for (primary in unavailable) for (fallback in unavailable) {
            assertNull(openConversationAttachment(primary, fallback))
        }
    }
}
