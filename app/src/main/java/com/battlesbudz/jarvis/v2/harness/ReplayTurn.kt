package com.battlesbudz.jarvis.v2.harness

import org.json.JSONArray
import org.json.JSONObject

/**
 * One recorded conversation turn as a replay task (self-improving harness
 * project, Phase 1; plan §4.3).
 *
 * Diagnostics already persist the exact final prompt of every submission;
 * what they do not retain is the structured input to prompt assembly, so a
 * turn could never be re-assembled under a different harness. This record
 * is captured at submission time instead: the user message, the history
 * slice actually offered to assembly, the memory/continuity/capture
 * sections as injected, the vision block verbatim plus the structured
 * detection lists behind it (the raw image is never stored), the tool plan
 * and the receipts the turn produced, and the final prompt text itself.
 *
 * Voice turns are captured at transcript level: [userPrompt] is the
 * transcript the harness consumed and [finalPrompt] is the assembled text
 * prompt. No raw audio is ever stored. Direct-audio turns (the model
 * consumed audio, not a transcript prompt) are recorded for evidence but
 * marked [replayable] = false.
 */
data class ReplayTurnRecord(
    val id: String,
    val capturedAtMs: Long,
    /** Harness version the turn was assembled under. */
    val harnessVersion: Int,
    val modelId: String,
    val kind: Kind,
    val replayable: Boolean,
    val userPrompt: String,
    val historyOffered: List<HistoryEntry>,
    /** Assembly was asked to seed context from [historyOffered]. */
    val seedContextRequested: Boolean,
    /** The submitted prompt actually carried the seeded context (false when the budget fallback dropped it). */
    val seeded: Boolean,
    val memorySection: String?,
    val continuitySection: String,
    val captureSection: String,
    /** The context block passed to assembly verbatim (the vision block on image turns). */
    val contextBlock: String?,
    val vision: VisionCapture?,
    val referenceSection: String?,
    val activeSubject: String?,
    val resolvedQuestion: String?,
    val toolPlan: List<ToolStep>,
    val toolReceipts: List<ToolReceipt>,
    val finalPrompt: String
) {
    enum class Kind { TEXT, VOICE_TEXT, IMAGE_TEXT, DIRECT_AUDIO }

    data class HistoryEntry(val role: String, val text: String)

    data class ToolStep(val name: String, val argumentsJson: String, val sourceClause: String)

    data class ToolReceipt(
        val name: String,
        val resultMessage: String,
        val succeeded: Boolean,
        val outcome: String
    )

    /** Structured vision evidence behind [contextBlock]; lets a future formatter re-render the block. */
    data class VisionCapture(
        /** "native" (in-process models) or "server" (Phone Inference server). */
        val source: String,
        val totalMs: Double,
        val detections: List<DetectionCapture>,
        val segments: List<SegmentCapture>,
        val poses: List<PoseCapture>,
        val classifications: List<ClassificationCapture>
    )

    data class BoxCapture(val x1: Float, val y1: Float, val x2: Float, val y2: Float)

    data class PointCapture(val x: Float, val y: Float)

    data class DetectionCapture(
        val label: String, val classId: Int, val confidence: Float, val box: BoxCapture
    )

    data class SegmentCapture(
        val label: String, val classId: Int, val confidence: Float,
        val box: BoxCapture, val points: List<PointCapture>
    )

    data class KeypointCapture(
        val name: String, val classId: Int, val x: Float, val y: Float, val confidence: Float
    )

    data class PoseCapture(
        val confidence: Float, val box: BoxCapture, val keypoints: List<KeypointCapture>
    )

    data class ClassificationCapture(val label: String, val classId: Int, val confidence: Float)
}

/** JSON codec for [ReplayTurnRecord]; [fromJson] returns null on any malformation. */
object ReplayTurnCodec {
    const val SCHEMA_VERSION = 1

