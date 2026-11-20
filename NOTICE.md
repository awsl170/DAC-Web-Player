# 第三方组件与许可声明（Third-Party Notices）

本项目（DAC Web Player）以 **GNU General Public License v3.0**（见 `LICENSE`）发布，版权归 Copyright (C) 2026 awsl170。
本仓库不含任何音源实现：搜索与直链由用户自行导入的插件提供，插件不在本项目分发范围内。
它包含下列以 **Apache License 2.0** 授权的第三方组件。完整的 Apache-2.0 许可全文见
`LICENSES/Apache-2.0.txt`，并随 APK 一并分发（`assets/licenses/Apache-2.0.txt`）。

## 一、关于 NOTICE 文件

Apache-2.0 第 4(d) 条要求：若被分发的工作中包含 `NOTICE` 文本文件，则衍生作品必须一并保留其中的
归属声明。本项目已对**全部 87 个最终解析出的依赖构件**（`releaseRuntimeClasspath`）逐个解包检查：

- **没有任何一个随 APK 分发的依赖构件包含 `NOTICE` 文件**，因此不存在需要额外传播的第三方 NOTICE 文本。
- 仅 `com.google.guava:guava` 与 `com.google.guava:failureaccess` 内含 `META-INF/LICENSE`（即 Apache-2.0
  许可全文）。AGP 在打包 APK 时会丢弃 jar 内的 `META-INF/LICENSE`，因此本项目把完整的 Apache-2.0
  文本放进 `app/src/main/assets/licenses/Apache-2.0.txt` 随 APK 分发（满足第 4(a) 条“提供许可副本”
  的要求），仓库内同时提供 `LICENSES/Apache-2.0.txt`。
- 检查到唯一带 `META-INF/NOTICE.txt` 的构件是 `org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21`，
  它属于**构建期工具**（Gradle 插件），不会被编入 APK，因此其 NOTICE 不适用于分发物。

复现方式：`./gradlew :app:dependencies --configuration releaseRuntimeClasspath` 后用脚本遍历
`~/.gradle/caches/modules-2/files-2.1` 下的 `.jar`/`.aar`，检索 `META-INF/**/NOTICE*`。

## 二、直接依赖

| 组件 | 版本 | 许可 | 用途 |
|---|---|---|---|
| androidx.core:core-ktx | 1.13.1 | Apache-2.0 | Kotlin 扩展 |
| androidx.appcompat:appcompat | 1.7.0 | Apache-2.0 | 兼容性 Activity / 主题 |
| com.google.android.material:material | 1.12.0 | Apache-2.0 | Material 3 组件 |
| androidx.constraintlayout:constraintlayout | 2.1.4 | Apache-2.0 | 布局 |
| androidx.recyclerview:recyclerview | 1.3.2 | Apache-2.0 | 列表 |
| androidx.documentfile:documentfile | 1.0.1 | Apache-2.0 | SAF 自定义下载目录 |
| androidx.lifecycle:lifecycle-runtime-ktx | 2.8.6 | Apache-2.0 | 生命周期 / 协程作用域 |
| org.jetbrains.kotlinx:kotlinx-coroutines-android | 1.8.1 | Apache-2.0 | 协程 |
| com.squareup.okhttp3:okhttp | 4.12.0 | Apache-2.0 | HTTP（搜索 / 直链 / 歌词 / 封面） |
| io.coil-kt:coil | 2.7.0 | Apache-2.0 | 图片加载与磁盘缓存 |
| androidx.media3:media3-exoplayer | 1.4.1 | Apache-2.0 | 播放内核 |
| androidx.media3:media3-session | 1.4.1 | Apache-2.0 | MediaSession / 后台播放 |
| androidx.media3:media3-datasource | 1.4.1 | Apache-2.0 | 缓存数据源（SimpleCache） |
| androidx.media3:media3-database | 1.4.1 | Apache-2.0 | SimpleCache 索引数据库 |
| androidx.media3:media3-ui | 1.4.1 | Apache-2.0 | 播放器 UI 组件 |

## 三、传递依赖（最终解析版本）

### androidx.activity

- `androidx.activity:activity:1.8.0`

### androidx.annotation

- `androidx.annotation:annotation:1.8.0`
- `androidx.annotation:annotation-experimental:1.4.0`
- `androidx.annotation:annotation-jvm:1.8.0`

### androidx.appcompat

- `androidx.appcompat:appcompat:1.7.0`
- `androidx.appcompat:appcompat-resources:1.7.0`

