#include "OpenXrApp.h"

#include <android/log.h>
#include <android_native_app_glue.h>
#include <GLES3/gl3.h>
#include <time.h>
#include <dlfcn.h>

#include <cstdio>
#include <cmath>
#include <algorithm>

#include "JavaVideoBridge.h"

namespace {
constexpr const char* kTag = "OpenXRQuestPlayer";

bool XrSucceeded(XrResult result) {
    return result >= 0;
}

float NormalizeAngle(float value) {
    constexpr float kPi = 3.14159265358979323846f;
    constexpr float kTwoPi = kPi * 2.0f;
    while (value <= -kPi) value += kTwoPi;
    while (value > kPi) value -= kTwoPi;
    return value;
}
}

void RegisterCurrentOpenXrApp(OpenXrApp* app);
void UnregisterCurrentOpenXrApp(OpenXrApp* app);

OpenXrApp::OpenXrApp(android_app* app) : app_(app) {
    __android_log_print(ANDROID_LOG_INFO, kTag, "OpenXrApp created");
    RegisterCurrentOpenXrApp(this);
}

OpenXrApp::~OpenXrApp() {
    shutdownOpenXr();
    destroyEgl();
    UnregisterCurrentOpenXrApp(this);
    __android_log_print(ANDROID_LOG_INFO, kTag, "OpenXrApp destroyed");
}

void OpenXrApp::onAppCmd(int32_t cmd) {
    switch (cmd) {
        case APP_CMD_INIT_WINDOW:
            window_ = app_->window;
            if (window_ != nullptr) {
                initializeEgl();
                initializeOpenXr();
            }
            break;
        case APP_CMD_TERM_WINDOW:
            destroyEgl();
            window_ = nullptr;
            break;
        case APP_CMD_RESUME:
            resumed_ = true;
            break;
        case APP_CMD_PAUSE:
            resumed_ = false;
            break;
        case APP_CMD_DESTROY:
            shutdownOpenXr();
            destroyEgl();
            break;
        default:
            break;
    }
}

void OpenXrApp::renderFrame() {
    if (!eglReady_) {
        return;
    }

    pollOpenXrEvents();
    javabridge::UpdateVideoTextureIfNeeded();
    const auto monotonicSeconds = static_cast<float>(clock()) / static_cast<float>(CLOCKS_PER_SEC);
    sceneRenderer_.updateTime(monotonicSeconds);

    if (xrReady_ && xrSessionRunning_) {
        renderOpenXrFrame();
    }

    if (!resumed_) {
        return;
    }

    clearFrame();
    sceneRenderer_.render();
    eglSwapBuffers(eglDisplay_, eglSurface_);
}

void OpenXrApp::initializeEgl() {
    if (eglReady_ || window_ == nullptr) {
        return;
    }

    eglDisplay_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    eglInitialize(eglDisplay_, nullptr, nullptr);

    constexpr EGLint configAttributes[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_BLUE_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_RED_SIZE, 8,
        EGL_DEPTH_SIZE, 24,
        EGL_NONE
    };

    EGLint numConfigs = 0;
    eglChooseConfig(eglDisplay_, configAttributes, &eglConfig_, 1, &numConfigs);

    constexpr EGLint contextAttributes[] = {
        EGL_CONTEXT_CLIENT_VERSION, 3,
        EGL_NONE
    };

    eglSurface_ = eglCreateWindowSurface(eglDisplay_, eglConfig_, window_, nullptr);
    eglContext_ = eglCreateContext(eglDisplay_, eglConfig_, EGL_NO_CONTEXT, contextAttributes);
    eglMakeCurrent(eglDisplay_, eglSurface_, eglSurface_, eglContext_);

    glEnable(GL_DEPTH_TEST);
    EGLint width = 0;
    EGLint height = 0;
    eglQuerySurface(eglDisplay_, eglSurface_, EGL_WIDTH, &width);
    eglQuerySurface(eglDisplay_, eglSurface_, EGL_HEIGHT, &height);
    sceneRenderer_.initialize();
    sceneRenderer_.setVideoMode(InputVideoLayout::Mono, InputVideoMode::Equirect360, 3);
    sceneRenderer_.resize(width, height);
    initializeVideoPlaybackIfReady();
    eglReady_ = true;
}

