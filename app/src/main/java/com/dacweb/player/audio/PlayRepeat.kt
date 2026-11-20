package com.dacweb.player.audio

import androidx.media3.common.Player

/**
 * 循环模式（由宿主自己实现，不交给 ExoPlayer）。
 *
 * ExoPlayer 里永远只放一个 mediaItem（换曲是整条替换），所以把
 * `REPEAT_MODE_ALL` 交给它只会变成"单曲循环"，播到结尾也不会发 ENDED，
 * 队列永远切不动。这里保存用户选择，真正切歌由 [PlayQueue.next] 完成：
 * - OFF：播完就停
 * - ALL：播完回到队列第一首
 * - ONE：播完重播当前这首
 */
object PlayRepeat {

    @Volatile
    var mode: Int = Player.REPEAT_MODE_OFF
        private set

    fun cycle(): Int {
        mode = when (mode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        return mode
    }

    fun set(value: Int) {
        mode = value
    }
}
