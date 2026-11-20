/*
 * ffaudio.c —— 用 FFmpeg 解码本地音频，直接输出成 DAC 需要的 PCM 排布。
 *
 * 为什么要有它：
 *   MediaExtractor / MediaCodec 给出的字段互相矛盾（真机上 24bit/96k 的 WAV：
 *   pcm-encoding=16bit、bits-per-sample=24、readSampleData 一次吐 32KB），
 *   于是"每样本占几个字节"只能靠猜。FFmpeg 解析容器时直接给
 *   AVCodecContext.sample_fmt / bits_per_raw_sample，再由 libswresample
 *   输出我们指定宽度的样本，取整/打包都在库里做，宿主侧不再判断。
 *
 * 只处理本地文件：path 走 file: 协议；content:// 用 fd + 自定义 AVIO 回调。
 */
#include <jni.h>
#include <android/log.h>
#include <string.h>
#include <stdlib.h>
#include <unistd.h>
#include <errno.h>
#include <stdio.h>

#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libswresample/swresample.h>
#include <libavutil/opt.h>
#include <libavutil/channel_layout.h>
#include <libavutil/error.h>
#include <libavutil/samplefmt.h>

#define TAG "FfAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

typedef struct {
    AVFormatContext *fmt;
    AVCodecContext *dec;
    SwrContext *swr;
    AVIOContext *pb;
    int stream;
    int fd;              /* 自备 fd（content://），-1 表示用文件路径 */
    int outBps;          /* 2 / 3 / 4 */
    int outRate;
    int outCh;
    AVFrame *frame;
    AVPacket *pkt;
    uint8_t *conv;       /* swr 输出暂存 */
    int convCap;         /* 字节 */
    uint8_t *pend;       /* 尚未被 Kotlin 取走的数据 */
    int pendLen;
    int pendOff;
    int eof;             /* 已送入 flush 包 */
    int drained;         /* swr 已 flush 完 */
    int srcRate, srcCh, srcBits;
    int64_t durationMs;
    char err[256];
} Ff;

static void set_err(Ff *f, const char *what, int code) {
    char buf[128] = {0};
    if (code != 0) av_strerror(code, buf, sizeof(buf));
    snprintf(f->err, sizeof(f->err), "%s%s%s", what,
             code != 0 ? "：" : "", code != 0 ? buf : "");
    LOGE("%s", f->err);
}

/* ------------------------------------------------------------------ AVIO(fd) */
/*
 * 必须给 AVIO 一个 seek 回调：只给 read 的话 FFmpeg 把这个输入当"不可 seek 的管道"，
 * wav/flac 这类需要回退读头的 demuxer 会在探测阶段就把流判错，表现为读几帧后"解码中断"。
 * 这也是 ffmpeg-kit / mobile-ffmpeg 的标准写法（read + seek 都挂上）。
 */
static int64_t fd_seek(void *opaque, int64_t offset, int whence) {
    int fd = *(int *) opaque;
    if (whence == AVSEEK_SIZE) {
        off_t cur = lseek(fd, 0, SEEK_CUR);
        if (cur < 0) return AVERROR(errno);
        off_t end = lseek(fd, 0, SEEK_END);
        lseek(fd, cur, SEEK_SET);
        return end < 0 ? AVERROR(errno) : (int64_t) end;
    }
    whence &= ~AVSEEK_FORCE;
    off_t r = lseek(fd, (off_t) offset, whence);
    return r < 0 ? AVERROR(errno) : (int64_t) r;
}

static int fd_read(void *opaque, uint8_t *buf, int size) {
    int fd = *(int *) opaque;
    int total = 0;
    while (total < size) {
        ssize_t n = read(fd, buf + total, (size_t) (size - total));
        if (n <= 0) {
            if (n < 0 && errno == EINTR) continue;
            break;
        }
        total += (int) n;
    }
    return total;
}

/*
 * 目标采样格式。FFmpeg 没有"3 字节打包"格式（AV_SAMPLE_FMT_S24 在 7.x 里不存在），
 * 所以 3 字节槽先按 S32 转换，再由 pack3() 取高 3 字节——等价于 24bit 左对齐进 32bit 字。
 */
