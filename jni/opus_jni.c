/* JNI glue for dev.gabriwar.periphone.Opus: libopus encoder/decoder on S16LE byte arrays.
 * 48 kHz only. Handles are the raw OpusEncoder / OpusDecoder pointers. */
#include <jni.h>
#include <stdint.h>
#include <opus.h>

#define FN(name) Java_dev_gabriwar_periphone_Opus_##name

JNIEXPORT jlong JNICALL FN(encCreate)(JNIEnv *env, jclass c, jint channels, jint bitrate) {
    int err = 0;
    OpusEncoder *e = opus_encoder_create(48000, channels, OPUS_APPLICATION_RESTRICTED_LOWDELAY, &err);
    if (err != OPUS_OK || !e) return 0;
    opus_encoder_ctl(e, OPUS_SET_BITRATE(bitrate));
    opus_encoder_ctl(e, OPUS_SET_VBR(1));
    return (jlong) (intptr_t) e;
}

JNIEXPORT jint JNICALL FN(encode)(JNIEnv *env, jclass c, jlong h, jbyteArray pcm, jint frames,
                                   jbyteArray out) {
    jint max = (*env)->GetArrayLength(env, out);
    jbyte *in = (*env)->GetPrimitiveArrayCritical(env, pcm, NULL);
    jbyte *o = (*env)->GetPrimitiveArrayCritical(env, out, NULL);
    int n = opus_encode((OpusEncoder *) (intptr_t) h, (const opus_int16 *) in, frames,
                        (unsigned char *) o, max);
    (*env)->ReleasePrimitiveArrayCritical(env, out, o, 0);
    (*env)->ReleasePrimitiveArrayCritical(env, pcm, in, JNI_ABORT);
    return n;
}

JNIEXPORT void JNICALL FN(encFree)(JNIEnv *env, jclass c, jlong h) {
    if (h) opus_encoder_destroy((OpusEncoder *) (intptr_t) h);
}

JNIEXPORT jlong JNICALL FN(decCreate)(JNIEnv *env, jclass c, jint channels) {
    int err = 0;
    OpusDecoder *d = opus_decoder_create(48000, channels, &err);
    return err == OPUS_OK ? (jlong) (intptr_t) d : 0;
}

/* pkt == NULL or len == 0: packet lost, conceal it (PLC). Returns frames decoded or < 0. */
JNIEXPORT jint JNICALL FN(decode)(JNIEnv *env, jclass c, jlong h, jbyteArray pkt, jint len,
                                   jbyteArray pcm, jint frames) {
    jbyte *p = pkt && len > 0 ? (*env)->GetPrimitiveArrayCritical(env, pkt, NULL) : NULL;
    jbyte *o = (*env)->GetPrimitiveArrayCritical(env, pcm, NULL);
    int n = opus_decode((OpusDecoder *) (intptr_t) h, (const unsigned char *) p, p ? len : 0,
                        (opus_int16 *) o, frames, 0);
    (*env)->ReleasePrimitiveArrayCritical(env, pcm, o, 0);
    if (p) (*env)->ReleasePrimitiveArrayCritical(env, pkt, p, JNI_ABORT);
    return n;
}

JNIEXPORT void JNICALL FN(decFree)(JNIEnv *env, jclass c, jlong h) {
    if (h) opus_decoder_destroy((OpusDecoder *) (intptr_t) h);
}
