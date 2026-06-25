#include "JavaVideoBridge.h"

#include <android/log.h>

#include <mutex>

namespace {
constexpr const char* kTag = "JavaVideoBridge";

JavaVM* gVm = nullptr;
std::mutex gMutex;
jobject gVideoPlayer = nullptr;
jmethodID gInitializePlayback = nullptr;
jmethodID gUpdateIfNeeded = nullptr;

JNIEnv* GetEnv(bool* didAttach) {
    *didAttach = false;
    if (gVm == nullptr) {
        return nullptr;
    }

    JNIEnv* env = nullptr;
    const jint state = gVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (state == JNI_OK) {
        return env;
    }
    if (state == JNI_EDETACHED && gVm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
        *didAttach = true;
        return env;
    }
    return nullptr;
}
}  // namespace

namespace javabridge {

void SetJavaVm(JavaVM* vm) {
    gVm = vm;
}

void SetVideoPlayer(JNIEnv* env, jobject videoPlayer) {
    std::lock_guard<std::mutex> lock(gMutex);
    if (gVideoPlayer != nullptr) {
        env->DeleteGlobalRef(gVideoPlayer);
    }
    gVideoPlayer = env->NewGlobalRef(videoPlayer);

    jclass cls = env->GetObjectClass(videoPlayer);
    gInitializePlayback = env->GetMethodID(cls, "initializePlayback", "(I)V");
    gUpdateIfNeeded = env->GetMethodID(cls, "updateIfNeeded", "()V");
    env->DeleteLocalRef(cls);
}

bool InitializeVideoPlayback(GLuint textureId) {
    bool didAttach = false;
    JNIEnv* env = GetEnv(&didAttach);
    if (env == nullptr || gVideoPlayer == nullptr || gInitializePlayback == nullptr) {
        return false;
    }

    env->CallVoidMethod(gVideoPlayer, gInitializePlayback, static_cast<jint>(textureId));
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        __android_log_print(ANDROID_LOG_ERROR, kTag, "initializePlayback threw");
        if (didAttach) {
            gVm->DetachCurrentThread();
        }
        return false;
    }

    if (didAttach) {
        gVm->DetachCurrentThread();
    }
    return true;
}

void UpdateVideoTextureIfNeeded() {
    bool didAttach = false;
    JNIEnv* env = GetEnv(&didAttach);
    if (env == nullptr || gVideoPlayer == nullptr || gUpdateIfNeeded == nullptr) {
        return;
    }

    env->CallVoidMethod(gVideoPlayer, gUpdateIfNeeded);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        __android_log_print(ANDROID_LOG_ERROR, kTag, "updateIfNeeded threw");
    }

    if (didAttach) {
        gVm->DetachCurrentThread();
    }
}

}  // namespace javabridge
