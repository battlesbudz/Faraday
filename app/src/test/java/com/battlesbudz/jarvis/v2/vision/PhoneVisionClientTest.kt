package com.battlesbudz.jarvis.v2.vision

import com.battlesbudz.phoneinference.Box
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.URL

class PhoneVisionClientTest {

    /** Canned connection: records the request, plays back one response. */
    private class FakeConnection(url: URL, private val code: Int, private val body: String) : HttpURLConnection(url) {
        val requestBody = ByteArrayOutputStream()
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode() = code
        override fun getInputStream(): InputStream =
            ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
        override fun getErrorStream(): InputStream =
            ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
        override fun getOutputStream(): OutputStream = requestBody
    }

    private fun clientFor(fake: FakeConnection) = PhoneVisionClient(openConnection = { fake })

    private val detectJson = JSONObject()
        .put("detections", org.json.JSONArray().put(
            JSONObject()
                .put("label", "person")
                .put("confidence", 0.9)
                .put("box", JSONObject().put("x1", 1.0).put("y1", 2.0).put("x2", 3.0).put("y2", 4.0))
        ).put(
            JSONObject()
                .put("label", "bus")
                .put("confidence", 0.75)
                .put("box", JSONObject().put("x1", 10.0).put("y1", 20.0).put("x2", 30.0).put("y2", 40.0))
        ))
        .put("inference_ms", 12.5)
        .toString()

    @Test fun detectParsesDetectionsAndSendsToken() {
        val image = byteArrayOf(1, 2, 3, 4)
        val fake = FakeConnection(URL("http://127.0.0.1:8080/detect"), 200, detectJson)
        val snapshot = runBlocking { clientFor(fake).detect(image, "test-token") }
        assertEquals(2, snapshot.detections.size)
        val first = snapshot.detections[0]
        assertEquals("person", first.label)
        assertEquals(0.9f, first.confidence)
        assertEquals(Box(1f, 2f, 3f, 4f), first.box)
        assertEquals(12.5, snapshot.detectionMs, 0.001)
        assertTrue(snapshot.segments.isEmpty())
        assertTrue(snapshot.poses.isEmpty())
        assertTrue(snapshot.classifications.isEmpty())
        assertEquals("test-token", fake.getRequestProperty("X-Vision-Token"))
        assertEquals("application/json", fake.getRequestProperty("Content-Type"))
        val sent = JSONObject(fake.requestBody.toString(Charsets.UTF_8.name()))
        assertEquals(
            java.util.Base64.getEncoder().encodeToString(image),
            sent.getString("image")
        )
        assertEquals(0.5, sent.getDouble("confidence"), 0.001)
    }

    @Test fun detectDropsContainedDuplicateDetections() {
        // A weaker person box fully inside a stronger person box is a
        // duplicate of the same person and must not reach consumers;
        // an unrelated car is untouched.
        val json = JSONObject()
            .put("detections", org.json.JSONArray().put(
                JSONObject()
                    .put("label", "person")
                    .put("confidence", 0.9)
                    .put("box", JSONObject().put("x1", 100.0).put("y1", 100.0).put("x2", 300.0).put("y2", 500.0))
            ).put(
                JSONObject()
                    .put("label", "person")
                    .put("confidence", 0.6)
                    .put("box", JSONObject().put("x1", 150.0).put("y1", 200.0).put("x2", 250.0).put("y2", 400.0))
            ).put(
                JSONObject()
                    .put("label", "car")
                    .put("confidence", 0.8)
                    .put("box", JSONObject().put("x1", 0.0).put("y1", 0.0).put("x2", 50.0).put("y2", 50.0))
            ))
            .put("inference_ms", 9.0)
            .toString()
        val fake = FakeConnection(URL("http://127.0.0.1:8080/detect"), 200, json)
        val snapshot = runBlocking { clientFor(fake).detect(byteArrayOf(1), "test-token") }
        assertEquals(2, snapshot.detections.size)
        val keptPerson = snapshot.detections.first { it.label == "person" }
        assertEquals(0.9f, keptPerson.confidence)
        assertEquals(Box(100f, 100f, 300f, 500f), keptPerson.box)
        assertEquals(1, snapshot.detections.count { it.label == "car" })
    }

    @Test fun detectUnauthorizedMentionsToken() {
        val fake = FakeConnection(URL("http://127.0.0.1:8080/detect"), 401, """{"error":"missing or invalid X-Vision-Token"}""")
        val error = runBlocking {
            runCatching { clientFor(fake).detect(byteArrayOf(1), "wrong-token") }.exceptionOrNull()
        }
        assertTrue(error is PhoneVisionClient.PhoneVisionException)
        assertTrue("expected 401 + token in: ${error?.message}", error?.message?.contains("401") == true)
        assertTrue("expected token in: ${error?.message}", error?.message?.contains("token", ignoreCase = true) == true)
    }

    @Test fun detectServerDownMentionsStartingServer() {
        val client = PhoneVisionClient(openConnection = { throw ConnectException("Connection refused") })
        val error = runBlocking {
            runCatching { client.detect(byteArrayOf(1), "token") }.exceptionOrNull()
        }
        assertTrue(error is PhoneVisionClient.PhoneVisionException)
        assertTrue("expected START SERVER hint in: ${error?.message}", error?.message?.contains("START SERVER") == true)
    }

    @Test fun detectMalformedJsonThrows() {
        val fake = FakeConnection(URL("http://127.0.0.1:8080/detect"), 200, "not json")
        val error = runBlocking {
            runCatching { clientFor(fake).detect(byteArrayOf(1), "token") }.exceptionOrNull()
        }
        assertTrue(error is PhoneVisionClient.PhoneVisionException)
    }

    @Test fun detectMissingDetectionsArrayThrows() {
        val fake = FakeConnection(URL("http://127.0.0.1:8080/detect"), 200, """{"inference_ms": 5.0}""")
        val error = runBlocking {
            runCatching { clientFor(fake).detect(byteArrayOf(1), "token") }.exceptionOrNull()
        }
        assertTrue(error is PhoneVisionClient.PhoneVisionException)
    }

    @Test fun healthParsesStatus() {
        val fake = FakeConnection(
            URL("http://127.0.0.1:8080/health"), 200,
            """{"status":"ok","model":"yolov8n","version":"1.1.2"}"""
        )
        val health = runBlocking { clientFor(fake).health() }
        assertEquals("ok", health.status)
        assertEquals("yolov8n", health.model)
        assertEquals("1.1.2", health.version)
    }

    @Test fun healthServerDownThrows() {
        val client = PhoneVisionClient(openConnection = { throw ConnectException("Connection refused") })
        val error = runBlocking {
            runCatching { client.health() }.exceptionOrNull()
        }
        assertTrue(error is PhoneVisionClient.PhoneVisionException)
    }
}
