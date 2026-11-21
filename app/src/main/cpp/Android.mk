LOCAL_PATH := $(call my-dir)

# ---------------------------------------------------------------- FFmpeg 预编译库
# 由 Ubuntu 侧 build-ffmpeg-android.sh 生成：ffmpeg/<abi>/lib/*.so
# 注意：文件名已被 scripts/rename-ffmpeg-soname.py 改成 libdacav*，因为老电视的
# /system/lib64 里自带 hi3751 的 libavcodec.so/libavformat.so，Android 5.1 的 linker
# 没有命名空间隔离，同名会被系统的老版本抢先解析 → 我们的 libffaudio.so dlopen 失败。
FF_ABI := $(LOCAL_PATH)/ffmpeg/$(TARGET_ARCH_ABI)

include $(CLEAR_VARS)
LOCAL_MODULE := dacavutil
LOCAL_SRC_FILES := ffmpeg/$(TARGET_ARCH_ABI)/lib/libdacvut.so
include $(PREBUILT_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := dacswresample
LOCAL_SRC_FILES := ffmpeg/$(TARGET_ARCH_ABI)/lib/libdacswresmp.so
include $(PREBUILT_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := dacavcodec
LOCAL_SRC_FILES := ffmpeg/$(TARGET_ARCH_ABI)/lib/libdacavc2.so
include $(PREBUILT_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := dacavformat
LOCAL_SRC_FILES := ffmpeg/$(TARGET_ARCH_ABI)/lib/libdacafmts.so
include $(PREBUILT_SHARED_LIBRARY)

# ---------------------------------------------------------------- USB 等时直通
include $(CLEAR_VARS)
LOCAL_MODULE    := uaciso
LOCAL_SRC_FILES := uac_iso.c audio_out.c
# AAudio 是 Android 8.0(API 26) 才有的 stub，当前 APP_PLATFORM=android-22 下 sysroot 里没有
# libaaudio.so；audio_out.c 里对应的函数已用 #if __ANDROID_API__ >= 26 屏蔽，故这里不链接。
LOCAL_LDLIBS    := -llog -lOpenSLES
LOCAL_CFLAGS    := -O2 -Wall
include $(BUILD_SHARED_LIBRARY)

# ---------------------------------------------------------------- FFmpeg 解封装/解码封装
include $(CLEAR_VARS)
LOCAL_MODULE           := ffaudio
LOCAL_SRC_FILES        := ffaudio.c
LOCAL_C_INCLUDES       := $(FF_ABI)/include
LOCAL_SHARED_LIBRARIES := dacavformat dacavcodec dacswresample dacavutil
LOCAL_LDLIBS           := -llog -lz
LOCAL_CFLAGS           := -O2 -Wall
include $(BUILD_SHARED_LIBRARY)
