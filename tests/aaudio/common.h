// Minimal stand-in for Shairport's common.h: only what audio_aaudio.c uses.
// Macros are copied verbatim from Shairport Sync 5.5.1 common.h.
#pragma once
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>

typedef enum {
  SPS_FORMAT_UNKNOWN = 0,
  SPS_FORMAT_S8,
  SPS_FORMAT_U8,
  SPS_FORMAT_S16_LE,
} sps_format_t;

typedef enum { SPS_RATE_UNKNOWN = 0, SPS_RATE_44100 = 7, SPS_RATE_48000 = 8 } sps_rate_t;

#define RATE_FROM_ENCODED_FORMAT(encoded_format) (((encoded_format >> 6) & 0x7FFFF) * 2)
#define RATE_TO_ENCODED_FORMAT(rate) (((rate / 2) & 0x7FFFF) << 6)
#define CHANNELS_FROM_ENCODED_FORMAT(encoded_format) ((encoded_format >> 25) & 0x7F)
#define CHANNELS_TO_ENCODED_FORMAT(channels) ((channels & 0x7F) << 25)
#define FORMAT_TO_ENCODED_FORMAT(format) (format & 0x3F)

typedef struct {
  double audio_backend_buffer_desired_length;
  double audio_backend_latency_offset;
} shairport_cfg;
extern shairport_cfg config;

#define debug(level, ...) (printf("debug: " __VA_ARGS__), printf("\n"))
#define warn(...) (printf("warn: " __VA_ARGS__), printf("\n"))

void mutex_unlock(void *arg);
#define pthread_mutex_lock_and_cleanup_push(mu)                                                    \
  if (pthread_mutex_lock(mu) == 0)                                                                 \
  pthread_cleanup_push(mutex_unlock, (void *)mu)
