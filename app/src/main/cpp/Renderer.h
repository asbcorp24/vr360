#ifndef VRVIDEOPLAYER_RENDERER_H
#define VRVIDEOPLAYER_RENDERER_H

#include <array>
#include <string>
#include <vector>

#include <EGL/egl.h>
#include <GLES/gl.h>

#include <cardboard.h>

#include "glm/mat4x4.hpp"
#include "glm/vec3.hpp"

#include "TexturedMesh.h"
#include "GLUtils.h"
#include "VRGuiButton.h"
#include "JavaInterface.h"

/**
 * Is the input video monoscopic or stereoscopic, and if stereoscopic, how are the views stored?
 */
enum class InputVideoLayout {
    MONO = 1,
    STEREO_HORIZ = 2,
    STEREO_VERT = 3,
    ANAGLYPH_RED_CYAN = 4,
};

/**
 * What is the geometry of the input video?
 */
enum class InputVideoMode {
    PLAIN_FOV = 1,
    EQUIRECT_180 = 2,
    EQUIRECT_360 = 3,
    CUBE_MAP = 4,
    EQUIANG_CUBE_MAP = 5,
    PYRAMID = 6,
    PANORAMA_180 = 7,
    PANORAMA_360 = 8,
    STEREO_180 = 9,
    DUAL_FISHEYE_180 = 10,
    STEREO_180_16X9 = 11,
    STEREO_130 = 12,
};

/**
 * How we should render the output?
 */
enum class OutputMode {
    MONO_LEFT = 1,
    MONO_RIGHT = 2,
    CARDBOARD_STEREO = 3,
};

inline bool isOutputModeMono(const OutputMode mode) {
    return mode == OutputMode::MONO_LEFT || mode == OutputMode::MONO_RIGHT;
}

class Renderer {
public:
    Renderer(JavaVM *vm, jobject javaContextObj, jobject javaAssetMgrObj,
             jobject javaVideoTexturePlayerObj, jobject javaControllerObj);

    ~Renderer();

    void OnSurfaceCreated(JNIEnv *env);

    void SetOptions(InputVideoLayout requestedInputLayout, InputVideoMode requestedInputMode,
                    OutputMode requestedOutputMode);

    void ScanCardboardQr();

    void ShowProgressBar();

    void SetScreenParams(int width, int height);

    void DrawFrame(float videoPosition, JNIEnv *env);

    void OnPause();

    void OnResume();

    void SetManualRotation(float yaw, float pitch, float roll);

    void LookAtPoint(float yawDeg, float pitchDeg, float fovDeg, int durationMs);

    void OnVideoSizeChanged(int width, int height);

    /**
     * Показать текстовую метку внутри VR-сферы.
     * pixelsArgb — массив Android ARGB_8888 из Kotlin Bitmap.getPixels().
     */
    void ShowTextMarkFromPixels(
            JNIEnv *env,
            const std::string &markId,
            jintArray pixelsArgb,
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

    /** Скрыть текущую текстовую метку. */
    void ClearTextMarks();


private:
    JavaInterface javaInterface;

    CardboardHeadTrackerPointer cardboardHeadTracker;
    CardboardLensDistortionPointer cardboardLensDistortion;
    CardboardDistortionRendererPointer cardboardDistortionRenderer;

    bool screenParamsChanged;
    bool deviceParamsChanged;
    int screenWidth;
    int screenHeight;
    float screenAspect;
    int videoWidth;
    int videoHeight;
    float videoAspect;



    bool glInitialized;
    InputVideoLayout inputVideoLayout;
    InputVideoMode inputVideoMode;
    OutputMode outputMode;

    unsigned long frameCount;
    GLuint programVideo;
    GLint programVideoParamPosition;
    GLint programVideoParamUV;
    GLint programVideoParamMVPMatrix;
    GLint programVideoParamColorMapMatrix;
    GLuint programVRGui;
    GLint programVRGuiParamPosition;
    GLint programVRGuiParamUV;
    GLint programVRGuiParamMVPMatrix;
    GLuint program2D;
    GLint program2DParamPosition;

    GLuint videoTexture;
    GLuint renderTexture;
    GLuint buttonTexture;
    GLuint textMarkIndicatorTexture = 0;

    // Текстовые метки внутри сферы. Каждая создаётся из Bitmap, пришедшего из Kotlin.
    struct TextMark3D {
        std::string id;
        GLuint texture = 0;
        TexturedMesh mesh;
        bool visible = false;
        uint64_t hideAtMs = 0;
        glm::vec3 direction = glm::vec3(0.0f, 0.0f, -1.0f);
        TexturedMesh pointMesh;
        TexturedMesh connectorMesh;
    };

    std::vector<TextMark3D> textMarks;

    GLuint framebuffer;

    std::array<glm::mat4, 2> cardboardEyeMatrices;
    std::array<glm::mat4, 2> cardboardProjectionMatrices;
    std::array<CardboardEyeTextureDescription, 2> cardboardEyeTextureDescriptions;

    std::array<TexturedMesh, 2> eyeMeshes;

    glm::mat4 viewMatrix;
    float yaw;
    float pitch;

/*
 * Управление камерой по команде сервера:
 * yaw/pitch/fov/duration.
 */
    bool useLookAtControl = false;

    float controlYaw = 0.0f;
    float controlPitch = 0.0f;
    float controlFov = 90.0f;

    float startYaw = 0.0f;
    float startPitch = 0.0f;
    float startFov = 90.0f;

    float targetYaw = 0.0f;
    float targetPitch = 0.0f;
    float targetFov = 90.0f;

    bool lookAtAnimating = false;
    uint64_t lookAtStartMs = 0;
    uint64_t lookAtDurationMs = 0;
    bool lookAtPoseFrozen = false;
    glm::mat4 frozenViewMatrix = glm::mat4(1.0f);
    float frozenYaw = 0.0f;
    float frozenPitch = 0.0f;

    /*
 * Ручной поворот от Android SensorManager.
 * Используется, если Cardboard HeadTracker не двигает сцену.
 */
    bool useManualRotation = false;
    float manualYaw = 0.0f;
    float manualPitch = 0.0f;
    float manualRoll = 0.0f;

    bool vrGuiShown = false;
    bool vrProgressBarShown = false;
    bool isHeadGesturingUp = false;
    float vrGuiCenterTheta = 0.0f;

    bool UpdateDeviceParams();

    void GlSetup();

    void GlTeardown();

    void ComputeMesh();

    void UpdatePose(JNIEnv *env);

    void RenderPointer();

    void RenderCardboardAlignLine();

    void RenderLookAtCountdown(int remainingSeconds) const;

    void RenderTextMarks(const glm::mat4 &mvpMatrix);

    uint64_t GetLookAtReleaseRemainingMs() const;

    void ExecuteButtonAction(const ButtonAction action, JNIEnv *env);

    void InitVideoTexture(JNIEnv *env, GLuint &textureId);

    void InitStaticTexture(JNIEnv *env, GLuint &textureId, const std::string &path);

    void UpdateLookAtAnimation();

    glm::mat4 BuildControlledViewMatrix() const;

    static float NormalizeAngle(float value);

    static float ShortestAngleDelta(float from, float to);

    static float SmoothStep(float t);

    glm::mat4 BuildMVPMatrix(int eye);

    glm::mat4 BuildColorMapMatrix(int eye);
};

#endif //VRVIDEOPLAYER_RENDERER_H
