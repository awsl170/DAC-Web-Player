package com.dacweb.player.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.dacweb.player.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * 局域网 SMB 服务器发现（电视上没有键盘可输入 IP，得能自己找）。
 *
 * 三个来源，结果按 IP 去重：
 *  1. **扫 445 端口**：本机所在 /24 里逐个 TCP 连 445，最可靠——不依赖任何名字服务，
 *     Windows 共享、群晖、路由器 USB 共享都能命中。
 *  2. **mDNS `_smb._tcp`**：群晖/树莓派/Samba 会广播，能顺带拿到名字。
 *  3. **NetBIOS NBSTAT 广播**：Windows 会回自己的计算机名，拿到名字让列表更好认。
 *
 * 扫端口是并发 32 路、单机 250 ms 超时，整个发现过程 ~2-4 秒。
 */
object SmbDiscovery {

    private const val TAG = "SmbScan"

    data class Found(val host: String, val name: String, val via: String) {
        /** 列表里显示的主标题 */
        val title: String get() = if (name.isNotBlank()) name else host
    }

    suspend fun discover(ctx: Context, timeoutMs: Long = 3500): List<Found> =
        withContext(Dispatchers.IO) {
            val found = ConcurrentHashMap<String, Found>()
            val subnets = localSubnets()
            AppLog.i(TAG, "start: subnets=${subnets.joinToString()}")

            coroutineScope {
                subnets.forEach { prefix -> launch { scanTcp(prefix, found) } }
                launch { mdns(ctx, found, timeoutMs) }
            }
            // 名字放在端口扫描之后：只对已确认开着 445 的主机发 NetBIOS 查询
            names(found, 800)
            AppLog.i(TAG, "done: ${found.keys.joinToString()}")
            found.values.sortedWith(compareBy({ it.name.isBlank() }, { it.host }))
        }

