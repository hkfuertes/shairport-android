LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := shairport_ap2
LOCAL_SRC_FILES := native_bridge.c audiotrack_bridge.c
LOCAL_CFLAGS := -std=c11 -Wall -Wextra
LOCAL_LDLIBS := -llog
include $(BUILD_SHARED_LIBRARY)
