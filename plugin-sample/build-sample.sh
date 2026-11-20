#!/usr/bin/env bash
# 把 plugin-sample 打成宿主可导入的插件包（myprovider.jar）。
#
# 前置：先在工程里构建过一次 app（生成 app/build/tmp/kotlin-classes/debug），
#       本机 JDK 与 Android SDK 路径见下。
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"

JAVA_HOME="${JAVA_HOME:-D:/Java/17}"
SDK="${ANDROID_SDK:-/e/mp/.android-sdk}"
BT="$SDK/build-tools/35.0.0"
ANDROID_JAR="$SDK/platforms/android-35/android.jar"
HOST_CLASSES="$APP/app/build/tmp/kotlin-classes/debug"

if [ ! -f "$ANDROID_JAR" ]; then
  echo "找不到 android.jar：$ANDROID_JAR（用 ANDROID_SDK 环境变量指定 SDK 目录）" >&2
  exit 1
fi
if [ ! -d "$HOST_CLASSES" ]; then
  echo "找不到宿主类：$HOST_CLASSES，请先在 MusicPlayer 里构建一次 app" >&2
  exit 1
fi

OUT="$HERE/build"
rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/pkg/META-INF"

# Windows 版 javac/d8 只认 Windows 路径（E:/xxx），MSYS 的 /e/xxx 在 ";" 列表里会解析失败
win() { cygpath -m "$1" 2>/dev/null || echo "$1"; }
W_ANDROID_JAR="$(win "$ANDROID_JAR")"
W_HOST_CLASSES="$(win "$HOST_CLASSES")"
CP="$W_HOST_CLASSES;$W_ANDROID_JAR"
echo "[1/4] javac"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 17 -target 17 \
  -classpath "$CP" -d "$OUT/classes" \
  $(find "$HERE/src" -name '*.java')

# 插件接口的 class 单独打成一个 jar，方便第三方作者只依赖它来编译（不用整个宿主源码）
API_JAR="$HERE/dac-plugin-api.jar"
rm -f "$API_JAR"
# 只打接口；PluginManager / Providers / BuiltinMetadata 是宿主实现，不该进这个 jar
( cd "$HOST_CLASSES" && "$JAVA_HOME/bin/jar" cfM "$API_JAR" \
    com/dacweb/player/plugin/MusicPlugin.class \
    com/dacweb/player/plugin/PluginFactory.class \
    com/dacweb/player/plugin/PluginTrack.class \
    com/dacweb/player/plugin/PluginStream.class \
    com/dacweb/player/plugin/PluginLyrics.class \
    com/dacweb/player/plugin/PluginMeta.class \
    com/dacweb/player/plugin/PluginLimitException.class )

echo "[2/4] d8 (--min-api 22，否则 Android 5.1 加载不了)"
# classpath 用体积很小的 api jar（用整个宿主 classes 目录会让 d8 把它也打进程序文件）
"$BT/d8.bat" --lib "$W_ANDROID_JAR" --classpath "$(win "$API_JAR")" --min-api 22 \
  --output "$(win "$OUT/dex")" $(find "$OUT/classes" -name '*.class')

echo "[3/4] 打包"
cp "$OUT/dex/classes.dex" "$OUT/pkg/classes.dex"
printf 'com.dacweb.plugin.Entry\n' > "$OUT/pkg/META-INF/dac-web-player.plugin"
rm -f "$HERE/myprovider.jar"
( cd "$OUT/pkg" && "$JAVA_HOME/bin/jar" cfM "$HERE/myprovider.jar" classes.dex META-INF/dac-web-player.plugin )

echo "[4/4] 完成：$HERE/myprovider.jar"
ls -l "$HERE/myprovider.jar"