    fun toJson(record: ReplayTurnRecord): String {
        val json = JSONObject()
        json.put("schema", SCHEMA_VERSION)
        json.put("id", record.id)
        json.put("capturedAtMs", record.capturedAtMs)
        json.put("harnessVersion", record.harnessVersion)
        json.put("modelId", record.modelId)
        json.put("kind", record.kind.name)
        json.put("replayable", record.replayable)
        json.put("userPrompt", record.userPrompt)
        json.put("historyOffered", JSONArray().also { array ->
            record.historyOffered.forEach { entry ->
                array.put(JSONObject().put("role", entry.role).put("text", entry.text))
            }
        })
        json.put("seedContextRequested", record.seedContextRequested)
        json.put("seeded", record.seeded)
        record.memorySection?.let { json.put("memorySection", it) }
        json.put("continuitySection", record.continuitySection)
        json.put("captureSection", record.captureSection)
        record.contextBlock?.let { json.put("contextBlock", it) }
        record.vision?.let { json.put("vision", visionToJson(it)) }
        record.referenceSection?.let { json.put("referenceSection", it) }
        record.activeSubject?.let { json.put("activeSubject", it) }
        record.resolvedQuestion?.let { json.put("resolvedQuestion", it) }
        json.put("toolPlan", JSONArray().also { array ->
            record.toolPlan.forEach { step ->
                array.put(JSONObject()
                    .put("name", step.name)
                    .put("argumentsJson", step.argumentsJson)
                    .put("sourceClause", step.sourceClause))
            }
        })
        json.put("toolReceipts", JSONArray().also { array ->
            record.toolReceipts.forEach { receipt ->
                array.put(JSONObject()
                    .put("name", receipt.name)
                    .put("resultMessage", receipt.resultMessage)
                    .put("succeeded", receipt.succeeded)
                    .put("outcome", receipt.outcome))
            }
        })
        json.put("finalPrompt", record.finalPrompt)
        return json.toString()
    }

    fun fromJson(text: String): ReplayTurnRecord? = runCatching {
        val json = JSONObject(text)
        if (json.optInt("schema", -1) != SCHEMA_VERSION) return null
        ReplayTurnRecord(
            id = json.getString("id"),
            capturedAtMs = json.getLong("capturedAtMs"),
            harnessVersion = json.getInt("harnessVersion"),
            modelId = json.getString("modelId"),
            kind = ReplayTurnRecord.Kind.valueOf(json.getString("kind")),
            replayable = json.getBoolean("replayable"),
            userPrompt = json.getString("userPrompt"),
            historyOffered = json.getJSONArray("historyOffered").mapObjects { entry ->
                ReplayTurnRecord.HistoryEntry(entry.getString("role"), entry.getString("text"))
            },
            seedContextRequested = json.getBoolean("seedContextRequested"),
            seeded = json.getBoolean("seeded"),
            memorySection = json.optString("memorySection").takeIf { json.has("memorySection") },
            continuitySection = json.getString("continuitySection"),
            captureSection = json.getString("captureSection"),
            contextBlock = json.optString("contextBlock").takeIf { json.has("contextBlock") },
            vision = if (json.has("vision")) visionFromJson(json.getJSONObject("vision")) else null,
            referenceSection = json.optString("referenceSection").takeIf { json.has("referenceSection") },
            activeSubject = json.optString("activeSubject").takeIf { json.has("activeSubject") },
            resolvedQuestion = json.optString("resolvedQuestion").takeIf { json.has("resolvedQuestion") },
            toolPlan = json.getJSONArray("toolPlan").mapObjects { step ->
                ReplayTurnRecord.ToolStep(
                    step.getString("name"), step.getString("argumentsJson"), step.getString("sourceClause"))
            },
            toolReceipts = json.getJSONArray("toolReceipts").mapObjects { receipt ->
                ReplayTurnRecord.ToolReceipt(
                    receipt.getString("name"), receipt.getString("resultMessage"),
                    receipt.getBoolean("succeeded"), receipt.getString("outcome"))
            },
            finalPrompt = json.getString("finalPrompt")
        )
    }.getOrNull()

