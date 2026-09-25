#include "audiotrack_bridge.h"

#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#define LOG_TAG "ShairportAP2"

static int bridge_started;

static jstring error_string(JNIEnv *env, const char *message) {
  return (*env)->NewStringUTF(env, message);
}

static jstring configure_nqptp_shared_memory(JNIEnv *env, jstring directory) {
  if (directory == NULL)
    return error_string(env, "No NQPTP shared-memory directory supplied");

  const char *dir = (*env)->GetStringUTFChars(env, directory, NULL);
  if (dir == NULL)
    return error_string(env, "Could not read NQPTP shared-memory directory");

  char path[512];
  int setenv_result = setenv("NQPTP_SHM_DIRECTORY", dir, 1);
  int setenv_errno = errno;
  int written = snprintf(path, sizeof(path), "%s%snqptp", dir,
                         dir[0] != '\0' && dir[strlen(dir) - 1] == '/' ? "" : "/");
  (*env)->ReleaseStringUTFChars(env, directory, dir);
  if (setenv_result != 0) {
    char message[160];
    snprintf(message, sizeof(message), "Cannot configure NQPTP directory: %s",
             strerror(setenv_errno));
    return error_string(env, message);
  }
  if (written < 0 || written >= (int)sizeof(path))
    return error_string(env, "NQPTP shared-memory path is too long");

  int fd = open(path, O_RDONLY);
  if (fd < 0) {
    char message[160];
    snprintf(message, sizeof(message), "Cannot read NQPTP shared memory: %s", strerror(errno));
    return error_string(env, message);
  }
  struct stat state;
  if (fstat(fd, &state) != 0) {
    int stat_errno = errno;
    close(fd);
    char message[160];
    snprintf(message, sizeof(message), "Cannot stat NQPTP shared memory: %s", strerror(stat_errno));
    return error_string(env, message);
  }
  if (state.st_size == 0) {
    close(fd);
    return error_string(env, "NQPTP shared memory is empty");
  }
  void *mapping = mmap(NULL, 1, PROT_READ, MAP_SHARED, fd, 0);
  int mmap_errno = errno;
  close(fd);
  if (mapping == MAP_FAILED) {
    char message[160];
    snprintf(message, sizeof(message), "Cannot map NQPTP shared memory: %s", strerror(mmap_errno));
    return error_string(env, message);
  }
  (void)*(volatile unsigned char *)mapping;
  munmap(mapping, 1);
  return NULL;
}

JNIEXPORT jstring JNICALL
Java_com_hkfuertes_shairportap2_NativeBridge_nativeStart(JNIEnv *env, jobject self,
                                                         jstring config_path,
                                                         jstring nqptp_shm_directory) {
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

  jstring nqptp_error = configure_nqptp_shared_memory(env, nqptp_shm_directory);
  if (nqptp_error != NULL)
    return nqptp_error;

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
                      "NQPTP shared memory and JNI/AudioTrack bridges ready; Shairport core is not linked yet");
  return NULL;
}

JNIEXPORT void JNICALL
Java_com_hkfuertes_shairportap2_NativeBridge_nativeStop(JNIEnv *env, jobject self) {
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
