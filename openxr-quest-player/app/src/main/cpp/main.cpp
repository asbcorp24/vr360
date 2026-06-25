#include <android/log.h>
#include <android_native_app_glue.h>

#include "OpenXrApp.h"

namespace {
constexpr const char* kTag = "OpenXRQuestPlayer";

void handleCmd(android_app* app, int32_t cmd) {
    auto* openXrApp = reinterpret_cast<OpenXrApp*>(app->userData);
    if (openXrApp != nullptr) {
        openXrApp->onAppCmd(cmd);
    }
}
}

void android_main(android_app* app) {
    OpenXrApp openXrApp(app);
    app->userData = &openXrApp;
    app->onAppCmd = handleCmd;

    __android_log_print(ANDROID_LOG_INFO, kTag, "android_main started");

    while (true) {
        int events = 0;
        android_poll_source* source = nullptr;

        while (ALooper_pollOnce(0, nullptr, &events, reinterpret_cast<void**>(&source)) >= 0) {
            if (source != nullptr) {
                source->process(app, source);
            }

            if (app->destroyRequested != 0) {
                __android_log_print(ANDROID_LOG_INFO, kTag, "android_main finished");
                return;
            }
        }

        openXrApp.renderFrame();
    }
}
