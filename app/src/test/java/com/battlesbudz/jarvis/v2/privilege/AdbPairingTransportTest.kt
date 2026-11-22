package com.battlesbudz.jarvis.v2.privilege

import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.ConnectException

class AdbPairingTransportTest {
    @Test fun mikaCurve25519KnownAnswerVectorMatchesMessagesKeysAndCiphertext() {
        val password = hex("32363532333543c733885a3d925d056813df66ca344f584bfd964293f3df5a0b9167052474dde398d060254abcf40d4839936842d7c75fb5540a9107b7d9f04540dde3d2ebc2")
        val aliceSeed = hex("ae27b884a0b2f1809bb7f294f5ab3d5287be454dc3b45a0547c5b32645f0d33a3118ef09d0e950cf9a9afbb627a5ec990ab98c2e02d63ca5ad15a7225346fcc8")
        val bobSeed = hex("b7470a72677f6482601496d8f22da450253077416d848bd67505d2b0301e8f7896f03c4b053ecd9228a5cb5174eedc295a76138da1385d295e72b909a4c99da2")
        val clientName = PairingAuth.CLIENT_NAME
        val serverName = PairingAuth.SERVER_NAME
        val aliceSpake = Spake2(Spake2.Role.ALICE, clientName, serverName)
        val bobSpake = Spake2(Spake2.Role.BOB, serverName, clientName)
        val aliceMessage = aliceSpake.generateMessage(password, aliceSeed)
        val bobMessage = bobSpake.generateMessage(password, bobSeed)
        assertArrayEquals(hex("507f1708d35b116d592f2f9b20613cbd0bad6ba31057e90f648cd1a484bb6f33"), aliceMessage)
        assertArrayEquals(hex("98848b90a5e5b6da50385722057ab92674dcea0a616af47f5aedf1f7450fe3cb"), bobMessage)
        assertArrayEquals(hex("4ae40a2626a62343b5dfe7f814cb8ae9ff46864fe8f6bd43abe38931d5cfe1567189d56eef2bfe3ac47ce09e892d74183728fd4c5b06aaa211995ae8daebd8e9"), aliceSpake.processMessage(bobMessage))
        assertArrayEquals(hex("4ae40a2626a62343b5dfe7f814cb8ae9ff46864fe8f6bd43abe38931d5cfe1567189d56eef2bfe3ac47ce09e892d74183728fd4c5b06aaa211995ae8daebd8e9"), bobSpake.processMessage(aliceMessage))
        val alice = PairingAuth.client(password, aliceSeed)
        val bob = PairingAuth.server(password, bobSeed)
        assertArrayEquals(hex("507f1708d35b116d592f2f9b20613cbd0bad6ba31057e90f648cd1a484bb6f33"), alice.message)
        assertArrayEquals(hex("98848b90a5e5b6da50385722057ab92674dcea0a616af47f5aedf1f7450fe3cb"), bob.message)
        alice.initCipher(bob.message); bob.initCipher(alice.message)
        assertArrayEquals(hex("99add9c6d77865c60c8affc3e635d8fcbb7fae6b1fe6cca32c9a58fb1cd2d2b77a0466f1cb050db0a8f42302fce2d052b632db7366"), alice.encrypt(hex("f9086b7b081ca7800d42e4f26954c59008c849903dd990c6187241da1bf14f5d1f13e150b4")))
    }

    @Test fun mAndNAreRegeneratedFromPublishedSeeds() {
        assertEquals(Spake2.M, Spake2.derivePoint(Spake2.SEED_M))
        assertEquals(Spake2.N, Spake2.derivePoint(Spake2.SEED_N))
        assertArrayEquals(hex("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e"), Spake2.M.encode())
        assertArrayEquals(hex("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778"), Spake2.N.encode())
    }

    @Test fun realTransportOverFakeSocketCompletesCoordinatorStateMachine() {
        val states = PairingStateMachine()
        val transport = AndroidAdbPairingTransport(FakeAdbChannelFactory("123456"))
        val coordinator = PairingCoordinator(states, { adbEndpoint("127.0.0.1", 37123) }, transport)
        assertEquals("device-guid", coordinator.pair("123456".toCharArray()))
        assertEquals(PairingStateMachine.State.CONNECTED, states.snapshot().state)
    }

    @Test fun wrongCodeUnreachableAndTlsFailuresBlockWithSpecificReasons() {
        assertPairFailure(FakeAdbChannelFactory("654321"), "Wireless-debugging pairing code was rejected.")
        val unreachable = object : AdbPairingChannelFactory {
            override fun connect(endpoint: AdbEndpoint): AdbPairingChannel = throw ConnectException("refused")
        }
        assertPairFailure(unreachable, "Wireless-debugging endpoint is unreachable.")
        val tlsFailure = object : AdbPairingChannelFactory {
            override fun connect(endpoint: AdbEndpoint): AdbPairingChannel =
                throw PairingTransportException("Wireless-debugging TLS handshake failed.")
        }
        assertPairFailure(tlsFailure, "Wireless-debugging TLS handshake failed.")
    }

    private fun assertPairFailure(factory: AdbPairingChannelFactory, reason: String) {
        val states = PairingStateMachine()
        val coordinator = PairingCoordinator(states, { adbEndpoint("127.0.0.1", 37123) }, AndroidAdbPairingTransport(factory))
        assertEquals(reason, coordinator.pair("123456".toCharArray()))
        assertEquals(PairingStateMachine.State.BLOCKED, states.snapshot().state)
        assertEquals(reason, states.snapshot().reason)
    }

    private class FakeAdbChannelFactory(private val acceptedCode: String) : AdbPairingChannelFactory {
        override fun connect(endpoint: AdbEndpoint): AdbPairingChannel = FakeChannel(acceptedCode)
    }

    private class FakeChannel(acceptedCode: String) : AdbPairingChannel {
        private val clientInput = PipedInputStream(32 * 1024)
        private val serverOutput = PipedOutputStream(clientInput)
        private val serverInput = PipedInputStream(32 * 1024)
        override val input = DataInputStream(clientInput)
        override val output = PipedOutputStream(serverInput)
        private val export = ByteArray(64) { it.toByte() }

        init {
            val code = acceptedCode.toByteArray(Charsets.UTF_8) + export
            Thread {
                try {
                    val fromClient = DataInputStream(serverInput)
                    val message = AndroidAdbPairingTransport.readPacket(fromClient, 0)!!
                    val auth = PairingAuth.server(code)
                    AndroidAdbPairingTransport.writePacket(serverOutput, 0, auth.message)
                    auth.initCipher(message)
                    val sealed = AndroidAdbPairingTransport.readPacket(fromClient, 1)!!
                    val peerInfo = auth.decrypt(sealed)
                    assertEquals(AndroidAdbPairingTransport.PEER_INFO_SIZE, peerInfo.size)
                    val response = ByteArray(AndroidAdbPairingTransport.PEER_INFO_SIZE)
                    response[0] = 1
                    "device-guid".toByteArray().copyInto(response, 1)
                    AndroidAdbPairingTransport.writePacket(serverOutput, 1, auth.encrypt(response))
                } catch (_: Throwable) { runCatching { serverOutput.close() } }
            }.apply { isDaemon = true; start() }
        }

        override fun export(label: String, length: Int): ByteArray {
            assertEquals("adb-label\u0000", label); assertEquals(64, length)
            return export.copyOf()
        }
        override fun close() { runCatching { output.close() }; runCatching { serverOutput.close() } }
    }

    private fun hex(value: String) = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
