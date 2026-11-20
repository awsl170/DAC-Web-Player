# DAC Web Player 插件 API（v2）

DAC Web Player 本身**不含任何音源**。搜索、直链、封面、歌词、标签全部由插件提供，
插件是普通的 `jar`/`dex`/`apk` 文件，用户从「设置 → 音源插件」导入即可。

本文档面向插件作者。接口源码：`app/src/main/java/com/dacweb/player/plugin/PluginApi.kt`。

- 接口版本：**v2**（`MusicPlugin.apiVersion`，宿主常量 `PluginManager.API_VERSION`）
- 最低 Android：**5.1（API 22）**。插件可以用 `android.*`、`org.json.*`、OkHttp、Kotlin 标准库与协程
- 插件字节码必须能被 5.1 加载：d8 必须用 `--min-api 22`（详见 §4），否则 Android 5.x 会报 dex 版本过高
- 接口只增不改：新增成员一律带默认实现，旧插件不需要重新编译

---

## 1. 两类插件

| 类型 | 必须实现 | `providesSearch` | `providesMetadata` | 典型用途 |
| --- | --- | --- | --- | --- |
| **音源插件** | `search` + `resolve` | `true` | 有元数据能力时 `true` | 自己的搜索接口 + 直链 |
| **元数据插件** | `cover` / `lyrics` / `searchLyrics` / `enrich` 中的任意几个 | `false` | `true` | 只补封面、歌词、专辑信息 |

宿主的行为：

- **搜索**：只问 `providesSearch = true` 的插件，按插件页顺序依次请求，前一个返回空列表才问下一个。
- **直链**：按曲目记住的 `provider` 路由回原来的插件；找不到（插件被删/停用）才回落到其它启用插件。
- **封面/标签/歌词**：`曲目所属音源插件 → 其它启用的元数据插件（插件页顺序）→ 内置元数据插件`，
  前面拿到结果就不再问后面，所以导入自己的元数据插件不会和内置的打架。
- 宿主内置一个元数据插件 `builtin.metadata`（网易云 + 酷狗公开接口，MusicBrainz 作备选），
  默认开启、可在插件页关掉，**不提供任何直链**。

## 2. 接口

```kotlin
interface MusicPlugin {
    val id: String              // 唯一 id，倒序域名风格，如 "com.example.myprovider"
    val name: String            // 显示名
    val version: String         // 默认 "1.0"，仅展示
    val description: String     // 插件页的一行说明

    // 能力声明
    val providesSearch: Boolean     // 默认 true；false = 不提供搜索/直链
    val providesMetadata: Boolean   // 默认 true；false = 不参与封面/歌词/标签回落
    val apiVersion: Int             // 默认 2；大于宿主支持版本时插件会被拒绝加载
    fun preferredMetadata(): List<String> = emptyList()  // 音源插件声明优先元数据源

    fun attach(ctx: Context) {} // 加载后调用一次，参数是 applicationContext

    // 搜索：page 从 1 开始；返回条数少于 size 视为最后一页
    fun search(keyword: String, page: Int, size: Int, quality: String): List<PluginTrack>

    // 直链；播放失败重试时宿主传 force = true
    fun resolve(track: PluginTrack, quality: String): PluginStream
    fun resolve(track: PluginTrack, quality: String, force: Boolean): PluginStream =
        resolve(track, quality)

    // 以下都是可选的，默认返回「没有」
    fun lyrics(name: String, artist: String): String?
    fun searchLyrics(name: String, artist: String, durationMs: Long): List<PluginLyrics>
    fun cover(track: PluginTrack): String?
    fun enrich(track: PluginTrack, quality: String): PluginMeta?
}

interface PluginFactory { fun create(): MusicPlugin }
```

### 数据结构（Java 照这个顺序写）

| 类型 | 构造器参数（按顺序） |
| --- | --- |
| `PluginTrack` | `String id, String name, String artist, String album, String cover, long durationMs, String quality, Map<String,String> extra` |
| `PluginStream` | `String url, String name, String artist, String album, String cover, String quality, String format, String size, Map<String,String> headers` |
| `PluginLyrics` | `String title, String artist, String source, String text` |
| `PluginMeta` | `String title, String artist, String album, int year, String coverUrl, String lyrics, int trackNo, int discNo, String alias` |

