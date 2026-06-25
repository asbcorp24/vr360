#pragma once

#include <EGL/egl.h>
#include <android/native_window.h>
#include <jni.h>
#include <vector>

#include "SceneRenderer.h"

struct android_app;

#define XR_USE_PLATFORM_ANDROID
#define XR_USE_GRAPHICS_API_OPENGL_ES
#include <openxr/openxr_platform.h>
#include <openxr/openxr.h>

class OpenXrApp {
public:
    explicit OpenXrApp(android_app* app);
    ~OpenXrApp();

    void onAppCmd(int32_t cmd);
    void renderFrame();
    void setLookAt(float yawDeg, float pitchDeg, float fovDeg, int durationMs);
    void setVideoMode(int inputLayout, int inputMode, int outputMode);
    void clearTextMarks();
    void showTextMark(
        const char* id,
        const int* pixelsArgb,
        int bitmapWidth,
        int bitmapHeight,
        float x,
        float y,
        float z,
        float yawDeg,
        float pitchDeg,
        float markWidth,
        float markHeight,
        int durationMs
    );
    void setVideoSize(int width, int height);
    void initializeVideoPlaybackIfReady();

private:
    void initializeEgl();
    void destroyEgl();
    void clearFrame() const;
    void initializeOpenXr();
    void shutdownOpenXr();
    void pollOpenXrEvents();
    void handleSessionStateChanged(const XrEventDataSessionStateChanged& event);
    void renderOpenXrFrame();
    void updateHeadPoseFromViews();
    bool loadOpenXrLoader();
    bool loadOpenXrFunctions();

    android_app* app_ = nullptr;
    ANativeWindow* window_ = nullptr;

    EGLDisplay eglDisplay_ = EGL_NO_DISPLAY;
    EGLConfig eglConfig_ = nullptr;
    EGLSurface eglSurface_ = EGL_NO_SURFACE;
    EGLContext eglContext_ = EGL_NO_CONTEXT;

    bool eglReady_ = false;
    bool resumed_ = false;
    bool xrReady_ = false;
    bool xrSessionRunning_ = false;
    bool xrSessionFocused_ = false;

    SceneRenderer sceneRenderer_{};

    void* openxrLoaderHandle_ = nullptr;

    PFN_xrGetInstanceProcAddr xrGetInstanceProcAddr_ = nullptr;
    PFN_xrInitializeLoaderKHR xrInitializeLoaderKHR_ = nullptr;
    PFN_xrCreateInstance xrCreateInstance_ = nullptr;
    PFN_xrDestroyInstance xrDestroyInstance_ = nullptr;
    PFN_xrGetSystem xrGetSystem_ = nullptr;
    PFN_xrEnumerateViewConfigurationViews xrEnumerateViewConfigurationViews_ = nullptr;
    PFN_xrCreateSession xrCreateSession_ = nullptr;
    PFN_xrDestroySession xrDestroySession_ = nullptr;
    PFN_xrEnumerateSwapchainFormats xrEnumerateSwapchainFormats_ = nullptr;
    PFN_xrCreateSwapchain xrCreateSwapchain_ = nullptr;
    PFN_xrDestroySwapchain xrDestroySwapchain_ = nullptr;
    PFN_xrEnumerateSwapchainImages xrEnumerateSwapchainImages_ = nullptr;
    PFN_xrAcquireSwapchainImage xrAcquireSwapchainImage_ = nullptr;
    PFN_xrWaitSwapchainImage xrWaitSwapchainImage_ = nullptr;
    PFN_xrReleaseSwapchainImage xrReleaseSwapchainImage_ = nullptr;
    PFN_xrCreateReferenceSpace xrCreateReferenceSpace_ = nullptr;
    PFN_xrDestroySpace xrDestroySpace_ = nullptr;
    PFN_xrPollEvent xrPollEvent_ = nullptr;
    PFN_xrBeginSession xrBeginSession_ = nullptr;
    PFN_xrEndSession xrEndSession_ = nullptr;
    PFN_xrWaitFrame xrWaitFrame_ = nullptr;
    PFN_xrBeginFrame xrBeginFrame_ = nullptr;
    PFN_xrEndFrame xrEndFrame_ = nullptr;
    PFN_xrLocateViews xrLocateViews_ = nullptr;
    PFN_xrGetOpenGLESGraphicsRequirementsKHR xrGetOpenGLESGraphicsRequirementsKHR_ = nullptr;

    XrInstance xrInstance_ = XR_NULL_HANDLE;
    XrSystemId xrSystemId_ = XR_NULL_SYSTEM_ID;
    XrSession xrSession_ = XR_NULL_HANDLE;
    XrSpace xrAppSpace_ = XR_NULL_HANDLE;
    XrSessionState xrSessionState_ = XR_SESSION_STATE_UNKNOWN;
    XrEventDataBuffer xrEventDataBuffer_{XR_TYPE_EVENT_DATA_BUFFER};

    std::vector<XrViewConfigurationView> xrConfigViews_{};
    std::vector<XrView> xrViews_{};

    struct XrSwapchainInfo {
        XrSwapchain handle = XR_NULL_HANDLE;
        int32_t width = 0;
        int32_t height = 0;
        std::vector<XrSwapchainImageOpenGLESKHR> images{};
    };

    std::vector<XrSwapchainInfo> xrSwapchains_{};
    GLuint xrFramebuffer_ = 0;
    int64_t xrColorSwapchainFormat_ = GL_RGBA8;
    bool videoPlaybackInitialized_ = false;
};