### androidx.arch.core

- `androidx.arch.core:core-common:2.2.0`
- `androidx.arch.core:core-runtime:2.2.0`

### androidx.cardview

- `androidx.cardview:cardview:1.0.0`

### androidx.collection

- `androidx.collection:collection:1.4.0`
- `androidx.collection:collection-jvm:1.4.0`

### androidx.concurrent

- `androidx.concurrent:concurrent-futures:1.1.0`

### androidx.constraintlayout

- `androidx.constraintlayout:constraintlayout:2.1.4`
- `androidx.constraintlayout:constraintlayout-core:1.0.4`

### androidx.coordinatorlayout

- `androidx.coordinatorlayout:coordinatorlayout:1.1.0`

### androidx.core

- `androidx.core:core:1.13.1`
- `androidx.core:core-ktx:1.13.1`

### androidx.cursoradapter

- `androidx.cursoradapter:cursoradapter:1.0.0`

### androidx.customview

- `androidx.customview:customview:1.1.0`
- `androidx.customview:customview-poolingcontainer:1.0.0`

### androidx.databinding

- `androidx.databinding:viewbinding:8.6.1`

### androidx.documentfile

- `androidx.documentfile:documentfile:1.0.1`

### androidx.drawerlayout

- `androidx.drawerlayout:drawerlayout:1.1.1`

### androidx.dynamicanimation

- `androidx.dynamicanimation:dynamicanimation:1.0.0`

### androidx.emoji2

- `androidx.emoji2:emoji2:1.3.0`
- `androidx.emoji2:emoji2-views-helper:1.3.0`

### androidx.exifinterface

- `androidx.exifinterface:exifinterface:1.3.7`

### androidx.fragment

- `androidx.fragment:fragment:1.5.4`

### androidx.interpolator

- `androidx.interpolator:interpolator:1.0.0`

### androidx.legacy

- `androidx.legacy:legacy-support-core-utils:1.0.0`

### androidx.lifecycle

- `androidx.lifecycle:lifecycle-common:2.8.6`
- `androidx.lifecycle:lifecycle-common-jvm:2.8.6`
- `androidx.lifecycle:lifecycle-livedata:2.8.6`
- `androidx.lifecycle:lifecycle-livedata-core:2.8.6`
- `androidx.lifecycle:lifecycle-livedata-core-ktx:2.8.6`
- `androidx.lifecycle:lifecycle-process:2.8.6`
- `androidx.lifecycle:lifecycle-runtime:2.8.6`
- `androidx.lifecycle:lifecycle-runtime-android:2.8.6`
- `androidx.lifecycle:lifecycle-runtime-ktx:2.8.6`
- `androidx.lifecycle:lifecycle-runtime-ktx-android:2.8.6`
- `androidx.lifecycle:lifecycle-viewmodel:2.8.6`
- `androidx.lifecycle:lifecycle-viewmodel-android:2.8.6`
- `androidx.lifecycle:lifecycle-viewmodel-savedstate:2.8.6`

### androidx.loader

- `androidx.loader:loader:1.0.0`

### androidx.localbroadcastmanager

- `androidx.localbroadcastmanager:localbroadcastmanager:1.0.0`

### androidx.media

- `androidx.media:media:1.7.0`

### androidx.media3

- `androidx.media3:media3-common:1.4.1`
- `androidx.media3:media3-container:1.4.1`
- `androidx.media3:media3-database:1.4.1`
- `androidx.media3:media3-datasource:1.4.1`
- `androidx.media3:media3-decoder:1.4.1`
- `androidx.media3:media3-exoplayer:1.4.1`
- `androidx.media3:media3-extractor:1.4.1`
- `androidx.media3:media3-session:1.4.1`
- `androidx.media3:media3-ui:1.4.1`

### androidx.print

- `androidx.print:print:1.0.0`

### androidx.profileinstaller

- `androidx.profileinstaller:profileinstaller:1.3.1`

### androidx.recyclerview

- `androidx.recyclerview:recyclerview:1.3.2`

### androidx.resourceinspection

- `androidx.resourceinspection:resourceinspection-annotation:1.0.1`

### androidx.savedstate

- `androidx.savedstate:savedstate:1.2.1`

### androidx.startup

- `androidx.startup:startup-runtime:1.1.1`

### androidx.tracing

- `androidx.tracing:tracing:1.0.0`

### androidx.transition

