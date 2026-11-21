# FFmpeg 的编译选项与来源（GPL / LGPL 合规说明）

本应用随 APK 分发 6 个原生库：`libuaciso.so`（本项目原创，GPL-3.0）、`libffaudio.so`（本项目原创，GPL-3.0）
以及 **FFmpeg 9.0** 的四个共享库（发布包里叫 `libdacavc2.so`、`libdacafmts.so`、`libdacvut.so`、`libdacswresmp.so`）。
其中 FFmpeg 各库以 **GNU Lesser General Public License v2.1 或更高版本（LGPL-2.1-or-later）** 授权。

> 为什么是 LGPL 而不是 GPL：FFmpeg 默认即为 LGPL-2.1-or-later，只有在 configure 时显式加上
> `--enable-gpl`（会启用 x264/x265 等 GPL 组件）或 `--enable-nonfree` 才改变许可。本项目的编译脚本
> **没有** 使用这两个开关，因此分发的库是 LGPL 版本，对本项目（GPL-3.0）与用户都更宽松。

## 1. 使用的 FFmpeg 版本与来源

| 项 | 值 |
|---|---|
| 版本 | FFmpeg 9.0（`https://ffmpeg.org/releases/ffmpeg-9.0.tar.xz`） |
| 上游源码 | https://ffmpeg.org/download.html · https://git.ffmpeg.org/ffmpeg.git |
| 是否修改源码 | 否（未打任何补丁，只做交叉编译配置） |
| 许可全文 | 仓库 `LICENSES/LGPL-2.1.txt`，APK 内 `assets/licenses/LGPL-2.1.txt` |
| 许可说明页 | https://ffmpeg.org/legal.html |

## 2. 编译工具链

| 项 | 值 |
|---|---|
| NDK | Android NDK r27c（`android-ndk-r27c-linux.zip`） |
| 目标平台 | `--target-os=android --api=31`（`minSdk = 31`） |
| ABI | `arm64-v8a`、`armeabi-v7a`、`x86_64` |
| 编译器 | NDK 自带 clang（`aarch64-linux-android31-clang` 等） |

## 3. 完整的 configure 选项

一键脚本：`scripts/build-ffmpeg-android.sh`（在 Ubuntu/Linux 上执行，产物直接写进
`MusicPlayer/prebuilt/ffmpeg/<abi>/{include,lib}`）。

核心选项（其余为 `--prefix`/`--sysroot`/`--cc`/`--cxx`/`--ar`/`--ranlib`/`--strip`/`--nm`
等工具链参数）：

```
--enable-cross-compile
--enable-shared --disable-static
--disable-programs --disable-doc --disable-debug
--disable-avdevice --disable-postproc --disable-avfilter --disable-swscale
--enable-swresample
--enable-small
--disable-everything
--enable-avformat --enable-avcodec --enable-avutil
--enable-protocol=file,pipe
--enable-demuxer=wav,flac,mp3,mov,aiff,dsf,asf,aac,dts,ogg
--enable-decoder=pcm_s16le,pcm_s24le,pcm_s32le,pcm_f32le,pcm_u8,flac,alac,mp3,aac,aac_latm,dca,opus,wmav1,wmav2,wmapro,wmavoice
--enable-parser=flac,mpegaudio,aac,dca,opus
--disable-encoder=* --disable-muxer=*
--extra-cflags=-O3 -fPIC -ffunction-sections -fdata-sections
--extra-ldflags=-Wl,--gc-sections
```

以上清单与**已分发二进制内嵌的 `FFMPEG_CONFIGURATION` 字符串逐项一致**，可自行复核：

```bash
strings prebuilt/ffmpeg/arm64-v8a/lib/libavformat.so | grep -m1 -- --enable-cross-compile
```

（`docs/ffmpeg-configure-flags.txt` 保存同一份文本，便于打包对照。）

说明：
- 只启用 `file`/`pipe` 协议 —— 网络取流仍由系统 Media3 负责，FFmpeg 不参与网络，也不编 TLS。
- 只编解码器、不编编码器/封装器，避免带进 GPL 组件。
- 若要在自己的构建中启用更多解复用器，只需追加到 `--enable-demuxer=` 并重新执行脚本；
  **请勿** 添加 `--enable-gpl` / `--enable-nonfree`，否则许可会变成 GPL（届时必须同步修改
  本文件与 `NOTICE.md`）。

## 4. LGPL-2.1 §6 要求的满足方式

LGPL-2.1 允许“使用该库的作品”以任意许可发布，但分发时必须：

1. **显著声明** 使用了该库，并提供许可副本 → 见 `NOTICE.md`、应用内「设置 → 关于 → 第三方组件」、
   APK 内 `assets/licenses/LGPL-2.1.txt`。
2. **提供可重新链接的形式**（§6b：合适的共享库机制）→ 本项目以 **独立 .so** 分发 FFmpeg，
   应用通过 `System.loadLibrary` 动态加载；任何人可以用自己编译的、接口兼容的
   `libav*.so` 替换 APK 中的同名文件后重新签名安装，无需修改本应用源码。
3. **提供库的完整对应源码**（§6a/§6d）→ FFmpeg 官方源码地址 + 本仓库的编译脚本（含全部选项）
   即可完整复现二进制；本仓库即为其“指定位置”（`scripts/build-ffmpeg-android.sh`、
   本文件）。未对源码做任何修改。

## 5. 复现步骤

```bash
# 1) 克隆本仓库
git clone https://github.com/awsl170/DAC-Web-Player
cd DAC-Web-Player

# 2) 在 Linux 上编译 FFmpeg（自动下载 NDK r27c 与 ffmpeg-9.0）
bash scripts/build-ffmpeg-android.sh

# 3) 编译 native 库（libuaciso / libffaudio）并放进 jniLibs
bash build-native.sh

# 4) 打包 APK
JAVA_HOME=/path/to/jdk17 ./gradlew --no-daemon assembleDebug
```

第 3 步会读取 `prebuilt/ffmpeg/<abi>/` 下的头文件与 `.so`，链接出
`libffaudio.so`；第 4 步把 `jniLibs/<abi>/*.so` 打进 APK。
