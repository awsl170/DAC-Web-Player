package com.dacweb.player.net

import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import java.util.Properties

/** SMB / SMB2 / SMB3（jcifs-ng）。 */
class SmbNet(private val s: NetServer) : NetClient {

    private val ctx: CIFSContext = run {
        val p = Properties()
        p["jcifs.smb.client.minVersion"] = "SMB202"
        p["jcifs.smb.client.maxVersion"] = "SMB311"
        p["jcifs.smb.client.dfs.disabled"] = "true"
        val base = BaseContext(PropertyConfiguration(p))
        base.withCredentials(
            NtlmPasswordAuthenticator(
                null,
                s.user.ifBlank { null },
                s.pass.ifBlank { null }
            )
        )
    }

    private fun baseUrl(): String {
        val portPart = if (s.port > 0 && s.port != 445) ":${s.port}" else ""
        return "smb://${s.host}$portPart/"
    }

    /** 目录 URL 必须以 "/" 结尾，否则 jcifs 会当成文件（报 "cannot find the file specified"）。 */
    private fun dir(path: String): SmbFile {
        val p = normalizePath(path)
        return SmbFile(if (p.isEmpty()) baseUrl() else baseUrl() + p + "/", ctx)
    }

    private fun file(path: String): SmbFile {
        val p = normalizePath(path)
        return SmbFile(baseUrl() + p, ctx)
    }

    override fun list(path: String): List<NetEntry> {
        val parent = normalizePath(path)
        val children = dir(path).listFiles()
        return children.mapNotNull { c ->
            val n = c.name.trimEnd('/')
            if (n.isEmpty() || n == "." || n == "..") return@mapNotNull null
            // 管理共享（C$、ADMIN$ …）和隐藏项不显示
            if (n.startsWith(".") || n.endsWith("$")) return@mapNotNull null
            val isDir = runCatching { c.isDirectory }.getOrDefault(false)
            if (!isDir && runCatching { c.length() }.isFailure) return@mapNotNull null
            val size = if (isDir) 0L else runCatching { c.length() }.getOrDefault(0L)
            NetEntry(n, isDir, size, if (parent.isEmpty()) n else "$parent/$n")
        }
    }

    override fun open(path: String): NetReader {
        val raf = SmbRandomAccessFile(file(path), "r")
        val len = runCatching { raf.length() }.getOrDefault(0L)
        return object : NetReader {
            override val size: Long get() = len
            override fun readAt(offset: Long, buf: ByteArray, off: Int, len2: Int): Int {
                raf.seek(offset)
                return raf.read(buf, off, len2)
            }
            override fun close() {
                runCatching { raf.close() }
            }
        }
    }

    override fun close() {}
}
