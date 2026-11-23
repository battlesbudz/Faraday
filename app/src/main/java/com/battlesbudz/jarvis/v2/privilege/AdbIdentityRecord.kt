package com.battlesbudz.jarvis.v2.privilege

import java.io.File
import java.util.Base64

/** Versioned private-file representation for the ADB key and paired device GUID. */
internal object AdbIdentityRecord {
    const val VERSION = 1
    data class Value(val publicKey: ByteArray, val privateKey: ByteArray, val peerInfo: String?)

    fun encode(value: Value): String = buildString {
        append("version=").append(VERSION).append('\n')
        append("public=").append(Base64.getEncoder().encodeToString(value.publicKey)).append('\n')
        append("private=").append(Base64.getEncoder().encodeToString(value.privateKey)).append('\n')
        value.peerInfo?.let { append("peer=").append(Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8))).append('\n') }
    }

    fun decode(lines: List<String>): Value {
        require(lines.isNotEmpty()) { "stored identity file is empty" }
        require(lines[0] == "version=$VERSION") { "unsupported identity format version: ${lines[0]}" }
        require(lines.size in 3..4) { "stored identity record has an invalid field count" }
        require(lines[1].startsWith("public=") && lines[2].startsWith("private=")) { "stored identity fields are malformed" }
        val pub = Base64.getDecoder().decode(lines[1].removePrefix("public="))
        val private = Base64.getDecoder().decode(lines[2].removePrefix("private="))
        val peer = lines.getOrNull(3)?.let {
            require(it.startsWith("peer=")) { "stored peer field is malformed" }
            String(Base64.getDecoder().decode(it.removePrefix("peer=")), Charsets.UTF_8).also { value ->
                require(value.isNotBlank()) { "stored peer info is empty" }
            }
        }
        return Value(pub, private, peer)
    }

    fun load(file: File, previouslyInitialized: Boolean): Value? {
        if (!file.exists()) {
            check(!previouslyInitialized) { "stored ADB identity file is missing" }
            return null
        }
        return decode(file.readLines())
    }

    fun save(file: File, value: Value) {
        file.parentFile?.mkdirs()
        val temp = File(file.path + ".tmp")
        temp.writeText(encode(value))
        check(temp.renameTo(file)) { "cannot atomically write stored ADB identity" }
    }
}
