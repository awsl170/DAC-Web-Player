## DAC Web Player 1.0

第一个正式版。包名已从 `com.music24.player` 改为 **`com.dacweb.player`**，与旧版本可以并存安装。

### 这个版本能做什么

- **USB DAC 独占直通**：UAC2 等时传输，16/24/32 bit PCM 绕过系统混音直接送 DAC；设备音量、时钟、采样率随曲目自动切换
- **音源全插件化**：搜索、直链、歌词、封面全部由插件提供，主程序不内置任何音源。插件是独立 jar，从「设置 → 音源插件」导入
- **本地曲库 + 在线播放 + 下载**：下载时复用已缓冲数据与当前直链；写入封面（800×800 JPEG）与完整标签（FLAC 写 VORBIS_COMMENT/PICTURE，MP3 写 ID3v2.3）
- **歌词**：逐行高亮、自动滚动、可搜索替换并写回文件
- **后台播放**：MediaSessionService + 通知栏/锁屏控制
- **电视可用**：无触摸屏时用遥控器方向键 + 确认键操作

### 安装

- 要求 **Android 5.1（API 22）及以上**，targetSdk 35
- 已签名正式包，证书 `CN=awsl170`，SHA-256 `0d468bb5e29f341b5d1f89ccf60edf0b43a970bbfd8d1c928d039c9422f28689`
- APK 内含 `arm64-v8a` / `armeabi-v7a` / `x86_64` 三套 native 库
- 只装了框架没有插件时搜不到内容，这是设计如此

### 已知限制

- 插件必须在启动时扫描到；导入后需重启应用
- 部分 USB DAC 的硬件音量控制不可用（会退化为软件增益）
- 逐字歌词暂不支持

**完整校验方式**

```bash
apksigner verify --print-certs app-release.apk
```

许可证 GPL-3.0。插件开发见 [docs/plugin-api.md](https://github.com/awsl170/DAC-Web-Player/blob/master/docs/plugin-api.md)。
