package com.battlesbudz.jarvis.v2.ai.storage

import java.net.ServerSocket
import kotlin.concurrent.thread
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ModelStorageTest {
    @Test fun resumableTransferAndFullResponseFallbackRetainExactBytes() = runBlocking {
        val bytes = ByteArray(32001) { (it % 251).toByte() }
        for (rangeSupported in listOf(true, false)) {
            val root = Files.createTempDirectory("model-transfer").toFile()
            val server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
            val worker = thread(isDaemon = true, name = "model-test-http") {
                while (!server.isClosed) {
                    val socket = try { server.accept() } catch (_: java.net.SocketException) { break }
                    socket.use {
                        val reader = it.getInputStream().bufferedReader()
                        val headers = generateSequence { reader.readLine()?.takeIf(String::isNotEmpty) }.toList()
                        val range = headers.firstOrNull { header -> header.startsWith("Range:", true) }
                            ?.substringAfter(':')?.trim()
                        val start = if (rangeSupported && range != null) range.substringAfter("bytes=").substringBefore('-').toInt() else 0
                        val end = if (rangeSupported && range == "bytes=0-0") 0 else bytes.lastIndex
                        val partial = rangeSupported && range != null
                        val response = buildString {
                            append("HTTP/1.1 ${if (partial) "206 Partial Content" else "200 OK"}\r\n")
                            if (partial) append("Content-Range: bytes $start-$end/${bytes.size}\r\n")
                            append("Content-Length: ${end - start + 1}\r\nConnection: close\r\n\r\n")
                        }
                        it.getOutputStream().apply { write(response.toByteArray()); write(bytes, start, end - start + 1); flush() }
                    }
                }
            }
            try {
                val target = root.resolve("model.part").apply { writeBytes(bytes.copyOfRange(0, 3456)) }
                var progress = 0L
                ModelDownloader().download("http://127.0.0.1:${server.localPort}/model", target,
                    onProgress = { count, _ -> progress = count }, onStatus = {})
                assertArrayEquals(bytes, target.readBytes())
                assertEquals(bytes.size.toLong(), progress)
            } finally {
                server.close()
                worker.join(2000)
                root.deleteRecursively()
            }
        }
    }

    @Test fun hashesModelBytesAndReportsCompletion() {
        val file = Files.createTempFile("model-hash", ".bin").toFile()
        try {
            file.writeText("abc")
            var finished = false
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                file.sha256 { count, total -> finished = count == 3L && total == 3L })
            assertTrue(finished)
            file.writeText("abd")
            assertNotEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", file.sha256())
        } finally { file.delete() }
    }
}