static enum AVSampleFormat out_fmt_for(int bps) {
    switch (bps) {
        case 2: return AV_SAMPLE_FMT_S16;
        case 3: return AV_SAMPLE_FMT_S32;
        case 4: return AV_SAMPLE_FMT_S32;
        default: return AV_SAMPLE_FMT_S16;
    }
}

static void ff_free(Ff *f) {
    if (!f) return;
    if (f->pend) free(f->pend);
    if (f->conv) free(f->conv);
    if (f->frame) av_frame_free(&f->frame);
    if (f->pkt) av_packet_free(&f->pkt);
    if (f->swr) swr_free(&f->swr);
    if (f->dec) avcodec_free_context(&f->dec);
    if (f->fmt) avformat_close_input(&f->fmt);
    if (f->pb) {
        if (f->pb->buffer) av_freep(&f->pb->buffer);
        avio_context_free(&f->pb);
    }
    if (f->fd >= 0) close(f->fd);
    free(f);
}

/* ------------------------------------------------------------------ open */
JNIEXPORT jlong JNICALL
Java_com_music24_player_audio_FfAudio_nativeOpen(JNIEnv *env, jclass clazz,
                                                 jstring jpath, jint jfd,
                                                 jint wantRate, jint wantBps) {
    Ff *f = (Ff *) calloc(1, sizeof(Ff));
    if (!f) return 0;
    f->fd = -1;
    const char *path = jpath ? (*env)->GetStringUTFChars(env, jpath, NULL) : NULL;
    int ret;

    if (jfd >= 0) {
        f->fd = dup(jfd);
        if (f->fd < 0) { set_err(f, "dup(fd) 失败", 0); ff_free(f); return 0; }
        /* dup 出来的 fd 与调用方共享同一个"文件描述"，偏移量是共享的：
         * 只要调用方先读过一点（比如探位深），这里就会从中间开始，必须强制回到 0。 */
        off_t size = lseek(f->fd, 0, SEEK_END);
        lseek(f->fd, 0, SEEK_SET);
        uint8_t magic[4] = {0};
        ssize_t got = read(f->fd, magic, 4);
        lseek(f->fd, 0, SEEK_SET);
        LOGI("fd open: size=%lld got=%d magic=%02x%02x%02x%02x", (long long) size, (int) got,
             magic[0], magic[1], magic[2], magic[3]);
        uint8_t *iobuf = (uint8_t *) av_malloc(64 * 1024);
        f->pb = avio_alloc_context(iobuf, 64 * 1024, 0, &f->fd, fd_read, NULL, fd_seek);
        f->fmt = avformat_alloc_context();
        f->fmt->pb = f->pb;
        f->fmt->flags |= AVFMT_FLAG_CUSTOM_IO;
        ret = avformat_open_input(&f->fmt, NULL, NULL, NULL);
    } else {
        ret = avformat_open_input(&f->fmt, path, NULL, NULL);
    }
    if (path) (*env)->ReleaseStringUTFChars(env, jpath, path);
    if (ret < 0) { set_err(f, "打不开文件", ret); ff_free(f); return 0; }

    ret = avformat_find_stream_info(f->fmt, NULL);
    if (ret < 0) { set_err(f, "读不到流信息", ret); ff_free(f); return 0; }

    const AVCodec *codec = NULL;
    f->stream = av_find_best_stream(f->fmt, AVMEDIA_TYPE_AUDIO, -1, -1, &codec, 0);
    if (f->stream < 0 || !codec) {
        set_err(f, "没有音频轨", f->stream);
        ff_free(f);
        return 0;
    }
    AVStream *st = f->fmt->streams[f->stream];
    f->dec = avcodec_alloc_context3(codec);
    if (!f->dec || avcodec_parameters_to_context(f->dec, st->codecpar) < 0) {
        set_err(f, "解码器初始化失败", 0);
        ff_free(f);
        return 0;
    }
    f->dec->pkt_timebase = st->time_base;
    ret = avcodec_open2(f->dec, codec, NULL);
    if (ret < 0) { set_err(f, "打不开解码器", ret); ff_free(f); return 0; }

    f->srcRate = f->dec->sample_rate;
    f->srcCh = f->dec->ch_layout.nb_channels > 0 ? f->dec->ch_layout.nb_channels
                                                 : st->codecpar->ch_layout.nb_channels;
    if (f->srcCh <= 0) f->srcCh = 1;

    /* "真实位深"：容器/解码器给的位深优先；float 一律按 32 处理 */
    int rawBits = f->dec->bits_per_raw_sample;
    if (rawBits <= 0) rawBits = av_get_bits_per_sample(f->dec->codec_id);
    if (rawBits <= 0) {
        switch (f->dec->sample_fmt) {
            case AV_SAMPLE_FMT_S16: rawBits = 16; break;
            case AV_SAMPLE_FMT_S32: rawBits = 32; break;
            case AV_SAMPLE_FMT_FLT:
            case AV_SAMPLE_FMT_DBL: rawBits = 32; break;
            default: rawBits = av_get_bytes_per_sample(f->dec->sample_fmt) * 8; break;
        }
    }
    f->srcBits = rawBits;

    /* wantBps=0 表示"按源位深"：24bit 源必须给 3 字节，否则直通会静悄悄降到 16bit。 */
    int autoBps = f->srcBits > 24 ? 4 : (f->srcBits > 16 ? 3 : 2);
    f->outBps = (wantBps == 2 || wantBps == 3 || wantBps == 4) ? wantBps : autoBps;
    f->outRate = wantRate > 0 ? wantRate : f->srcRate;
    f->outCh = f->srcCh >= 2 ? 2 : 1;

    AVChannelLayout outLayout;
    av_channel_layout_default(&outLayout, f->outCh);

    ret = swr_alloc_set_opts2(&f->swr,
                              &outLayout, out_fmt_for(f->outBps), f->outRate,
                              &f->dec->ch_layout, f->dec->sample_fmt, f->srcRate,
                              0, NULL);
    av_channel_layout_uninit(&outLayout);
    if (ret < 0 || swr_init(f->swr) < 0) {
        set_err(f, "重采样器初始化失败", ret);
        ff_free(f);
        return 0;
    }

    f->frame = av_frame_alloc();
    f->pkt = av_packet_alloc();
    /* swr 的 S16/S24/S32 输出中最宽是 4 字节（3 字节槽也是先按 S32 转换），按 4 分配 */
    f->convCap = f->outCh * 4 * 8192;
    f->conv = (uint8_t *) malloc(f->convCap);
    f->pend = (uint8_t *) malloc(f->convCap);
    if (!f->frame || !f->pkt || !f->conv || !f->pend) {
        set_err(f, "内存不足", 0);
        ff_free(f);
        return 0;
    }

    if (st->duration > 0 && st->duration != AV_NOPTS_VALUE) {
        double sec = st->duration * av_q2d(st->time_base);
        f->durationMs = (int64_t) (sec * 1000.0);
    } else if (f->fmt->duration > 0) {
        f->durationMs = f->fmt->duration / (AV_TIME_BASE / 1000);
    }

    LOGI("open ok: %s src=%dHz/%dch/%dbit fmt=%d -> out=%dHz/%dch/%dbytes",
         codec->name, f->srcRate, f->srcCh, f->srcBits, f->dec->sample_fmt,
         f->outRate, f->outCh, f->outBps);
    return (jlong) (intptr_t) f;
}

