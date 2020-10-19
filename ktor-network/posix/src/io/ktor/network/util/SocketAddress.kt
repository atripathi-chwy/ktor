/*
 * Copyright 2014-2020 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.network.util

import io.ktor.network.interop.*
import kotlinx.cinterop.*
import platform.posix.*
import platform.posix.sockaddr_in
import platform.posix.sockaddr_in6

/**
 * Represents pair of network ip and port.
 */
public sealed class SocketAddress(
    public val family: sa_family_t,
    public val port: Int
) {
    internal abstract fun nativeAddress(block: (address: CPointer<sockaddr>, size: socklen_t) -> Unit)

    /**
     * String representation of socket address part.
     */
    public abstract val address: String
}

internal class IPv4Address(
    family: sa_family_t,
    private val nativeAddress: in_addr,
    port: Int
) : SocketAddress(family, port) {
    private val ip: in_addr_t = nativeAddress.s_addr

    override fun nativeAddress(block: (address: CPointer<sockaddr>, size: socklen_t) -> Unit) {
        cValue<sockaddr_in> {
            sin_addr.s_addr = ip
            sin_port = port.convert()
            sin_family = family

            block(ptr.reinterpret(), sockaddr_in.size.convert())
        }
    }

    override val address: String
        get() {
            val address = ByteArray(INET_ADDRSTRLEN)
            address.usePinned { addressBuf ->
                ktor_inet_ntop(
                    AF_INET,
                    nativeAddress.ptr,
                    addressBuf.addressOf(0),
                    address.size.convert()
                )
            }
            return address.toKString()
        }
}

internal class IPv6Address(
    family: sa_family_t,
    private val rawAddress: in6_addr,
    port: Int,
    private val flowInfo: uint32_t,
    private val scopeId: uint32_t
) : SocketAddress(family, port) {
    private val ip = ByteArray(16) {
        0.toByte()
//        rawAddress.__u6_addr.__u6_addr8[it].toByte()
    }

    override fun nativeAddress(block: (address: CPointer<sockaddr>, size: socklen_t) -> Unit) {
        cValue<sockaddr_in6> {
            sin6_family = family

            ip.forEachIndexed { index, byte ->
//                sin6_addr.__u6_addr.__u6_addr8[index] = byte.convert()
            }

            sin6_flowinfo = flowInfo
            sin6_port = port.convert()
            sin6_scope_id = scopeId

            block(ptr.reinterpret(), sockaddr_in6.size.convert())
        }
    }

    override val address: String
        get() {
            val address = ByteArray(INET6_ADDRSTRLEN)
            address.usePinned { addressBuf ->
                ktor_inet_ntop(AF_INET, rawAddress.ptr, addressBuf.addressOf(0), address.size.convert())
            }
            return address.toKString()
        }
}
