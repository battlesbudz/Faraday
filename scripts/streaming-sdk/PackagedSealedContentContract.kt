package com.google.ai.edge.litertlm

import com.google.gson.JsonObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** SDK-owned test module, compiled against the actual packaged classes.jar.
 * Synthetic payload only. This class is never included in the AAR or APK. */
@OptIn(ExperimentalEncodingApi::class)
fun main(args: Array<String>) {
    require(args.size == 1)
    var checks = 0
    fun verify(value: Boolean) { checks++; check(value) { "packaged_sealed_content_contract_$checks" } }
    val original = floatArrayOf(0.5f, -0.0f)
    val sealed = Content.SealedAudioEmbeddings.fromSealedAudio(original, 160, 2,
        "synthetic-package-contract", "a".repeat(64))
    val before = sealed.toJson().toString()
    original.fill(Float.NaN)
    verify(sealed.toJson().toString() == before)
    val wire = sealed.toJson()
    verify(wire.get("type").asString == "audio")
    val metadata = wire.getAsJsonObject("projected_audio")
    verify(metadata.get("complete").asBoolean)
    verify(metadata.get("projection").asString == "audio_adapter")
    verify(metadata.get("end_marker").asString == "runtime")
    verify(metadata.get("pcm_samples").asInt == 160)
    verify(metadata.get("token_count").asInt == 1)
    verify(metadata.get("embedding_width").asInt == 2)
    val bytes = Base64.decode(wire.get("blob").asString)
    verify(bytes.size == 8)
    val values = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    verify(values.float == 0.5f)
    verify(values.float.toRawBits() == Int.MIN_VALUE)
    // Mutating a returned JSON object must not mutate the SDK-owned bytes.
    wire.addProperty("blob", "changed")
    verify(sealed.toJson().toString() == before)
    verify(sealed.toString() == "[SealedAudioEmbeddings]")
    val receipt = JsonObject().apply {
        addProperty("passed", true)
        addProperty("checks", checks)
        addProperty("scope", "packaged SDK serialization and copied synthetic payload; no JNI, model or device")
    }
    File(args[0]).writeText(receipt.toString() + "\n")
    println(receipt)
}
