package com.dacweb.player.plugin

import android.content.Context
import android.net.Uri
import com.dacweb.player.log.AppLog
import dalvik.system.DexClassLoader
import java.io.File

/**
 * 插件管理：只加载用户导入的外部插件（.dex / .jar / .apk），App 不自带任何音源。
 *
 * 插件包协议：
 * 1. 包里有一个类实现 [MusicPlugin]（object 或带无参构造的 class），
 *    或者实现 [PluginFactory] 由宿主调用 create()；
 * 2. 压缩包内 `META-INF/dac-web-player.plugin` 的第一行是入口类全名；
 *    没有该文件时宿主尝试 `com.dacweb.plugin.Entry`；
 * 3. 用户从「设置 → 音源插件」导入，宿主复制到 files/plugins/ 并用 DexClassLoader 加载。
 *
 * 加载失败的插件不会拖垮 app：条目会带 error 显示在插件页里。
 */
object PluginManager {

    private const val TAG = "plugins"
    private const val ENTRY_FILE = "META-INF/dac-web-player.plugin"
    private const val DEFAULT_ENTRY = "com.dacweb.plugin.Entry"
    private const val PREF = "plugin_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_ACTIVE = "active"
    private const val KEY_BUILTIN = "builtin_metadata"

    /** 宿主实现的接口版本，与 [MusicPlugin.apiVersion] 比较。 */
    const val API_VERSION = 2

    /** 一个已发现（可能加载失败）的插件。 */
    data class Entry(
        val id: String,
        val name: String,
        val version: String,
        val description: String,
        val file: String,
        val enabled: Boolean,
        val error: String? = null
    ) {
        val ok: Boolean get() = error == null
    }

    private val loaded = LinkedHashMap<String, MusicPlugin>()
    /** 插件 id → 磁盘文件名。 */
    private val fileOf = HashMap<String, String>()
    private var errors = HashMap<String, String>()

    @Volatile
    private var initializedFor: Context? = null

    fun dir(ctx: Context): File = File(ctx.filesDir, "plugins").apply { mkdirs() }

