package com.battlesbudz.jarvis.v2.vision

import com.battlesbudz.phoneinference.Box
import com.battlesbudz.phoneinference.Detection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Base64

/**
 * HTTP client for the Phone Inference app's localhost vision server
 * (START SERVER in that app). The server binds 127.0.0.1:8080 only:
 *
 *   GET  /health -> { status, model, version, ... } (no token required)
 *   POST /detect -> { image: base64, confidence? } ->
 *                   { detections: [{label, confidence, box}], inference_ms }
 *                   (requires the X-Vision-Token header)
 *
 * The server returns detections only — no segmentation, pose, or
 * classification — so [detect] returns a [NativeVisionSnapshot] with those
 * lists empty. [VisionContextFormatter.formatServerDetections] formats
 * that shape; do not feed it to [VisionContextFormatter.format], which
 * would print a misleading "People count: 0" for a server snapshot whose
 * poses were never computed.
 *
 * Failures are honest, never silent: unreachable server, rejected token,
 * timeout, and malformed responses all throw [PhoneVisionException] with
 * a message that names the cause and the fix. Callers surface that message
 * (via [VisionContextFormatter.failureNote]) instead of silently falling
 * back to in-process vision — otherwise a user testing the server path
 * could never tell whether the server was exercised at all.
 *
 * The token is never logged and never put in a URL. It travels only in
 * the X-Vision-Token header to loopback, matching the server's contract.
 */