    /** 本机所在网段（只取 IPv4 的 /24 前缀），例如 ["192.168.101"]。 */
    private fun localSubnets(): List<String> {
        val out = LinkedHashSet<String>()
        runCatching {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (addr !is Inet4Address) continue
                    val ip = addr.address.map { it.toInt() and 0xFF }
                    if (ip[0] == 169 && ip[1] == 254) continue      // link-local
                    if (ip[0] == 127) continue
                    out.add("${ip[0]}.${ip[1]}.${ip[2]}")
                }
            }
        }
        return out.toList().take(3)
    }

    /** 并发扫 /24 的 445 端口。 */
    private suspend fun scanTcp(prefix: String, found: ConcurrentHashMap<String, Found>) {
        val sem = kotlinx.coroutines.sync.Semaphore(32)
        coroutineScope {
            (1..254).map { last ->
                async(Dispatchers.IO) {
                    sem.acquire()
                    try {
                        val host = "$prefix.$last"
                        if (portOpen(host, 445, 240)) {
                            found.putIfAbsent(host, Found(host, "", "smb"))
                        }
                    } finally {
                        sem.release()
                    }
                }
            }.awaitAll()
        }
    }

    private fun portOpen(host: String, port: Int, timeoutMs: Int): Boolean = runCatching {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), timeoutMs)
            true
        }
    }.getOrDefault(false)

    /** mDNS：_smb._tcp 的服务名就是设备名，能当显示名用。 */
    private suspend fun mdns(ctx: Context, found: ConcurrentHashMap<String, Found>, timeoutMs: Long) {
        val lock = runCatching {
            (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                ?.createMulticastLock("dac-smb-mdns")?.apply { setReferenceCounted(false); acquire() }
        }.getOrNull()
        try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<Unit> { cont ->
                    val nsd = ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: run {
                        cont.resume(Unit); return@suspendCancellableCoroutine
                    }
                    val listener = object : NsdManager.DiscoveryListener {
                        override fun onDiscoveryStarted(serviceType: String?) {}
                        override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                            runCatching { nsd.stopServiceDiscovery(this) }
                            if (cont.isActive) cont.resume(Unit)
                        }
                        override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
                        override fun onDiscoveryStopped(serviceType: String?) {
                            if (cont.isActive) cont.resume(Unit)
                        }
                        override fun onServiceLost(serviceInfo: NsdServiceInfo?) {}
                        override fun onServiceFound(info: NsdServiceInfo?) {
                            if (info == null) return
                            runCatching {
                                nsd.resolveService(info, object : NsdManager.ResolveListener {
                                    override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
                                    override fun onServiceResolved(r: NsdServiceInfo?) {
                                        val host = r?.host?.hostAddress ?: return
                                        val name = r.serviceName ?: ""
                                        found.putIfAbsent(host, Found(host, name, "mdns"))
                                    }
                                })
                            }
                        }
                    }
                    runCatching { nsd.discoverServices("_smb._tcp", NsdManager.PROTOCOL_DNS_SD, listener) }
                        .onFailure {
                            AppLog.w(TAG, "mdns failed: ${it.message}")
                            if (cont.isActive) cont.resume(Unit)
                        }
                    cont.invokeOnCancellation {
                        runCatching { nsd.stopServiceDiscovery(listener) }
                    }
                }
            }
        } finally {
            runCatching { lock?.release() }
        }
    }

    /** NetBIOS NBSTAT 查询：拿计算机名。失败就不显示名字，不影响可用性。 */
    private suspend fun names(found: ConcurrentHashMap<String, Found>, timeoutMs: Long) {
        val hosts = found.keys.toList()
        if (hosts.isEmpty()) return
        coroutineScope {
            hosts.map { host ->
                async(Dispatchers.IO) {
                    val name = nbName(host, timeoutMs) ?: return@async
                    found.computeIfPresent(host) { _, old ->
                        if (old.name.isBlank()) old.copy(name = name) else old
                    }
                }
            }.awaitAll()
        }
    }

    private fun nbName(host: String, timeoutMs: Long): String? = runCatching {
        val pkt = nbstatQuery()
        DatagramSocket().use { s ->
            s.soTimeout = timeoutMs.toInt()
            s.send(DatagramPacket(pkt, pkt.size, InetAddress.getByName(host), 137))
            val buf = ByteArray(2048)
            val resp = DatagramPacket(buf, buf.size)
            s.receive(resp)
            parseNbstat(buf, resp.length)
        }
    }.getOrNull()

    /** NBSTAT 请求：通配名 "*"，qtype 0x0021（NBSTAT），qclass IN。 */
    private fun nbstatQuery(): ByteArray {
        val name = ByteArray(16).also { it[0] = 0x2A.toByte() }
        val enc = ByteArray(32)
        for (i in 0 until 16) {
            val b = name[i].toInt() and 0xFF
            enc[i * 2] = ('A'.code + (b shr 4)).toByte()
            enc[i * 2 + 1] = ('A'.code + (b and 0x0F)).toByte()
        }
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x12, 0x34, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
        out.write(32)
        out.write(enc)
        out.write(0)
        out.write(byteArrayOf(0x00, 0x21, 0x00, 0x01))
        return out.toByteArray()
    }

    private fun parseNbstat(data: ByteArray, len: Int): String? {
        if (len < 12) return null
        var off = 12
        val qd = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
        val an = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
        repeat(qd) {
            while (off < len && data[off].toInt() != 0) off += (data[off].toInt() and 0xFF) + 1
            off += 5
        }
        repeat(an) {
            if (off >= len) return null
            off += if ((data[off].toInt() and 0xC0) == 0xC0) 2 else 34
            if (off + 10 > len) return null
            val rdLen = ((data[off + 8].toInt() and 0xFF) shl 8) or (data[off + 9].toInt() and 0xFF)
            off += 10
            if (off + rdLen > len) return null
            val count = data[off].toInt() and 0xFF
            var p = off + 1
            for (i in 0 until count) {
                if (p + 18 > off + rdLen) break
                val raw = String(data, p, 15, Charsets.US_ASCII).trim('\u0000', ' ')
                val suffix = data[p + 15].toInt() and 0xFF
                val flags = ((data[p + 16].toInt() and 0xFF) shl 8) or (data[p + 17].toInt() and 0xFF)
                p += 18
                // 0x00 = 工作站名，0x20 = 文件服务器名；flags 的 0x8000 是"组名"
                // （不做这个过滤，Windows 会先回 WORKGROUP，列表里所有机器都叫 WORKGROUP）
                val isGroup = flags and 0x8000 != 0
                if (!isGroup && (suffix == 0x00 || suffix == 0x20) && raw.isNotBlank() &&
                    !raw.startsWith("__MSBROWSE")
                ) {
                    return raw
                }
            }
            off += rdLen
        }
        return null
    }
}