全部是 `@JvmOverloads`：Java 里可以从右往左省略**带默认值**的后缀参数。各类型最少实参个数：

| 类型 | 最少实参 | 例子 |
| --- | --- | --- |
| `PluginTrack` | 2（`id, name`） | `new PluginTrack("p:1", "歌名")` |
| `PluginStream` | 1（`url`） | `new PluginStream(url)` |
| `PluginLyrics` | 4（全参） | `new PluginLyrics("歌名", "歌手", "我的音源", lrc)` |
| `PluginMeta` | 0（全都有默认值） | `new PluginMeta()`，或按需传前几个 |

`extra`、`headers` 是 `Map`，**不能传 null**，没有内容就传 `Collections.emptyMap()`。

### Java 怎么写这个接口

Kotlin 的 `val` 在 Java 侧就是 getter，**不是字段**。一个最小可用的 Java 入口类长这样：

```java
package com.example.provider;

import com.dacweb.player.plugin.*;
import java.io.IOException;
import java.util.*;

public final class Entry implements MusicPlugin {
    private final String id = "com.example.provider";

    @Override public String getId() { return id; }
    @Override public String getName() { return "我的音源"; }
    // 其余都是可选：getVersion() / getDescription() / getProvidesSearch() /
    // getProvidesMetadata() / getApiVersion() / preferredMetadata() / attach() /
    // lyrics() / searchLyrics() / cover() / enrich()
    // 不写就用接口里的默认值——它们由宿主的 MusicPlugin$-CC 提供，见 §4。

    @Override public void attach(android.content.Context ctx) { }

    @Override
    public List<PluginTrack> search(String keyword, int page, int size, String quality)
            throws IOException {
        List<PluginTrack> out = new ArrayList<>();
        out.add(new PluginTrack("p:1", "歌名", "歌手", "专辑",
                "https://example.com/cover.jpg", 215000L, quality,
                Collections.singletonMap("sid", "1")));
        return out;
    }

    @Override
    public PluginStream resolve(PluginTrack track, String quality) throws IOException {
        String sid = track.getExtra().get("sid");
        if (sid == null) throw new IOException("曲目缺少 sid");
        return new PluginStream("https://example.com/" + sid + ".mp3",
                track.getName(), track.getArtist(), track.getAlbum(), track.getCover(),
                quality, "mp3", "", Collections.emptyMap());
    }
}
```

**必须实现**的只有 `getId()` / `getName()` / `search(...)` / `resolve(track, quality)`；
其余成员不实现就沿用接口默认值。数据类的字段在 Java 里是 `getXxx()`（如 `track.getExtra()`）。

Java 里还有一条容易踩的：这些 `String` 参数在 Kotlin 侧是**非空类型**，传 `null` 会在构造器里
直接抛 `NullPointerException`（`Intrinsics.checkNotNullParameter`）。不知道的值一律传 `""`。

字段语义（容易猜错的两个）：

- `PluginStream.format`：扩展名风格的小写串（`"mp3"` / `"flac"` / `"m4a"`），
  宿主用它决定下载时写哪套标签、存成什么扩展名。**不要写 MIME**。不知道就传 `""`。
- `PluginStream.size`：给人看的字节数文案，如 `"43.2 MB"`；只用于界面显示，不知道传 `""`。
- `PluginStream.quality`：展示文案，如 `"24bit/96kHz FLAC"`；不参与任何决策。
- `PluginTrack.quality`：音质标记，宿主原样回传给 `resolve()`，插件自己解释。

`PluginLimitException`（继承 `IOException`）表示「今日额度用尽」，宿主会单独提示。

## 3. 调用约定（重要）

1. **全部阻塞式**：宿主已经在 IO 线程调用，插件里直接发网络请求即可，
   **不要自己 `runBlocking`、不要起线程、不要回调**。
2. **自己设超时**：宿主不设超时，一次请求卡死会拖住整次搜索。建议 connect 6s / read 8s / call 12s。
3. **幂等**：同一 `PluginTrack` 可能被多次 `resolve`（失败重试、DAC 与播放器各要一次流）。
4. **`extra` 是你的私有通道**：`search` 返回的对象会被存进队列/歌单/数据库，`resolve` 时原样带回。
   签名、时间戳、音源标记都塞这里，不要依赖全局变量或单例缓存。
