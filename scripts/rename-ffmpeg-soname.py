#!/usr/bin/env python3
"""把预编译 FFmpeg 的 soname 改成不冲突的名字.

为什么需要：老电视（例如康佳 5.1）的 /system/lib64 里自带 hi3751 的
libavcodec.so / libavformat.so，Android 5.1 的 linker 没有命名空间隔离，
我们的 libffaudio.so 在 DT_NEEDED 里写的 `libavcodec.so` 会命中系统那份老版本
（2018 年的 FFmpeg），符号对不上 → dlopen 直接失败 → 所有 native 功能（标签、直通）
报 UnsatisfiedLinkError。

做法：把 libav*.so 改名成 libdacav*.so，并把所有 .so 里出现的旧 soname 字符串
原地替换成等长的新名字（ELF 的 .dynstr 可以原地改写，长度不变最安全）。

用法： python3 scripts/rename-ffmpeg-soname.py [prebuilt/ffmpeg]
"""
import os
import sys

# 旧名 -> 新名，长度必须相同（原地覆盖，不能改变 dynstr 偏移）
RENAME = {
    b"libavcodec.so": b"libdacavc2.so",     # 13
    b"libavformat.so": b"libdacafmts.so",   # 14
    b"libavutil.so": b"libdacvut.so",       # 12
    b"libswresample.so": b"libdacswresmp.so",  # 16
}

ABIS = ["arm64-v8a", "armeabi-v7a", "x86_64"]


def patch_file(path: str) -> int:
    with open(path, "rb") as f:
        data = f.read()
    orig = data
    hits = 0
    for old, new in RENAME.items():
        assert len(old) == len(new), (old, new)
        hits += data.count(old)
        data = data.replace(old, new)
    if data != orig:
        with open(path, "wb") as f:
            f.write(data)
    return hits


def main() -> int:
    root = sys.argv[1] if len(sys.argv) > 1 else "prebuilt/ffmpeg"
    for abi in ABIS:
        libdir = os.path.join(root, abi, "lib")
        if not os.path.isdir(libdir):
            print("skip", libdir)
            continue
        # 先改内容（就地改名 soname），再改文件名
        for name in sorted(os.listdir(libdir)):
            if not name.endswith(".so"):
                continue
            p = os.path.join(libdir, name)
            n = patch_file(p)
            print(f"{abi}/{name}: patched {n} refs")
        for old, new in RENAME.items():
            op = os.path.join(libdir, old.decode())
            np = os.path.join(libdir, new.decode())
            if os.path.exists(op):
                os.replace(op, np)
                print(f"{abi}: renamed {old.decode()} -> {new.decode()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
