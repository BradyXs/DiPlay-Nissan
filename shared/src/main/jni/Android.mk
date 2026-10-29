LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := xcertplay_i2c
LOCAL_SRC_FILES := linux_i2c_jni.c
include $(BUILD_SHARED_LIBRARY)

# Bundled Opus for Android 4.4 devices without platform Opus codecs.
ifeq ($(wildcard $(LOCAL_PATH)/libopus/opus_sources.mk),)
$(error libopus sources missing: run scripts/prepare-libopus.py)
endif
include $(LOCAL_PATH)/libopus/opus_sources.mk
include $(LOCAL_PATH)/libopus/celt_sources.mk
include $(LOCAL_PATH)/libopus/silk_sources.mk

include $(CLEAR_VARS)
LOCAL_MODULE := opus_microphone
LOCAL_SRC_FILES := opus_encoder_jni.c \
    $(addprefix libopus/,$(OPUS_SOURCES) \
    $(CELT_SOURCES) $(SILK_SOURCES) $(SILK_SOURCES_FIXED))
LOCAL_C_INCLUDES := $(LOCAL_PATH)/libopus/include \
    $(LOCAL_PATH)/libopus/celt $(LOCAL_PATH)/libopus/silk \
    $(LOCAL_PATH)/libopus/silk/fixed
LOCAL_CFLAGS := -O2 -DOPUS_BUILD -DFIXED_POINT=1 -DDISABLE_FLOAT_API -DUSE_ALLOCA
LOCAL_LDLIBS := -lm
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := legacy_ncm_claim
LOCAL_SRC_FILES := legacy_ncm_claim.c
LOCAL_CFLAGS := -Wall -Wextra -Werror
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := local_hotspot_radio
LOCAL_SRC_FILES := local_hotspot_radio.c
LOCAL_CFLAGS := -Wall -Wextra -Werror
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := usb_host_compat
LOCAL_SRC_FILES := usb_host_compat.c
LOCAL_CFLAGS := -Wall -Wextra -Werror
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := speex_echo
LOCAL_SRC_FILES := speex_echo_jni.c \
    speexdsp/mdf.c speexdsp/preprocess.c speexdsp/fftwrap.c \
    speexdsp/kiss_fft.c speexdsp/kiss_fftr.c speexdsp/filterbank.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/speexdsp $(LOCAL_PATH)/speexdsp/include
LOCAL_CFLAGS := -O2 -DFLOATING_POINT -DUSE_KISS_FFT -DEXPORT= -Wno-unused-parameter
LOCAL_LDLIBS := -lm
include $(BUILD_SHARED_LIBRARY)
