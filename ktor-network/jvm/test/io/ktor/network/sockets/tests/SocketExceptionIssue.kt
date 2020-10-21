/*
 * Copyright 2014-2020 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.network.sockets.tests

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.util.network.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlin.coroutines.*
import kotlin.io.use
import kotlin.test.*

class SocketExceptionIssue : CoroutineScope {
    private val testJob = Job()
    private val selector = ActorSelectorManager(Dispatchers.Default + testJob)

    override val coroutineContext: CoroutineContext
        get() = testJob

    @AfterTest
    fun tearDown() {
        testJob.cancel()
        selector.close()
    }

    @Test
    fun testBroadcastFails(): Unit = runBlocking {
        withTimeout(15000) {
            // The following code in the assertFails lambda should throw a SocketException.
            assertFails {
                aSocket(selector)
                    .udp()
                    .bind {
                        // Do not set broadcast to true so exception is thrown.
                    }
                    .use { socket ->
                        // Send a broadcast which will fail because broadcasts are not enabled on this socket.
                        socket.send(
                            Datagram(
                                packet = buildPacket { writeText("0123456789") },
                                address = NetworkAddress("255.255.255.255", 56700)
                            )
                        )
                    }
            }
        }
    }
}
