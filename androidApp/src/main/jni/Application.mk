APP_OPTIM := release
APP_PLATFORM := android-23
APP_ABI := armeabi-v7a arm64-v8a x86_64
UNIFIEDVPN_NATIVE_SOURCE_ROOT := $(call my-dir)
APP_CFLAGS := -O3 \
    -ffile-prefix-map=$(UNIFIEDVPN_NATIVE_SOURCE_ROOT)=/usr/src/unifiedvpn/android-native \
    -fmacro-prefix-map=$(UNIFIEDVPN_NATIVE_SOURCE_ROOT)=/usr/src/unifiedvpn/android-native \
    -fdebug-prefix-map=$(UNIFIEDVPN_NATIVE_SOURCE_ROOT)=/usr/src/unifiedvpn/android-native
APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true
NDK_TOOLCHAIN_VERSION := clang