class PhoneVisionClient(
    private val openConnection: (URL) -> HttpURLConnection = { url -> url.openConnection() as HttpURLConnection },
) {
    companion object {
        const val DEFAULT_DETECT_URL = "http://127.0.0.1:8080/detect"
        const val DEFAULT_HEALTH_URL = "http://127.0.0.1:8080/health"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 30_000
        const val DEFAULT_CONFIDENCE = 0.25
    }

    /** GET /health response. */
    data class HealthStatus(val status: String, val model: String, val version: String)

    /** Every failure from this client: the message names the cause and the fix. */
    class PhoneVisionException(message: String) : IOException(message)

    /**
     * POSTs [imageBytes] to the server and returns its detections as a
     * [NativeVisionSnapshot] (detections only; segments/poses/classifications
     * empty — the server does not compute them).
     */
    suspend fun detect(
        imageBytes: ByteArray,
        token: String,
        url: String = DEFAULT_DETECT_URL,
        confidence: Double = DEFAULT_CONFIDENCE,
    ): NativeVisionSnapshot = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("image", Base64.getEncoder().encodeToString(imageBytes))
            .put("confidence", confidence)
            .toString()
        val response = post(url, token, body)
        parseDetections(response)
    }

    /** GET /health. Throws [PhoneVisionException] when the server is unreachable. */
    suspend fun health(url: String = DEFAULT_HEALTH_URL): HealthStatus = withContext(Dispatchers.IO) {
        // openConnection is inside the try on purpose: a refused connection
        // throws ConnectException here, and it must be mapped to the
        // honest PhoneVisionException (START SERVER hint) below.
        var connection: HttpURLConnection? = null
        try {
            connection = openConnection(URL(url))
            connection.apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
            }
            val code = connection.responseCode
            val body = readBody(connection, code)
            if (code != HttpURLConnection.HTTP_OK) {
                throw PhoneVisionException(
                    "Phone Inference server returned HTTP $code: ${body.take(200)}"
                )
            }
            val json = parseJson(body, "health")
            HealthStatus(
                status = json.optString("status", "unknown"),
                model = json.optString("model", "unknown"),
                version = json.optString("version", "unknown"),
            )
        } catch (error: PhoneVisionException) {
            throw error
        } catch (error: ConnectException) {
            throw PhoneVisionException(
                "Phone Inference server is not reachable at $url. " +
                    "Start the server in the Phone Inference app (START SERVER)."
            )
        } catch (error: SocketTimeoutException) {
            throw PhoneVisionException("Phone Inference server timed out at $url.")
        } catch (error: IOException) {
            throw PhoneVisionException("Phone Inference server request failed: ${error.message}")
        } finally {
            connection?.disconnect()
        }
    }

    private fun post(url: String, token: String, body: String): String {
        // openConnection is inside the try on purpose: see health() above.
        var connection: HttpURLConnection? = null
        try {
            connection = openConnection(URL(url))
            val bodyBytes = body.toByteArray(Charsets.UTF_8)
            connection.apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                // Explicit Content-Length: NanoHTTPD 2.3.1 (the Phone Inference
                // app's server) misparses chunked POSTs, reading the JSON body
                // as the request line ("Syntax error. HTTP verb {...}").
                setFixedLengthStreamingMode(bodyBytes.size)
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Vision-Token", token)
            }
            connection.outputStream.use { it.write(bodyBytes) }
            val code = connection.responseCode
            val responseBody = readBody(connection, code)
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED) {
                throw PhoneVisionException(
                    "Phone Inference server rejected the token (HTTP 401). " +
                        "Check that the token in Settings matches the one shown in the Phone Inference app."
                )
            }
            if (code != HttpURLConnection.HTTP_OK) {
                throw PhoneVisionException(
                    "Phone Inference server returned HTTP $code: ${responseBody.take(200)}"
                )
            }
            return responseBody
        } catch (error: PhoneVisionException) {
            throw error
        } catch (error: ConnectException) {
            throw PhoneVisionException(
                "Phone Inference server is not reachable at $url. " +
                    "Start the server in the Phone Inference app (START SERVER)."
            )
        } catch (error: SocketTimeoutException) {
            throw PhoneVisionException("Phone Inference server timed out at $url.")
        } catch (error: IOException) {
            throw PhoneVisionException("Phone Inference server request failed: ${error.message}")
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseDetections(body: String): NativeVisionSnapshot {
        val json = parseJson(body, "detect")
        val detectionsJson = json.optJSONArray("detections")
            ?: throw PhoneVisionException(
                "Phone Inference server returned an unexpected response (no detections array)."
            )
        val detections = ArrayList<Detection>(detectionsJson.length())
        for (i in 0 until detectionsJson.length()) {
            val item = detectionsJson.optJSONObject(i)
                ?: throw PhoneVisionException(
                    "Phone Inference server returned an unexpected response (detection $i is not an object)."
                )
            val label = item.optString("label", "")
            if (label.isBlank()) {
                throw PhoneVisionException(
                    "Phone Inference server returned an unexpected response (detection $i has no label)."
                )
            }
            val boxJson = item.optJSONObject("box")
                ?: throw PhoneVisionException(
                    "Phone Inference server returned an unexpected response (detection $i has no box)."
                )
            detections += Detection(
                label = label,
                // The server does not return class IDs, and nothing
                // downstream consumes classId for server detections
                // (the formatter uses label + confidence + box).
                classId = -1,
                confidence = item.optDouble("confidence", Double.NaN).toFloatOrNull()
                    ?: throw PhoneVisionException(
                        "Phone Inference server returned an unexpected response " +
                            "(detection $i has no numeric confidence)."
                    ),
                box = Box(
                    boxJson.optDouble("x1", Double.NaN).toFloatOrNull()
                        ?: throw PhoneVisionException(
                            "Phone Inference server returned an unexpected response " +
                                "(detection $i box has no numeric x1)."
                        ),
                    boxJson.optDouble("y1", Double.NaN).toFloatOrNull()
                        ?: throw PhoneVisionException(
                            "Phone Inference server returned an unexpected response " +
                                "(detection $i box has no numeric y1)."
                        ),
                    boxJson.optDouble("x2", Double.NaN).toFloatOrNull()
                        ?: throw PhoneVisionException(
                            "Phone Inference server returned an unexpected response " +
                                "(detection $i box has no numeric x2)."
                        ),
                    boxJson.optDouble("y2", Double.NaN).toFloatOrNull()
                        ?: throw PhoneVisionException(
                            "Phone Inference server returned an unexpected response " +
                                "(detection $i box has no numeric y2)."
                        ),
                ),
            )
        }
        val inferenceMs = json.optDouble("inference_ms", Double.NaN)
        if (inferenceMs.isNaN()) {
            throw PhoneVisionException(
                "Phone Inference server returned an unexpected response (no numeric inference_ms)."
            )
        }
        return NativeVisionSnapshot(
            detections = detections,
            detectionMs = inferenceMs,
            segments = emptyList(),
            segmentationMs = 0.0,
            poses = emptyList(),
            poseMs = 0.0,
            classifications = emptyList(),
            classificationMs = 0.0,
        )
    }

    private fun parseJson(body: String, endpoint: String): JSONObject {
        return try {
            JSONObject(body)
        } catch (error: Exception) {
            throw PhoneVisionException(
                "Phone Inference server returned an unexpected response " +
                    "($endpoint body is not JSON: ${body.take(120)})."
            )
        }
    }

    private fun readBody(connection: HttpURLConnection, code: Int): String {
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        return stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
    }

    private fun Double.toFloatOrNull(): Float? = if (isNaN()) null else toFloat()
}
