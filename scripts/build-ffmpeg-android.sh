#!/bin/bash
# ============================================================================
# 在 Ubuntu 上交叉编译 FFmpeg（Android 用），产物写到工程里供 App 链接。
#
# 用法：  bash build-ffmpeg-android.sh
#        FFMPEG_VER=7.1 bash build-ffmpeg-android.sh     # 指定版本（默认 9.0）
#        ABIS="arm64-v8a" bash build-ffmpeg-android.sh   # 只编一个 ABI（先试水用）
#        （首次会下载 NDK ~600MB、FFmpeg ~11MB，缓存在 ~/.cache/dac-ffmpeg）
#
# 产物：  /e/库/音乐播放器/MusicPlayer/prebuilt/ffmpeg/<abi>/{include,lib}
#         <abi> = arm64-v8a / armeabi-v7a / x86_64
#
# 只编需要的部分（Flac/ALAC/MP3/PCM + wav/flac/mp3/mov/aiff 解析 + swresample），
# 每个 ABI 大约 4~6 MB，够用又不臃肿。
# 注意：不编 TLS —— 网络曲目仍走 Android 的 MediaExtractor/MediaCodec（HTTPS 由系统负责），
#       FFmpeg 只负责"本地文件 → PCM"，因此只需要 file/pipe 两种协议。
# ============================================================================
set -euo pipefail

NDK_VER="r27c"
FFMPEG_VER="${FFMPEG_VER:-9.0}"   # 想回到旧版：FFMPEG_VER=7.1 bash build-ffmpeg-android.sh
API=22                       # 与 app/build.gradle 的 minSdk 22 对齐（5.1 也能加载）
ABIS=${ABIS:-"arm64-v8a armeabi-v7a x86_64"}

# 工程里放 prebuilt 的位置（NTFS 挂载点，脚本会自动探测）
PROJ_GUESS=(
  "/run/media/pangna/新加卷/库/音乐播放器/MusicPlayer"
  "/e/库/音乐播放器/MusicPlayer"
  "/mnt/e/库/音乐播放器/MusicPlayer"
  "/media/$USER/E/库/音乐播放器/MusicPlayer"
)
PROJ=""
for p in "${PROJ_GUESS[@]}"; do
  [ -d "$p/app/src/main/cpp" ] && PROJ="$p" && break
done
if [ -z "$PROJ" ]; then
  echo "找不到工程目录，请手动设置： PROJ=/path/to/MusicPlayer bash $0"
  exit 1
fi
OUT_ROOT="$PROJ/prebuilt/ffmpeg"
echo "== 工程目录: $PROJ"
echo "== 输出目录: $OUT_ROOT"

CACHE="${XDG_CACHE_HOME:-$HOME/.cache}/dac-ffmpeg"
mkdir -p "$CACHE"
NDK_DIR="$CACHE/android-ndk-$NDK_VER"
FF_SRC="$CACHE/ffmpeg-$FFMPEG_VER"

need=""
for t in curl tar xz make git python3; do
  command -v "$t" >/dev/null 2>&1 || need="$need $t"
done
if [ -n "$need" ]; then
  echo "缺少工具:$need"
  echo "请先执行:  sudo apt update && sudo apt install -y curl tar xz-utils make git python3 build-essential"
  exit 1
fi

# ---------------------------------------------------------------- NDK
if [ ! -d "$NDK_DIR" ]; then
  echo "== 下载 NDK $NDK_VER (约 600MB，仅首次)"
  curl -L --retry 3 -o "$CACHE/ndk.zip" \
    "https://dl.google.com/android/repository/android-ndk-$NDK_VER-linux.zip"
  unzip -q "$CACHE/ndk.zip" -d "$CACHE"
  rm -f "$CACHE/ndk.zip"
fi
TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64"
[ -d "$TOOLCHAIN" ] || { echo "NDK 解压异常: $TOOLCHAIN"; exit 1; }

# ---------------------------------------------------------------- FFmpeg 源码
if [ ! -d "$FF_SRC" ]; then
  echo "== 下载 FFmpeg $FFMPEG_VER"
  curl -L --retry 3 -o "$CACHE/ffmpeg.tar.xz" "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VER.tar.xz"
  tar -xJf "$CACHE/ffmpeg.tar.xz" -C "$CACHE"
  rm -f "$CACHE/ffmpeg.tar.xz"
fi

