// USB Audio Class 等时（isochronous）直通输出的最小实现。
//
// Java 侧：UsbManager.openDevice() -> claimInterface(iface, force=true) -> getFileDescriptor()
// 这里：dup fd -> SETINTERFACE(alt) -> 反复 SUBMITURB / REAPURBNDELAY 推送 PCM。
//
// 目前只送 1 kHz 正弦测试音，用来验证“独占 + 等时传输”这条路通不通；
// 真正的播放数据要等 ExoPlayer 的自定义 AudioSink 接进来。

#include <jni.h>
#include <android/log.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <errno.h>
#include <pthread.h>

#define TAG "uaciso"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static int g_fd = -1;
static volatile int g_running = 0;
static pthread_t g_thread;
static int g_iface = 0, g_alt = 1, g_ep = 0x01;
static int g_rate = 48000, g_channels = 2, g_bps = 2;
static int g_packets = 8, g_nurbs = 8;
static int g_max_packet_bytes = 0;
static struct usbdevfs_urb **g_urbs = NULL;
static unsigned char **g_bufs = NULL;
static volatile int g_submitted = 0, g_completed = 0, g_errors = 0;
static long long g_acc = 0;
static double g_phase = 0.0;
static volatile float g_gain = 0.25f;
// 起播/欠载恢复时的淡入（0..1），避免第一帧从静音直接跳到满幅导致"啪"的爆音
static volatile float g_ramp = 0.0f;
static int g_ramp_frames = 0;

// ---- 流模式（真正播放 PCM）用的环形缓冲 ----
static volatile int g_mode = 0;          // 0 = 测试音, 1 = 播放流
static volatile int g_paused = 0;
static volatile long long g_frames_out = 0;
static unsigned char *g_rb = NULL;
static size_t g_rb_cap = 0, g_rb_head = 0, g_rb_tail = 0;
static int g_urbs_kb = 32;               /* 同时挂起的 URB 数（深度越大越抗抖动） */
static int g_rb_kb = 1024;              /* 环形缓冲大小（KB），可由 Java 侧设置 */
static volatile int g_underruns = 0;    /* 欠载次数（每次包填不满就 +1） */
static pthread_mutex_t g_rb_lock = PTHREAD_MUTEX_INITIALIZER;

// USB 流生命周期互斥：PlaybackService 的 500ms 定时同步和设置页按钮可能同时来停流，
// 没有互斥时会 join 同一个线程两次、close(fd) 两次，真机上直接把 USB 子系统搞崩。
static pthread_mutex_t g_life = PTHREAD_MUTEX_INITIALIZER;

static void stop_engine_locked(void);
static int bytes_per_frame(void) { return g_channels * g_bps; }

static void write_sample(unsigned char *p, double v) {
    if (g_bps == 2) {
        int s = (int)lrint(v * 32767.0);
        p[0] = (unsigned char)(s & 0xFF);
        p[1] = (unsigned char)((s >> 8) & 0xFF);
    } else if (g_bps == 3) {
        int s = (int)lrint(v * 8388607.0);
        p[0] = (unsigned char)(s & 0xFF);
        p[1] = (unsigned char)((s >> 8) & 0xFF);
        p[2] = (unsigned char)((s >> 16) & 0xFF);
    } else {
        int s = (int)lrint(v * 2147483647.0);
        p[0] = (unsigned char)(s & 0xFF);
        p[1] = (unsigned char)((s >> 8) & 0xFF);
        p[2] = (unsigned char)((s >> 16) & 0xFF);
        p[3] = (unsigned char)((s >> 24) & 0xFF);
    }
}

static void fill_tone(struct usbdevfs_urb *u) {
    unsigned char *base = (unsigned char *) u->buffer;
    int off = 0;
    const double step = 2.0 * M_PI * 1000.0 / (double) g_rate;
    for (int k = 0; k < u->number_of_packets; k++) {
        g_acc += g_rate;
        int frames = (int) (g_acc / 8000);
        g_acc %= 8000;
        if (frames <= 0) frames = 1;
        int bytes = frames * bytes_per_frame();
        if (bytes > g_max_packet_bytes) {
            frames = g_max_packet_bytes / bytes_per_frame();
            bytes = frames * bytes_per_frame();
        }
        u->iso_frame_desc[k].length = (unsigned int) bytes;
        u->iso_frame_desc[k].actual_length = 0;
        u->iso_frame_desc[k].status = 0;
        for (int f = 0; f < frames; f++) {
            double v = 0.05 * g_gain * sin(g_phase);
            g_phase += step;
            if (g_phase > 2.0 * M_PI) g_phase -= 2.0 * M_PI;
            for (int c = 0; c < g_channels; c++) {
                write_sample(base + off, v);
                off += g_bps;
            }
        }
    }
    u->buffer_length = off;
}