void OpenXrApp::destroyEgl() {
    if (!eglReady_) {
        return;
    }

    eglMakeCurrent(eglDisplay_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);

    if (eglContext_ != EGL_NO_CONTEXT) {
        eglDestroyContext(eglDisplay_, eglContext_);
    }
    if (eglSurface_ != EGL_NO_SURFACE) {
        eglDestroySurface(eglDisplay_, eglSurface_);
    }
    if (eglDisplay_ != EGL_NO_DISPLAY) {
        eglTerminate(eglDisplay_);
    }

    eglDisplay_ = EGL_NO_DISPLAY;
    eglSurface_ = EGL_NO_SURFACE;
    eglContext_ = EGL_NO_CONTEXT;
    eglConfig_ = nullptr;
    sceneRenderer_.shutdown();
    eglReady_ = false;
}

void OpenXrApp::clearFrame() const {
    EGLint width = 0;
    EGLint height = 0;
    eglQuerySurface(eglDisplay_, eglSurface_, EGL_WIDTH, &width);
    eglQuerySurface(eglDisplay_, eglSurface_, EGL_HEIGHT, &height);

    glViewport(0, 0, width, height);
    glClearColor(0.03f, 0.05f, 0.08f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
}

void OpenXrApp::initializeOpenXr() {
    if (xrReady_) {
        return;
    }

    if (!loadOpenXrLoader()) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "OpenXR loader not available");
        return;
    }
    if (!loadOpenXrFunctions()) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "OpenXR core functions not available");
        return;
    }

    if (xrInitializeLoaderKHR_ != nullptr) {
        XrLoaderInitInfoAndroidKHR loaderInitInfo{XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR};
        loaderInitInfo.applicationVM = app_->activity->vm;
        loaderInitInfo.applicationContext = app_->activity->clazz;

        const XrResult loaderInitResult = xrInitializeLoaderKHR_(
            reinterpret_cast<const XrLoaderInitInfoBaseHeaderKHR*>(&loaderInitInfo)
        );
        if (!XrSucceeded(loaderInitResult)) {
            __android_log_print(
                ANDROID_LOG_ERROR,
                kTag,
                "xrInitializeLoaderKHR failed: %d",
                loaderInitResult
            );
            return;
        }
    } else {
        __android_log_print(ANDROID_LOG_WARN, kTag, "xrInitializeLoaderKHR not available");
    }

    XrInstanceCreateInfoAndroidKHR androidCreateInfo{XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR};
    androidCreateInfo.applicationVM = app_->activity->vm;
    androidCreateInfo.applicationActivity = app_->activity->clazz;

    const char* extensions[] = {
        XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME,
        XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME,
    };

    XrInstanceCreateInfo createInfo{XR_TYPE_INSTANCE_CREATE_INFO};
    createInfo.next = &androidCreateInfo;
    createInfo.enabledExtensionCount = 2;
    createInfo.enabledExtensionNames = extensions;
    snprintf(createInfo.applicationInfo.applicationName, XR_MAX_APPLICATION_NAME_SIZE, "OpenXRQuestPlayer");
    createInfo.applicationInfo.applicationVersion = 1;
    snprintf(createInfo.applicationInfo.engineName, XR_MAX_ENGINE_NAME_SIZE, "vr360");
    createInfo.applicationInfo.engineVersion = 1;
    createInfo.applicationInfo.apiVersion = XR_API_VERSION_1_0;

    XrResult result = xrCreateInstance_(&createInfo, &xrInstance_);
    if (!XrSucceeded(result)) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "xrCreateInstance failed: %d", result);
        return;
    }

    auto loadInstanceFn = [this](const char* name, PFN_xrVoidFunction* fn) -> bool {
        const XrResult fnResult = xrGetInstanceProcAddr_(xrInstance_, name, fn);
        if (!XrSucceeded(fnResult)) {
            __android_log_print(ANDROID_LOG_ERROR, kTag, "xrGetInstanceProcAddr failed for %s: %d", name, fnResult);
            return false;
        }
        return true;
    };

    if (!loadInstanceFn("xrDestroyInstance", reinterpret_cast<PFN_xrVoidFunction*>(&xrDestroyInstance_)) ||
        !loadInstanceFn("xrGetSystem", reinterpret_cast<PFN_xrVoidFunction*>(&xrGetSystem_)) ||
        !loadInstanceFn("xrEnumerateViewConfigurationViews", reinterpret_cast<PFN_xrVoidFunction*>(&xrEnumerateViewConfigurationViews_)) ||
        !loadInstanceFn("xrCreateSession", reinterpret_cast<PFN_xrVoidFunction*>(&xrCreateSession_)) ||
        !loadInstanceFn("xrDestroySession", reinterpret_cast<PFN_xrVoidFunction*>(&xrDestroySession_)) ||
        !loadInstanceFn("xrEnumerateSwapchainFormats", reinterpret_cast<PFN_xrVoidFunction*>(&xrEnumerateSwapchainFormats_)) ||
        !loadInstanceFn("xrCreateSwapchain", reinterpret_cast<PFN_xrVoidFunction*>(&xrCreateSwapchain_)) ||
        !loadInstanceFn("xrDestroySwapchain", reinterpret_cast<PFN_xrVoidFunction*>(&xrDestroySwapchain_)) ||
        !loadInstanceFn("xrEnumerateSwapchainImages", reinterpret_cast<PFN_xrVoidFunction*>(&xrEnumerateSwapchainImages_)) ||
        !loadInstanceFn("xrAcquireSwapchainImage", reinterpret_cast<PFN_xrVoidFunction*>(&xrAcquireSwapchainImage_)) ||
        !loadInstanceFn("xrWaitSwapchainImage", reinterpret_cast<PFN_xrVoidFunction*>(&xrWaitSwapchainImage_)) ||
        !loadInstanceFn("xrReleaseSwapchainImage", reinterpret_cast<PFN_xrVoidFunction*>(&xrReleaseSwapchainImage_)) ||
        !loadInstanceFn("xrCreateReferenceSpace", reinterpret_cast<PFN_xrVoidFunction*>(&xrCreateReferenceSpace_)) ||
        !loadInstanceFn("xrDestroySpace", reinterpret_cast<PFN_xrVoidFunction*>(&xrDestroySpace_)) ||
        !loadInstanceFn("xrPollEvent", reinterpret_cast<PFN_xrVoidFunction*>(&xrPollEvent_)) ||
        !loadInstanceFn("xrBeginSession", reinterpret_cast<PFN_xrVoidFunction*>(&xrBeginSession_)) ||
        !loadInstanceFn("xrEndSession", reinterpret_cast<PFN_xrVoidFunction*>(&xrEndSession_)) ||
        !loadInstanceFn("xrWaitFrame", reinterpret_cast<PFN_xrVoidFunction*>(&xrWaitFrame_)) ||
        !loadInstanceFn("xrBeginFrame", reinterpret_cast<PFN_xrVoidFunction*>(&xrBeginFrame_)) ||
        !loadInstanceFn("xrEndFrame", reinterpret_cast<PFN_xrVoidFunction*>(&xrEndFrame_)) ||
        !loadInstanceFn("xrLocateViews", reinterpret_cast<PFN_xrVoidFunction*>(&xrLocateViews_)) ||
        !loadInstanceFn("xrGetOpenGLESGraphicsRequirementsKHR", reinterpret_cast<PFN_xrVoidFunction*>(&xrGetOpenGLESGraphicsRequirementsKHR_))) {
        shutdownOpenXr();
        return;
    }

    XrSystemGetInfo systemInfo{XR_TYPE_SYSTEM_GET_INFO};
    systemInfo.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
    result = xrGetSystem_(xrInstance_, &systemInfo, &xrSystemId_);
    if (!XrSucceeded(result)) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "xrGetSystem failed: %d", result);
        shutdownOpenXr();
        return;
    }

    XrGraphicsRequirementsOpenGLESKHR graphicsRequirements{XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR};
    result = xrGetOpenGLESGraphicsRequirementsKHR_(xrInstance_, xrSystemId_, &graphicsRequirements);
    if (!XrSucceeded(result)) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "xrGetOpenGLESGraphicsRequirementsKHR failed: %d", result);
        shutdownOpenXr();
        return;
    }

    uint32_t viewCount = 0;
    result = xrEnumerateViewConfigurationViews_(
        xrInstance_,
        xrSystemId_,
        XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,
        0,
        &viewCount,
        nullptr
    );
    if (!XrSucceeded(result) || viewCount == 0) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "xrEnumerateViewConfigurationViews count failed: %d", result);
        shutdownOpenXr();
        return;
    }

    xrConfigViews_.assign(viewCount, XrViewConfigurationView{XR_TYPE_VIEW_CONFIGURATION_VIEW});
    xrViews_.assign(viewCount, XrView{XR_TYPE_VIEW});

    result = xrEnumerateViewConfigurationViews_(
        xrInstance_,
        xrSystemId_,
        XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,
        viewCount,
        &viewCount,
        xrConfigViews_.data()
    );
    if (!XrSucceeded(result)) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "xrEnumerateViewConfigurationViews data failed: %d", result);
        shutdownOpenXr();
        return;
    }

    XrGraphicsBindingOpenGLESAndroidKHR graphicsBinding{XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR};
    graphicsBinding.display = eglDisplay_;
    graphicsBinding.config = eglConfig_;
    graphicsBinding.context = eglContext_;

    XrSessionCreateInfo sessionCreateInfo{XR_TYPE_SESSION_CREATE_INFO};
    sessionCreateInfo.next = &graphicsBinding;
    sessionCreateInfo.systemId = xrSystemId_;
    result = xrCreateSession_(xrInstance_, &sessionCreateInfo, &xrSession_);
    if (!XrSucceeded(result)) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "xrCreateSession failed: %d", result);
        shutdownOpenXr();
        return;
    }

    uint32_t formatCount = 0;
    result = xrEnumerateSwapchainFormats_(xrSession_, 0, &formatCount, nullptr);
    if (!XrSucceeded(result) || formatCount == 0) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "xrEnumerateSwapchainFormats count failed: %d", result);
        shutdownOpenXr();
        return;
    }
    std::vector<int64_t> formats(formatCount);
    result = xrEnumerateSwapchainFormats_(xrSession_, formatCount, &formatCount, formats.data());
    if (!XrSucceeded(result)) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "xrEnumerateSwapchainFormats data failed: %d", result);
        shutdownOpenXr();
        return;
    }
    if (std::find(formats.begin(), formats.end(), static_cast<int64_t>(GL_SRGB8_ALPHA8)) != formats.end()) {
        xrColorSwapchainFormat_ = GL_SRGB8_ALPHA8;
    } else if (std::find(formats.begin(), formats.end(), static_cast<int64_t>(GL_RGBA8)) != formats.end()) {
        xrColorSwapchainFormat_ = GL_RGBA8;
    } else {
        xrColorSwapchainFormat_ = formats.front();
    }

    xrSwapchains_.clear();
    xrSwapchains_.reserve(xrConfigViews_.size());
    for (const auto& configView : xrConfigViews_) {
        XrSwapchainCreateInfo swapchainCreateInfo{XR_TYPE_SWAPCHAIN_CREATE_INFO};
        swapchainCreateInfo.arraySize = 1;
        swapchainCreateInfo.format = xrColorSwapchainFormat_;
        swapchainCreateInfo.width = static_cast<int32_t>(configView.recommendedImageRectWidth);
        swapchainCreateInfo.height = static_cast<int32_t>(configView.recommendedImageRectHeight);
        swapchainCreateInfo.mipCount = 1;
        swapchainCreateInfo.faceCount = 1;
        swapchainCreateInfo.sampleCount = configView.recommendedSwapchainSampleCount;
        swapchainCreateInfo.usageFlags = XR_SWAPCHAIN_USAGE_SAMPLED_BIT | XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT;

        XrSwapchainInfo swapchainInfo{};
        swapchainInfo.width = swapchainCreateInfo.width;
        swapchainInfo.height = swapchainCreateInfo.height;
        result = xrCreateSwapchain_(xrSession_, &swapchainCreateInfo, &swapchainInfo.handle);
        if (!XrSucceeded(result)) {
            __android_log_print(ANDROID_LOG_ERROR, kTag, "xrCreateSwapchain failed: %d", result);
            shutdownOpenXr();
            return;
        }

        uint32_t imageCount = 0;
        result = xrEnumerateSwapchainImages_(swapchainInfo.handle, 0, &imageCount, nullptr);
        if (!XrSucceeded(result) || imageCount == 0) {
            __android_log_print(ANDROID_LOG_ERROR, kTag, "xrEnumerateSwapchainImages count failed: %d", result);
            shutdownOpenXr();
            return;
        }
        swapchainInfo.images.assign(imageCount, XrSwapchainImageOpenGLESKHR{XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR});
        result = xrEnumerateSwapchainImages_(
            swapchainInfo.handle,
            imageCount,
            &imageCount,
            reinterpret_cast<XrSwapchainImageBaseHeader*>(swapchainInfo.images.data())
        );
        if (!XrSucceeded(result)) {
            __android_log_print(ANDROID_LOG_ERROR, kTag, "xrEnumerateSwapchainImages data failed: %d", result);
            shutdownOpenXr();
            return;
        }

        xrSwapchains_.push_back(std::move(swapchainInfo));
    }

    if (xrFramebuffer_ == 0) {
        glGenFramebuffers(1, &xrFramebuffer_);
    }

    XrReferenceSpaceCreateInfo spaceCreateInfo{XR_TYPE_REFERENCE_SPACE_CREATE_INFO};
    spaceCreateInfo.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL;
    spaceCreateInfo.poseInReferenceSpace.orientation.w = 1.0f;
    result = xrCreateReferenceSpace_(xrSession_, &spaceCreateInfo, &xrAppSpace_);
    if (!XrSucceeded(result)) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "xrCreateReferenceSpace failed: %d", result);
        shutdownOpenXr();
        return;
    }

    xrReady_ = true;
    __android_log_print(ANDROID_LOG_INFO, kTag, "OpenXR initialized");
}

