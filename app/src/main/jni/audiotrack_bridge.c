#include "audiotrack_bridge.h"

#include <android/log.h>
#include <stdint.h>

#define LOG_TAG "ShairportAP2"

static JavaVM *java_vm;
static jclass bridge_class;
static jmethodID create_method;
static jmethodID start_method;
static jmethodID write_method;
static jmethodID flush_method;
static jmethodID stop_method;
static jmethodID release_method;
static jmethodID playback_head_method;

static int clear_exception(JNIEnv *env) {
  if (!(*env)->ExceptionCheck(env))
    return 0;
  (*env)->ExceptionClear(env);
  return -1;
}

static JNIEnv *attach_thread(int *attached) {
  JNIEnv *env = NULL;
  *attached = 0;
  if (java_vm == NULL)
    return NULL;
  jint result = (*java_vm)->GetEnv(java_vm, (void **)&env, JNI_VERSION_1_6);
  if (result == JNI_OK)
    return env;
  if (result != JNI_EDETACHED ||
      (*java_vm)->AttachCurrentThread(java_vm, &env, NULL) != JNI_OK)
    return NULL;
  *attached = 1;
  return env;
}

static void detach_thread(int attached) {
  if (attached)
    (*java_vm)->DetachCurrentThread(java_vm);
}

static int call_int(jmethodID method) {
  int attached;
  JNIEnv *env = attach_thread(&attached);
  if (env == NULL)
    return -1;
  jint result = (*env)->CallStaticIntMethod(env, bridge_class, method);
  if (clear_exception(env))
    result = -1;
  detach_thread(attached);
  return result;
}

static void call_void(jmethodID method) {
  int attached;
  JNIEnv *env = attach_thread(&attached);
  if (env == NULL)
    return;
  (*env)->CallStaticVoidMethod(env, bridge_class, method);
  clear_exception(env);
  detach_thread(attached);
}

int audiotrack_bridge_init(JNIEnv *env, JavaVM *vm) {
  java_vm = vm;
  jclass local = (*env)->FindClass(env, "com/hkfuertes/shairportap2/AudioTrackBridge");
  if (local == NULL)
    return -1;
  bridge_class = (*env)->NewGlobalRef(env, local);
  (*env)->DeleteLocalRef(env, local);
  if (bridge_class == NULL)
    return -1;

  create_method = (*env)->GetStaticMethodID(env, bridge_class, "create", "(II)I");
  start_method = (*env)->GetStaticMethodID(env, bridge_class, "start", "()I");
  write_method = (*env)->GetStaticMethodID(env, bridge_class, "write",
                                            "(Ljava/nio/ByteBuffer;I)I");
  flush_method = (*env)->GetStaticMethodID(env, bridge_class, "flush", "()V");
  stop_method = (*env)->GetStaticMethodID(env, bridge_class, "stop", "()V");
  release_method = (*env)->GetStaticMethodID(env, bridge_class, "release", "()V");
  playback_head_method = (*env)->GetStaticMethodID(env, bridge_class,
                                                     "playbackHeadFrames", "()J");
  return clear_exception(env);
}

int audiotrack_bridge_create(int sample_rate, int buffer_frames) {
  int attached;
  JNIEnv *env = attach_thread(&attached);
  if (env == NULL)
    return -1;
  jint result = (*env)->CallStaticIntMethod(env, bridge_class, create_method, sample_rate,
                                             buffer_frames);
  if (clear_exception(env))
    result = -1;
  detach_thread(attached);
  return result;
}

int audiotrack_bridge_start(void) { return call_int(start_method); }

int audiotrack_bridge_write(void *samples, int bytes) {
  int attached;
  JNIEnv *env = attach_thread(&attached);
  if (env == NULL)
    return -1;
  jobject buffer = (*env)->NewDirectByteBuffer(env, samples, bytes);
  if (buffer == NULL || clear_exception(env)) {
    detach_thread(attached);
    return -1;
  }
  jint result = (*env)->CallStaticIntMethod(env, bridge_class, write_method, buffer, bytes);
  (*env)->DeleteLocalRef(env, buffer);
  if (clear_exception(env))
    result = -1;
  detach_thread(attached);
  return result;
}

void audiotrack_bridge_flush(void) { call_void(flush_method); }
void audiotrack_bridge_stop(void) { call_void(stop_method); }
void audiotrack_bridge_release(void) { call_void(release_method); }

uint64_t audiotrack_bridge_playback_head_frames(void) {
  int attached;
  JNIEnv *env = attach_thread(&attached);
  if (env == NULL)
    return 0;
  jlong result = (*env)->CallStaticLongMethod(env, bridge_class, playback_head_method);
  if (clear_exception(env))
    result = 0;
  detach_thread(attached);
  return (uint64_t)result;
}

int audiotrack_bridge_probe(void) {
  int result = audiotrack_bridge_create(48000, 1024);
  if (result != 0)
    return result;
  result = audiotrack_bridge_start();
  if (result == 0) {
    int16_t silence[1024 * 2] = {0};
    result = audiotrack_bridge_write(silence, (int)sizeof(silence));
    if (result >= 0)
      result = 0;
  }
  audiotrack_bridge_stop();
  audiotrack_bridge_flush();
  audiotrack_bridge_release();
  __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "AudioTrack bridge probe: %d", result);
  return result;
}
