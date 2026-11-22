package com.dacweb.player.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.dacweb.player.R
import com.dacweb.player.cache.MediaCache
import com.dacweb.player.prefs.AppPrefs
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var exo: ExoPlayer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        /**
         * 播放缓冲的硬上限：32 MiB。24bit/96k FLAC 约 3 Mbps，32 MiB ≈ 90 秒，
         * 局域网读一整首的前几十 MB 也就一两秒，够用且给 192 MB 堆留出余量。
         */
        const val MAX_BUFFER_BYTES = 32 * 1024 * 1024

        const val ACTION_STOP = "com.dacweb.player.action.STOP"

        /**
         * 通知栏的「上一首 / 下一首」用自定义会话命令：
         * ExoPlayer 里永远只有一条 mediaItem（换曲由 PlayQueue 负责），
         * 所以播放器原生没有 SEEK_TO_PREVIOUS/NEXT，通知栏默认也就只显示播放/暂停。
         */
        const val CMD_PREV = "com.dacweb.player.cmd.PREV"
        const val CMD_NEXT = "com.dacweb.player.cmd.NEXT"

        /** 退出应用（大屏模式）时收掉播放服务，连缓存一起释放。 */
        fun stop(ctx: Context) {
            runCatching {
                ctx.startService(Intent(ctx, PlaybackService::class.java).setAction(ACTION_STOP))
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 播放数据过一层 SimpleCache：同一首歌的回跳/重播、以及「下载」时已下过的片段
        // 都不用再走网络。缓存只在本次使用期间有效——服务销毁、彻底退出时清空，
        // 不在设备上长期留几十上百 MB（见 onDestroy / purgeOnStop）。
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
            // 切后台 / 熄屏时由 ExoPlayer 自己持 CPU 与 Wi-Fi 锁：
            // 之前没有锁，一退出前台 CPU 就被降频、Wi-Fi 进省电，网络曲目立刻断续。
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        exo = player

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

        val session = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
            .build()

        // 通知栏三键：上一首 / 播放暂停（media3 自带）/ 下一首
        runCatching {
            session.setCustomLayout(
                listOf(
                    CommandButton.Builder()
                        .setSessionCommand(SessionCommand(CMD_PREV, Bundle.EMPTY))
                        .setDisplayName(getString(R.string.notif_prev))
                        .setIconResId(R.drawable.ic_skip_previous)
                        .build(),
                    CommandButton.Builder()
                        .setSessionCommand(SessionCommand(CMD_NEXT, Bundle.EMPTY))
                        .setDisplayName(getString(R.string.notif_next))
                        .setIconResId(R.drawable.ic_skip_next)
                        .build()
                )
            )
        }
        mediaSession = session

        // 通知渠道给个中文名，别在系统设置里显示 default_notification_channel_name
        runCatching {
            setMediaNotificationProvider(
                DefaultMediaNotificationProvider.Builder(this)
                    .setChannelId("dac_playback")
                    .setChannelName(R.string.notif_channel)
                    .build()
            )
        }

        // DAC 独占直通跟着播放器走（换曲/暂停/播放都在这里同步，切后台也能继续）。
        // 未开独占时 DacRoute.sync 直接返回，几乎没有开销。
        scope.launch {
            while (isActive) {
                runCatching { DacRoute.sync(this@PlaybackService, mediaSession?.player) }
                delay(500)
            }
        }
    }

    private inner class SessionCallback : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            // 把上一首/下一首加进这个控制器可用的会话命令里，
            // 否则通知栏那两个自定义按钮会被判定为「不可用」而不显示。
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .add(SessionCommand(CMD_PREV, Bundle.EMPTY))
                .add(SessionCommand(CMD_NEXT, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            val p = session.player
            when (customCommand.customAction) {
                CMD_PREV -> scope.launch {
                    runCatching { PlayQueue.prev(this@PlaybackService, p) }
                        .onSuccess { if (!it) p.seekTo(0) }
                }

                CMD_NEXT -> scope.launch {
                    runCatching { PlayQueue.next(this@PlaybackService, p) }
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // 大屏模式退出应用时用：连播放一起收掉，不留后台
            runCatching { exo?.pause() }
            stopSelf()
            return START_NOT_STICKY
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        // 大屏（电视/盒子/平板）：退出应用就彻底关掉，不留后台
        if (AppPrefs.largeScreen(this)) {
            runCatching { player?.pause() }
            stopSelf()
            return
        }
        // 手机：正在播就继续留在后台（通知栏可控），没在播才收掉服务
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
        exo = null
        // 本次使用结束：把音频缓冲清掉，不在设备上留着
        runCatching { MediaCache.purge(this) }
        super.onDestroy()
    }

}