/* [srcRate, srcCh, srcBits, outRate, outCh, outBps, durationMs, (int)isFloat] */
JNIEXPORT jintArray JNICALL
Java_com_music24_player_audio_FfAudio_nativeInfo(JNIEnv *env, jclass clazz, jlong h) {
    Ff *f = (Ff *) (intptr_t) h;
    jint v[8] = {0};
    if (f) {
        v[0] = f->srcRate;
        v[1] = f->srcCh;
        v[2] = f->srcBits;
        v[3] = f->outRate;
        v[4] = f->outCh;
        v[5] = f->outBps;
        v[6] = (jint) f->durationMs;
        v[7] = (f->dec && (f->dec->sample_fmt == AV_SAMPLE_FMT_FLT ||
                           f->dec->sample_fmt == AV_SAMPLE_FMT_DBL)) ? 1 : 0;
    }
    jintArray arr = (*env)->NewIntArray(env, 8);
    (*env)->SetIntArrayRegion(env, arr, 0, 8, v);
    return arr;
}

JNIEXPORT jstring JNICALL
Java_com_music24_player_audio_FfAudio_nativeLastError(JNIEnv *env, jclass clazz, jlong h) {
    Ff *f = (Ff *) (intptr_t) h;
    return (*env)->NewStringUTF(env, f && f->err[0] ? f->err : "");
}

