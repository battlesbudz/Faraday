package com.battlesbudz.jarvis.v2.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.battlesbudz.jarvis.v2.actions.ActionRequest
import com.battlesbudz.jarvis.v2.actions.AndroidToolCapabilityProbe
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.URL

/**
 * Private/incognito mode must make zero external network calls that could leak
 * what the user is asking about: web search (browse_* tools), Wikipedia, and
 * Wikidata lookups are all blocked, with an honest signal instead of silence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrivateModeNetworkBlockTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** Fake HTTP: records every URL it is asked for; fails the test if called when it must not be. */
    private class FakeHttp(
        private val forbid: Boolean,
        val calls: MutableList<URL> = mutableListOf()
    ) : (URL) -> ByteArray {
        override fun invoke(url: URL): ByteArray {
            calls += url
            if (forbid) error("network call forbidden in private mode: $url")
            val q = url.query.orEmpty()
            val json = when {
                url.host.contains("wikipedia") && q.contains("list=search") ->
                    JSONObject().put("query", JSONObject().put("search", JSONArray().put(
                        JSONObject().put("title", "Cat").put("snippet", "The cat is a small domesticated cat species.")
                    )))
                url.host.contains("wikipedia") && q.contains("prop=extracts") ->
                    JSONObject().put("query", JSONObject().put("pages",
                        JSONObject().put("1", JSONObject().put("extract", "The cat is a small domesticated cat species kept as a pet."))))
                url.host.contains("wikidata") ->
                    JSONObject().put("search", JSONArray().put(
                        JSONObject().put("id", "Q146").put("label", "cat").put("description", "small domesticated cat species")
                    ))
                else -> JSONObject()
            }
            return json.toString().toByteArray()
        }
    }

    // ---- Wikipedia / Wikidata / supplied-URL fetches ----

    @Test fun privateModeBlocksWikipediaLookup() = runBlocking {
        val http = FakeHttp(forbid = true)
        val client = ReferenceGroundingClient(readBytes = http, isPrivate = { true })
        assertNull(client.fetchIfRequested("search wikipedia for cat"))
        assertTrue("no network call may happen in private mode", http.calls.isEmpty())
    }

    @Test fun privateModeBlocksWikidataLookup() = runBlocking {
        val http = FakeHttp(forbid = true)
        val client = ReferenceGroundingClient(readBytes = http, isPrivate = { true })
        assertNull(client.fetchIfRequested("search wikidata for cat"))
        assertTrue("no network call may happen in private mode", http.calls.isEmpty())
    }

    @Test fun privateModeBlocksSuppliedUrlFetch() = runBlocking {
        val http = FakeHttp(forbid = true)
        val client = ReferenceGroundingClient(readBytes = http, isPrivate = { true })
        assertNull(client.fetchIfRequested("summarize https://example.com/article"))
        assertTrue("no network call may happen in private mode", http.calls.isEmpty())
    }

    @Test fun nonPrivateModeStillFetchesWikipedia() = runBlocking {
        val http = FakeHttp(forbid = false)
        val client = ReferenceGroundingClient(readBytes = http, isPrivate = { false })
        val grounding = client.fetchIfRequested("search wikipedia for cat")
        assertNotNull(grounding)
        assertTrue("expected wikipedia evidence, got: ${grounding!!.context.take(200)}",
            grounding.context.contains("Wikipedia"))
        assertTrue(http.calls.isNotEmpty())
    }

    // ---- Web search via browse_* tools (capability probe) ----

    private val browseTools = listOf(
        "browse_open", "browse_read", "browse_click", "browse_back", "browse_forward",
        "browse_fill", "browse_submit", "browse_handoff", "browse_login"
    )

    @Test fun privateModeBlocksAllBrowseTools() {
        val probe = AndroidToolCapabilityProbe(context, isPrivate = { true })
        for (tool in browseTools) {
            val reason = probe.missingReason(ActionRequest(tool, mapOf("url" to "https://example.com")))
            assertNotNull("$tool must be blocked in private mode", reason)
            assertTrue("$tool block must say why, was: $reason",
                reason!!.contains("private", ignoreCase = true))
        }
    }

    @Test fun nonPrivateModeDoesNotBlockBrowseTools() {
        val probe = AndroidToolCapabilityProbe(context, isPrivate = { false })
        for (tool in browseTools) {
            assertNull("$tool must stay available outside private mode",
                probe.missingReason(ActionRequest(tool)))
        }
    }

    @Test fun privateModeDoesNotBlockNonBrowseTools() {
        val probe = AndroidToolCapabilityProbe(context, isPrivate = { true })
        val reason = probe.missingReason(ActionRequest("read_battery"))
        // Battery may genuinely be unavailable under Robolectric; the point is the
        // private-mode browse block must not fire for non-browse tools.
        if (reason != null) assertFalse("non-browse tool must not hit the private block, was: $reason",
            reason.contains("private", ignoreCase = true))
    }
}
