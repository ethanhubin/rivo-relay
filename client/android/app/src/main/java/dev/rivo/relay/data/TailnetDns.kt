package dev.rivo.relay.data

import okhttp3.Dns
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.UnknownHostException
import java.security.SecureRandom

/** OS DNS first; only tailnet names may use Quad100 or a bounded last-known answer.
 * HTTPS still uses the original hostname/SNI and normal certificate verification.
 */
class TailnetDns(
    private val system: Dns = Dns.SYSTEM,
    private val magic: Dns = MagicDns(),
    private val clock: () -> Long = System::nanoTime,
) : Dns {
    private data class Answer(val addresses: List<InetAddress>, val at: Long)
    private val remembered = linkedMapOf<String, Answer>()
    override fun lookup(hostname: String): List<InetAddress> {
        val host = hostname.lowercase().trimEnd('.')
        if (!host.endsWith(".ts.net")) return system.lookup(hostname)
        fun remember(addresses: List<InetAddress>): List<InetAddress> {
            if (addresses.isEmpty()) throw UnknownHostException(host)
            synchronized(remembered) {
                remembered.remove(host)
                remembered[host] = Answer(addresses.toList(), clock())
                while (remembered.size > 32) remembered.remove(remembered.keys.first())
            }
            return addresses
        }
        try { return remember(system.lookup(host)) } catch (_: UnknownHostException) { }
        try { return remember(magic.lookup(host)) } catch (_: UnknownHostException) { }
        synchronized(remembered) {
            remembered[host]?.takeIf { clock() - it.at in 0..600_000_000_000L }?.let { return it.addresses.toList() }
            remembered.remove(host)
        }
        throw UnknownHostException(host)
    }
}

/** Direct queries to the VPN's own resolver; never sends tailnet names to public DNS. */
class MagicDns(private val resolver: InetSocketAddress = InetSocketAddress(InetAddress.getByAddress(byteArrayOf(100, 100, 100, 100)), 53)) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val host = hostname.lowercase().trimEnd('.')
        if (!host.endsWith(".ts.net")) throw UnknownHostException(host)
        try {
            for (type in listOf(1, 28)) {
                val id = random.nextInt(65536)
                val query = query(host, id, type)
                val answer = DatagramSocket().use { socket ->
                    socket.soTimeout = 1500
                    socket.connect(resolver)
                    socket.send(DatagramPacket(query, query.size))
                    val packet = DatagramPacket(ByteArray(4096), 4096)
                    socket.receive(packet)
                    parse(packet.data.copyOf(packet.length), query, host, id, type)
                }
                if (answer.isNotEmpty()) return answer
            }
        } catch (_: Exception) { /* expose only a DNS category, not raw packet/endpoint data */ }
        throw UnknownHostException(host)
    }
    companion object {
        private val random = SecureRandom()
        internal fun query(host: String, id: Int, type: Int): ByteArray {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { out ->
                out.writeShort(id); out.writeShort(0x0100); out.writeShort(1)
                repeat(3) { out.writeShort(0) }
                for (label in host.split('.')) {
                    require(label.length in 1..63 && label.all { it.code in 33..126 })
                    out.writeByte(label.length); out.writeBytes(label)
                }
                out.writeByte(0); out.writeShort(type); out.writeShort(1)
            }
            return bytes.toByteArray().also { require(it.size <= 271) }
        }
        internal fun parse(data: ByteArray, query: ByteArray, host: String, id: Int, type: Int): List<InetAddress> {
            fun u16(p: Int): Int { require(p >= 0 && p + 1 < data.size); return ((data[p].toInt() and 255) shl 8) or (data[p + 1].toInt() and 255) }
            require(data.size >= query.size && u16(0) == id)
            val flags = u16(2)
            require(flags and 0x8000 != 0 && flags and 0x7A0F == 0 && u16(4) == 1)
            require(data.copyOfRange(12, query.size).contentEquals(query.copyOfRange(12, query.size)))
            require(u16(6) <= 64)
            fun name(start: Int): Pair<String, Int> {
                var p = start; var end = -1
                val labels = mutableListOf<String>()
                repeat(128) {
                    require(p in data.indices)
                    val n = data[p].toInt() and 255
                    when {
                        n == 0 -> return labels.joinToString(".").lowercase() to (if (end >= 0) end else p + 1)
                        n and 0xC0 == 0xC0 -> { val target = u16(p) and 0x3FFF; require(target < p); if (end < 0) end = p + 2; p = target }
                        else -> { require(n in 1..63 && p + n < data.size); labels += String(data, p + 1, n, Charsets.US_ASCII); p += n + 1 }
                    }
                }
                error("Invalid DNS name")
            }
            var p = query.size
            val result = mutableListOf<InetAddress>()
            repeat(u16(6)) {
                val (owner, next) = name(p); p = next
                val rrType = u16(p); val rrClass = u16(p + 2); val size = u16(p + 8); p += 10
                require(p + size <= data.size)
                if (owner == host && rrType == type && rrClass == 1 && size == if (type == 1) 4 else 16) {
                    result += InetAddress.getByAddress(host, data.copyOfRange(p, p + size))
                }
                p += size
            }
            return result
        }
    }
}
