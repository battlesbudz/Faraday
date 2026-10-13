package com.battlesbudz.jarvis.v2.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.annotation.Keep
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID

/** Copy user-selected content locally; no enduring provider permission or full-size bitmap needed. */
@Keep object ChatMediaStore {
    fun prepare(context: Context, source: Uri, kind: AttachmentKind): ChatAttachment {
        val input = context.contentResolver.openInputStream(source) ?: error("This file could not be opened.")
        val raw = input.use(AttachmentPolicy::readBounded)
        val data = if (kind == AttachmentKind.IMAGE) {
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(raw))) { decoder, info, _ ->
                val scale = minOf(1.0, 1536.0 / maxOf(info.size.width, info.size.height))
                decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            try { ByteArrayOutputStream().use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)) { "Could not prepare this image." }
                out.toByteArray()
            } } finally { bitmap.recycle() }
        } else { AttachmentPolicy.validateAudio(raw); raw }
        val directory = File(context.filesDir, "chat-media").apply { mkdirs() }
        val target = File(directory, UUID.randomUUID().toString() + if (kind == AttachmentKind.IMAGE) ".jpg" else ".wav")
        try { target.writeBytes(data) } catch (error: Exception) { target.delete(); throw error }
        return ChatAttachment(Uri.fromFile(target).toString(), kind)
    }
    fun prepareVoiceNote(context: Context, pcm: ByteArray): ChatAttachment {
        val wav = com.battlesbudz.jarvis.v2.voice.WavEncoder.pcm16Mono(pcm)
        AttachmentPolicy.validateAudio(wav)
        val directory = File(context.filesDir, "chat-media").apply { mkdirs() }
        val target = File(directory, UUID.randomUUID().toString() + ".wav")
        try { target.writeBytes(wav) } catch (error: Exception) { target.delete(); throw error }
        return ChatAttachment(Uri.fromFile(target).toString(), AttachmentKind.AUDIO)
    }
    fun discard(context: Context, attachment: ChatAttachment) {
        val uri = Uri.parse(attachment.uri)
        if (uri.scheme != "file") return
        val file = File(uri.path ?: return)
        if (file.parentFile?.canonicalFile == File(context.filesDir, "chat-media").canonicalFile) file.delete()
    }
}