    private fun visionToJson(vision: ReplayTurnRecord.VisionCapture): JSONObject {
        val json = JSONObject()
        json.put("source", vision.source)
        json.put("totalMs", vision.totalMs)
        json.put("detections", JSONArray().also { array ->
            vision.detections.forEach { detection ->
                array.put(JSONObject()
                    .put("label", detection.label).put("classId", detection.classId)
                    .put("confidence", detection.confidence.toDouble()).put("box", boxToJson(detection.box)))
            }
        })
        json.put("segments", JSONArray().also { array ->
            vision.segments.forEach { segment ->
                array.put(JSONObject()
                    .put("label", segment.label).put("classId", segment.classId)
                    .put("confidence", segment.confidence.toDouble()).put("box", boxToJson(segment.box))
                    .put("points", JSONArray().also { points ->
                        segment.points.forEach { point -> points.put(JSONObject().put("x", point.x.toDouble()).put("y", point.y.toDouble())) }
                    }))
            }
        })
        json.put("poses", JSONArray().also { array ->
            vision.poses.forEach { pose ->
                array.put(JSONObject()
                    .put("confidence", pose.confidence.toDouble()).put("box", boxToJson(pose.box))
                    .put("keypoints", JSONArray().also { keypoints ->
                        pose.keypoints.forEach { keypoint ->
                            keypoints.put(JSONObject()
                                .put("name", keypoint.name).put("classId", keypoint.classId)
                                .put("x", keypoint.x.toDouble()).put("y", keypoint.y.toDouble())
                                .put("confidence", keypoint.confidence.toDouble()))
                        }
                    }))
            }
        })
        json.put("classifications", JSONArray().also { array ->
            vision.classifications.forEach { classification ->
                array.put(JSONObject()
                    .put("label", classification.label).put("classId", classification.classId)
                    .put("confidence", classification.confidence.toDouble()))
            }
        })
        return json
    }

    private fun visionFromJson(json: JSONObject): ReplayTurnRecord.VisionCapture =
        ReplayTurnRecord.VisionCapture(
            source = json.getString("source"),
            totalMs = json.getDouble("totalMs"),
            detections = json.getJSONArray("detections").mapObjects { detection ->
                ReplayTurnRecord.DetectionCapture(
                    detection.getString("label"), detection.getInt("classId"),
                    detection.getDouble("confidence").toFloat(), boxFromJson(detection.getJSONObject("box")))
            },
            segments = json.getJSONArray("segments").mapObjects { segment ->
                ReplayTurnRecord.SegmentCapture(
                    segment.getString("label"), segment.getInt("classId"),
                    segment.getDouble("confidence").toFloat(), boxFromJson(segment.getJSONObject("box")),
                    segment.getJSONArray("points").mapObjects { point ->
                        ReplayTurnRecord.PointCapture(
                            point.getDouble("x").toFloat(), point.getDouble("y").toFloat())
                    })
            },
            poses = json.getJSONArray("poses").mapObjects { pose ->
                ReplayTurnRecord.PoseCapture(
                    pose.getDouble("confidence").toFloat(), boxFromJson(pose.getJSONObject("box")),
                    pose.getJSONArray("keypoints").mapObjects { keypoint ->
                        ReplayTurnRecord.KeypointCapture(
                            keypoint.getString("name"), keypoint.getInt("classId"),
                            keypoint.getDouble("x").toFloat(), keypoint.getDouble("y").toFloat(),
                            keypoint.getDouble("confidence").toFloat())
                    })
            },
            classifications = json.getJSONArray("classifications").mapObjects { classification ->
                ReplayTurnRecord.ClassificationCapture(
                    classification.getString("label"), classification.getInt("classId"),
                    classification.getDouble("confidence").toFloat())
            }
        )

    private fun boxToJson(box: ReplayTurnRecord.BoxCapture): JSONObject = JSONObject()
        .put("x1", box.x1.toDouble()).put("y1", box.y1.toDouble())
        .put("x2", box.x2.toDouble()).put("y2", box.y2.toDouble())

    private fun boxFromJson(json: JSONObject): ReplayTurnRecord.BoxCapture = ReplayTurnRecord.BoxCapture(
        json.getDouble("x1").toFloat(), json.getDouble("y1").toFloat(),
        json.getDouble("x2").toFloat(), json.getDouble("y2").toFloat())

    private fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
        (0 until length()).map { transform(getJSONObject(it)) }
}
