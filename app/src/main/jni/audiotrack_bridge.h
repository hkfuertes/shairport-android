#ifndef SHAIRPORT_AUDIOTRACK_BRIDGE_H
#define SHAIRPORT_AUDIOTRACK_BRIDGE_H

#include <jni.h>
#include <stdint.h>

int audiotrack_bridge_init(JNIEnv *env, JavaVM *vm);
int audiotrack_bridge_probe(void);
int audiotrack_bridge_create(int sample_rate, int buffer_frames);
int audiotrack_bridge_start(void);
int audiotrack_bridge_write(void *samples, int bytes);
void audiotrack_bridge_flush(void);
void audiotrack_bridge_stop(void);
void audiotrack_bridge_release(void);
uint64_t audiotrack_bridge_playback_head_frames(void);

#endif
