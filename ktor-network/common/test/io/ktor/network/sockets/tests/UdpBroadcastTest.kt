/*
 * Copyright 2014-2020 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.network.sockets.tests

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.test.dispatcher.*
import io.ktor.util.*
import io.ktor.util.network.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlin.test.*

class UdpBroadcastTest {

    @Test
    fun testBroadcastSuccessful() = testSuspend {
        if (!PlatformUtils.IS_JVM && !PlatformUtils.IS_NATIVE) return@testSuspend
        withTimeout(15000) {
            // TODO: Calling [use] instead of [let] causes [UdpBroadcastTest] to get stuck on native.
            SelectorManager().let /*use*/ { selector ->
                val serverSocket = CompletableDeferred<BoundDatagramSocket>()
                val server = launch {
                    aSocket(selector)
                        .udp()
                        .bind(NetworkAddress("0.0.0.0", 56700))
                        .use { socket ->
                            serverSocket.complete(socket)
                            val received = socket.receive()
                            assertEquals("0123456789", received.packet.readText())
                        }
                }

                serverSocket.await()

                aSocket(selector)
                    .udp()
                    .bind(NetworkAddress("0.0.0.0", 0)) {
                        broadcast = true
                    }
                    .use { socket ->
                        socket.send(
                            Datagram(
                                packet = buildPacket { writeText("0123456789") },
                                address = NetworkAddress("255.255.255.255", 56700)
                            )
                        )
                    }

                server.join()
            }
        }
    }

    // TODO: this test does not catch the permission denied exception
//    @Test
//    fun testBroadcastFails() = testSuspend {
//        if (!PlatformUtils.IS_JVM && !PlatformUtils.IS_NATIVE) return@testSuspend
//        withTimeout(15000) {
//            SelectorManager().use { selector ->
//                assertFails {
//                    aSocket(selector)
//                        .udp()
//                        .bind {
//                            // Do not set broadcast to true so exception is thrown.
//                        }
//                        .use { socket ->
//                            socket.send(
//                                Datagram(
//                                    packet = buildPacket { writeText("0123456789") },
//                                    address = NetworkAddress("255.255.255.255", 56700)
//                                )
//                            )
//                        }
//                }
//            }
//        }
//    }
}
