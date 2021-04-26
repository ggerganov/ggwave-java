//
// Created by ggerganov on 26.04.21.
//

#include "test-cpp.h"

#include <android/log.h>
#include <jni.h>

#include <ggwave/ggwave.h>

namespace {
    JavaVM* g_jvm;
    jobject g_mainObject;
    ggwave_Instance g_ggwave;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_ggwave_MainActivity_initNative(JNIEnv * env, jobject obj) {
    __android_log_print(ANDROID_LOG_DEBUG, "ggwave (native)", "Initializing native module");

    ggwave_Parameters parameters = ggwave_getDefaultParameters();
    parameters.sampleFormatInp = GGWAVE_SAMPLE_FORMAT_I16;
    parameters.sampleFormatOut = GGWAVE_SAMPLE_FORMAT_I16;
    parameters.sampleRateInp = 48000;
    g_ggwave = ggwave_init(parameters);

    env->GetJavaVM(&g_jvm);
    g_mainObject = env->NewGlobalRef(obj);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_ggwave_MainActivity_processCaptureData(JNIEnv *env, jobject thiz, jshortArray data) {
    jsize dataSize = env->GetArrayLength(data);

    jboolean isCopy = false;
    jshort * cData = env->GetShortArrayElements(data, &isCopy);

    char output[256];
    int ret = ggwave_decode(g_ggwave, (char *) cData, 2*dataSize, output);

    if (ret != 0) {
        __android_log_print(ANDROID_LOG_DEBUG, "ggwave (native)", "Received message: '%s'", output);

        jclass handlerClass = env->GetObjectClass(g_mainObject);
        jmethodID mid_onReceivedMessage = env->GetMethodID(handlerClass, "onNativeReceivedMessage", "([B)V");
        jbyteArray jba_message = env->NewByteArray(strlen(output));

        env->SetByteArrayRegion(jba_message, 0, strlen(output), (jbyte*) output);
        env->CallVoidMethod(g_mainObject, mid_onReceivedMessage, jba_message);
        env->DeleteLocalRef(jba_message);
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_ggwave_MainActivity_sendMessage(JNIEnv *env, jobject thiz, jstring message) {
    __android_log_print(ANDROID_LOG_DEBUG, "ggwave (native)", "Encoding message: '%s'", env->GetStringUTFChars(message, NULL));

    const int n = ggwave_encode(g_ggwave, env->GetStringUTFChars(message, NULL), env->GetStringLength(message), GGWAVE_TX_PROTOCOL_AUDIBLE_FAST, 10, NULL, 1);

    char waveform[n];

    const int ret = ggwave_encode(g_ggwave, env->GetStringUTFChars(message, NULL), env->GetStringLength(message), GGWAVE_TX_PROTOCOL_AUDIBLE_FAST, 10, waveform, 0);

    if (2*ret != n) {
        __android_log_print(ANDROID_LOG_ERROR, "ggwave (native)", "Failed to encode message");
    }

    jclass handlerClass = env->GetObjectClass(g_mainObject);
    jmethodID mid_onMessageEncoded = env->GetMethodID(handlerClass, "onNativeMessageEncoded","([S)V");
    jshortArray jba_message = env->NewShortArray(ret);

    env->SetShortArrayRegion(jba_message, 0, ret, (jshort*) waveform);
    env->CallVoidMethod(g_mainObject, mid_onMessageEncoded, jba_message);
    env->DeleteLocalRef(jba_message);
}