/* ------------------------------------------------------------------ read */
/* 把一次 swr 输出搬到 pend；返回写入 pend 的字节数 */
static int push_conv(Ff *f, int samples) {
    if (samples <= 0) return 0;
    int n = samples * f->outCh;
    if (f->outBps == 3) {
        /* S32 → 3 字节：取每个样本高 3 字节（小端），即 24bit 左对齐进 32bit 字 */
        int maxS = f->convCap / 4;   /* 源是 S32，每样本 4 字节 */
        if (n > maxS) n = maxS;
        const uint8_t *src = f->conv;
        uint8_t *dst = f->pend;
        for (int i = 0; i < n; i++) {
            dst[0] = src[1];
            dst[1] = src[2];
            dst[2] = src[3];
            src += 4;
            dst += 3;
        }
        f->pendLen = n * 3;
    } else {
        int bytes = n * f->outBps;
        if (bytes > f->convCap) bytes = f->convCap;
        memcpy(f->pend, f->conv, (size_t) bytes);
        f->pendLen = bytes;
    }
    f->pendOff = 0;
    return f->pendLen;
}

/*
 * 返回：>0 本次读到的字节数；0 表示放完；<0 出错。
 * 每次调用最多填满 len，多余数据留在 pend 里等下次取。
 */
JNIEXPORT jint JNICALL
Java_com_music24_player_audio_FfAudio_nativeRead(JNIEnv *env, jclass clazz,
                                                 jlong h, jbyteArray jbuf, jint cap) {
    Ff *f = (Ff *) (intptr_t) h;
    if (!f || cap <= 0) return -1;

    int outMax = f->convCap / (f->outCh * 4);   /* swr 输出最宽 4 字节/样本 */

    for (;;) {
        if (f->pendOff < f->pendLen) {
            int n = f->pendLen - f->pendOff;
            if (n > cap) n = cap;
            (*env)->SetByteArrayRegion(env, jbuf, 0, n, (const jbyte *) (f->pend + f->pendOff));
            f->pendOff += n;
            return n;
        }

        int ret = avcodec_receive_frame(f->dec, f->frame);
        if (ret == 0) {
            uint8_t *out = f->conv;
            int got = swr_convert(f->swr, &out, outMax,
                                  (const uint8_t **) f->frame->extended_data,
                                  f->frame->nb_samples);
            av_frame_unref(f->frame);
            if (got > 0) { push_conv(f, got); continue; }
            if (got < 0) { set_err(f, "重采样失败", got); return -1; }
            continue;
        }
        if (ret == AVERROR_EOF) {
            if (!f->drained) {
                uint8_t *out = f->conv;
                int got = swr_convert(f->swr, &out, outMax, NULL, 0);
                f->drained = 1;
                if (got > 0) { push_conv(f, got); continue; }
            }
            return 0;
        }
        if (ret != AVERROR(EAGAIN)) { set_err(f, "解码失败", ret); return -1; }

        /* 需要更多输入 */
        if (f->eof) {
            if (!f->drained) {
                uint8_t *out = f->conv;
                int got = swr_convert(f->swr, &out, outMax, NULL, 0);
                f->drained = 1;
                if (got > 0) { push_conv(f, got); continue; }
            }
            return 0;
        }

        for (;;) {
            int r = av_read_frame(f->fmt, f->pkt);
            if (r == AVERROR_EOF) {
                avcodec_send_packet(f->dec, NULL);
                f->eof = 1;
                break;
            }
            if (r < 0) { set_err(f, "读取失败", r); return -1; }
            if (f->pkt->stream_index == f->stream) {
                int s = avcodec_send_packet(f->dec, f->pkt);
                av_packet_unref(f->pkt);
                if (s < 0 && s != AVERROR(EAGAIN)) { set_err(f, "送包失败", s); return -1; }
                break;
            }
            av_packet_unref(f->pkt);
        }
    }
}

