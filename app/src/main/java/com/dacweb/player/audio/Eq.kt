package com.dacweb.player.audio

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer

/**
 * 系统级均衡器封装（android.media.audiofx，无需额外依赖）。
 * 音频会话由 ExoPlayer 创建，[PlaybackService] 在 onAudioSessionIdChanged 时调用 [attach]。
 * 设置持久化在 "eq_prefs"，所有播放（前台/后台）共用同一套参数。
 */
object Eq {

    private const val PREFS = "eq_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PRESET = "preset"
    private const val KEY_BASS = "bass"
    private const val KEY_VIRT = "virt"
    private const val KEY_LEVELS = "levels"

    private var equalizer: Equalizer? = null
    private var bass: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var session = 0
    private var appCtx: Context? = null

    @Volatile
    var available = false
        private set

    @Volatile
    var enabled = true
        private set

    val bandCount: Int get() = runCatching { equalizer?.numberOfBands?.toInt() ?: 0 }.getOrDefault(0)

    fun attach(ctx: Context, audioSessionId: Int) {
        if (audioSessionId <= 0 || audioSessionId == session) return
        appCtx = ctx.applicationContext
        release()
        session = audioSessionId
        runCatching {
            val eq = Equalizer(0, audioSessionId)
            equalizer = eq
            available = true
            restore(ctx)
        }.onFailure {
            available = false
        }
    }

    fun release() {
        runCatching { equalizer?.release() }
        runCatching { bass?.release() }
        runCatching { virtualizer?.release() }
        equalizer = null
        bass = null
        virtualizer = null
        session = 0
        available = false
    }

    fun bandCenterFreq(band: Int): Int =
        runCatching { equalizer?.getCenterFreq(band.toShort())?.div(1000) ?: 0 }.getOrDefault(0)

    fun bandLevelRange(): IntArray =
        runCatching {
            val r = equalizer?.bandLevelRange ?: return IntArray(0)
            intArrayOf(r[0].toInt(), r[1].toInt())
        }.getOrDefault(IntArray(0))

    fun bandLevel(band: Int): Int =
        runCatching { equalizer?.getBandLevel(band.toShort())?.toInt() ?: 0 }.getOrDefault(0)

    fun setBandLevel(band: Int, level: Int) {
        runCatching { equalizer?.setBandLevel(band.toShort(), level.toShort()) }
        saveLevels()
    }

    fun presetCount(): Int = runCatching { equalizer?.numberOfPresets?.toInt() ?: 0 }.getOrDefault(0)

    fun presetName(i: Int): String =
        runCatching { equalizer?.getPresetName(i.toShort())?.toString().orEmpty() }.getOrDefault("")

    fun currentPreset(): Int = runCatching { equalizer?.currentPreset?.toInt() ?: -1 }.getOrDefault(-1)

    fun applyPreset(i: Int) {
        runCatching { equalizer?.usePreset(i.toShort()) }
        saveLevels()
        save()
    }

    fun setEnabled(on: Boolean) {
        enabled = on
        runCatching { equalizer?.enabled = on }
        runCatching { bass?.enabled = on && bassStrength > 0 }
        runCatching { virtualizer?.enabled = on && virtStrength > 0 }
        save()
    }

    var bassStrength: Int = 0
        private set

    fun setBass(strength: Int) {
        bassStrength = strength.coerceIn(0, 1000)
        runCatching {
            if (bass == null) bass = BassBoost(0, session)
            bass?.setStrength(bassStrength.toShort())
            bass?.enabled = enabled && bassStrength > 0
        }
        save()
    }

    var virtStrength: Int = 0
        private set

    fun setVirtualizer(strength: Int) {
        virtStrength = strength.coerceIn(0, 1000)
        runCatching {
            if (virtualizer == null) virtualizer = Virtualizer(0, session)
            virtualizer?.setStrength(virtStrength.toShort())
            virtualizer?.enabled = enabled && virtStrength > 0
        }
        save()
    }

    // ---------- 持久化 ----------

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun restore(ctx: Context) {
        val p = prefs(ctx)
        enabled = p.getBoolean(KEY_ENABLED, true)
        bassStrength = p.getInt(KEY_BASS, 0)
        virtStrength = p.getInt(KEY_VIRT, 0)
        val levels = p.getString(KEY_LEVELS, null)
        if (!levels.isNullOrBlank()) {
            levels.split(',').forEachIndexed { i, v ->
                v.toIntOrNull()?.let { runCatching { equalizer?.setBandLevel(i.toShort(), it.toShort()) } }
            }
        } else {
            runCatching { equalizer?.usePreset(p.getInt(KEY_PRESET, 0).toShort()) }
        }
        runCatching { equalizer?.enabled = enabled }
        if (bassStrength > 0) runCatching {
            if (bass == null) bass = BassBoost(0, session)
            bass?.setStrength(bassStrength.toShort())
            bass?.enabled = enabled
        }
        if (virtStrength > 0) runCatching {
            if (virtualizer == null) virtualizer = Virtualizer(0, session)
            virtualizer?.setStrength(virtStrength.toShort())
            virtualizer?.enabled = enabled
        }
    }

    private fun saveLevels() {
        val ctx = appCtx ?: return
        val sb = StringBuilder()
        for (i in 0 until bandCount) {
            if (i > 0) sb.append(',')
            sb.append(bandLevel(i))
        }
        prefs(ctx).edit().putString(KEY_LEVELS, sb.toString()).putInt(KEY_PRESET, -1).apply()
    }

    private fun save() {
        val ctx = appCtx ?: return
        prefs(ctx).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putInt(KEY_BASS, bassStrength)
            .putInt(KEY_VIRT, virtStrength)
            .apply()
    }
}
