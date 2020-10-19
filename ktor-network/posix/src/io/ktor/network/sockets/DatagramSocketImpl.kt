/*
 * Copyright 2014-2020 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.network.sockets

import io.ktor.network.selector.*
import io.ktor.network.util.*
import io.ktor.util.network.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import io.ktor.utils.io.errors.*
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import platform.posix.*
import kotlin.coroutines.*

internal class DatagramSocketImpl(
    private val descriptor: Int,
    private val selector: SelectorManager,
    private val _localAddress: NetworkAddress,
    private val _remoteAddress: NetworkAddress?,
    parent: CoroutineContext = EmptyCoroutineContext
) : BoundDatagramSocket, ConnectedDatagramSocket, Socket, CoroutineScope {
    private val _context: CompletableJob = Job(parent[Job])
    private val selectable: SelectableNative = SelectableNative(descriptor)

    override val coroutineContext: CoroutineContext = parent + Dispatchers.Unconfined + _context

    override val socketContext: Job
        get() = _context

    override val localAddress: NetworkAddress
        get() = _localAddress
    override val remoteAddress: NetworkAddress
        get() = _remoteAddress!! // TODO: What should happen here?

    private val sender = Channel<Datagram>()

    private val receiver = produce<Datagram>(coroutineContext) {
        while (true) {
            val received = receiveImpl()
            channel.send(received)
        }
    }

    init {
        makeShared()

        launch {
            sender.consumeEach { datagram ->
                sendImpl(datagram)
            }
        }
    }

    override val outgoing: SendChannel<Datagram>
        get() = sender
    override val incoming: ReceiveChannel<Datagram>
        get() = receiver

    override fun attachForReading(channel: ByteChannel): WriterJob =
        attachForReadingImpl(channel, descriptor, selectable, selector)

    override fun attachForWriting(channel: ByteChannel): ReaderJob =
        attachForWritingImpl(channel, descriptor, selectable, selector)

    private tailrec suspend fun sendImpl(
        datagram: Datagram,
        bytes: ByteArray = datagram.packet.readBytes()
    ) {
        var bytesWritten: Int? = null
        bytes.usePinned { pinned ->
            datagram.address.address.nativeAddress { address, addressSize ->
                bytesWritten = sendto(
                    descriptor,
                    pinned.addressOf(0),
                    bytes.size.convert(),
                    0,
                    address,
                    addressSize
                ).toInt()
            }
        }
        when (bytesWritten ?: error("bytesWritten cannot be null")) {
            0 -> throw IOException("Failed writing to closed socket")
            -1 -> {
                if (errno == EAGAIN) {
                    selector.select(selectable, SelectInterest.WRITE)
                    sendImpl(datagram, bytes)
                } else {
                    throw PosixException.forErrno()
                }
            }
        }
    }

    private tailrec suspend fun receiveImpl(
        buffer: IoBuffer = DefaultDatagramByteBufferPool.borrow()
    ): Datagram {
        memScoped {
            val clientAddress = alloc<sockaddr_storage>()
            val clientAddressLength: UIntVarOf<UInt> = alloc()
            clientAddressLength.value = sockaddr_storage.size.convert()

            val count = try {
                buffer.write { memory, startIndex, endIndex ->
                    val bufferStart = memory.pointer + startIndex
                    val size = endIndex - startIndex
                    val bytesRead = recvfrom(
                        descriptor,
                        bufferStart,
                        size.convert(),
                        0,
                        clientAddress.ptr.reinterpret(),
                        clientAddressLength.ptr
                    ).toInt()

                    when (bytesRead) {
                        0 -> throw IOException("Failed reading from closed socket")
                        -1 -> {
                            if (errno == EAGAIN) {
                                return@write 0
                            }
                            throw PosixException.forErrno()
                        }
                    }

                    bytesRead
                }
            } catch (throwable: Throwable) {
                buffer.release(DefaultDatagramByteBufferPool)
                throw throwable
            }
            if (count > 0) {
                val address = clientAddress.reinterpret<sockaddr>().toSocketAddress()
                val datagram = Datagram(
                    buildPacket { writeFully(buffer) },
                    NetworkAddress(address.address, address.port, address)
                )
                buffer.release(DefaultDatagramByteBufferPool)
                return datagram
            } else {
                selector.select(selectable, SelectInterest.READ)
                return receiveImpl(buffer)
            }
        }
    }

    override fun close() {
        _context.complete()
        _context.invokeOnCompletion {
            shutdown(descriptor, SHUT_RDWR)
            close(descriptor)
        }
    }
}
