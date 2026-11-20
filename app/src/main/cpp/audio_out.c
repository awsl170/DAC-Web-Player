#include <jni.h>
#include <math.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <android/log.h>
#include <aaudio/AAudio.h>
#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>

#define TAG "audiotone"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

/* 1 kHz 测试音，幅度 0.06，避免太吵 */
static void fill_sine(int16_t *buf, int frames, int channels, int rate, double *phase) {
    double step = 2.0 * M_PI * 1000.0 / (double) rate;
    for (int i = 0; i < frames; i++) {
        int16_t v = (int16_t) (32767.0 * 0.06 * sin(*phase));
        for (int c = 0; c < channels; c++) buf[i * channels + c] = v;
        *phase += step;
        if (*phase > 2.0 * M_PI) *phase -= 2.0 * M_PI;
    }
}

JNIEXPORT jint JNICALL
Java_com_music24_player_audio_NativeTone_nativeAAudioTone(
        JNIEnv *env, jobject thiz, jint rate, jint ms, jint channels) {
#if __ANDROID_API__ >= 26
    AAudioStreamBuilder *builder = NULL;
    AAudioStream *stream = NULL;

    if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK || builder == NULL) return -1;
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSampleRate(builder, rate);
    AAudioStreamBuilder_setChannelCount(builder, channels);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);

    aaudio_result_t r = AAudioStreamBuilder_openStream(builder, &stream);
    AAudioStreamBuilder_delete(builder);
    if (r != AAUDIO_OK || stream == NULL) {
        LOGI("AAudio open failed: %d", r);
        return -2;
    }

    if (AAudioStream_requestStart(stream) != AAUDIO_OK) {
        AAudioStream_close(stream);
        return -3;
    }

    const int frames = 256;
    int16_t *buf = (int16_t *) malloc(sizeof(int16_t) * frames * channels);
    if (buf == NULL) {
        AAudioStream_requestStop(stream);
        AAudioStream_close(stream);
        return -4;
    }

    double phase = 0.0;
    long total = (long) rate * (long) ms / 1000L;
    long written = 0;
    int result = 0;

    while (written < total) {
        int n = (int) ((total - written) < frames ? (total - written) : frames);
        fill_sine(buf, n, channels, rate, &phase);
        aaudio_result_t w = AAudioStream_write(stream, buf, n, 1000000000LL);
        if (w < 0) {
            LOGI("AAudio write failed: %d", w);
            result = -5;
            break;
        }
        if (w == 0) continue;
        written += w;
    }

    free(buf);
    AAudioStream_requestStop(stream);
    AAudioStream_close(stream);
    return result;
#else
    /* Android 8.0 以下没有 AAudio（测试音只有这里用，正式播放走 OpenSL/等时直通） */
    (void) env; (void) thiz; (void) rate; (void) ms; (void) channels;
    return -100;
#endif
}

JNIEXPORT jint JNICALL
Java_com_music24_player_audio_NativeTone_nativeOpenSLTone(
        JNIEnv *env, jobject thiz, jint rate, jint ms) {
    SLObjectItf engineObj = NULL;
    SLEngineItf engine = NULL;
    SLObjectItf mixObj = NULL;
    SLObjectItf playerObj = NULL;
    SLPlayItf play = NULL;
    SLAndroidSimpleBufferQueueItf queue = NULL;
    int result = 0;

    if (slCreateEngine(&engineObj, 0, NULL, 0, NULL, NULL) != SL_RESULT_SUCCESS) return -1;
    if ((*engineObj)->Realize(engineObj, SL_BOOLEAN_FALSE) != SL_RESULT_SUCCESS) {
        (*engineObj)->Destroy(engineObj);
        return -2;
    }
    if ((*engineObj)->GetInterface(engineObj, SL_IID_ENGINE, &engine) != SL_RESULT_SUCCESS) {
        (*engineObj)->Destroy(engineObj);
        return -3;
    }
    if ((*engine)->CreateOutputMix(engine, &mixObj, 0, NULL, NULL) != SL_RESULT_SUCCESS) {
        (*engineObj)->Destroy(engineObj);
        return -4;
    }
    if ((*mixObj)->Realize(mixObj, SL_BOOLEAN_FALSE) != SL_RESULT_SUCCESS) {
        (*mixObj)->Destroy(mixObj);
        (*engineObj)->Destroy(engineObj);
        return -5;
    }

    SLDataLocator_AndroidSimpleBufferQueue loc = {SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE, 2};
    SLDataFormat_PCM fmt;
    memset(&fmt, 0, sizeof(fmt));
    fmt.formatType = SL_DATAFORMAT_PCM;
    fmt.numChannels = 2;
    fmt.samplesPerSec = (SLuint32) rate * 1000;
    fmt.bitsPerSample = SL_PCMSAMPLEFORMAT_FIXED_16;
    fmt.containerSize = SL_PCMSAMPLEFORMAT_FIXED_16;
    fmt.channelMask = SL_SPEAKER_FRONT_LEFT | SL_SPEAKER_FRONT_RIGHT;
    fmt.endianness = SL_BYTEORDER_LITTLEENDIAN;

    SLDataSource src = {&loc, &fmt};
    SLDataLocator_OutputMix outLoc = {SL_DATALOCATOR_OUTPUTMIX, mixObj};
    SLDataSink sink = {&outLoc, NULL};

    const SLInterfaceID ids[] = {SL_IID_BUFFERQUEUE};
    const SLboolean req[] = {SL_BOOLEAN_TRUE};

    if ((*engine)->CreateAudioPlayer(engine, &playerObj, &src, &sink, 1, ids, req) != SL_RESULT_SUCCESS) {
        (*mixObj)->Destroy(mixObj);
        (*engineObj)->Destroy(engineObj);
        return -6;
    }
    if ((*playerObj)->Realize(playerObj, SL_BOOLEAN_FALSE) != SL_RESULT_SUCCESS) {
        (*playerObj)->Destroy(playerObj);
        (*mixObj)->Destroy(mixObj);
        (*engineObj)->Destroy(engineObj);
        return -7;
    }
    (*playerObj)->GetInterface(playerObj, SL_IID_PLAY, &play);
    (*playerObj)->GetInterface(playerObj, SL_IID_BUFFERQUEUE, &queue);

    long frames = (long) rate * (long) ms / 1000L;
    size_t bytes = (size_t) frames * 2 * sizeof(int16_t);
    int16_t *pcm = (int16_t *) malloc(bytes);
    if (pcm == NULL) {
        result = -8;
    } else {
        double phase = 0.0;
        fill_sine(pcm, (int) frames, 2, rate, &phase);
        if ((*queue)->Enqueue(queue, pcm, bytes) == SL_RESULT_SUCCESS &&
            (*play)->SetPlayState(play, SL_PLAYSTATE_PLAYING) == SL_RESULT_SUCCESS) {
            usleep((useconds_t) ms * 1000);
        } else {
            result = -9;
        }
        (*play)->SetPlayState(play, SL_PLAYSTATE_STOPPED);
        free(pcm);
    }

    (*playerObj)->Destroy(playerObj);
    (*mixObj)->Destroy(mixObj);
    (*engineObj)->Destroy(engineObj);
    return result;
}