# 精简开关：只要"本地文件 → PCM"这一条路
COMMON_FLAGS=(
  --enable-cross-compile
  --enable-shared --disable-static
  --disable-programs --disable-doc --disable-debug
  --disable-avdevice --disable-avfilter --disable-swscale
  --enable-swresample
  --enable-small
  --disable-everything
  --enable-avformat --enable-avcodec --enable-avutil
  --enable-protocol=file,pipe
  --enable-demuxer=wav,flac,mp3,mov,aiff,dsf,asf,aac,dts,ogg
  --enable-decoder=pcm_s16le,pcm_s24le,pcm_s32le,pcm_f32le,pcm_u8,flac,alac,mp3,aac,aac_latm,dca,opus,wmav1,wmav2,wmapro,wmavoice
  --enable-parser=flac,mpegaudio,aac,dca,opus
  --disable-encoder=* --disable-muxer=*
  --extra-cflags='-O3 -fPIC -ffunction-sections -fdata-sections'
  --extra-ldflags='-Wl,--gc-sections'
  --extra-cflags="-O3 -fPIC -ffunction-sections -fdata-sections"
  --extra-ldflags="-Wl,--gc-sections"
)

build_one() {
  local abi="$1" arch="$2" cpu="$3" triple="$4"
  local prefix="$OUT_ROOT/$abi"
  echo "=================================================================="
  echo "== 编译 $abi  ($triple, api $API)"
  echo "=================================================================="
  rm -rf "$prefix"
  mkdir -p "$prefix"
  make -C "$FF_SRC" distclean >/dev/null 2>&1 || true
  (
    cd "$FF_SRC"
    ./configure \
      --prefix="$prefix" \
      --target-os=android --arch="$arch" --cpu="$cpu" \
      --sysroot="$TOOLCHAIN/sysroot" \
      --cc="$TOOLCHAIN/bin/${triple}${API}-clang" \
      --cxx="$TOOLCHAIN/bin/${triple}${API}-clang++" \
      --ar="$TOOLCHAIN/bin/llvm-ar" \
      --ranlib="$TOOLCHAIN/bin/llvm-ranlib" \
      --strip="$TOOLCHAIN/bin/llvm-strip" \
      --nm="$TOOLCHAIN/bin/llvm-nm" \
      "${COMMON_FLAGS[@]}" \
      > "$CACHE/configure-$abi.log" 2>&1
    make -j"$(nproc)" > "$CACHE/make-$abi.log" 2>&1
    make install >> "$CACHE/make-$abi.log" 2>&1
  )
  # 去掉静态库和示例，只留 .so + 头文件
  find "$prefix/lib" -name '*.a' -delete 2>/dev/null || true
  find "$prefix/lib" -name '*.so' -exec "$TOOLCHAIN/bin/llvm-strip" --strip-unneeded {} \; 2>/dev/null || true
  # make install 会额外装一份 share/ffmpeg/examples/*.c（上游演示程序，本项目不编译），
  # 三个 ABI 各一份完全相同的内容，留着只会占仓库并把 Sonar 重复率顶到 10% 以上
  rm -rf "$prefix/share"
  echo "   ✅ $abi 完成: $(du -sh "$prefix/lib" | cut -f1)  (lib: $(ls "$prefix/lib" | tr '\n' ' '))"
}

for abi in $ABIS; do
  case "$abi" in
    arm64-v8a)
      build_one arm64-v8a aarch64 armv8-a aarch64-linux-android ;;
    armeabi-v7a)
      build_one armeabi-v7a arm armv7-a armv7a-linux-androideabi ;;
    x86_64)
      build_one x86_64 x86_64 x86-64 x86_64-linux-android ;;
    *)
      echo "跳过未知 ABI: $abi" ;;
  esac
done

# 头文件三个 ABI 完全一致：只保留一份 prebuilt/ffmpeg/include，
# 其余 ABI 的 include 删掉（否则仓库里有三份相同头文件，既占空间，
# 也会让 SonarQube 的重复率被算成 70%+）。
FIRST=""
for abi in $ABIS; do
  if [ -d "$OUT_ROOT/$abi/include" ]; then
    if [ -z "$FIRST" ]; then
      FIRST="$OUT_ROOT/$abi/include"
    fi
  fi
done
if [ -n "$FIRST" ] && [ ! -d "$OUT_ROOT/include" ]; then
  cp -r "$FIRST" "$OUT_ROOT/include"
  for abi in $ABIS; do
    [ "$OUT_ROOT/$abi/include" = "$FIRST" ] || rm -rf "$OUT_ROOT/$abi/include"
  done
  rm -rf "$FIRST"
  echo "   ✅ 头文件已合并为 $OUT_ROOT/include（三个 ABI 共用）"
fi

echo
echo "全部完成。产物："
find "$OUT_ROOT" -name '*.so' -printf '  %p  %s bytes\n' 2>/dev/null | sort
echo
echo "编译日志在 $CACHE/make-*.log（失败时看这个）。"
echo "接下来在 Windows 侧跑 App 构建即可（我会接线）。"
