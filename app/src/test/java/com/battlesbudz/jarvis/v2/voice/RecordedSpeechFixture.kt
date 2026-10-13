package com.battlesbudz.jarvis.v2.voice

import java.security.MessageDigest

/** Public human speech, pinned independently of the production encoder/gates. */
internal object RecordedSpeechFixture {
    const val PATH = "/recorded-audio/librispeech-1089-134686-0000.wav"
    const val TEXT = "HE HOPED THERE WOULD BE STEW FOR DINNER TURNIPS AND CARROTS AND BRUISED POTATOES AND FAT MUTTON PIECES TO BE LADLED OUT IN THICK PEPPERED FLOUR FATTENED SAUCE"
    val wav: ByteArray get() = requireNotNull(javaClass.getResourceAsStream(PATH)).use { it.readBytes() }.also {
        check(it.size == 333_964 && String(it, 0, 4) == "RIFF" && String(it, 36, 4) == "data")
        check(sha256(it) == "0b1785dba56f22af426ccb25d318f7e103558fd40e1c3ab064b455dba2afae12")
    }
    val pcm: ByteArray get() = wav.copyOfRange(44, 333_964)
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