5. **曲目 `id` 必须全局唯一**（指 `PluginTrack.id`，不是插件 `id`）：宿主直接把它当
   `MediaItem.mediaId`，**不会**自动加插件前缀。推荐 `"<插件短名>:<原始 id>"`，
   否则两个插件都返回 `"123"` 会串台。插件自身的 `id` 只需在其他已安装插件之间不重复。
6. **分页**：返回条数 `< size` 即最后一页。想表达「还有更多」，就尽量返回满 `size` 条。
7. **音质名**：宿主固定六档（传给你的是枚举名，不是界面文案）：

   | 传给插件的值 | 界面显示 | 含义 |
   | --- | --- | --- |
   | `MASTER` | 母带 | 24bit/192kHz |
   | `HIRES` | 高解析 | 24bit/96kHz（默认档） |
   | `CD` | CD | 16bit/44.1kHz |
   | `LOSSLESS` | 无损 | FLAC |
   | `MP3_320` | 320k | MP3 320kbps |
   | `MP3_128` | 128k | MP3 128kbps |

   插件自己映射到这六档之一；**不认识的档位按 `HIRES` 处理，不要抛异常**。
   界面上没有「标准」这类档位，别按字面去匹配中文。

   `PluginStream.quality` 只是**展示文案**，直接回传 `"HIRES"` 也能用，但播放页会显示 `HIRES`；
   想显示成中文就自己映射（如 `HIRES → "24bit/96kHz FLAC"`）。
   `resolve()` 收到的 `quality` 是**用户当前档位**，不是 `search` 时写进 `PluginTrack.quality` 的那个值；
   两者冲突时以 `resolve` 的参数为准。
8. **失败语义**（`search` / `resolve` / `resolve(force)` 已在接口上声明 `throws IOException`，
   Java 直接写即可，Kotlin 重写时不用再加注解）：
   - 普通失败 → `throw new IOException("说明")`，宿主显示「<插件名> 取直链失败：说明」
   - 额度用尽 → `throw new PluginLimitException("今日访问已达限额")`
   - 搜不到 → 返回空列表。抛异常也不会让整个搜索失败：宿主会记下错误、
     继续问下一个启用的音源插件；只有**所有**插件都失败时才把最后一条错误显示给用户。
     但"没结果"用空列表表达仍然更准确
   - 其它 RuntimeException 宿主也会兜住，但提示文案不如上两种友好
9. **直链可以是本地路径**：`file:///…` 或 `/sdcard/…` 宿主都能播（DAC 直通走系统 extractor），
   所以插件也可以用来接本地媒体服务器、已下载文件。
10. **分页参数由宿主给**：`page` 从 1 开始，`size` 目前固定请求 **30**，
    `keyword` 可能为空串（空搜索框）。`page<1` / `size<=0` 宿主不会传；返回条数少于 `size` 即最后一页。
11. **`extra` 会被持久化**：宿主把整个 `Map<String,String>` 按 JSON 存进播放队列/歌单/历史，
    所以 value 只能是字符串、别塞太长（几十 KB 以内），也别依赖它做单例缓存。
12. **`attach(ctx)` 的时机**：插件加载成功后立刻调用一次（`getId()` 之后），传入 `applicationContext`，
    异常会被宿主吞掉。它通常发生在打开插件页/启动扫描时，**基本等于主线程**，不要在里面做网络请求。
13. **请求头**：`PluginStream.headers` 是预留字段，**宿主当前不会**把它传给播放器
    （ExoPlayer 的请求头是全局的）。需要鉴权的直链请把 token / 过期时间拼进 URL。

## 4. 打包

插件就是一个 `classes.dex` 打包成的 zip：

```
myprovider.jar
 ├─ classes.dex
 └─ META-INF/dac-web-player.plugin     ← 纯文本，第一行写入口类全名
```

`META-INF/dac-web-player.plugin` 内容（缺省为 `com.dacweb.plugin.Entry`）：

```
com.example.provider.Entry
```

入口类查找顺序：`INSTANCE` 静态字段（Kotlin `object`）→ 无参构造 → 实现 `PluginFactory` 时调用 `create()`。

**入口类必须是 `public`**，并提供 `public` 的无参构造（Kotlin 的 `object` / `class` 默认满足）。
包级私有类即使在 jar 里也实例化失败，插件页会显示「加载失败」。

