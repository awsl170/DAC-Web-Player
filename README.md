# DAC Web Player

一个不上架的 Android 在线音乐播放器：搜索、在线播放、下载并写入完整标签、歌单与本地曲库，
以及面向 USB DAC 的**独占直通输出**（UAC 等时传输，16/24/32 bit 原样送 DAC）。

本应用**不自带任何音源**：搜索、播放直链、歌词、封面全部由**插件**提供（接口
`com.dacweb.player.plugin.MusicPlugin`）。插件是独立的 jar/dex 文件，由用户自行导入，
详见 [docs/plugin-api.md](docs/plugin-api.md) 与示例 `plugin-sample/`。
宿主只内置一个**可选、可关闭**的元数据插件（网易云 + 酷狗公开接口，补封面/歌词，不提供任何直链）。

> 🤖 **本项目由 AI 编写**：代码、文档与提交内容绝大部分由 AI（Claude / DeepSeek 系列模型）
> 在人类需求驱动下生成，经真机反复验证与修正。人类作者负责提出需求、设计取舍与验收。
> 因此代码风格可能不一致，也可能存在未发现的缺陷——请把它当作"能跑的参考实现"而非工业级产品，
> 重要场景请自行审计。

- **最低系统**：Android 5.1（API 22），目标 API 35；电视盒子（无触摸屏）可用遥控器操作
- **许可**：GPL-3.0（第三方组件 Apache-2.0，见 [NOTICE.md](NOTICE.md)）
- **语言/构建**：Kotlin + XML 视图，AGP 8.6.1 / Gradle 8.8 / Kotlin 2.0.21 / JDK 17

> ⚠️ 本仓库只包含播放器与插件框架，不含任何站点的接口地址或解析代码。插件由第三方提供，
> 使用哪个插件、访问哪些服务由使用者自行选择并承担相应责任；本项目不托管、不分发任何音频内容。

## 安装

