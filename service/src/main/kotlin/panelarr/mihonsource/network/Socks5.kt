// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.network

import okhttp3.Dns
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

/**
 * SOCKS5 with username/password (RFC 1928 and RFC 1929), done per client.
 *
 * The JDK's own SOCKS support only takes credentials from the JVM-wide Authenticator,
 * which would mix up sources with different proxies. So for an authenticated SOCKS5
 * proxy the client connects "directly" through [SocketFactory], whose sockets speak
 * SOCKS5 to the proxy themselves, and [Dns] hands out placeholder addresses that carry
 * the host name, so the proxy resolves names (no DNS lookups leak from this host).
 */
internal object Socks5 {
    private const val VERSION: Byte = 5
    private const val AUTH_VERSION: Byte = 1
    private const val METHOD_NONE: Byte = 0
    private const val METHOD_PASSWORD: Byte = 2
    private const val METHOD_REJECTED: Byte = 0xFF.toByte()
    private const val CMD_CONNECT: Byte = 1
    private const val ATYP_IPV4: Int = 1
    private const val ATYP_DOMAIN: Int = 3
    private const val ATYP_IPV6: Int = 4
    private const val IPV4_BYTES = 4
    private const val IPV6_BYTES = 16
    private const val PORT_BYTES = 2
    private const val MAX_FIELD = 255
    private const val BYTE_MASK = 0xFF
    private const val BITS_PER_BYTE = 8

    /** Whether [proxy] needs our own SOCKS5 handling. */
    fun handles(proxy: EffectiveProfile.Proxy?): Boolean =
        proxy != null && proxy.type == java.net.Proxy.Type.SOCKS && proxy.username != null

    /** Placeholder address that keeps the host name, so the proxy can resolve it. */
    fun placeholder(host: String): InetAddress = InetAddress.getByAddress(host, byteArrayOf(0, 0, 0, 0))

    class SocketFactory(
        private val proxy: () -> EffectiveProfile.Proxy?,
    ) : javax.net.SocketFactory() {
        override fun createSocket(): Socket = proxy()?.takeIf(::handles)?.let(::Socks5Socket) ?: Socket()

        override fun createSocket(
            host: String,
            port: Int,
        ): Socket = createSocket().apply { connect(InetSocketAddress(host, port)) }

        override fun createSocket(
            host: String,
            port: Int,
            localHost: InetAddress,
            localPort: Int,
        ): Socket = createSocket(host, port)

        override fun createSocket(
            host: InetAddress,
            port: Int,
        ): Socket = createSocket().apply { connect(InetSocketAddress(host, port)) }

        override fun createSocket(
            address: InetAddress,
            port: Int,
            localAddress: InetAddress,
            localPort: Int,
        ): Socket = createSocket(address, port)
    }

    class ProxyDns(
        private val proxy: () -> EffectiveProfile.Proxy?,
    ) : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            if (handles(proxy())) listOf(placeholder(hostname)) else Dns.SYSTEM.lookup(hostname)
    }

    /** A socket that, on connect, opens a SOCKS5 tunnel to the endpoint through [proxy]. */
    class Socks5Socket(
        private val proxy: EffectiveProfile.Proxy,
    ) : Socket() {
        override fun connect(
            endpoint: SocketAddress,
            timeout: Int,
        ) {
            val target = endpoint as? InetSocketAddress ?: throw IOException("unsupported address $endpoint")
            super.connect(InetSocketAddress(proxy.host, proxy.port), timeout)
            val previous = soTimeout
            if (timeout > 0) soTimeout = timeout
            try {
                handshake(target)
            } catch (e: IOException) {
                close()
                throw e
            } finally {
                soTimeout = previous
            }
        }

        override fun connect(endpoint: SocketAddress) = connect(endpoint, 0)

        private fun handshake(target: InetSocketAddress) {
            val output = getOutputStream()
            val input = DataInputStream(getInputStream())
            negotiate(input, output)
            connectTo(target, input, output)
        }

        private fun negotiate(
            input: DataInputStream,
            output: java.io.OutputStream,
        ) {
            output.write(byteArrayOf(VERSION, 2, METHOD_NONE, METHOD_PASSWORD))
            output.flush()
            if (input.readByte() != VERSION) throw IOException("the SOCKS proxy doesn't speak SOCKS5")
            when (input.readByte()) {
                METHOD_NONE -> Unit
                METHOD_PASSWORD -> authenticate(input, output)
                METHOD_REJECTED -> throw IOException("the SOCKS proxy accepts none of the offered authentication methods")
                else -> throw IOException("the SOCKS proxy chose an unknown authentication method")
            }
        }

        private fun connectTo(
            target: InetSocketAddress,
            input: DataInputStream,
            output: java.io.OutputStream,
        ) {
            // The host name, not an address, so the proxy resolves it.
            val host = target.hostString
            val name = host.toByteArray()
            if (name.size > MAX_FIELD) throw IOException("host name too long for SOCKS5")
            output.write(byteArrayOf(VERSION, CMD_CONNECT, 0, ATYP_DOMAIN.toByte(), name.size.toByte()) + name + port(target.port))
            output.flush()
            if (input.readByte() != VERSION) throw IOException("bad SOCKS5 reply")
            val reply = input.readByte().toInt() and BYTE_MASK
            if (reply != 0) throw IOException("the SOCKS proxy refused the connection to $host:${target.port} (code $reply)")
            input.readByte() // reserved
            when (input.readByte().toInt()) {
                ATYP_IPV4 -> input.skipNBytes(IPV4_BYTES.toLong())
                ATYP_IPV6 -> input.skipNBytes(IPV6_BYTES.toLong())
                ATYP_DOMAIN -> input.skipNBytes((input.readByte().toInt() and BYTE_MASK).toLong())
                else -> throw IOException("bad SOCKS5 reply address")
            }
            input.skipNBytes(PORT_BYTES.toLong())
        }

        private fun authenticate(
            input: DataInputStream,
            output: java.io.OutputStream,
        ) {
            val user = proxy.username.orEmpty().toByteArray()
            val password = proxy.password.orEmpty().toByteArray()
            if (user.size > MAX_FIELD || password.size > MAX_FIELD) throw IOException("SOCKS5 credentials too long")
            output.write(byteArrayOf(AUTH_VERSION, user.size.toByte()) + user + byteArrayOf(password.size.toByte()) + password)
            output.flush()
            input.readByte()
            if (input.readByte() != 0.toByte()) throw IOException("the SOCKS proxy rejected the username or password")
        }

        private fun port(port: Int) = byteArrayOf((port shr BITS_PER_BYTE).toByte(), (port and BYTE_MASK).toByte())
    }
}
