package com.dacweb.player.net

import android.util.Xml
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/** WebDAV（标准 PROPFIND 列目录 + Range 读取，不引入额外库）。 */
class DavNet(private val s: NetServer) : NetClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(java.time.Duration.ofSeconds(8))
        .readTimeout(java.time.Duration.ofSeconds(30))
        .build()

    private val auth = Credentials.basic(s.user, s.pass)

    private fun url(path: String): String {
        val p = if (path.isBlank()) "/" else path
        val portPart = if (s.port > 0 && s.port != 80 && s.port != 443) ":$s.port" else ""
        return "http://${s.host}$portPart${if (p.startsWith("/")) p else "/$p"}"
    }

    private fun propfind(path: String, depth: String): String {
        val body = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:displayname/></d:prop></d:propfind>"""
        val req = Request.Builder()
            .url(url(path))
            .method("PROPFIND", body.toRequestBody(null))
            .header("Depth", depth)
            .header("Authorization", auth)
            .header("Content-Type", "application/xml; charset=utf-8")
            .build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("WebDAV ${r.code} ${r.message}")
            return r.body?.string().orEmpty()
        }
    }

    override fun list(path: String): List<NetEntry> {
        val base = if (path.isBlank()) "/" else path.trimEnd('/').ifEmpty { "/" }
        val xml = propfind(base, "1")
        val p = Xml.newPullParser().apply { setInput(java.io.StringReader(xml)) }
        val out = mutableListOf<NetEntry>()
        var href: String? = null
        var isDir = false
        var len = 0L
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (p.name) {
                    "response" -> { href = null; isDir = false; len = 0L }
                    "href" -> href = p.nextText()
                    "collection" -> isDir = true
                    "getcontentlength" -> len = p.nextText().trim().toLongOrNull() ?: 0L
                }
            } else if (event == XmlPullParser.END_TAG && p.name == "response") {
                val h = href?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                if (h != null) {
                    val clean = h.trimEnd('/')
                    val own = base.trimEnd('/')
                    if (clean.isNotEmpty() && clean != own && !clean.endsWith(own)) {
                        val name = clean.substringAfterLast('/')
                        if (name.isNotEmpty() && name != "." && name != "..") {
                            out += NetEntry(name, isDir, len, NetStore.join(base, name))
                        }
                    }
                }
            }
            event = p.next()
        }
        return out
    }

    override fun open(path: String): NetReader {
        val head = Request.Builder().url(url(path)).head().header("Authorization", auth).build()
        val total = runCatching {
            http.newCall(head).execute().use { r ->
                if (!r.isSuccessful) 0L else r.header("Content-Length")?.toLongOrNull() ?: 0L
            }
        }.getOrDefault(0L)

        return NetStore.sequentialReader(total) { offset ->
            val req = Request.Builder().url(url(path))
                .header("Authorization", auth)
                .apply { if (offset > 0) header("Range", "bytes=$offset-") }
                .build()
            val resp = http.newCall(req).execute()
            if (!resp.isSuccessful && resp.code != 206) {
                resp.close()
                throw java.io.IOException("WebDAV ${resp.code}")
            }
            (resp.body?.byteStream() ?: InputStream.nullInputStream()) as InputStream
        }
    }

    override fun close() {}
}