void OpenXrApp::shutdownOpenXr() {
    xrSessionRunning_ = false;
    xrSessionFocused_ = false;
    xrSessionState_ = XR_SESSION_STATE_UNKNOWN;
    for (auto& swapchain : xrSwapchains_) {
        if (xrDestroySwapchain_ != nullptr && swapchain.handle != XR_NULL_HANDLE) {
            xrDestroySwapchain_(swapchain.handle);
        }
    }
    xrSwapchains_.clear();
    if (xrFramebuffer_ != 0) {
        glDeleteFramebuffers(1, &xrFramebuffer_);
        xrFramebuffer_ = 0;
    }
    if (xrDestroySpace_ != nullptr && xrAppSpace_ != XR_NULL_HANDLE) {
        xrDestroySpace_(xrAppSpace_);
        xrAppSpace_ = XR_NULL_HANDLE;
    }
    if (xrDestroySession_ != nullptr && xrSession_ != XR_NULL_HANDLE) {
        xrDestroySession_(xrSession_);
        xrSession_ = XR_NULL_HANDLE;
    }
    if (xrDestroyInstance_ != nullptr && xrInstance_ != XR_NULL_HANDLE) {
        xrDestroyInstance_(xrInstance_);
        xrInstance_ = XR_NULL_HANDLE;
    }
    if (openxrLoaderHandle_ != nullptr) {
        dlclose(openxrLoaderHandle_);
        openxrLoaderHandle_ = nullptr;
    }
    xrSystemId_ = XR_NULL_SYSTEM_ID;
    xrConfigViews_.clear();
    xrViews_.clear();
    xrReady_ = false;
}

