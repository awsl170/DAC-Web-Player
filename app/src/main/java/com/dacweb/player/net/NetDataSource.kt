package com.dacweb.player.net

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException

/**
 * 把 dacnet-smb/ftp/sftp/dav 这些自定义 scheme 接到 [NetStore] 的会话上，
 * 其余 scheme 原样交给上层（MediaCache → http/file/content），所以它包在整条链的最外层。
 */
class NetDataSource(private val upstreamFactory: DataSource.Factory) : BaseDataSource(true) {

    private var upstream: DataSource? = null
    private var reader: NetReader? = null
    private var uriStr: String? = null
    private var pos = 0L

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uriStr = dataSpec.uri.toString()
        pos = dataSpec.position
        val scheme = dataSpec.uri.scheme.orEmpty()
        if (scheme.startsWith("dacnet-")) {
            val client = NetStore.client() ?: throw IOException("未连接文件服务器")
            val path = dataSpec.uri.path.orEmpty().ifEmpty { "/" }
            val r = try {
                client.open(path)
            } catch (e: Exception) {
                throw IOException(e)
            }
            reader = r
            transferStarted(dataSpec)
            return if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length
            else (r.size - pos).coerceAtLeast(0L)
        }
        val d = upstreamFactory.createDataSource()
        upstream = d
        val len = d.open(dataSpec)
        transferStarted(dataSpec)
        return len
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        reader?.let { r ->
            val n = try {
                r.readAt(pos, buffer, offset, length)
            } catch (e: Exception) {
                throw IOException(e)
            }
            if (n > 0) {
                pos += n
                bytesTransferred(n)
            }
            return if (n < 0) C.RESULT_END_OF_INPUT else n
        }
        val d = upstream ?: return C.RESULT_END_OF_INPUT
        val n = d.read(buffer, offset, length)
        if (n > 0) bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uriStr?.let { Uri.parse(it) }

    override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()

    override fun close() {
        val r = reader
        reader = null
        r?.let {
            runCatching { it.close() }
            transferEnded()
        }
        val d = upstream
        upstream = null
        runCatching { d?.close() }
        uriStr = null
    }

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = NetDataSource(upstream)
    }
}
