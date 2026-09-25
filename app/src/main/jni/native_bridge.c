#include "audiotrack_bridge.h"

#include <android/log.h>
#include <errno.h>
#include <jni.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

#define LOG_TAG "ShairportAP2"

static int bridge_started;

static jstring error_string(JNIEnv *env, const char *message) {
  return (*env)->NewStringUTF(env, message);
}

JNIEXPORT jstring JNICALL
Java_com_hkuertes_shairportap2_NativeBridge_nativeStart(JNIEnv *env, jobject self,
                                                         jstring config_path) {
  (void)self;
  if (config_path == NULL)
    return error_string(env, "No configuration path supplied");

  const char *path = (*env)->GetStringUTFChars(env, config_path, NULL);
  if (path == NULL)
    return error_string(env, "Could not read configuration path");

  int readable = access(path, R_OK);
  int access_errno = errno;
  (*env)->ReleaseStringUTFChars(env, config_path, path);
  if (readable != 0) {
    char message[160];
    snprintf(message, sizeof(message), "Cannot read configuration: %s",
             strerror(access_errno));
    return error_string(env, message);
  }

  if (!bridge_started) {
    int audio_status = audiotrack_bridge_probe();
    if (audio_status != 0) {
      char message[160];
      snprintf(message, sizeof(message), "AudioTrack bridge failed: %d", audio_status);
      return error_string(env, message);
    }
  }

  bridge_started = 1;
  __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                      "JNI and AudioTrack bridges ready; Shairport core is not linked yet");
  return NULL;
}

JNIEXPORT void JNICALL
Java_com_hkuertes_shairportap2_NativeBridge_nativeStop(JNIEnv *env, jobject self) {
  (void)env;
  (void)self;
  audiotrack_bridge_release();
  if (bridge_started)
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "JNI bridge stopped");
  bridge_started = 0;
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
  (void)reserved;
  JNIEnv *env = NULL;
  if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK ||
      audiotrack_bridge_init(env, vm) != 0)
    return JNI_ERR;
  __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "JNI bridge loaded");
  return JNI_VERSION_1_6;
}
