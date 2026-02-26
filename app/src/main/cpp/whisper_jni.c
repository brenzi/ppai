/*
 * JNI bridge between the Android app and whisper.cpp.
 *
 * Provides init, transcribe, free, and isLoaded functions via JNI.
 * Follows the same pattern as privatemode_jni.c.
 */

#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <stdatomic.h>
#include <android/log.h>
#include "whisper.h"

#define TAG "WhisperJNI"

/* Sanitize a buffer in-place so it is valid Modified UTF-8 for NewStringUTF.
 * Replaces any byte that would start an invalid sequence with '?'. */
static void sanitize_mutf8(char *buf, size_t len) {
    size_t i = 0;
    while (i < len) {
        unsigned char c = (unsigned char)buf[i];
        if (c == 0) break;
        if (c < 0x80) { i++; continue; }                          /* ASCII */
        if ((c & 0xE0) == 0xC0) {                                 /* 2-byte */
            if (i + 1 < len && (buf[i+1] & 0xC0) == 0x80) { i += 2; continue; }
        } else if ((c & 0xF0) == 0xE0) {                          /* 3-byte */
            if (i + 2 < len && (buf[i+1] & 0xC0) == 0x80
                            && (buf[i+2] & 0xC0) == 0x80) { i += 3; continue; }
        } else if ((c & 0xF8) == 0xF0) {                          /* 4-byte */
            if (i + 3 < len && (buf[i+1] & 0xC0) == 0x80
                            && (buf[i+2] & 0xC0) == 0x80
                            && (buf[i+3] & 0xC0) == 0x80) { i += 4; continue; }
        }
        buf[i] = '?';                                             /* invalid */
        i++;
    }
}

static struct whisper_context *ctx = NULL;

/* Abort flag — set from Kotlin to cancel a running transcription. */
static atomic_int abort_flag = 0;

static bool check_abort(void *user_data) {
    (void)user_data;
    return atomic_load(&abort_flag) != 0;
}

/* Progress (0-100) — updated by whisper's progress callback, polled from Kotlin. */
static atomic_int progress_pct = 0;

static void on_progress(struct whisper_context *wctx, struct whisper_state *wstate,
                         int progress, void *user_data) {
    (void)wctx; (void)wstate; (void)user_data;
    atomic_store(&progress_pct, progress);
}

JNIEXPORT jint JNICALL
Java_ch_brenzi_prettyprivateai_whisper_WhisperNative_nativeInit(
    JNIEnv *env, jobject thiz, jstring modelPath) {

    if (ctx != NULL) {
        whisper_free(ctx);
        ctx = NULL;
    }

    const char *path = (*env)->GetStringUTFChars(env, modelPath, NULL);
    struct whisper_context_params cparams = whisper_context_default_params();
    ctx = whisper_init_from_file_with_params(path, cparams);
    (*env)->ReleaseStringUTFChars(env, modelPath, path);

    if (ctx == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Failed to init whisper context");
        return -1;
    }
    return 0;
}

JNIEXPORT jstring JNICALL
Java_ch_brenzi_prettyprivateai_whisper_WhisperNative_nativeTranscribe(
    JNIEnv *env, jobject thiz, jfloatArray samples, jint n_threads, jstring language) {

    if (ctx == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "transcribe called but context is NULL");
        return (*env)->NewStringUTF(env, "");
    }

    /* Reset abort flag and progress at the start of each transcription. */
    atomic_store(&abort_flag, 0);
    atomic_store(&progress_pct, 0);

    jsize n_samples = (*env)->GetArrayLength(env, samples);
    jfloat *data = (*env)->GetFloatArrayElements(env, samples, NULL);
    const char *lang = (*env)->GetStringUTFChars(env, language, NULL);

    int threads = n_threads > 0 ? n_threads : 2;
    __android_log_print(ANDROID_LOG_INFO, TAG,
        "whisper_full: %d samples (%.1fs audio), %d threads, lang=%s",
        n_samples, (float)n_samples / 16000.0f, threads, lang);

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.language = lang;
    params.n_threads = threads;
    params.greedy.best_of = 1;
    params.single_segment = true;
    params.no_timestamps = true;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_special = false;
    params.print_timestamps = false;
    params.abort_callback = check_abort;
    params.abort_callback_user_data = NULL;
    params.progress_callback = on_progress;
    params.progress_callback_user_data = NULL;

    /* Scale audio_ctx to actual recording length instead of default 1500 (30s).
     * Whisper uses 50 mel frames/second, so n_samples/16000*50 = n_samples/320.
     * Add 10% headroom and clamp to [64, 1500]. */
    int frames_needed = (int)(n_samples / 320) + (int)(n_samples / 3200) + 16;
    if (frames_needed < 64) frames_needed = 64;
    if (frames_needed > 1500) frames_needed = 1500;
    params.audio_ctx = frames_needed;
    __android_log_print(ANDROID_LOG_INFO, TAG, "audio_ctx=%d for %d samples", frames_needed, n_samples);

    int ret = whisper_full(ctx, params, data, n_samples);
    (*env)->ReleaseFloatArrayElements(env, samples, data, JNI_ABORT);
    (*env)->ReleaseStringUTFChars(env, language, lang);

    if (atomic_load(&abort_flag) != 0) {
        __android_log_print(ANDROID_LOG_INFO, TAG, "whisper_full aborted by caller");
        return (*env)->NewStringUTF(env, "");
    }

    if (ret != 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "whisper_full failed: %d", ret);
        return (*env)->NewStringUTF(env, "");
    }

    int n_segments = whisper_full_n_segments(ctx);
    __android_log_print(ANDROID_LOG_INFO, TAG, "whisper_full done: %d segments", n_segments);
    /* Estimate buffer size: 256 bytes per segment */
    size_t buf_size = (size_t)n_segments * 256 + 1;
    char *buf = (char *)malloc(buf_size);
    buf[0] = '\0';
    size_t offset = 0;

    for (int i = 0; i < n_segments; i++) {
        const char *text = whisper_full_get_segment_text(ctx, i);
        if (text == NULL) continue;
        size_t len = strlen(text);
        if (offset + len + 1 >= buf_size) {
            buf_size = offset + len + 256;
            buf = (char *)realloc(buf, buf_size);
        }
        memcpy(buf + offset, text, len);
        offset += len;
    }
    buf[offset] = '\0';
    sanitize_mutf8(buf, offset);

    jstring result = (*env)->NewStringUTF(env, buf);
    free(buf);
    return result;
}

JNIEXPORT jint JNICALL
Java_ch_brenzi_prettyprivateai_whisper_WhisperNative_nativeGetProgress(
    JNIEnv *env, jobject thiz) {
    return (jint)atomic_load(&progress_pct);
}

JNIEXPORT void JNICALL
Java_ch_brenzi_prettyprivateai_whisper_WhisperNative_nativeAbort(
    JNIEnv *env, jobject thiz) {

    __android_log_print(ANDROID_LOG_INFO, TAG, "nativeAbort called");
    atomic_store(&abort_flag, 1);
}

JNIEXPORT void JNICALL
Java_ch_brenzi_prettyprivateai_whisper_WhisperNative_nativeFree(
    JNIEnv *env, jobject thiz) {

    if (ctx != NULL) {
        whisper_free(ctx);
        ctx = NULL;
    }
}

JNIEXPORT jboolean JNICALL
Java_ch_brenzi_prettyprivateai_whisper_WhisperNative_nativeIsLoaded(
    JNIEnv *env, jobject thiz) {

    return ctx != NULL ? JNI_TRUE : JNI_FALSE;
}