static size_t rb_used(void) { return (g_rb_tail + g_rb_cap - g_rb_head) % g_rb_cap; }
static size_t rb_free_bytes(void) { return g_rb_cap - 1 - rb_used(); }

/* 对一段已经打包好的 PCM 就地做软件衰减（g_gain），并叠加起播淡入（g_ramp）。 */
/* 为什么放在发包这一侧而不是写入侧：环形缓冲里最多压着 1.4 s 未衰减的数据， */
/* 在 fill_stream 里缩放，拖动音量条对正在出声的包立刻生效，不需要清空缓冲。 */
static void apply_gain_ramp(unsigned char *p, int frames) {
    if (frames <= 0 || g_bps < 2) return;
    const float step = g_ramp_frames > 0 ? 1.0f / (float) g_ramp_frames : 1.0f;
    const float gain = g_gain;
    float ramp = g_ramp;
    unsigned char *q = p;
    for (int f = 0; f < frames; f++) {
        float g = gain * ramp;
        for (int c = 0; c < g_channels; c++, q += g_bps) {
            if (g >= 0.9999f) continue;   /* 不衰减、已淡入满：数据原样送出 */
            if (g_bps == 2) {
                int s = (int) (short) ((unsigned short) (q[0] | (q[1] << 8)));
                s = (int) lrintf((float) s * g);
                if (s > 32767) s = 32767;
                if (s < -32768) s = -32768;
                q[0] = (unsigned char) (s & 0xFF);
                q[1] = (unsigned char) ((s >> 8) & 0xFF);
            } else if (g_bps == 3) {
                int s = (int) (q[0] | (q[1] << 8) | (q[2] << 16));
                if (s & 0x800000) s |= ~0xFFFFFF;
                long v = lrint((double) s * (double) g);
                if (v > 8388607) v = 8388607;
                if (v < -8388608) v = -8388608;
                q[0] = (unsigned char) (v & 0xFF);
                q[1] = (unsigned char) ((v >> 8) & 0xFF);
                q[2] = (unsigned char) ((v >> 16) & 0xFF);
            } else {
                int s = (int) ((unsigned int) q[0] | ((unsigned int) q[1] << 8) |
                               ((unsigned int) q[2] << 16) | ((unsigned int) q[3] << 24));
                long long v = llrint((double) s * (double) g);
                if (v > 2147483647LL) v = 2147483647LL;
                if (v < -2147483648LL) v = -2147483648LL;
                q[0] = (unsigned char) (v & 0xFF);
                q[1] = (unsigned char) ((v >> 8) & 0xFF);
                q[2] = (unsigned char) ((v >> 16) & 0xFF);
                q[3] = (unsigned char) ((v >> 24) & 0xFF);
            }
        }
        ramp += step;
        if (ramp > 1.0f) ramp = 1.0f;
    }
    g_ramp = ramp;
}

static void fill_stream(struct usbdevfs_urb *u) {
    unsigned char *base = (unsigned char *) u->buffer;
    int off = 0;
    for (int k = 0; k < u->number_of_packets; k++) {
        g_acc += g_rate;
        int frames = (int) (g_acc / 8000);
        g_acc %= 8000;
        if (frames <= 0) frames = 1;
        int bytes = frames * bytes_per_frame();
        if (bytes > g_max_packet_bytes) {
            frames = g_max_packet_bytes / bytes_per_frame();
            bytes = frames * bytes_per_frame();
        }
        u->iso_frame_desc[k].length = (unsigned int) bytes;
        u->iso_frame_desc[k].actual_length = 0;
        u->iso_frame_desc[k].status = 0;

        int got = 0;
        if (!g_paused && g_rb != NULL) {
            pthread_mutex_lock(&g_rb_lock);
            size_t avail = rb_used();
            got = avail < (size_t) bytes ? (int) avail : bytes;
            for (int i = 0; i < got; i++) {
                base[off + i] = g_rb[g_rb_head];
                g_rb_head = (g_rb_head + 1) % g_rb_cap;
            }
            pthread_mutex_unlock(&g_rb_lock);
        }
        int got_frames = got / bytes_per_frame();
        if (got_frames > 0) apply_gain_ramp(base + off, got_frames);
        if (got < bytes) {
            g_underruns++;
            memset(base + off + got, 0, (size_t) (bytes - got));
            /* 欠载/暂停：把淡入重置为 0，下次有数据时重新 5 ms 淡入，不硬切出爆音 */
            g_ramp = 0.0f;
        }
        off += bytes;
        if (!g_paused) g_frames_out += frames;
    }
    u->buffer_length = off;
}

