#!/usr/bin/env python3
# 插件 jar 结构体检：不依赖设备，检查“能不能被宿主正常加载”的硬条件
import glob
import os
import re
import subprocess
import sys
import tempfile
import zipfile


def find_dexdump():
    """优先用环境变量，其次在 ANDROID_SDK_ROOT / ANDROID_HOME 的 build-tools 里找。"""
    env = os.environ.get("DEXDUMP")
    if env and os.path.isfile(env):
        return env
    for root in (os.environ.get("ANDROID_SDK_ROOT"), os.environ.get("ANDROID_HOME")):
        if not root:
            continue
        for cand in sorted(glob.glob(os.path.join(root, "build-tools", "*", "dexdump*")),
                           reverse=True):
            if os.path.isfile(cand):
                return cand
    return "dexdump"


DEXDUMP = find_dexdump()


def main(jar):
    problems, notes = [], []
    if not os.path.isfile(jar):
        print("MISSING", jar)
        return 2
    z = zipfile.ZipFile(jar)
    names = z.namelist()
    print(f"jar    : {jar}  ({os.path.getsize(jar)} B)")
    print(f"entries: {names}")

    if "classes.dex" not in names:
        problems.append("没有 classes.dex（典型错误：直接 jar 打包了 .class）")
        print("\n".join(problems))
        return 1

    dex = z.read("classes.dex")
    ver = dex[4:7].decode("ascii", "replace")
    print(f"dex ver: {ver}  ({dex[:8]})")
    if ver not in ("035",):
        problems.append(f"dex 版本 {ver}，Android 5.1 只认 035")

    marker = None
    for n in names:
        if n.lower().endswith("meta-inf/dac-web-player.plugin"):
            marker = n
    if not marker:
        problems.append("缺少 META-INF/dac-web-player.plugin（宿主找不到入口类）")
    else:
        line = z.read(marker).decode("utf-8", "replace").splitlines()
        print(f"marker : {marker} -> {line[:1]}")
        if not line or not line[0].strip():
            problems.append("marker 首行为空，应写入口类的全名")

    if b"DefaultImpls" in dex:
        notes.append("dex 里出现 DefaultImpls 引用（Kotlin 插件没加 -Xjvm-default=all 的迹象）")

    tmp = os.path.join(tempfile.gettempdir(), "dt-classes.dex")
    with open(tmp, "wb") as f:
        f.write(dex)
    out = subprocess.run([DEXDUMP, "-f", tmp], capture_output=True, text=True,
                         errors="replace").stdout
    import re
    classes = re.findall(r"Class descriptor\s*:\s*'(L[^']+;)'", out)
    print(f"classes: {len(classes)}")
    for c in classes[:20]:
        print("   ", c)
    defs = [c for c in classes if c.startswith("Lcom/dacweb/player/plugin/")]
    if defs:
        problems.append(f"插件里打包了宿主接口类：{defs}（--classpath 指到目录而不是单个 jar 的典型后果）")
    if marker and line and line[0].strip():
        want = "L" + line[0].strip().replace(".", "/") + ";"
        if want not in classes:
            problems.append(f"marker 指向的类 {line[0].strip()} 不在 dex 里")

    print()
    for p in problems:
        print("PROBLEM:", p)
    for n in notes:
        print("NOTE   :", n)
    print("RESULT :", "FAIL" if problems else "OK")
    return 0 if not problems else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
