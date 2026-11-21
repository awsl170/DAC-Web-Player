package com.dacweb.player.net

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session

/** SFTP（mwiede 维护的 jsch 分支）。 */
class SftpNet(private val s: NetServer) : NetClient {

    private val session: Session = JSch().getSession(s.user, s.host, if (s.port > 0) s.port else 22).apply {
        setPassword(s.pass)
        setConfig("StrictHostKeyChecking", "no")
        timeout = 20000
        connect()
    }

    private val channel: ChannelSftp = (session.openChannel("sftp") as ChannelSftp).apply {
        connect(20000)
    }

    private val lock = Any()

    override fun list(path: String): List<NetEntry> = synchronized(lock) {
        val base = normalizePath(path).ifBlank { normalizePath(s.startPath).ifBlank { "." } }
        @Suppress("UNCHECKED_CAST")
        val vec = channel.ls(base) as java.util.Vector<ChannelSftp.LsEntry>
        vec.mapNotNull { e ->
            val n = e.filename
            if (n == "." || n == "..") null
            else NetEntry(
                n,
                e.attrs.isDir,
                if (e.attrs.isDir) 0L else e.attrs.size,
                NetStore.join(if (base == ".") "" else base, n)
            )
        }
    }

    override fun open(path: String): NetReader {
        val total = synchronized(lock) { runCatching { channel.stat(path).size }.getOrDefault(0L) }
        return NetStore.sequentialReader(total) { offset ->
            synchronized(lock) {
                val full = if (offset > 0) channel.get(path, null, offset) else channel.get(path)
                full
            }
        }
    }

    override fun close() {
        runCatching { channel.disconnect() }
        runCatching { session.disconnect() }
    }
}