static struct usbdevfs_urb *alloc_urb(void) {
    size_t sz = sizeof(struct usbdevfs_urb) + sizeof(struct usbdevfs_iso_packet_desc) * g_packets;
    struct usbdevfs_urb *u = (struct usbdevfs_urb *) calloc(1, sz);
    if (!u) return NULL;
    u->type = USBDEVFS_URB_TYPE_ISO;
    u->endpoint = (unsigned char) g_ep;
    u->number_of_packets = g_packets;
    return u;
}

static void *worker(void *arg) {
    (void) arg;
    while (g_running) {
        struct usbdevfs_urb *u = NULL;
        int r = ioctl(g_fd, USBDEVFS_REAPURBNDELAY, &u);
        if (r == 0 && u != NULL) {
            g_completed++;
            if (u->status != 0) {
                g_errors++;
                u->status = 0;
            }
            if (!g_running) break;
            if (g_mode == 1) fill_stream(u); else fill_tone(u);
            if (ioctl(g_fd, USBDEVFS_SUBMITURB, u) == 0) g_submitted++;
            else g_errors++;
        } else {
            usleep(300);
        }
    }
    return NULL;
}

static void cleanup(void) {
    if (g_urbs) {
        for (int i = 0; i < g_nurbs; i++) {
            if (g_urbs[i]) {
                ioctl(g_fd, USBDEVFS_DISCARDURB, g_urbs[i]);
                free(g_urbs[i]);
                g_urbs[i] = NULL;
            }
            if (g_bufs && g_bufs[i]) {
                free(g_bufs[i]);
                g_bufs[i] = NULL;
            }
        }
        free(g_urbs);
        g_urbs = NULL;
    }
    if (g_bufs) {
        free(g_bufs);
        g_bufs = NULL;
    }
}

JNIEXPORT void JNICALL
Java_com_dacweb_player_usb_UacIso_nativeSetGain(JNIEnv *env, jobject thiz, jfloat gain) {
    (void) env; (void) thiz;
    if (gain < 0.0f) gain = 0.0f;
    if (gain > 1.0f) gain = 1.0f;
    g_gain = gain;
}