入口查找的准确行为（`PluginManager.loadOne`）：

1. `Class.forName(入口类名, true, DexClassLoader)` —— 静态初始化块会在这里执行；
2. 先找**public static 字段** `INSTANCE`（Kotlin `object` 自动生成），它可以是 `MusicPlugin` 或 `PluginFactory`；
3. 找不到就 `getDeclaredConstructor().newInstance()`，所以要有可访问的无参构造（Java 里写 `public Entry() {}`）；
4. 实例如果是 `PluginFactory`，再调 `create()` 拿 `MusicPlugin`；
5. 校验 `id` 非空、`apiVersion <= 2`、`id` 不和其他已加载插件重复，然后 `attach(ctx)`。

`META-INF/dac-web-player.plugin` 的解析方式：zip 里取该条目，**第一个非空行** `trim()` 之后就当作类名。
所以文件可以是 UTF-8 无 BOM 的 `com.example.provider.Entry\n`，LF / CRLF 都行；**不要加 BOM**
（`trim()` 不剥 BOM，会变成找不到的类名）。

`providesMetadata` 默认是 `true`：只做搜索/直链、不补封面歌词的插件请显式写成 `false`，
否则宿主会在每次播放/下载时白问它一遍元数据。

宿主用 `DexClassLoader` 加载，**父 ClassLoader 是宿主自己的**，因此：

- 可以直接用 `com.dacweb.player.plugin.*`、`android.*`、`org.json.*`；
- Kotlin 标准库、协程、OkHttp 宿主里已有，**不必打包**（打包了也会先命中父加载器）；
- 用到的其它第三方库（Gson、jsoup 等）要么一起打进 dex，要么别依赖；
- 资源文件（`res/`、`assets/`）不会被打进插件，也不建议做：插件只能依赖代码与网络。

打包细节：`classes.dex` 可以 deflate 压缩（`jar cfM` 的默认行为即可，不必 stored、不必 4 字节对齐）；
如果依赖第三方库，把它们的 `.class` 和你的 `.class` 一起交给 d8 就行
（`d8 ... $(find out -name '*.class') libs/*.jar` 这种写法 d8 也接受 jar 作为输入）。

编译插件只需要两样东西：`android.jar` 和**插件接口的 class**（`com.dacweb.player.plugin.*`）。
注意 `app-debug.apk` **不能**直接当 javac 的 classpath（里面是 `classes.dex`，没有 `.class`），
下列方式任选其一：

- **推荐**：直接用 `plugin-sample/dac-plugin-api.jar`。它只包含
  `MusicPlugin`、`PluginFactory`、`PluginTrack`、`PluginStream`、`PluginLyrics`、`PluginMeta`、
  `PluginLimitException` 这 7 个类型，不含宿主实现（`PluginManager` / `Providers` 等）。
  仓库里这个 jar 由 `cd plugin-sample && bash build-sample.sh` 生成；
- 自己构建过宿主的话，也可以指 `app/build/tmp/kotlin-classes/debug` 目录，
  但**只限 javac 用**，d8 的 `--classpath` 必须指上面那个 jar（否则宿主实现类会被打进你的 dex）。

从 `.class` 到 dex 的最小流程（`d8` 在 Android SDK 的 `build-tools/<ver>/` 下；
Windows 上该目录里叫 `d8.bat`，直接用 `d8` 会提示找不到命令。classpath 分隔符 Windows 是 `;`、Linux/macOS 是 `:`）：

```bash
SDK=/path/to/android-sdk
API_JAR=plugin-sample/dac-plugin-api.jar          # 只含插件接口，约 14 KB

javac -encoding UTF-8 -source 8 -target 8 \
  -cp "$SDK/platforms/android-35/android.jar:$API_JAR" \   # Windows 把 : 换成 ;
  -d out $(find src -name '*.java')

# --min-api 22 是关键：>=24 会生成 dex 037，>=26 生成 dex 038，Android 5.1 只认 035
mkdir -p dex   # --output 指定的目录必须先存在，否则 d8 报 "Invalid output: dex"
"$SDK/build-tools/35.0.0/d8" --lib "$SDK/platforms/android-35/android.jar" \
  --classpath "$API_JAR" --min-api 22 --output dex $(find out -name '*.class')

mkdir -p payload/META-INF
cp dex/classes.dex payload/
echo com.example.provider.Entry > payload/META-INF/dac-web-player.plugin
(cd payload && jar cfM ../myprovider.jar classes.dex META-INF/dac-web-player.plugin)
```

