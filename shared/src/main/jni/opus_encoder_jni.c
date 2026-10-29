#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <opus.h>

/*
 * One 20ms/48kHz/mono PCM16LE frame becomes one raw Opus access unit.
 * No RTP or encryption is performed here; the existing CarPlay packetizer owns both.
 * libopus 1.3.1 sources are provided at build time from a pinned upstream tag.
 */
enum { FRAME_SAMPLES = 960, FRAME_BYTES = 1920, MAX_PACKET_BYTES = 1275 };

static OpusEncoder *encoder_from_handle(jlong handle) {
    return (OpusEncoder *)(intptr_t)handle;
}

JNIEXPORT jlong JNICALL
Java_com_shilapi_xcertplay_media_NativeOpusEncoder_nativeCreate(
        JNIEnv *env, jclass clazz, jint bitrate, jint complexity) {
    (void)env;
    (void)clazz;
    if (bitrate < 6000 || bitrate > 128000 || complexity < 0 || complexity > 10) return 0;

    int error = OPUS_OK;
    OpusEncoder *encoder = opus_encoder_create(48000, 1, OPUS_APPLICATION_VOIP, &error);
    if (encoder == NULL || error != OPUS_OK) {
        if (encoder != NULL) opus_encoder_destroy(encoder);
        return 0;
    }
    if (opus_encoder_ctl(encoder, OPUS_SET_BITRATE(bitrate)) != OPUS_OK ||
        opus_encoder_ctl(encoder, OPUS_SET_COMPLEXITY(complexity)) != OPUS_OK ||
        opus_encoder_ctl(encoder, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE)) != OPUS_OK ||
        opus_encoder_ctl(encoder, OPUS_SET_DTX(0)) != OPUS_OK) {
        opus_encoder_destroy(encoder);
        return 0;
    }
    return (jlong)(intptr_t)encoder;
}

JNIEXPORT jbyteArray JNICALL
Java_com_shilapi_xcertplay_media_NativeOpusEncoder_nativeEncode(
        JNIEnv *env, jclass clazz, jlong handle, jbyteArray pcm) {
    (void)clazz;
    OpusEncoder *encoder = encoder_from_handle(handle);
    if (encoder == NULL || pcm == NULL || (*env)->GetArrayLength(env, pcm) != FRAME_BYTES)
        return NULL;

    jbyte input[FRAME_BYTES];
    opus_int16 samples[FRAME_SAMPLES];
    unsigned char encoded[MAX_PACKET_BYTES];
    (*env)->GetByteArrayRegion(env, pcm, 0, FRAME_BYTES, input);
    if ((*env)->ExceptionCheck(env)) return NULL;
    for (int i = 0; i < FRAME_SAMPLES; i++) {
        uint16_t lo = (uint8_t)input[i * 2];
        uint16_t hi = (uint8_t)input[i * 2 + 1];
        samples[i] = (opus_int16)(lo | (hi << 8));
    }
    int count = opus_encode(encoder, samples, FRAME_SAMPLES, encoded, MAX_PACKET_BYTES);
    if (count <= 0 || count > MAX_PACKET_BYTES) return NULL;
    jbyteArray packet = (*env)->NewByteArray(env, count);
    if (packet == NULL) return NULL;
    (*env)->SetByteArrayRegion(env, packet, 0, count, (const jbyte *)encoded);
    return packet;
}

JNIEXPORT void JNICALL
Java_com_shilapi_xcertplay_media_NativeOpusEncoder_nativeDestroy(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void)env;
    (void)clazz;
    OpusEncoder *encoder = encoder_from_handle(handle);
    if (encoder != NULL) opus_encoder_destroy(encoder);
}

/*
 * Decoder side (issue #272): some legacy ROMs expose no Opus decoder through
 * MediaCodec at all (neither hardware nor OMX.google), which muted phone calls
 * end to end. The same bundled libopus decodes instead; the renderer feeds the
 * PCM straight to its track. The handle owns a scratch buffer sized for the
 * largest legal Opus frame (120 ms) so decode never allocates per packet.
 */
typedef struct {
    OpusDecoder *decoder;
    int channels;
    opus_int16 *scratch;
} OpusDecoderHandle;

enum { MAX_FRAME_SAMPLES_PER_CHANNEL = 5760 };

JNIEXPORT jlong JNICALL
Java_com_shilapi_xcertplay_media_NativeOpusDecoder_nativeCreate(
        JNIEnv *env, jclass clazz, jint sampleRate, jint channels) {
    (void)env;
    (void)clazz;
    if (!(sampleRate == 8000 || sampleRate == 12000 || sampleRate == 16000 ||
          sampleRate == 24000 || sampleRate == 48000) ||
        (channels != 1 && channels != 2)) {
        return 0;
    }
    int error = OPUS_OK;
    OpusDecoder *decoder = opus_decoder_create(sampleRate, channels, &error);
    if (decoder == NULL || error != OPUS_OK) {
        if (decoder != NULL) opus_decoder_destroy(decoder);
        return 0;
    }
    OpusDecoderHandle *handle = (OpusDecoderHandle *)malloc(sizeof(OpusDecoderHandle));
    if (handle == NULL) {
        opus_decoder_destroy(decoder);
        return 0;
    }
    handle->scratch = (opus_int16 *)malloc(
        sizeof(opus_int16) * MAX_FRAME_SAMPLES_PER_CHANNEL * (size_t)channels);
    if (handle->scratch == NULL) {
        free(handle);
        opus_decoder_destroy(decoder);
        return 0;
    }
    handle->decoder = decoder;
    handle->channels = channels;
    return (jlong)(intptr_t)handle;
}

JNIEXPORT jbyteArray JNICALL
Java_com_shilapi_xcertplay_media_NativeOpusDecoder_nativeDecode(
        JNIEnv *env, jclass clazz, jlong handleValue, jbyteArray packet) {
    (void)clazz;
    OpusDecoderHandle *handle = (OpusDecoderHandle *)(intptr_t)handleValue;
    if (handle == NULL || handle->decoder == NULL || packet == NULL) return NULL;
    jsize length = (*env)->GetArrayLength(env, packet);
    if (length <= 0) return NULL;
    jbyte *bytes = (*env)->GetByteArrayElements(env, packet, NULL);
    if (bytes == NULL) return NULL;
    int samples = opus_decode(handle->decoder, (const unsigned char *)bytes, length,
                              handle->scratch, MAX_FRAME_SAMPLES_PER_CHANNEL, 0);
    (*env)->ReleaseByteArrayElements(env, packet, bytes, JNI_ABORT);
    if (samples <= 0) return NULL;
    /* All target ABIs are little-endian, matching PCM16LE track input. */
    jsize byteCount = (jsize)samples * handle->channels * 2;
    jbyteArray pcm = (*env)->NewByteArray(env, byteCount);
    if (pcm == NULL) return NULL;
    (*env)->SetByteArrayRegion(env, pcm, 0, byteCount, (const jbyte *)handle->scratch);
    return pcm;
}

JNIEXPORT void JNICALL
Java_com_shilapi_xcertplay_media_NativeOpusDecoder_nativeDestroy(
        JNIEnv *env, jclass clazz, jlong handleValue) {
    (void)env;
    (void)clazz;
    OpusDecoderHandle *handle = (OpusDecoderHandle *)(intptr_t)handleValue;
    if (handle == NULL) return;
    if (handle->decoder != NULL) opus_decoder_destroy(handle->decoder);
    free(handle->scratch);
    free(handle);
}
