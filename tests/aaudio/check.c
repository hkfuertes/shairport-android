// Device check for Shairport's aaudio backend (native/patches/shairport-sync/0007).
// Plays only zeros. Build and run with tests/aaudio/run.sh.
#include "audio_aaudio.c"

#include <assert.h>
#include <unistd.h>

shairport_cfg config;

void mutex_unlock(void *arg) { pthread_mutex_unlock((pthread_mutex_t *)arg); }

void parse_audio_options(__attribute__((unused)) const char *stanza,
                         __attribute__((unused)) uint32_t formats,
                         __attribute__((unused)) uint32_t rates,
                         __attribute__((unused)) uint32_t channels) {}

int32_t search_for_suitable_configuration(unsigned int channels, unsigned int rate,
                                          unsigned int format,
                                          __attribute__((unused)) int (*check)(unsigned int,
                                                                               unsigned int,
                                                                               unsigned int)) {
  return CHANNELS_TO_ENCODED_FORMAT(channels) | RATE_TO_ENCODED_FORMAT(rate) |
         FORMAT_TO_ENCODED_FORMAT(format);
}

static long play_seconds(double seconds) {
  static int16_t silence[2 * 441];
  for (int i = 0; i < (int)(seconds * 100); i++)
    assert(audio_aaudio.play(silence, 441, 0, 0, 0) == 0);
  long frames = -1;
  assert(audio_aaudio.delay(&frames) == 0);
  printf("delay after %.1f s: %ld frames (%.0f ms)\n", seconds, frames, frames * 1000.0 / 44100);
  return frames;
}

int main(void) {
  assert(audio_aaudio.init(0, NULL) == 0);
  int32_t format = audio_aaudio.get_configuration(2, 44100, SPS_FORMAT_S16_LE);
  assert(RATE_FROM_ENCODED_FORMAT(format) == 44100 && CHANNELS_FROM_ENCODED_FORMAT(format) == 2);
  assert(audio_aaudio.configure(format, NULL) == 0);

  long frames = -1;
  assert(audio_aaudio.delay(&frames) == 0 && frames == 0); // not started yet
  // Blocking writes keep the device buffer full: the delay is the whole queue plus the
  // hardware path, and it must stay bounded while we keep writing.
  long first = play_seconds(1.0), second = play_seconds(2.0);
  assert(first > 0 && first < 44100 && second > 0 && second < 44100);
  long drift = second - first;
  assert(drift > -8192 && drift < 8192); // within one deep-buffer burst

  audio_aaudio.flush(); // closes; the next play() reopens with fresh counters
  assert(audio_aaudio.delay(&frames) == 0 && frames == 0);
  long reopened = play_seconds(0.5);
  assert(reopened > 0 && reopened < 44100);

  audio_aaudio.deinit();
  puts("PASS aaudio backend");
  return 0;
}