> Windows 下 javac / d8 只认 `E:/xxx` 这种路径，`/e/xxx` 放进 `;` 拼接的 classpath 会解析失败；
> MSYS/Git Bash 里可以用 `cygpath -m` 转换。d8 的 `--classpath` 请指向**单个 jar**，
> 指向整个 `kotlin-classes` 目录会把宿主类一起打进你的 dex（产物从 2 KB 变成 1.7 MB）。

> **Windows 补充**：SDK 的 `build-tools/<ver>/` 里只有 `d8.bat`（没有 `d8`），脚本里要写 `d8.bat`；
> 它内部会调 `java`，所以先把 `D:/Java/17/bin` 加进 `PATH`（或 `export JAVA_HOME=D:/Java/17` 后再 `export PATH="$JAVA_HOME/bin:$PATH"`）。

> **编译通过 ≠ 5.1 能跑**：`android.jar` 只提供 Android 的类，但 `javac` 仍会优先解析 JDK 自带的 `java.*`。
> 用了比 API 22 新的 JDK 类，编译照过、装到电视上运行时才 `NoClassDefFoundError`。要避开：
> `java.time.*`（API 26）、`java.util.Base64`（26）、`java.util.stream.*`（24）、`java.util.Optional`（24）、
> `java.nio.file.*`（26）。判断标准就是"这个类在 Android 5.1 上有没有"。

> **Windows 补充**：SDK 的 `build-tools/<ver>/` 里只有 `d8.bat`（没有 `d8`），
> 脚本里要写 `d8.bat`；它内部会调 `java`，所以要么把 `D:/Java/17/bin` 放进 `PATH`，
> 要么先 `export JAVA_HOME=D:/Java/17` 并把 `$JAVA_HOME/bin` 加进 `PATH`。

> **编译通过 ≠ 5.1 能跑**：`android.jar` 只提供 Android 的类，但 `javac` 仍会优先解析 JDK 自带的
> `java.*`。用了比 API 22 新的 JDK 类，编译照过、装到电视上运行时才 `NoClassDefFoundError`。要避开的：
> `java.time.*`（API 26）、`java.util.Base64`（26）、`java.util.stream.*`（24）、`java.util.Optional`（24）、
> `java.nio.file.*`（26）、`var` 之外的新语法也建议别用。判断标准就是"这个方法在 Android 5.1 上有没有"。

> **d8 会给你生成一个合成引用 `MusicPlugin$-CC`**：`--min-api 22` 时，接口默认方法会被 desugar 成
> `com.dacweb.player.plugin.MusicPlugin$-CC` 里的静态方法，你的 Entry 类里会出现一两条指向它的合成桥接。
> 这个类是**宿主 APK 里带的**（`classes8.dex` 中就有），不用你打包、也打不进插件。
> 插件列表能加载、能搜索，就说明它解析正常；如果哪天报 `NoClassDefFoundError: MusicPlugin$-CC`，
> 那是宿主版本太旧（低于 1.0），不是插件的问题。

> **Kotlin 插件必读**：宿主用 `-Xjvm-default=all` 编译，因此接口的默认方法在宿主里是真正的
> Java default 方法，**不存在** `MusicPlugin$DefaultImpls` 这个类。如果你的插件用 kotlinc 编译时
> 没加 `-Xjvm-default=all`，字节码会去引用 `MusicPlugin$DefaultImpls`，加载时报
> `Failed resolution of: Lcom/dacweb/player/plugin/MusicPlugin$DefaultImpls`。
> 所以 Kotlin 插件务必加：`-Xjvm-default=all`。纯 Java 插件不受影响。

交卷前可以本地自检（不需要装到设备上）：

```bash
ANDROID_SDK_ROOT=/path/to/android-sdk python3 tools/check-plugin.py myprovider.jar
```

它会检查四件事：包里有没有 `classes.dex`、dex 版本是否是 035（5.1 只认这个）、
`META-INF/dac-web-player.plugin` 写的入口类是否真在 dex 里、以及有没有把宿主接口类误打包进你的 dex。
输出 `RESULT : OK` 就说明结构上没问题；剩下的只能靠导入后实测。