static int start_engine_locked(jint fd, jint iface, jint alt, jint ep,
                               jint rate, jint channels, jint bps,
                               jint packets, jint max_packet, int mode) {
    if (g_running) return -1;
    g_mode = mode;
    g_paused = 0;
    g_frames_out = 0;
    g_fd = dup(fd);
    if (g_fd < 0) return -errno;

    g_iface = iface; g_alt = alt; g_ep = ep;
    g_rate = rate; g_channels = channels; g_bps = bps;
    g_packets = packets > 0 ? packets : 8;
    g_nurbs = g_urbs_kb > 0 ? g_urbs_kb : 16;
    if (g_nurbs < 4) g_nurbs = 4;
    if (g_nurbs > 128) g_nurbs = 128;
    g_acc = 0; g_phase = 0.0;
    // 5 ms 淡入：起播和欠载恢复时把 0→满幅 的台阶变成斜坡，消掉"咔/啪"的爆音
    g_ramp = 0.0f;
    g_ramp_frames = g_rate / 200;
    if (g_ramp_frames < 16) g_ramp_frames = 16;
    if (g_ramp_frames > 4096) g_ramp_frames = 4096;
    g_submitted = g_completed = g_errors = 0;
    g_underruns = 0;

    int fbytes = bytes_per_frame();
    int max_frames = g_rate / 8000 + 2;
    g_max_packet_bytes = max_frames * fbytes;
    // 每个 microframe 的包不能超过端点 wMaxPacketSize，否则内核直接返回 -EINVAL
    if (max_packet > 0 && g_max_packet_bytes > max_packet) {
        g_max_packet_bytes = max_packet - (max_packet % fbytes);
        if (g_max_packet_bytes <= 0) g_max_packet_bytes = max_packet;
    }

    struct usbdevfs_setinterface si;
    si.interface = (unsigned int) g_iface;
    si.altsetting = (unsigned int) g_alt;
    if (ioctl(g_fd, USBDEVFS_SETINTERFACE, &si) != 0) {
        int e = errno;
        LOGE("SETINTERFACE(%d,%d) failed: %s", g_iface, g_alt, strerror(e));
        close(g_fd); g_fd = -1;
        return -e;
    }

    g_urbs = (struct usbdevfs_urb **) calloc((size_t) g_nurbs, sizeof(void *));
    g_bufs = (unsigned char **) calloc((size_t) g_nurbs, sizeof(void *));
    if (!g_urbs || !g_bufs) { cleanup(); close(g_fd); g_fd = -1; return -ENOMEM; }

    for (int i = 0; i < g_nurbs; i++) {
        g_bufs[i] = (unsigned char *) calloc(1, (size_t) g_max_packet_bytes * g_packets);
        g_urbs[i] = alloc_urb();
        if (!g_bufs[i] || !g_urbs[i]) { cleanup(); close(g_fd); g_fd = -1; return -ENOMEM; }
        g_urbs[i]->buffer = g_bufs[i];
        g_urbs[i]->buffer_length = g_max_packet_bytes * g_packets;
        g_urbs[i]->signr = 0;
    }

    g_running = 1;
    if (g_mode == 1) {
        size_t want = (size_t) g_rb_kb * 1024u;
        if (want < 64u * 1024u) want = 64u * 1024u;
        if (want > 16u * 1024u * 1024u) want = 16u * 1024u * 1024u;
        if (g_rb != NULL && g_rb_cap != want) {
            free(g_rb);
            g_rb = NULL;
        }
        if (g_rb == NULL) {
            g_rb_cap = want;
            g_rb = (unsigned char *) malloc(g_rb_cap);
            LOGI("ring buffer: %d KB", (int) (g_rb_cap / 1024));
        }
        g_rb_head = g_rb_tail = 0;
    }

    for (int i = 0; i < g_nurbs; i++) {
        if (g_mode == 1) fill_stream(g_urbs[i]); else fill_tone(g_urbs[i]);
        if (ioctl(g_fd, USBDEVFS_SUBMITURB, g_urbs[i]) == 0) {
            g_submitted++;
        } else {
            g_errors++;
            LOGE("SUBMITURB %d failed: %s (ep=0x%02x packets=%d buf_len=%d total=%d)",
                 i, strerror(errno), g_ep, g_packets, g_urbs[i]->buffer_length,
                 g_max_packet_bytes * g_packets);
            g_running = 0;
            cleanup();
            close(g_fd); g_fd = -1;
            return -errno;
        }
    }

    if (pthread_create(&g_thread, NULL, worker, NULL) != 0) {
        g_running = 0;
        cleanup();
        close(g_fd); g_fd = -1;
        return -EAGAIN;
    }
    LOGI("started: iface=%d alt=%d ep=0x%02X rate=%d ch=%d bps=%d", g_iface, g_alt, g_ep, g_rate, g_channels, g_bps);
    return 0;
}

// 生命周期串行化：Java 侧可能从两个线程同时调 start/stop，
// 并发 join / 关 fd 会让 USB 子系统崩掉（真机上就是"点停止直通后设备重启"）。
static int start_engine(jint fd, jint iface, jint alt, jint ep,
                        jint rate, jint channels, jint bps,
                        jint packets, jint max_packet, int mode) {
    pthread_mutex_lock(&g_life);
    int rc = start_engine_locked(fd, iface, alt, ep, rate, channels, bps, packets, max_packet, mode);
    pthread_mutex_unlock(&g_life);
    return rc;
}

JNIEXPORT jint JNICALL
Java_com_dacweb_player_usb_UacIso_nativeStart(JNIEnv *env, jobject thiz,
                                               jint fd, jint iface, jint alt, jint ep,
                                               jint rate, jint channels, jint bps,
                                               jint packets, jint max_packet) {
    (void) env; (void) thiz;
    return start_engine(fd, iface, alt, ep, rate, channels, bps, packets, max_packet, 0);
}

JNIEXPORT jint JNICALL
Java_com_dacweb_player_usb_UacIso_nativeStreamStart(JNIEnv *env, jobject thiz,
                                                     jint fd, jint iface, jint alt, jint ep,
                                                     jint rate, jint channels, jint bps,
                                                     jint packets, jint max_packet) {
    (void) env; (void) thiz;
    return start_engine(fd, iface, alt, ep, rate, channels, bps, packets, max_packet, 1);
}

