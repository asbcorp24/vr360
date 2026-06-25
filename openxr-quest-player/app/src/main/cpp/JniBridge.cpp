#include <jni.h>

#include <string>
#include <mutex>
#include <vector>

#include "JavaVideoBridge.h"
#include "OpenXrApp.h"

namespace {
std::mutex gAppMutex;
OpenXrApp* gCurrentApp = nullptr;
}

void RegisterCurrentOpenXrApp(OpenXrApp* app) {
    std::lock_guard<std::mutex> lock(gAppMutex);
    gCurrentApp = app;
}

void UnregisterCurrentOpenXrApp(OpenXrApp* app) {
    std::lock_guard<std::mutex> lock(gAppMutex);
    if (gCurrentApp == app) {
        gCurrentApp = nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_cz_mormegil_openxrquestplayer_QuestNativeBridge_nativeSendLookAt(
    JNIEnv*,
    jobject,
    jfloat yawDeg,
    jfloat pitchDeg,
    jfloat fovDeg,
    jint durationMs
) {
    std::lock_guard<std::mutex> lock(gAppMutex);
    if (gCurrentApp != nullptr) {
        gCurrentApp->setLookAt(yawDeg, pitchDeg, fovDeg, durationMs);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_cz_mormegil_openxrquestplayer_QuestNativeBridge_nativeSetVideoMode(
    JNIEnv*,
    jobject,
    jint inputLayout,
    jint inputMode,
    jint outputMode
) {
    std::lock_guard<std::mutex> lock(gAppMutex);
    if (gCurrentApp != nullptr) {
        gCurrentApp->setVideoMode(inputLayout, inputMode, outputMode);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_cz_mormegil_openxrquestplayer_QuestNativeBridge_nativeClearTextMarks(
    JNIEnv*,
    jobject
) {
    std::lock_guard<std::mutex> lock(gAppMutex);
    if (gCurrentApp != nullptr) {
        gCurrentApp->clearTextMarks();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_cz_mormegil_openxrquestplayer_QuestNativeBridge_nativeShowTextMark(
    JNIEnv* env,
    jobject,
    jstring id,
    jintArray pixelsArgb,
    jint bitmapWidth,
    jint bitmapHeight,
    jfloat x,
    jfloat y,
    jfloat z,
    jfloat yawDeg,
    jfloat pitchDeg,
    jfloat markWidth,
    jfloat markHeight,
    jint durationMs
) {
    if (id == nullptr || pixelsArgb == nullptr || bitmapWidth <= 0 || bitmapHeight <= 0) {
        return;
    }

    const char* idChars = env->GetStringUTFChars(id, nullptr);
    if (idChars == nullptr) {
        return;
    }
    std::string idCopy(idChars);
    env->ReleaseStringUTFChars(id, idChars);

    const jsize pixelCount = env->GetArrayLength(pixelsArgb);
    std::vector<jint> pixels(static_cast<size_t>(pixelCount));
    env->GetIntArrayRegion(pixelsArgb, 0, pixelCount, pixels.data());

    std::lock_guard<std::mutex> lock(gAppMutex);
    if (gCurrentApp != nullptr) {
        gCurrentApp->showTextMark(
            idCopy.c_str(),
            reinterpret_cast<const int*>(pixels.data()),
            bitmapWidth,
            bitmapHeight,
            x,
            y,
            z,
            yawDeg,
            pitchDeg,
            markWidth,
            markHeight,
            durationMs
        );
    }
}

extern "C" JNIEXPORT void JNICALL
Java_cz_mormegil_openxrquestplayer_QuestNativeBridge_nativeAttachVideoPlayer(
    JNIEnv* env,
    jobject,
    jobject videoTexturePlayer
) {
    javabridge::SetVideoPlayer(env, videoTexturePlayer);
    std::lock_guard<std::mutex> lock(gAppMutex);
    if (gCurrentApp != nullptr) {
        gCurrentApp->initializeVideoPlaybackIfReady();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_cz_mormegil_openxrquestplayer_QuestNativeBridge_nativeOnVideoSizeChanged(
    JNIEnv*,
    jobject,
    jint width,
    jint height
) {
    std::lock_guard<std::mutex> lock(gAppMutex);
    if (gCurrentApp != nullptr) {
        gCurrentApp->setVideoSize(width, height);
    }
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    javabridge::SetJavaVm(vm);
    return JNI_VERSION_1_6;
}