void OpenXrApp::setLookAt(float yawDeg, float pitchDeg, float fovDeg, int durationMs) {
    sceneRenderer_.setLookAt(yawDeg, pitchDeg, fovDeg, durationMs);
}

void OpenXrApp::setVideoMode(int inputLayout, int inputMode, int outputMode) {
    sceneRenderer_.setVideoMode(
        static_cast<InputVideoLayout>(inputLayout),
        static_cast<InputVideoMode>(inputMode),
        outputMode
    );
}

void OpenXrApp::clearTextMarks() {
    sceneRenderer_.clearTextMarks();
}

void OpenXrApp::showTextMark(
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
) {
    sceneRenderer_.showTextMark(
        id,
        pixelsArgb,
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

void OpenXrApp::setVideoSize(int width, int height) {
    sceneRenderer_.setVideoSize(width, height);
}

void OpenXrApp::initializeVideoPlaybackIfReady() {
    if (videoPlaybackInitialized_) {
        return;
    }
    const GLuint textureId = sceneRenderer_.videoTextureId();
    if (textureId == 0) {
        return;
    }
    videoPlaybackInitialized_ = javabridge::InitializeVideoPlayback(textureId);
}

void OpenXrApp::pollOpenXrEvents() {
    if (!xrReady_ || xrPollEvent_ == nullptr) {
        return;
    }

    while (true) {
        xrEventDataBuffer_ = {XR_TYPE_EVENT_DATA_BUFFER};
        const XrResult result = xrPollEvent_(xrInstance_, &xrEventDataBuffer_);
        if (result == XR_EVENT_UNAVAILABLE) {
            break;
        }
        if (!XrSucceeded(result)) {
            __android_log_print(ANDROID_LOG_ERROR, kTag, "xrPollEvent failed: %d", result);
            break;
        }

        const auto* header = reinterpret_cast<const XrEventDataBaseHeader*>(&xrEventDataBuffer_);
        if (header->type == XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED) {
            handleSessionStateChanged(*reinterpret_cast<const XrEventDataSessionStateChanged*>(header));
        }
    }
}

void OpenXrApp::handleSessionStateChanged(const XrEventDataSessionStateChanged& event) {
    xrSessionState_ = event.state;
    xrSessionFocused_ = (xrSessionState_ == XR_SESSION_STATE_FOCUSED);

    switch (xrSessionState_) {
        case XR_SESSION_STATE_READY: {
            XrSessionBeginInfo beginInfo{XR_TYPE_SESSION_BEGIN_INFO};
            beginInfo.primaryViewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
            const XrResult result = xrBeginSession_(xrSession_, &beginInfo);
            if (XrSucceeded(result)) {
                xrSessionRunning_ = true;
            } else {
                __android_log_print(ANDROID_LOG_ERROR, kTag, "xrBeginSession failed: %d", result);
            }
            break;
        }
        case XR_SESSION_STATE_STOPPING: {
            xrSessionRunning_ = false;
            const XrResult result = xrEndSession_(xrSession_);
            if (!XrSucceeded(result)) {
                __android_log_print(ANDROID_LOG_ERROR, kTag, "xrEndSession failed: %d", result);
            }
            break;
        }
        case XR_SESSION_STATE_EXITING:
        case XR_SESSION_STATE_LOSS_PENDING:
            xrSessionRunning_ = false;
            break;
        default:
            break;
    }
}

void OpenXrApp::renderOpenXrFrame() {
    if (!xrSessionRunning_) {
        return;
    }

    XrFrameWaitInfo frameWaitInfo{XR_TYPE_FRAME_WAIT_INFO};
    XrFrameState frameState{XR_TYPE_FRAME_STATE};
    XrResult result = xrWaitFrame_(xrSession_, &frameWaitInfo, &frameState);
    if (!XrSucceeded(result)) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "xrWaitFrame failed: %d", result);
        return;
    }

    XrFrameBeginInfo beginInfo{XR_TYPE_FRAME_BEGIN_INFO};
    result = xrBeginFrame_(xrSession_, &beginInfo);
    if (!XrSucceeded(result)) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "xrBeginFrame failed: %d", result);
        return;
    }

    XrCompositionLayerProjection layer{XR_TYPE_COMPOSITION_LAYER_PROJECTION};
    std::vector<XrCompositionLayerProjectionView> layerViews;

    if (frameState.shouldRender == XR_TRUE) {
        XrViewState viewState{XR_TYPE_VIEW_STATE};
        uint32_t viewCountOutput = 0;
        XrViewLocateInfo locateInfo{XR_TYPE_VIEW_LOCATE_INFO};
        locateInfo.viewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
        locateInfo.displayTime = frameState.predictedDisplayTime;
        locateInfo.space = xrAppSpace_;

        result = xrLocateViews_(
            xrSession_,
            &locateInfo,
            &viewState,
            static_cast<uint32_t>(xrViews_.size()),
            &viewCountOutput,
            xrViews_.data()
        );

        if (XrSucceeded(result) &&
            (viewState.viewStateFlags & XR_VIEW_STATE_POSITION_VALID_BIT) != 0 &&
            (viewState.viewStateFlags & XR_VIEW_STATE_ORIENTATION_VALID_BIT) != 0 &&
            viewCountOutput > 0 &&
            viewCountOutput == xrSwapchains_.size()) {
            updateHeadPoseFromViews();

            layerViews.resize(viewCountOutput);
            glBindFramebuffer(GL_FRAMEBUFFER, xrFramebuffer_);

            for (uint32_t eye = 0; eye < viewCountOutput; ++eye) {
                auto& swapchain = xrSwapchains_[eye];
                XrSwapchainImageAcquireInfo acquireInfo{XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO};
                uint32_t imageIndex = 0;
                result = xrAcquireSwapchainImage_(swapchain.handle, &acquireInfo, &imageIndex);
                if (!XrSucceeded(result)) {
                    __android_log_print(ANDROID_LOG_ERROR, kTag, "xrAcquireSwapchainImage failed: %d", result);
                    continue;
                }

                XrSwapchainImageWaitInfo waitInfo{XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO};
                waitInfo.timeout = XR_INFINITE_DURATION;
                result = xrWaitSwapchainImage_(swapchain.handle, &waitInfo);
                if (!XrSucceeded(result)) {
                    __android_log_print(ANDROID_LOG_ERROR, kTag, "xrWaitSwapchainImage failed: %d", result);
                    XrSwapchainImageReleaseInfo releaseInfo{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};
                    xrReleaseSwapchainImage_(swapchain.handle, &releaseInfo);
                    continue;
                }

                glFramebufferTexture2D(
                    GL_FRAMEBUFFER,
                    GL_COLOR_ATTACHMENT0,
                    GL_TEXTURE_2D,
                    swapchain.images[imageIndex].image,
                    0
                );
                glClearColor(0.03f, 0.05f, 0.08f, 1.0f);
                glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

                const XrFovf& fov = xrViews_[eye].fov;
                const auto projection = math::PerspectiveFromFov(
                    fov.angleLeft,
                    fov.angleRight,
                    fov.angleUp,
                    fov.angleDown,
                    0.1f,
                    20.0f
                );

                const XrQuaternionf& orientation = xrViews_[eye].pose.orientation;
                const auto eyeView = math::FromQuaternion(
                    -orientation.x,
                    -orientation.y,
                    -orientation.z,
                    orientation.w
                );

                sceneRenderer_.renderEye(projection, eyeView, swapchain.width, swapchain.height);

                layerViews[eye] = {XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW};
                layerViews[eye].pose = xrViews_[eye].pose;
                layerViews[eye].fov = xrViews_[eye].fov;
                layerViews[eye].subImage.swapchain = swapchain.handle;
                layerViews[eye].subImage.imageRect.offset = {0, 0};
                layerViews[eye].subImage.imageRect.extent = {swapchain.width, swapchain.height};
                layerViews[eye].subImage.imageArrayIndex = 0;

                XrSwapchainImageReleaseInfo releaseInfo{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};
                result = xrReleaseSwapchainImage_(swapchain.handle, &releaseInfo);
                if (!XrSucceeded(result)) {
                    __android_log_print(ANDROID_LOG_ERROR, kTag, "xrReleaseSwapchainImage failed: %d", result);
                }
            }

            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            layer.space = xrAppSpace_;
            layer.viewCount = static_cast<uint32_t>(layerViews.size());
            layer.views = layerViews.data();
        }
    }

    XrFrameEndInfo endInfo{XR_TYPE_FRAME_END_INFO};
    endInfo.displayTime = frameState.predictedDisplayTime;
    endInfo.environmentBlendMode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
    XrCompositionLayerBaseHeader* layers[] = {
        reinterpret_cast<XrCompositionLayerBaseHeader*>(&layer)
    };
    endInfo.layerCount = layerViews.empty() ? 0u : 1u;
    endInfo.layers = layerViews.empty() ? nullptr : layers;
    result = xrEndFrame_(xrSession_, &endInfo);
    if (!XrSucceeded(result)) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "xrEndFrame failed: %d", result);
    }
}

