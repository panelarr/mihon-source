// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.fixtures

import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A small SOCKS5 server that requires username/password (RFC 1929), records the host
 * names it's asked to connect to, and sends every connection to [target].
 */
class TestSocks5Server(
    private val username: String,
    private val password: String,
    private val target: InetSocketAddress,
) : AutoCloseable {
    private val server = ServerSocket(0)
    val port: Int get() = server.localPort
    val requestedHosts = CopyOnWriteArrayList<String>()
    val rejectedLogins = CopyOnWriteArrayList<String>()

    init {
        thread(isDaemon = true, name = "test-socks5") {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { serve(client) }.onFailure { client.close() } }
            }
        }
    }

    private fun serve(client: Socket) {
        val input = DataInputStream(client.getInputStream())
        val output = client.getOutputStream()
        check(input.readByte() == 5.toByte())
        val methods = ByteArray(input.readByte().toInt()).also(input::readFully)
        if (2.toByte() !in methods) {
            output.write(byteArrayOf(5, 0xFF.toByte()))
            client.close()
            return
        }
        output.write(byteArrayOf(5, 2))
        check(input.readByte() == 1.toByte())
        val user = String(ByteArray(input.readByte().toInt()).also(input::readFully))
        val pass = String(ByteArray(input.readByte().toInt() and 0xFF).also(input::readFully))
        if (user != username || pass != password) {
            rejectedLogins += user
            output.write(byteArrayOf(1, 1))
            client.close()
            return
        }
        output.write(byteArrayOf(1, 0))
        check(input.readByte() == 5.toByte() && input.readByte() == 1.toByte())
        input.readByte()
        check(input.readByte() == 3.toByte()) { "expected a host name, not an address" }
        val host = String(ByteArray(input.readByte().toInt() and 0xFF).also(input::readFully))
        input.readUnsignedShort()
        requestedHosts += host
        val upstream = Socket(target.address, target.port)
        output.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        output.flush()
        pump(input, upstream.getOutputStream(), upstream, client)
        pump(upstream.getInputStream(), output, upstream, client)
    }

    private fun pump(
        from: InputStream,
        to: OutputStream,
        vararg sockets: Socket,
    ) {
        thread(isDaemon = true) {
            runCatching { from.copyTo(to) }
            sockets.forEach { runCatching { it.close() } }
        }
    }

    override fun close() = server.close()
}
