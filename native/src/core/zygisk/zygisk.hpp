/**
 * Zygisk core header declaring NativeBridge callbacks, logging macros,
 * and the main hook_entry/hookJniNativeMethods entry points.
 */
#pragma once

#include <jni.h>
#include <core.hpp>

#define ZYGISKLDR       "libzygisk.so"
#define NBPROP          "ro.dalvik.vm.native.bridge"

#if defined(__LP64__)
/** Zygisk debug log (64-bit tagged). */
#define ZLOGD(...) LOGD("zygisk64: " __VA_ARGS__)
/** Zygisk error log (64-bit tagged). */
#define ZLOGE(...) LOGE("zygisk64: " __VA_ARGS__)
/** Zygisk info log (64-bit tagged). */
#define ZLOGI(...) LOGI("zygisk64: " __VA_ARGS__)
/** Zygisk warning log (64-bit tagged). */
#define ZLOGW(...) LOGW("zygisk64: " __VA_ARGS__)
#else
/** Zygisk debug log (32-bit tagged). */
#define ZLOGD(...) LOGD("zygisk32: " __VA_ARGS__)
/** Zygisk error log (32-bit tagged). */
#define ZLOGE(...) LOGE("zygisk32: " __VA_ARGS__)
/** Zygisk info log (32-bit tagged). */
#define ZLOGI(...) LOGI("zygisk32: " __VA_ARGS__)
/** Zygisk warning log (32-bit tagged). */
#define ZLOGW(...) LOGW("zygisk32: " __VA_ARGS__)
#endif

// Extreme verbose logging (disabled by default; uncomment for debug)
// #define ZLOGV(...) ZLOGD(__VA_ARGS__)
#define ZLOGV(...) (void*)0

/** Entry point: install PLT hooks for Zygisk bootstrapping. Called from NativeBridgeItf. */
void hook_entry();
/** Hook JNI native methods for a given class, saving original function pointers. */
void hookJniNativeMethods(JNIEnv *env, const char *clz, JNINativeMethod *methods, int numMethods);

// The reference of the following structs
// https://cs.android.com/android/platform/superproject/main/+/main:art/libnativebridge/include/nativebridge/native_bridge.h

/** Android native bridge runtime callbacks for JNI method inspection. */
struct NativeBridgeRuntimeCallbacks {
    const char* (*getMethodShorty)(JNIEnv* env, jmethodID mid);
    uint32_t (*getNativeMethodCount)(JNIEnv* env, jclass clazz);
    uint32_t (*getNativeMethods)(JNIEnv* env, jclass clazz, JNINativeMethod* methods,
                                 uint32_t method_count);
};

/** Native bridge callbacks struct exported by libzygisk.so. The isCompatibleWith field triggers code injection. */
struct NativeBridgeCallbacks {
    uint32_t version;
    void *padding[5];
    bool (*isCompatibleWith)(uint32_t);
};