void OpenXrApp::updateHeadPoseFromViews() {
    if (xrViews_.empty()) {
        return;
    }

    const XrQuaternionf& q = xrViews_[0].pose.orientation;
    const float m02 = 2.0f * (q.x * q.z - q.w * q.y);
    const float m12 = 2.0f * (q.y * q.z + q.x * q.w);
    const float m22 = 1.0f - 2.0f * (q.x * q.x + q.y * q.y);
    const float forwardX = -m02;
    const float forwardY = -m12;
    const float forwardZ = -m22;

    const float pitch = std::asin(std::clamp(forwardY, -1.0f, 1.0f));
    const float yaw = NormalizeAngle(-std::atan2(forwardX, forwardZ));
    sceneRenderer_.setHeadPose(yaw, pitch);
}

bool OpenXrApp::loadOpenXrLoader() {
    if (openxrLoaderHandle_ != nullptr) {
        return true;
    }

    openxrLoaderHandle_ = dlopen("libopenxr_loader.so", RTLD_NOW | RTLD_LOCAL);
    if (openxrLoaderHandle_ == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "dlopen libopenxr_loader.so failed: %s", dlerror());
        return false;
    }
    return true;
}

bool OpenXrApp::loadOpenXrFunctions() {
    xrGetInstanceProcAddr_ = reinterpret_cast<PFN_xrGetInstanceProcAddr>(
        dlsym(openxrLoaderHandle_, "xrGetInstanceProcAddr")
    );
    xrCreateInstance_ = reinterpret_cast<PFN_xrCreateInstance>(
        dlsym(openxrLoaderHandle_, "xrCreateInstance")
    );

    if (xrGetInstanceProcAddr_ == nullptr || xrCreateInstance_ == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "Failed to resolve loader entry points");
        return false;
    }

    PFN_xrVoidFunction initLoaderFn = nullptr;
    const XrResult initLoaderLookup = xrGetInstanceProcAddr_(
        XR_NULL_HANDLE,
        "xrInitializeLoaderKHR",
        &initLoaderFn
    );
    if (XrSucceeded(initLoaderLookup)) {
        xrInitializeLoaderKHR_ = reinterpret_cast<PFN_xrInitializeLoaderKHR>(initLoaderFn);
    } else {
        xrInitializeLoaderKHR_ = nullptr;
        __android_log_print(
            ANDROID_LOG_WARN,
            kTag,
            "xrInitializeLoaderKHR lookup failed: %d",
            initLoaderLookup
        );
    }
    return true;
}