// 把解码出的 PCM 写进环形缓冲；缓冲满时阻塞（2 ms 轮询），返回实际写入字节数。
JNIEXPORT jint JNICALL
Java_com_dacweb_player_usb_UacIso_nativeWrite(JNIEnv *env, jobject thiz,
                                               jbyteArray data, jint length) {
    (void) thiz;
    if (!g_running || g_mode != 1 || g_rb == NULL || length <= 0) return -1;
    jbyte *src = (*env)->GetByteArrayElements(env, data, NULL);
    if (src == NULL) return -2;
    int total = length, done = 0;
    while (done < total && g_running) {
        pthread_mutex_lock(&g_rb_lock);
        size_t freeb = rb_free_bytes();
        int take = freeb < (size_t) (total - done) ? (int) freeb : (total - done);
        for (int i = 0; i < take; i++) {
            g_rb[g_rb_tail] = (unsigned char) src[done + i];
            g_rb_tail = (g_rb_tail + 1) % g_rb_cap;
        }
        pthread_mutex_unlock(&g_rb_lock);
        done += take;
        if (done < total) usleep(2000);
    }
    (*env)->ReleaseByteArrayElements(env, data, src, JNI_ABORT);
    return done;
}

JNIEXPORT void JNICALL
Java_com_dacweb_player_usb_UacIso_nativeSetPaused(JNIEnv *env, jobject thiz, jboolean paused) {
    (void) env; (void) thiz;
    g_paused = paused ? 1 : 0;
}

JNIEXPORT jlong JNICALL
Java_com_dacweb_player_usb_UacIso_nativeFrames(JNIEnv *env, jobject thiz) {
    (void) env; (void) thiz;
    return (jlong) g_frames_out;
}

JNIEXPORT void JNICALL
Java_com_dacweb_player_usb_UacIso_nativeSetBuffer(JNIEnv *env, jobject thiz, jint kb) {
    (void) env; (void) thiz;
    if (kb < 64) kb = 64;
    if (kb > 16384) kb = 16384;
    g_rb_kb = kb;
}

JNIEXPORT void JNICALL
Java_com_dacweb_player_usb_UacIso_nativeSetUrbs(JNIEnv *env, jobject thiz, jint n) {
    (void) env; (void) thiz;
    if (n < 4) n = 4;
    if (n > 128) n = 128;
    g_urbs_kb = n;
}

JNIEXPORT jint JNICALL
Java_com_dacweb_player_usb_UacIso_nativeCapacity(JNIEnv *env, jobject thiz) {
    (void) env; (void) thiz;
    return (jint) g_rb_cap;
}

JNIEXPORT jint JNICALL
Java_com_dacweb_player_usb_UacIso_nativeUnderruns(JNIEnv *env, jobject thiz) {
    (void) env; (void) thiz;
    return g_underruns;
}

JNIEXPORT jint JNICALL
Java_com_dacweb_player_usb_UacIso_nativeBuffered(JNIEnv *env, jobject thiz) {
    (void) env; (void) thiz;
    pthread_mutex_lock(&g_rb_lock);
    size_t used = g_rb_cap ? rb_used() : 0;
    pthread_mutex_unlock(&g_rb_lock);
    return (jint) used;
}

JNIEXPORT void JNICALL
Java_com_dacweb_player_usb_UacIso_nativeStop(JNIEnv *env, jobject thiz) {
    (void) env; (void) thiz;
    pthread_mutex_lock(&g_life);
    stop_engine_locked();
    pthread_mutex_unlock(&g_life);
}

static void stop_engine_locked(void) {
    if (!g_running) return;
    g_running = 0;
    g_mode = 0;
    g_paused = 0;
    pthread_join(g_thread, NULL);
    cleanup();
    if (g_fd >= 0) {
        /* 关流前先把 alt setting 退回 0：否则 DAC 端还在收尾的半个包，
           退出时耳机里会爆出一串沙沙声。 */
        struct usbdevfs_setinterface si;
        si.interface = (unsigned int) g_iface;
        si.altsetting = 0;
        ioctl(g_fd, USBDEVFS_SETINTERFACE, &si);
        usleep(20000);
        close(g_fd);
        g_fd = -1;
    }
    LOGI("stopped: submitted=%d completed=%d errors=%d", g_submitted, g_completed, g_errors);
}

JNIEXPORT jintArray JNICALL
Java_com_dacweb_player_usb_UacIso_nativeStats(JNIEnv *env, jobject thiz) {
    (void) thiz;
    jintArray arr = (*env)->NewIntArray(env, 3);
    if (!arr) return NULL;
    jint vals[3] = { g_submitted, g_completed, g_errors };
    (*env)->SetIntArrayRegion(env, arr, 0, 3, vals);
    return arr;
}

JNIEXPORT jboolean JNICALL
Java_com_dacweb_player_usb_UacIso_nativeRunning(JNIEnv *env, jobject thiz) {
    (void) env; (void) thiz;
    return g_running ? JNI_TRUE : JNI_FALSE;
}
