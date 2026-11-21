package com.dacweb.player.net

import android.content.Context
import org.json.JSONObject

/** 一台网络文件服务器的连接参数。type: smb / ftp / sftp / dav（WebDAV）。 */
data class NetServer(
    val type: String,
    val host: String,
    val port: Int,
    val user: String,
    val pass: String,
    val startPath: String
) {
    val scheme: String get() = "dacnet-$type"

    val label: String
        get() = "${type.uppercase()} · $host" + if (port > 0) ":$port" else ""
}

/**
 * 把用户手输的路径规整成「相对根、正斜杠、无首尾斜杠」的形式。
 * 兼容 "/flac/1.1"、"flac\1.1"、整个 smb://host/flac 粘进来这几种写法。
 */
fun normalizePath(raw: String): String {
    var s = raw.trim().replace('\\', '/')
    if (s.contains("://")) {
        val i = s.indexOf("://")
        val slash = s.indexOf('/', i + 3)
        s = if (slash >= 0) s.substring(slash) else ""
    }
    return s.trim('/')
}

/** 服务器上的一个条目（只读文件名/大小/是否目录，不请求任何元数据）。 */
data class NetEntry(val name: String, val dir: Boolean, val size: Long, val path: String)

/** 远程文件的顺序/随机读取器。 */
interface NetReader : AutoCloseable {
    val size: Long
    /** 从 offset 开始读，返回实际读到的字节数，-1 = 到末尾。 */
    fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int
}

/** 一个服务器会话：列目录 + 打开文件。 */
interface NetClient : AutoCloseable {
    fun list(path: String): List<NetEntry>
    fun open(path: String): NetReader
}

/** 当前会话（同一时刻只连一台服务器）+ 连接参数持久化。 */
object NetStore {

    private const val PREF = "net_server"
    private const val KEY = "last"
    private const val KEY_KNOWN = "known"

    @Volatile
    var current: NetServer? = null
        private set

    @Volatile
    private var client: NetClient? = null

    private val lock = Any()

    fun load(ctx: Context): NetServer? {
        current?.let { return it }
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null) ?: return null
        val s = runCatching {
            val j = JSONObject(raw)
            NetServer(
                type = j.getString("type"),
                host = j.getString("host"),
                port = j.optInt("port", 0),
                user = j.optString("user"),
                pass = j.optString("pass"),
                startPath = j.optString("path")
            )
        }.getOrNull() ?: return null
        current = s
        return s
    }

    private fun save(ctx: Context, s: NetServer) {
        val j = JSONObject()
            .put("type", s.type).put("host", s.host).put("port", s.port)
            .put("user", s.user).put("pass", s.pass).put("path", s.startPath)
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, j.toString()).apply()
    }

    /** 记住这台服务器（同一 host 只留最新一份），下次自动发现选中时就能直接登录 */
    fun remember(ctx: Context, s: NetServer) {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val map = runCatching { JSONObject(sp.getString(KEY_KNOWN, "{}")) }.getOrDefault(JSONObject())
        map.put(s.host, toJson(s))
        sp.edit().putString(KEY_KNOWN, map.toString()).apply()
    }

    /** 拿这台主机上次成功的登录参数；没连过返回 null */
    fun knownFor(ctx: Context, host: String): NetServer? {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val map = runCatching { JSONObject(sp.getString(KEY_KNOWN, "{}")) }.getOrDefault(JSONObject())
        val raw = map.optJSONObject(host) ?: return null
        return runCatching { fromJson(raw) }.getOrNull()
    }

    private fun toJson(s: NetServer): JSONObject = JSONObject()
        .put("type", s.type).put("host", s.host).put("port", s.port)
        .put("user", s.user).put("pass", s.pass).put("path", s.startPath)

    private fun fromJson(j: JSONObject): NetServer = NetServer(
        type = j.getString("type"),
        host = j.getString("host"),
        port = j.optInt("port", 0),
        user = j.optString("user"),
        pass = j.optString("pass"),
        startPath = j.optString("path")
    )

    /** 建立（或复用）会话；参数变化或上次失败时重建。 */
    fun connect(ctx: Context, s: NetServer): NetClient = synchronized(lock) {
        if (current == s && client != null) return client!!
        client?.let { runCatching { it.close() } }
        val c = create(s)
        client = c
        current = s
        save(ctx, s)
        remember(ctx, s)
        c
    }

    fun client(): NetClient? = client

    fun close() {
        synchronized(lock) {
            client?.let { runCatching { it.close() } }
            client = null
        }
    }

    fun create(s: NetServer): NetClient = when (s.type) {
        "smb" -> SmbNet(s)
        "ftp" -> FtpNet(s)
        "sftp" -> SftpNet(s)
        else -> DavNet(s)
    }

    fun uriFor(s: NetServer, path: String): android.net.Uri =
        android.net.Uri.Builder().scheme(s.scheme).authority(s.host).path(path).build()

    /** 顺序读取的通用实现：按 offset 重新定位，顺序读时复用同一个流。 */
    fun sequentialReader(size: Long, open: (Long) -> java.io.InputStream): NetReader =
        object : NetReader {
            private var stream: java.io.InputStream? = null
            private var pos = 0L
            private var lastOff = -1L

            override val size: Long get() = size

            override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
                if (stream == null || offset != pos || offset < lastOff) {
                    stream?.let { runCatching { it.close() } }
                    stream = open(offset)
                    pos = offset
                }
                lastOff = offset
                val n = stream!!.read(buf, off, len)
                if (n > 0) pos += n
                return n
            }

            override fun close() {
                stream?.let { runCatching { it.close() } }
                stream = null
            }
        }

    fun join(dir: String, name: String): String {
        val d = if (dir.endsWith("/")) dir else "$dir/"
        return d + name
    }
}