    /** 首次使用/进程重启后加载一次；也用于"导入/删除后刷新"。 */
    @Synchronized
    fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        initializedFor = app
        loaded.clear()
        fileOf.clear()
        errors = HashMap()
        dir(app).listFiles()?.sortedBy { it.name }?.forEach { f ->
            if (!f.isFile) return@forEach
            if (f.length() <= 0L) return@forEach
            runCatching { loadOne(app, f) }
                .onFailure { e ->
                    errors[f.name] = e.message ?: e.javaClass.simpleName
                    AppLog.w(TAG, "load ${f.name} failed", e)
                }
        }
    }

    private fun loadOne(ctx: Context, f: File) {
        val dexDir = File(ctx.codeCacheDir, "plugin_dex").apply { mkdirs() }
        val cl = DexClassLoader(f.absolutePath, dexDir.absolutePath, null, javaClass.classLoader)
        val entryName = entryClassName(f) ?: DEFAULT_ENTRY
        val cls = Class.forName(entryName, true, cl)
        val obj: Any = runCatching { cls.getField("INSTANCE").get(null) }.getOrNull()
            ?: runCatching { cls.getDeclaredConstructor().newInstance() }.getOrNull()
            ?: throw IllegalStateException("没有找到插件实例（需要 object 或空构造函数）")
        val plugin = when (obj) {
            is MusicPlugin -> obj
            is PluginFactory -> obj.create()
            else -> throw IllegalStateException("$entryName 没有实现 MusicPlugin / PluginFactory")
        }
        if (plugin.id.isBlank()) throw IllegalStateException("插件 id 为空")
        if (plugin.apiVersion > API_VERSION) throw IllegalStateException(
            "插件要求接口 v${plugin.apiVersion}，当前应用只支持 v$API_VERSION"
        )
        if (loaded.containsKey(plugin.id)) throw IllegalStateException("插件 id 重复：${plugin.id}")
        runCatching { plugin.attach(ctx) }
        loaded[plugin.id] = plugin
        fileOf[plugin.id] = f.name
    }

    private fun entryClassName(f: File): String? = runCatching {
        java.util.zip.ZipFile(f).use { zip ->
            val e = zip.getEntry(ENTRY_FILE) ?: return@use null
            zip.getInputStream(e).bufferedReader().use { r ->
                r.readLines().firstOrNull { it.isNotBlank() }?.trim()
            }
        }
    }.getOrNull()

    /** 插件页展示用：磁盘上的插件（含加载失败的条目）。 */
    fun entries(ctx: Context): List<Entry> {
        // 只在进程内第一次用到时加载；运行期反复 refresh 会重建 DexClassLoader（慢且吃内存）
        if (initializedFor !== ctx.applicationContext) refresh(ctx)
        val enabledIds = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getStringSet(KEY_ENABLED, null)
        val out = mutableListOf<Entry>()
        val shownIds = HashSet<String>()
        // 内置元数据插件（封面/歌词）排在最前，和外部插件走同一套条目，同样可以关掉
        val builtin = BuiltinMetadata.get().also { runCatching { it.attach(ctx.applicationContext) } }
        out += Entry(
            id = builtin.id,
            name = builtin.name,
            version = builtin.version,
            description = builtin.description,
            file = "",
            enabled = builtinEnabled(ctx),
            error = null
        )
        shownIds += builtin.id
        // 同一插件被导入多份时（同一个 id 多个文件），列表里只留一条：
        // 否则删掉一份后另一份还在，看起来像"删不掉"。
        dir(ctx).listFiles()?.sortedBy { it.name }?.forEach { f ->
            val id = fileOf.entries.firstOrNull { it.value == f.name }?.key
            val p = id?.let { loaded[it] }
            if (p != null) {
                if (!shownIds.add(p.id)) return@forEach
                out += Entry(
                    id = p.id,
                    name = p.name,
                    version = p.version,
                    description = p.description,
                    file = f.name,
                    enabled = enabledIds?.contains(p.id) ?: true,
                    error = null
                )
                return@forEach
            }
            val err = errors[f.name]?.takeIf { it.isNotEmpty() }
            out += Entry(
                id = "file:${f.name}",
                name = f.name,
                version = "",
                description = "",
                file = f.name,
                enabled = enabledIds?.contains("file:${f.name}") ?: true,
                error = err ?: "加载失败"
            )
        }
        return out
    }

    fun enabledPlugins(ctx: Context): List<MusicPlugin> =
        entries(ctx).filter { it.enabled && it.ok }.mapNotNull { byId(ctx, it.id) }

    /** 内置元数据插件默认开启，用户可以在插件页单独关掉。 */
    fun builtinEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_BUILTIN, true)

    fun setBuiltinEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_BUILTIN, on).apply()
    }

    fun byId(ctx: Context, id: String?): MusicPlugin? {
        if (id.isNullOrBlank()) return null
        if (id == BuiltinMetadata.ID) return BuiltinMetadata.get().also {
            runCatching { it.attach(ctx.applicationContext) }
        }
        if (initializedFor !== ctx.applicationContext) refresh(ctx)
        return loaded[id]
    }

    /** 当前优先搜索的插件 id；空表示按插件列表顺序。 */
    fun activeId(ctx: Context): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_ACTIVE, "").orEmpty()

    fun setActive(ctx: Context, id: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY_ACTIVE, id).apply()
    }

    fun isEnabled(ctx: Context, id: String): Boolean {
        val set = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getStringSet(KEY_ENABLED, null) ?: return true
        return set.contains(id)
    }

    fun setEnabled(ctx: Context, id: String, on: Boolean) {
        if (id == BuiltinMetadata.ID) {
            setBuiltinEnabled(ctx, on)
            return
        }
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val set = (sp.getStringSet(KEY_ENABLED, null)?.toMutableSet() ?: mutableSetOf())
        if (on) set.add(id) else set.remove(id)
        sp.edit().putStringSet(KEY_ENABLED, set).apply()
        if (!on && activeId(ctx) == id) setActive(ctx, "")
    }

    /** 导入一个插件文件，返回 null 表示成功，否则是错误信息。 */
    fun import(ctx: Context, uri: Uri): String? {
        val name = (uri.lastPathSegment ?: "plugin.dex").substringAfterLast('/')
        val target = File(dir(ctx), name)
        return runCatching {
            ctx.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "打不开文件" }
                target.outputStream().use { out -> input.copyTo(out) }
            }
            refresh(ctx)
            val err = errors[target.name]
            if (err.isNullOrEmpty()) null else removeAndFail(ctx, target, err)
        }.getOrElse { e ->
            runCatching { target.delete() }
            e.message ?: e.javaClass.simpleName
        }
    }

    private fun removeAndFail(ctx: Context, f: File, err: String): String {
        runCatching { f.delete() }
        refresh(ctx)
        return err
    }

    /**
     * 删除插件。按"插件 id"删：同一个 id 如果被导入过好几份，所有文件一起删，
     * 这样不会出现"删掉一条、刷新后又冒出来"的现象。返回 null 表示成功。
     */
    fun delete(ctx: Context, entry: Entry): String? {
        if (entry.id == BuiltinMetadata.ID) return "内置插件不能删除，可以关掉"
        val targets = mutableListOf<File>()
        if (entry.id.startsWith("file:")) {
            targets += File(dir(ctx), entry.file)
        } else {
            fileOf.filterValues { true }.forEach { (id, name) -> if (id == entry.id) targets += File(dir(ctx), name) }
            // 加载失败的重复文件（id 相同但被判重复）也一起清掉
            dir(ctx).listFiles()?.forEach { f -> if (errors.containsKey(f.name) && f.name.contains(entry.id)) targets += f }
        }
        if (targets.isEmpty()) targets += File(dir(ctx), entry.file)
        var failed: String? = null
        targets.distinct().forEach { f ->
            if (f.exists() && !f.delete()) failed = "无权删除 ${f.name}"
        }
        refresh(ctx)
        return failed
    }
}