- `androidx.transition:transition:1.5.0`

### androidx.vectordrawable

- `androidx.vectordrawable:vectordrawable:1.1.0`
- `androidx.vectordrawable:vectordrawable-animated:1.1.0`

### androidx.versionedparcelable

- `androidx.versionedparcelable:versionedparcelable:1.1.1`

### androidx.viewpager

- `androidx.viewpager:viewpager:1.0.0`

### androidx.viewpager2

- `androidx.viewpager2:viewpager2:1.1.0-beta02`

### com.google.android.material

- `com.google.android.material:material:1.12.0`

### com.google.errorprone

- `com.google.errorprone:error_prone_annotations:2.15.0`

### com.google.guava

- `com.google.guava:failureaccess:1.0.2`
- `com.google.guava:guava:33.0.0-android`
- `com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava`

### com.squareup.okhttp3

- `com.squareup.okhttp3:okhttp:4.12.0`

### com.squareup.okio

- `com.squareup.okio:okio:3.9.0`
- `com.squareup.okio:okio-jvm:3.9.0`

### io.coil-kt

- `io.coil-kt:coil:2.7.0`
- `io.coil-kt:coil-base:2.7.0`

### org.jetbrains

- `org.jetbrains:annotations:23.0.0`

### org.jetbrains.kotlin

- `org.jetbrains.kotlin:kotlin-bom:1.8.22`
- `org.jetbrains.kotlin:kotlin-stdlib:2.0.21`
- `org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.8.22`
- `org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.8.22`

### org.jetbrains.kotlinx

- `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1`
- `org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.8.1`
- `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1`
- `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.8.1`

## 四、FFmpeg（LGPL-2.1-or-later）

APK 内含 FFmpeg 7.1 的四个动态库：`libavcodec.so`、`libavformat.so`、`libavutil.so`、`libswresample.so`
（`arm64-v8a`、`armeabi-v7a`、`x86_64` 各一套），用于本地音频文件的解码与重采样。它们与上述
Apache-2.0 组件不同，采用 **GNU Lesser General Public License v2.1 或更高版本**。

| 项 | 内容 |
|---|---|
| 组件 | FFmpeg 7.1（libavcodec / libavformat / libavutil / libswresample） |
| 许可 | LGPL-2.1-or-later（configure 未使用 `--enable-gpl` / `--enable-nonfree`） |
| 上游 | https://ffmpeg.org/ · 源码 https://ffmpeg.org/releases/ffmpeg-7.1.tar.xz |
| 修改 | 无。仅以 NDK r27c 交叉编译，未改动任何源码。 |
| 许可全文 | 仓库 `LICENSES/LGPL-2.1.txt`；APK 内 `assets/licenses/LGPL-2.1.txt` |
| 编译选项 | `docs/ffmpeg-build.md`、`docs/ffmpeg-configure-flags.txt`、`scripts/build-ffmpeg-android.sh` |

FFmpeg 以**独立共享库**形式分发，应用通过 `System.loadLibrary("avcodec")` 等动态加载：
任何人可以用自己编译的、接口兼容的 `libav*.so` 替换 APK 中的同名文件并重新签名安装，
从而使用修改后的 FFmpeg —— 这满足 LGPL-2.1 第 6(b) 条关于“合适的共享库机制”的要求。
完整的编译脚本与全部 configure 选项随仓库提供（第 6(a)/6(d) 条），可完整复现这些二进制。

FFmpeg 的 copyright 归 FFmpeg 项目及其贡献者所有（Copyright (c) 2000-2026 the FFmpeg developers）。

## 五、本项目自身代码

除上述第三方组件外，其余代码（Kotlin / Java / C）均由本项目作者编写，以 GPL-3.0 授权，包括：

- `app/src/main/cpp/uac_iso.c` —— UAC 等时传输与测试音（USBDEVFS 直接调用）
- `app/src/main/cpp/audio_out.c` —— AAudio / OpenSL ES 测试音（当前未接入播放链路）
- `app/src/main/java/com/dacweb/player/usb/*` —— UAC2 描述符解析、Feature Unit 音量、采样率控制
- `app/src/main/cpp/ffaudio.c` —— FFmpeg 解码门面（JNI）

## 六、非代码资产

- 应用图标（`res/drawable/ic_launcher_foreground.xml` 等）为项目自制矢量图。
- 未内置任何第三方音乐、封面或字体文件；封面与歌词均在运行时从公开接口获取，不随安装包分发。
