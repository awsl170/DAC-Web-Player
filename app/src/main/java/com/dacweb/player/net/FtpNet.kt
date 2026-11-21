package com.dacweb.player.net

import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply

/** FTP（Apache Commons Net）。同一时刻一条控制连接，读与列目录串行化。 */
class FtpNet(private val s: NetServer) : NetClient {

    private val ftp = FTPClient().apply {
        connectTimeout = 8000
        defaultTimeout = 20000
    }

    init {
        val port = if (s.port > 0) s.port else 21
        ftp.connect(s.host, port)
        if (!FTPReply.isPositiveCompletion(ftp.replyCode)) {
            runCatching { ftp.disconnect() }
            throw java.io.IOException("FTP 拒绝连接：${ftp.replyString.orEmpty()}")
        }
        if (!ftp.login(s.user.ifBlank { "anonymous" }, s.pass)) {
            runCatching { ftp.logout(); ftp.disconnect() }
            throw java.io.IOException("FTP 登录失败：${ftp.replyString.orEmpty()}")
        }
        ftp.enterLocalPassiveMode()
        ftp.setFileType(FTP.BINARY_FILE_TYPE)
        ftp.setDataTimeout(java.time.Duration.ofSeconds(20))
        if (s.startPath.isNotBlank()) runCatching { ftp.changeWorkingDirectory(s.startPath) }
    }

    private val lock = Any()

    override fun list(path: String): List<NetEntry> = synchronized(lock) {
        val base = normalizePath(path)
        ftp.listFiles(if (base.isEmpty()) "." else base).mapNotNull { f ->
            val n = f.name
            if (n == "." || n == "..") null
            else NetEntry(n, f.isDirectory, f.size, NetStore.join(base, n))
        }
    }

    override fun open(path: String): NetReader {
        val total: Long = synchronized(lock) {
            runCatching {
                val f = ftp.mlistFile(path)
                if (f == null || f.isDirectory) 0L else f.size
            }.getOrDefault(0L)
        }
        return NetStore.sequentialReader(total) { offset ->
            synchronized(lock) {
                ftp.restartOffset = offset
                ftp.retrieveFileStream(path) ?: throw java.io.IOException("无法打开文件：${ftp.replyString.orEmpty()}")
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            runCatching { ftp.logout() }
            runCatching { ftp.disconnect() }
        }
    }
}