/* ------------------------------------------------------ 跳过（代替 seek）
 * av_seek_frame 对自定义 AVIO / 尺寸未知的 WAV 并不可靠：要么落到数据块外
 * 读不到东西，要么干脆卡死在 demuxer 里。这里改成"解码并丢弃"，
 * 用已验证解码出的样本数换算时间，只依赖解码器本身，绝不会卡住不返回。
 * 返回 0 = 已到目标位置；1 = 读到文件末尾；<0 = 出错。
 */
JNIEXPORT jint JNICALL
Java_com_music24_player_audio_FfAudio_nativeSkipTo(JNIEnv *env, jclass clazz,
                                                   jlong h, jlong ms) {
    Ff *f = (Ff *) (intptr_t) h;
    if (!f) return -1;
    if (ms <= 0) return 0;
    double accMs = 0.0;
    const double target = (double) ms;
    for (;;) {
        if (accMs >= target) {
            /* 把 swr 里残留的尾巴和 pend 丢掉，从新位置干净开始 */
            if (f->swr) swr_convert(f->swr, NULL, 0, NULL, 0);
            f->pendLen = f->pendOff = 0;
            return 0;
        }
        int ret = avcodec_receive_frame(f->dec, f->frame);
        if (ret == 0) {
            int ns = f->frame->nb_samples;
            if (ns > 0 && f->srcRate > 0) accMs += ns * 1000.0 / (double) f->srcRate;
            av_frame_unref(f->frame);
            continue;
        }
        if (ret == AVERROR_EOF) return 1;
        if (ret != AVERROR(EAGAIN)) { set_err(f, "跳过时解码失败", ret); return -1; }

        if (f->eof) return 1;
        for (;;) {
            int r = av_read_frame(f->fmt, f->pkt);
            if (r == AVERROR_EOF) {
                avcodec_send_packet(f->dec, NULL);
                f->eof = 1;
                break;
            }
            if (r < 0) { set_err(f, "跳过时读取失败", r); return -1; }
            if (f->pkt->stream_index == f->stream) {
                int sr = avcodec_send_packet(f->dec, f->pkt);
                av_packet_unref(f->pkt);
                if (sr < 0 && sr != AVERROR(EAGAIN)) { set_err(f, "跳过时送包失败", sr); return -1; }
                break;
            }
            av_packet_unref(f->pkt);
        }
    }
}

/* ------------------------------------------------------------------ seek */
JNIEXPORT jint JNICALL
Java_com_music24_player_audio_FfAudio_nativeSeek(JNIEnv *env, jclass clazz,
                                                 jlong h, jlong ms) {
    Ff *f = (Ff *) (intptr_t) h;
    if (!f) return -1;
    AVStream *st = f->fmt->streams[f->stream];
    int64_t ts = av_rescale_q(ms * 1000, AV_TIME_BASE_Q, st->time_base);
    int ret = av_seek_frame(f->fmt, f->stream, ts, AVSEEK_FLAG_BACKWARD);
    if (ret < 0) { set_err(f, "定位失败", ret); return ret; }
    avcodec_flush_buffers(f->dec);
    if (f->swr) swr_convert(f->swr, NULL, 0, NULL, 0);
    f->pendLen = f->pendOff = 0;
    f->eof = f->drained = 0;
    return 0;
}

JNIEXPORT void JNICALL
Java_com_music24_player_audio_FfAudio_nativeClose(JNIEnv *env, jclass clazz, jlong h) {
    Ff *f = (Ff *) (intptr_t) h;
    if (f) ff_free(f);
}

/* ------------------------------------------------------------------ tags */
/*
 * 只读标签：打开 demuxer、读 fmt/stream 的 metadata 字典、取 attached_pic，
 * 不建解码器、不建重采样器——比 nativeOpen 便宜得多，可以在扫描本地库时逐文件调用。
 * 返回 Object[]{ String[10], byte[] 封面 或 null, String 封面 MIME 或 null }。
 */
