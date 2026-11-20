#!/usr/bin/env bash
# 重新编译 native 等时输出库，并放进 app/src/main/jniLibs。
#
# 为什么不在 Gradle 里直接开 externalNativeBuild：
#   工程真实路径是 E:\库\音乐播放器（非 ASCII），AGP 调 ndk-build 时
#   编译器拿到的输出路径会变成乱码，报 “unable to open output file”。
#   所以改成“在 ASCII 路径下预编译 -> 拷 jniLibs”，Gradle 只负责打包。
#
# 用法： bash build-native.sh
set -euo pipefail

PROJ="$(cd "$(dirname "$0")" && pwd)"
NDK="${NDK:-/e/mp/.ndk/android-ndk-r30}"
# 旧的 /e/mp/.uacbuild 目录被系统锁住（里面每个文件都 Access denied，删不掉），
# 这里换一个新目录；如果新目录也删不掉，就退回临时目录，避免整个脚本卡死。
WORK=${WORK:-/e/mp/.uacbuild_n}
rm -rf "$WORK" 2>/dev/null || WORK="$(mktemp -d /e/mp/.uacb.XXXXXX)"
mkdir -p "$WORK/jni"
echo "work dir: $WORK"
cp "$PROJ/app/src/main/cpp/"*.c "$WORK/jni/"
cp "$PROJ/app/src/main/cpp/Android.mk" "$WORK/jni/"
cp "$PROJ/app/src/main/cpp/Application.mk" "$WORK/jni/"
# FFmpeg 预编译库（Ubuntu 侧生成）一起带进 ASCII 工作目录，路径里不能有中文
rm -rf "$WORK/jni/ffmpeg"
cp -r "$PROJ/prebuilt/ffmpeg" "$WORK/jni/ffmpeg"

cd "$WORK"
"$NDK/ndk-build.cmd" NDK_PROJECT_PATH=. APP_BUILD_SCRIPT=jni/Android.mk \
    NDK_APPLICATION_MK=jni/Application.mk -j4

for abi in arm64-v8a armeabi-v7a x86_64; do
  mkdir -p "$PROJ/app/src/main/jniLibs/$abi"
  # ndk-build 会把 libuaciso.so、libffaudio.so 和 4 个 libav*.so 都放进 libs/<abi>
  cp "$WORK/libs/$abi/"*.so "$PROJ/app/src/main/jniLibs/$abi/"
  echo "installed $abi: $(ls "$PROJ/app/src/main/jniLibs/$abi" | tr '\n' ' ')"
done