## 5. 示例

可直接编译的 Java 示例在 `plugin-sample/`（返回两条假数据，直链指向公开测试音频）：

```bash
cd plugin-sample && bash build-sample.sh   # 产物 myprovider.jar
```

音源插件（Kotlin）：

```kotlin
class Entry : MusicPlugin {
    override val id = "com.example.provider"
    override val name = "我的音源"
    override val description = "示例：搜索 + 直链"
    override fun attach(ctx: Context) { MyApi.init(ctx) }

    override fun search(keyword: String, page: Int, size: Int, quality: String) =
        MyApi.search(keyword, page, size, quality).map {
            PluginTrack(
                id = "my:${it.id}",            // 全局唯一
                name = it.name, artist = it.artist, album = it.album,
                cover = it.cover, durationMs = it.durationMs, quality = quality,
                extra = mapOf("sid" to it.id)  // resolve 时原样带回
            )
        }

    override fun resolve(track: PluginTrack, quality: String): PluginStream {
        val url = MyApi.url(track.extra["sid"]!!, quality) ?: throw IOException("没有拿到直链")
        return PluginStream(url, track.name, track.artist, track.album, track.cover,
            quality, "flac", "")
    }

    override fun lyrics(name: String, artist: String): String? = MyApi.lrc(name, artist)
}
```

只做歌词的元数据插件（Kotlin）：

```kotlin
class Entry : MusicPlugin {
    override val id = "com.example.lyrics"
    override val name = "我的歌词源"
    override val providesSearch = false          // 不参与搜索
    override val providesMetadata = true
    override fun search(keyword: String, page: Int, size: Int, quality: String) =
        emptyList<PluginTrack>()
    override fun resolve(track: PluginTrack, quality: String): PluginStream =
        throw IOException("本插件只提供歌词")
    override fun lyrics(name: String, artist: String): String? = MyApi.lrc(name, artist)
}
```

## 6. 导入与调试

1. 「设置 → 音源插件 → 导入插件」，选 jar/dex/apk（复制到应用私有目录 `files/plugins/`）；
2. 列表里打开开关（可以同时启用多个）；
3. 回首页搜索；
4. 失败时先看插件页条目的错误行（加载失败、id 重复、接口版本不符都会写在这里），
   再看 logcat（插件相关日志的 tag 是 `plugins`，**只在该次加载失败时**才无条件输出；
   宿主 debug 包会输出全部日志）：
   ```bash
   adb logcat -s plugins
   ```
   正式包默认不打印调试日志，需要在「设置 → 通用 → 详细调试日志」打开后才输出。

内置元数据插件在列表最上方，只能开关、不能删除。

## 6.1 常见错误

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| `Failed resolution of: Lcom/dacweb/player/plugin/MusicPlugin$DefaultImpls` | Kotlin 插件编译时没加 `-Xjvm-default=all` | kotlinc 加 `-Xjvm-default=all` 重编 |
| 装上后 5.1 电视提示「dex 版本过高」/ 插件不出现 | d8 用了 `--min-api 27` 以上，产出 dex 037/038 | `--min-api 22` 重编 |
| 插件页显示「插件 id 重复」 | 两个插件返回了相同 `id` | 换成域名风格唯一 id |
| 插件页显示「插件要求接口 v3」 | `apiVersion` 大于宿主 `API_VERSION`（当前 2） | 改回 2 或去掉该成员 |
| 导入后列表里没有 | 压缩包里没有 `classes.dex`（直接 `jar cf` 打包了 `.class`） | 先 d8 再打包 |
| 搜索转圈很久后失败 | 插件内网络请求没设超时 | connect 6s / read 8s / call 12s |
| 播放失败但插件日志没输出 | 正式包默认关调试日志 | 「设置 → 通用 → 详细调试日志」打开 |

## 7. 版本与兼容

- 宿主是 `1.0`，接口 v2；不兼容的改动会递增 `API_VERSION`，
  老插件会显示「插件要求接口 vX」而不是运行到一半崩掉。
- 新增能力一律走「带默认实现的新成员」，因此只有想用新能力时才需要重新编译插件。
- 已移除：插件页的「设为当前」（现在支持同时启用多个插件，按顺序回落）。