static const char *const TAG_KEYS[10] = {
    "title", "artist", "album", "album_artist", "date",
    "track", "disc", "genre", "comment", "encoder"
};

static void tag_close(AVFormatContext **fmt, AVIOContext **pb, int *fd) {
    if (*fmt) avformat_close_input(fmt);
    if (*pb) {
        if ((*pb)->buffer) av_freep(&(*pb)->buffer);
        avio_context_free(pb);
    }
    if (*fd >= 0) { close(*fd); *fd = -1; }
}

JNIEXPORT jobjectArray JNICALL
Java_com_music24_player_audio_FfAudio_nativeTags(JNIEnv *env, jclass clazz,
                                                 jstring jpath, jint jfd) {
    jclass strCls = (*env)->FindClass(env, "java/lang/String");
    jclass objCls = (*env)->FindClass(env, "java/lang/Object");
    jobjectArray tags = (*env)->NewObjectArray(env, 10, strCls, NULL);
    jobjectArray out = (*env)->NewObjectArray(env, 3, objCls, NULL);
    if (!tags || !out) return out;
    (*env)->SetObjectArrayElement(env, out, 0, tags);

    AVFormatContext *fmt = NULL;
    AVIOContext *pb = NULL;
    int fd = -1;
    int ret;
    const char *path = jpath ? (*env)->GetStringUTFChars(env, jpath, NULL) : NULL;

    if (jfd >= 0) {
        fd = dup(jfd);
        if (fd < 0) { LOGE("tags: dup(fd) 失败"); goto done; }
        lseek(fd, 0, SEEK_SET);
        uint8_t *iobuf = (uint8_t *) av_malloc(64 * 1024);
        pb = avio_alloc_context(iobuf, 64 * 1024, 0, &fd, fd_read, NULL, fd_seek);
        fmt = avformat_alloc_context();
        if (!pb || !fmt) goto done;
        fmt->pb = pb;
        fmt->flags |= AVFMT_FLAG_CUSTOM_IO;
        ret = avformat_open_input(&fmt, NULL, NULL, NULL);
    } else {
        ret = avformat_open_input(&fmt, path, NULL, NULL);
    }
    if (ret < 0) {
        char buf[128] = {0};
        av_strerror(ret, buf, sizeof(buf));
        LOGE("tags: open failed %s", buf);
        goto done;
    }
    avformat_find_stream_info(fmt, NULL);

    AVStream *ast = NULL;
    for (unsigned i = 0; i < fmt->nb_streams; i++) {
        if (fmt->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_AUDIO) {
            ast = fmt->streams[i];
            break;
        }
    }
    for (int i = 0; i < 10; i++) {
        const AVDictionaryEntry *e = av_dict_get(fmt->metadata, TAG_KEYS[i], NULL, 0);
        if ((!e || !e->value) && ast) e = av_dict_get(ast->metadata, TAG_KEYS[i], NULL, 0);
        if (e && e->value && e->value[0]) {
            jstring s = (*env)->NewStringUTF(env, e->value);
            if (s) (*env)->SetObjectArrayElement(env, tags, i, s);
        }
    }

    for (unsigned i = 0; i < fmt->nb_streams; i++) {
        AVStream *st = fmt->streams[i];
        if (!(st->disposition & AV_DISPOSITION_ATTACHED_PIC)) continue;
        if (st->attached_pic.size <= 0 || !st->attached_pic.data) continue;
        jbyteArray arr = (*env)->NewByteArray(env, st->attached_pic.size);
        if (arr) {
            (*env)->SetByteArrayRegion(env, arr, 0, st->attached_pic.size,
                                       (const jbyte *) st->attached_pic.data);
            (*env)->SetObjectArrayElement(env, out, 1, arr);
            const char *mime = st->codecpar->codec_id == AV_CODEC_ID_PNG ? "image/png" : "image/jpeg";
            (*env)->SetObjectArrayElement(env, out, 2, (*env)->NewStringUTF(env, mime));
            LOGI("tags: cover=%d bytes %s", (int) st->attached_pic.size, mime);
        }
        break;
    }

done:
    if (path) (*env)->ReleaseStringUTFChars(env, jpath, path);
    tag_close(&fmt, &pb, &fd);
    return out;
}
