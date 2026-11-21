package com.dacweb.player.audio

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.dacweb.player.cache.MediaCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private companion object {
        /**
         * 播放缓冲的硬上限：32 MiB。24bit/96k FLAC 约 3 Mbps，32 MiB ≈ 90 秒，
         * 局域网读一整首的前几十 MB 也就一两秒，够用且给 192 MB 堆留出余量。
         */
        const val MAX_BUFFER_BYTES = 32 * 1024 * 1024
    }

    override fun onCreate() {
        super.onCreate()
        // 播放数据先过 SimpleCache：边播边把字节写进磁盘缓存（LRU 1.5GB），
        // 之后回跳/重播同一首不用再走网络。
        //
        // 缓冲上限原来是「600 秒」——对 24bit/96k FLAC（~3 Mbps）就是 200 MB+，
        // 而进程堆只有 192 MB：网络快的时候 ExoPlayer 会把缓冲填满直到 OOM
        // (ExoPlayerImplInternal.maybeContinueLoading)。改成「2 分钟 + 48 MiB」双上限，
        // 并声明以字节上限优先。
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 90_000, 2_500, 5_000)
            .setTargetBufferBytes(MAX_BUFFER_BYTES)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()

        val player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    // 最外层：dacnet-smb/ftp/sftp/dav 走网络文件服务器，其余 scheme 原样往下传
                    com.dacweb.player.net.NetDataSource.Factory(MediaCache.switchingFactory(this))
                )
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        // 均衡器跟随 ExoPlayer 的音频会话
        Eq.attach(this, player.audioSessionId)
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                Eq.attach(this@PlaybackService, audioSessionId)
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state != Player.STATE_ENDED) return
                if (PlayQueue.size == 0) return
                scope.launch {
                    // ExoPlayer 只放单条 mediaItem，循环语义由宿主负责（见 PlayRepeat）
                    when (PlayRepeat.mode) {
                        Player.REPEAT_MODE_ONE -> {
                            player.seekTo(0)
                            player.play()
                        }
                        else -> runCatching { PlayQueue.next(this@PlaybackService, player) }
                    }
                }
            }
        })

        // 队列切歌由 PlayQueue 做，ExoPlayer 内部永远不循环（否则 ENDED 不再触发）
        player.repeatMode = Player.REPEAT_MODE_OFF
        mediaSession = MediaSession.Builder(this, player).build()

        // DAC 独占直通跟着播放器走（换曲/暂停/播放都在这里同步，切后台也能继续）。
        // 未开独占时 DacRoute.sync 直接返回，几乎没有开销。
        scope.launch {
            while (isActive) {
                runCatching { DacRoute.sync(this@PlaybackService, mediaSession?.player) }
                delay(500)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        runCatching { DacRoute.stop(this, mediaSession?.player) }
        Eq.release()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
