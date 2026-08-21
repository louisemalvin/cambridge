LOCAL_PATH := $(call my-dir)

ifndef GSTREAMER_ROOT_ANDROID
$(error GSTREAMER_ROOT_ANDROID is not defined. Install the official GStreamer Android SDK and set GSTREAMER_ROOT_ANDROID.)
endif

ifeq ($(TARGET_ARCH_ABI),arm64-v8a)
GSTREAMER_ARCH := arm64
else ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)
GSTREAMER_ARCH := armv7
else ifeq ($(TARGET_ARCH_ABI),x86)
GSTREAMER_ARCH := x86
else ifeq ($(TARGET_ARCH_ABI),x86_64)
GSTREAMER_ARCH := x86_64
else
$(error Unsupported Android ABI for the official GStreamer SDK: $(TARGET_ARCH_ABI))
endif

GSTREAMER_ROOT := $(GSTREAMER_ROOT_ANDROID)/$(GSTREAMER_ARCH)
GSTREAMER_NDK_BUILD_PATH := $(GSTREAMER_ROOT)/share/gst-android/ndk-build
GSTREAMER_PLUGINS := coreelements app videoparsersbad rtp rtpmanager udp rsrtp
GSTREAMER_EXTRA_DEPS := gstreamer-video-1.0 gstreamer-app-1.0 gstreamer-rtp-1.0
G_IO_MODULES :=
# AGP requires every native output path in its build model to be absolute.
override GSTREAMER_BUILD_DIR := $(LOCAL_PATH)/../../../build/gst-android-build/$(TARGET_ARCH_ABI)
GSTREAMER_JAVA_SRC_DIR := $(LOCAL_PATH)/../java
GSTREAMER_INCLUDE_FONTS := no
GSTREAMER_INCLUDE_CA_CERTIFICATES := no

CAMBRIDGE_RECEIVER_SOURCE := $(LOCAL_PATH)/../../../../../../receiver/obs/cambridge-obs-source/src

include $(CLEAR_VARS)
LOCAL_MODULE := cambridge_gstreamer
LOCAL_SRC_FILES := gstreamer_jni.cpp gstreamer_sender.cpp
LOCAL_C_INCLUDES := $(CAMBRIDGE_RECEIVER_SOURCE)
LOCAL_CPP_FEATURES := exceptions rtti
LOCAL_CPPFLAGS += -std=c++17 -Wall -Wextra -Werror
LOCAL_SHARED_LIBRARIES := gstreamer_android
LOCAL_LDLIBS := -llog
include $(BUILD_SHARED_LIBRARY)

include $(GSTREAMER_NDK_BUILD_PATH)/gstreamer-1.0.mk
