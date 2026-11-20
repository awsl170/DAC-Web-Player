package com.dacweb.player.audio

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

/**
 * 全局持有一个 MediaController，MainActivity 与 PlayerActivity 共用。
 * Service 自身负责后台播放与通知栏。
 */
object PlayerHub {

    private var future: ListenableFuture<MediaController>? = null

    var controller: MediaController? = null
        private set

    private val pending = mutableListOf<(MediaController) -> Unit>()

    fun connect(ctx: Context, onReady: (MediaController) -> Unit) {
        controller?.let { onReady(it); return }
        pending += onReady
        if (future != null) return

        val appCtx = ctx.applicationContext
        val token = SessionToken(appCtx, ComponentName(appCtx, PlaybackService::class.java))
        val f = MediaController.Builder(appCtx, token).buildAsync()
        future = f
        f.addListener({
            val c = runCatching { f.get() }.getOrNull()
            controller = c
            if (c != null) {
                val callbacks = pending.toList()
                pending.clear()
                callbacks.forEach { it(c) }
            }
        }, ContextCompat.getMainExecutor(appCtx))
    }

    fun release() {
        controller?.release()
        controller = null
        future = null
    }
}
