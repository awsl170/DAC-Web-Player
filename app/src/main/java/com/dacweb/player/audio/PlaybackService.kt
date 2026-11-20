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

    override fun onCreate() {
        super.onCreate()
        // 播放数据先过 SimpleCache：边播边把字节写进磁盘缓存（LRU 1.5GB），
        // 之后回跳/重播同一首不用再走网络。读缓冲也给大一点。
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 600_000, 2_500, 5_000)
            .build()

        val player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(MediaCache.switchingFactory(this)))
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
