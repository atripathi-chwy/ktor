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

class UdpSocketTest {

    @Test
    fun test(): Unit = testSuspend {
        if (!PlatformUtils.IS_JVM && !PlatformUtils.IS_NATIVE) return@testSuspend
        withTimeout(15000) {
            // TODO: Calling [use] instead of [let] causes [UdpSocketTest] to get stuck on native.
            SelectorManager().let /*use*/ { selector ->
                aSocket(selector).udp().bind(NetworkAddress("127.0.0.1", 8000)) {
                    reuseAddress = true
                    reusePort = true
                }.use { socket ->
                    // Send messages to localhost
                    launch {
                        val address = NetworkAddress("127.0.0.1", 8000)
                        repeat(10) {
                            val bytePacket = buildPacket { append("hello") }
                            val data = Datagram(bytePacket, address)
                            socket.send(data)
                        }
                    }
                    // Receive messages from localhost
                    repeat(10) {
                        val incoming = socket.receive()
                        assertEquals("hello", incoming.packet.readText())
                    }
                }
            }
        }
    }
}