到 [Releases](https://github.com/awsl170/DAC-Web-Player/releases) 下载 `app-release.apk` 直接安装
（Android 5.1 及以上；已签名的正式包，**不要**再叠加安装旧版 `com.music24.player`）。

- 一台设备只需装一个 ABI 无关的包：APK 内含 `arm64-v8a` / `armeabi-v7a` / `x86_64` 三套 native 库
- 首次使用：进「设置 → 音源插件」导入插件 jar，否则搜索不到任何内容（主程序不自带音源）
- 接 USB DAC：插上后进「设置 → DAC 直通」，选设备即可独占输出；电视遥控器可用方向键 + 确认键操作
- 该版本签名证书 SHA-256：`0d468bb5e29f341b5d1f89ccf60edf0b43a970bbfd8d1c928d039c9422f28689`
  （`CN=awsl170`）；想核对可执行 `apksigner verify --print-certs app-release.apk`

## 功能

**音乐发现与播放**
- 搜索与直链全部走插件；宿主提供六档通用音质名（母带 24bit/192k、24bit/96k、16bit/44.1k、
  FLAC 无损、320k、128k），由插件自行映射
- 搜索分页（滑到底自动加载）、列表自动补封面（插件 `cover()` 或搜索结果自带）、搜索中居中转圈
- 后台播放（MediaSessionService）、通知栏/锁屏控制、耳机线控、播放队列、循环（关/列表/单曲）、随机
- 播放页：封面、封面主色状态栏、**歌词**（逐行高亮、自动滚动、可手动搜索替换并写回文件）、
  播放队列、**均衡器**（系统 `audiofx`，可调每段增益 / 低音 / 环绕 / 预设）
- **主动缓冲**：SimpleCache（1.5 GB LRU）+ 15 s / 最大 600 s 预读，回跳已播位置不再重新缓冲

**本地曲目**
- 本地文件优先播放，顶栏显示真实规格（如 `24bit · 96kHz · 1024kb/s`，读取自文件头）
- 本地曲目自动隐藏「下载」按钮

**下载与标签**
- 并发下载队列（1–6），按 host 只探测一次 Range，断点续传，失败重试
- 下载复用已缓冲数据；正在播放的曲目直接复用当前直链，不重复消耗额度
- 文件名 `歌名-作曲家.ext`，写入：封面（800×800 JPEG）、标题、艺术家、专辑、年份、音轨号、
  碟号、别名、歌词（FLAC 写 `LYRICS`/`UNSYNCEDLYRICS`/`USLT`，MP3 写 `USLT` + `TXXX`）
- 歌词来源：文件内嵌歌词 → 插件 / 内置元数据插件歌词 → 手动搜索替换；无有效歌词时写入 `[00:05:00]纯音乐,请欣赏`
- 下载目录可选（SAF 任意文件夹，默认 `Music/24bit`），可在下载队列里更改

**歌单 / 本地库 / 缓存**
- 歌单：新建、重命名、删除、批量添加、记住音质、详情页播放与移除
- 本地库：扫描下载目录，本地文件优先播放，展示封面，长按删除
- 缓存：图片按 MB 上限（100/200/300/500/1024）LRU、全部缓存 2 天过期、分类清理
- 元数据与封面有本地缓存，同一首歌二次播放/下载不再重复请求

**插件**
- 「设置 → 音源插件」导入 jar/dex/apk，可同时启用多个；删除时按插件整体清理；同一插件重复导入只保留一条
- 搜索按插件顺序轮询，空结果自动回落下一个；曲目记住来源插件，换曲仍用同一个
- 插件分两类（接口里有能力声明）：**音源插件**（搜索 + 直链）与**元数据插件**（封面 / 标签 / 歌词）
- 内置一个**元数据插件**（网易云 + 酷狗，MusicBrainz 作备选），只用公开接口补封面和歌词，
  **默认开启、可以在插件页关掉**；只补元数据，不提供任何音频直链
- 回落顺序：曲目所属音源插件 → 其它已启用的元数据插件 → 内置元数据插件（前面拿到就不问后面，
  所以导入自己的元数据插件不会和内置打架）
- 插件接口见 [docs/plugin-api.md](docs/plugin-api.md)，可直接编译的示例见 `plugin-sample/`

**个性化**
- 6 套主题配色 + 深/浅色跟随系统；歌词字号（12–28 sp）与颜色可调
- 设置拆分为「常规」「音频输出」「主题」三页；关于页含 GPLv3 版权/无担保声明与源码链接，
  第三方组件（Apache-2.0）在子页面单独列出

**USB DAC 独占直通**
- 原始描述符解析（Class-Specific AC/AS、Feature Unit、Clock Source、FORMAT_TYPE_I），
  force claim 摘除内核 `snd-usb-audio`，UAC2 硬件音量读写（Feature Unit 通道级）
- native 等时传输（`USBDEVFS_SUBMITURB`）+ 环形缓冲 + 16 个 URB 抖动余量；1 kHz 测试音可用
- 两种模式：**完全直通**（不改采样率、不重采样、原始字节送 DAC）与**兼容模式**
  （可选 16/24/32 bit 与跟随源/44.1k/48k/96k/192k，必要时做线性重采样保证速度正确）
- 直通并入播放页：在「设置 → 音频输出 → DAC」开启独占后返回播放页即出声；
  顶栏 `DAC` 标签按状态上色（蓝=独占中、灰=已插入未独占、红=出错），点开是设备/模式/位深/
  采样率/音量面板；音量键在直通时直接调软件增益
- 换曲/退出直通有淡入淡出与 USB 流生命周期互斥，避免爆音与并发关流导致的设备异常

## 构建

### 环境

| 项目 | 版本 |
|---|---|
| JDK | 17（如 `D:/Java/17`） |
| Android SDK | platforms;android-35、build-tools;35.0.0 |
| Gradle | 8.8（仓库自带 wrapper） |
| NDK | r30（仅编译 `cpp/` 下的 native 代码时需要） |

### 步骤

```bash
# 1. 克隆到一个 *纯 ASCII* 路径（AGP 会拒绝非 ASCII 工程路径）
git clone https://github.com/<you>/<repo>.git /path/to/ascii/MusicPlayer

# 2. 指向你的 SDK
echo 'sdk.dir=/path/to/android-sdk' > local.properties

# 3. 编译 native（改了 cpp 才需要；产物在 app/src/main/jniLibs）
bash build-native.sh

# 4. 构建 APK
JAVA_HOME=/path/to/jdk17 ./gradlew --no-daemon assembleDebug --console=plain
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

`gradle.properties` 里用 `org.gradle.java.home` 固定 JDK 17（必须是正斜杠），
`android.overridePathCheck=true` 用于容忍仓库路径检查。

> 一次性提示：`./gradlew` 在部分 MSYS/Git-Bash 环境下需要写成 `bash ./gradlew`；
> 又因为 Gradle daemon 会继承 stdout，**不要把构建结果接管道或 `tail`**，请重定向到文件后再 `grep`。

### 仓库结构

```
app/src/main/
├── java/com/dacweb/player/
│   ├── plugin/       插件接口、DexClassLoader 加载、多插件路由（不含任何具体音源）
│   ├── audio/        播放服务、播放队列、DAC 直通解码器、HTTP MediaSource
│   ├── usb/          UAC 描述符解析、DacSession、等时传输 JNI 封装
│   ├── download/     并发下载队列、Range 续传、元数据补全
│   ├── tag/          FLAC(Vorbis Comment/PICTURE) 与 MP3(ID3v2.3) 标签读写
│   ├── store/        歌单、封面本地化
│   ├── local/        本地曲库扫描（SAF / MediaStore）
│   ├── cache/        media3 SimpleCache、缓存统计与清理
│   └── ...
├── cpp/               uac_iso.c（等时传输/测试音）、audio_out.c
├── res/               布局、主题、色板、字符串
└── assets/            更新日志、许可文本
```

## 已知问题

- 部分 DAC 的 UAC2 feedback 端点未实现，长时间播放仍可能出现欠载（取决于设备自身缓冲）。
- 压缩轨（FLAC/MP3/AAC）在完全直通下按解码器输出建流，因此多数量 24bit FLAC 实际是
  16bit 通路；只有 `audio/raw` 轨（WAV 等）会按真实位深直通。
- 兼容模式的线性重采样只保证可用，音质不如完全直通；重采样只在采样率不一致时才启用。
- 直通模式下音频缓存键使用 URL，直链过期后旧缓存只能等 LRU 淘汰。
- 插件不支持自定义请求头（`PluginStream.headers` 为预留字段），需要鉴权的直链请把 token 拼进 URL。
- 未做搜索历史、睡眠定时、倍速播放、仅 Wi-Fi 下载。
- 版本 1.0：插件接口为 v2，只增不改；不兼容的改动会递增 `API_VERSION` 并写在 `changelog.txt` 与 `docs/plugin-api.md`。

## 许可

本项目以 **GNU General Public License v3.0** 发布，全文见 [LICENSE](LICENSE)。

它依赖若干 **Apache-2.0** 组件（AndroidX、Material、Media3、OkHttp、Coil、Kotlin 协程等）。
Apache-2.0 与 GPL-3.0 兼容（Apache-2.0 代码可并入 GPLv3 作品）。完整的组件清单、版本与
NOTICE 检查结论见 [NOTICE.md](NOTICE.md)，Apache-2.0 全文见 [LICENSES/Apache-2.0.txt](LICENSES/Apache-2.0.txt)。

APK 里还包含 **FFmpeg 7.1** 的 `libavcodec/libavformat/libavutil/libswresample`（每个 ABI 一套），
以 **LGPL-2.1-or-later** 授权：它以独立 `.so` 动态加载，未修改源码。随仓库提供的
[scripts/build-ffmpeg-android.sh](scripts/build-ffmpeg-android.sh) 与
[docs/ffmpeg-build.md](docs/ffmpeg-build.md) 记录了完整的 NDK 版本与全部 `configure` 选项，
任何人可据此复现同一二进制，或用自己编译的接口兼容版本替换 APK 内的同名 `.so`（LGPL-2.1 §6）。
LGPL-2.1 全文见 [LICENSES/LGPL-2.1.txt](LICENSES/LGPL-2.1.txt)。

按 GPLv3 第 5、6 条，分发二进制时须同时提供对应源代码；本仓库即为其源代码
（含 `build-native.sh`、`scripts/build-ffmpeg-android.sh` 与全部 `configure` 选项，
即 GPLv3 第 1 条所说的"控制编译与安装的脚本"）。

## 隐私

应用不含任何统计、埋点、账号或云同步；所有偏好、歌单、缓存均保存在设备本地。
应用自身的网络请求只有两类：你导入的插件（在应用进程内运行，只导入你信任的插件），
以及内置元数据插件对网易云 / 酷狗公开接口的歌词、封面查询（可在插件页关闭）。
音频始终由你启用的音源插件提供的直链获取，应用不内嵌任何站点解析逻辑。

## 关于"AI 编写"

本项目的代码、文档、changelog、插件示例均在 AI 辅助下完成：

- 需求、界面取舍、验证（真机听感、DAC 兼容性）由人类作者完成；
- 实现由 AI 生成，并按"先结论后依据、改动到文件与行"的方式逐轮迭代；
- 由于是 AI 产出，可能存在：风格不统一、历史包袱（如早先内置音源被移除后的兼容映射字符串）、
  未被覆盖的边界情况。欢迎以 Issue/PR 形式纠正。

如果你要在此基础上做商业分发，请自行完成代码审计与合规检查；许可证仍是 GPL-3.0。

## 贡献

欢迎 Issue 与 PR。提交前请确保 `./gradlew assembleDebug` 通过，并尽量保持单一主题的提交粒度。
涉及第三方接口的改动请在 PR 描述里说明验证方式（接口易变）。
