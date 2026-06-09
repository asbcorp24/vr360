#include "Renderer.h"

#include <cmath>

#include <array>
#include <fstream>
#include <vector>
#include <string>
#include <vector>
#include <algorithm>
#include <cstdint>

#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

#include <cardboard.h>

#include "glm/vec2.hpp"
#include "glm/vec3.hpp"
#include "glm/vec4.hpp"
#include "glm/mat4x4.hpp"
#include "glm/geometric.hpp"
#define GLM_ENABLE_EXPERIMENTAL
#include "glm/gtx/quaternion.hpp"
#include "glm/gtx/matrix_operation.hpp"
#include "glm/ext/matrix_transform.hpp"
#include "glm/ext/matrix_clip_space.hpp"
#include "glm/ext/scalar_constants.hpp"
#include "glm/gtc/type_ptr.hpp"

#include "VRGuiButton.h"
#include "GLUtils.h"
#include "logger.h"
#include "VRGuiProgressBar.h"

#define LOG_TAG "VRVideoPlayerR"

constexpr uint64_t kPredictionTimeWithoutVsyncNanos = 50'000'000UL;
constexpr float kzNear = 0.1f;
constexpr float kzFar = 2.0f;

constexpr const char *kVertexShader = R"glsl(#version 300 es
uniform mat4 u_MVP;
in vec4 a_Position;
in vec2 a_UV;
out vec2 v_UV;

void main() {
  v_UV = a_UV;
  gl_Position = u_MVP * a_Position;
})glsl";

constexpr const char *kFragmentShader = R"glsl(#version 300 es
#extension GL_OES_EGL_image_external : enable
#extension GL_OES_EGL_image_external_essl3 : enable
precision mediump float;

uniform samplerExternalOES u_Texture;
uniform mat4 u_ColorMap;
in vec2 v_UV;
out vec4 fragColor;

void main() {
  fragColor = u_ColorMap * texture(u_Texture, v_UV);
})glsl";

constexpr const char *kFragmentShaderVRGui = R"glsl(#version 300 es
precision mediump float;

uniform sampler2D u_Texture;
in vec2 v_UV;
out vec4 fragColor;

void main() {
  fragColor = texture(u_Texture, v_UV);
})glsl";

constexpr const char *kVertexShader2D = R"glsl(#version 300 es
in vec4 a_Position;

void main() {
  gl_Position = a_Position;
})glsl";

constexpr const char *kFragmentShader2D = R"glsl(#version 300 es
precision mediump float;

out vec4 fragColor;

void main() {
  fragColor = vec4(1.0);
})glsl";

static constexpr float M_TWO_PI = (float) M_PI * 2.0f;

static constexpr float PLAIN_FOV_Z = -1.0f;

static constexpr float VR_GUI_BUTTON_GRID = M_PI * 8 / 180.0f;
static constexpr float VR_GUI_BUTTON_SIZE = M_PI * 7 / 180.0f;
static constexpr float VR_GUI_BUTTON_PHI_0 = -0.5f * VR_GUI_BUTTON_GRID;
static constexpr float VR_GUI_DISTANCE = kzFar * 0.4f;

static constexpr float HEAD_GESTURE_PITCH_LIMIT = glm::radians(60.0f);
static constexpr float HEAD_GESTURE_PITCH_LIMIT_RETURN = glm::radians(45.0f);

static constexpr int PROGRESS_BAR_SHOW_TIME = 3;

static constexpr glm::vec3 Y_AXIS = {0.0f, 1.0f, 0.0f};
static constexpr glm::vec4 NEG_Z_AXIS = {0.0f, 0.0f, -1.0f, 1.0f};

static constexpr std::array<float, 3> pointerCoords = {0.0f, 0.0f, VR_GUI_DISTANCE};
static constexpr std::array<float, 6> cardboardAlignLineCoords = {
        0.0f, -0.2f, 0.5f,
        0.0f, -1.0f, 0.5f
};

static constexpr GLubyte trivial2DData[] = {0, 1};

/*
 * ВАЖНО:
 * Делаем file-scope флаг, чтобы не менять Renderer.h.
 *
 * true  — есть Cardboard QR, используем Cardboard distortion.
 * false — QR нет, но всё равно рисуем два глаза split-screen без distortion.
 */
static bool gCardboardReady = false;

/*
 * Серверная точка yaw/pitch должна быть временной:
 * 1) плавно повернулись к точке;
 * 2) немного удержали точку;
 * 3) сбросили серверную базу, чтобы голова снова двигалась в нормальной системе координат.
 */
static constexpr uint64_t kLookAtHoldMs = 1500;
static bool gLookAtHolding = false;
static uint64_t gLookAtHoldStartMs = 0;

static std::array<VRGuiButton, 10> vrGuiButtons{
        VRGuiButton(M_PI - 1 * VR_GUI_BUTTON_GRID, VR_GUI_BUTTON_PHI_0 - 1 * VR_GUI_BUTTON_GRID,
                    VR_GUI_DISTANCE, VR_GUI_BUTTON_SIZE, 0, 0, ButtonAction::RECENTER_2D,
                    ButtonBehavior::DELAYED_TRIGGER, true),
        VRGuiButton(M_PI - 0 * VR_GUI_BUTTON_GRID, VR_GUI_BUTTON_PHI_0 - 0 * VR_GUI_BUTTON_GRID,
                    VR_GUI_DISTANCE, VR_GUI_BUTTON_SIZE, 256, 0, ButtonAction::RECENTER_YAW,
                    ButtonBehavior::DELAYED_TRIGGER, true),
        VRGuiButton(M_PI - 2 * VR_GUI_BUTTON_GRID, VR_GUI_BUTTON_PHI_0 - 0 * VR_GUI_BUTTON_GRID,
                    VR_GUI_DISTANCE, VR_GUI_BUTTON_SIZE, 512, 0, ButtonAction::VOLUME_DOWN,
                    ButtonBehavior::AUTO_REPEAT, true),
        VRGuiButton(M_PI - 1 * VR_GUI_BUTTON_GRID, VR_GUI_BUTTON_PHI_0 - 0 * VR_GUI_BUTTON_GRID,
                    VR_GUI_DISTANCE, VR_GUI_BUTTON_SIZE, 768, 0, ButtonAction::VOLUME_UP,
                    ButtonBehavior::AUTO_REPEAT, true),
        VRGuiButton(M_PI - 2 * VR_GUI_BUTTON_GRID, VR_GUI_BUTTON_PHI_0 - 1 * VR_GUI_BUTTON_GRID,
                    VR_GUI_DISTANCE, VR_GUI_BUTTON_SIZE, 0, 256, ButtonAction::OPEN_FILE,
                    ButtonBehavior::DELAYED_TRIGGER, true),
        VRGuiButton(M_PI + 1 * VR_GUI_BUTTON_GRID, VR_GUI_BUTTON_PHI_0 - 1 * VR_GUI_BUTTON_GRID,
                    VR_GUI_DISTANCE, VR_GUI_BUTTON_SIZE, 256, 256, ButtonAction::PLAY,
                    ButtonBehavior::DELAYED_TRIGGER, false),
        VRGuiButton(M_PI + 1 * VR_GUI_BUTTON_GRID, VR_GUI_BUTTON_PHI_0 - 1 * VR_GUI_BUTTON_GRID,
                    VR_GUI_DISTANCE, VR_GUI_BUTTON_SIZE, 512, 256, ButtonAction::BACK,
                    ButtonBehavior::DELAYED_TRIGGER, true),
        VRGuiButton(M_PI + 2 * VR_GUI_BUTTON_GRID, VR_GUI_BUTTON_PHI_0 - 1 * VR_GUI_BUTTON_GRID,
                    VR_GUI_DISTANCE, VR_GUI_BUTTON_SIZE, 768, 256, ButtonAction::FORWARD,
                    ButtonBehavior::AUTO_REPEAT, true),
        VRGuiButton(M_PI + 2 * VR_GUI_BUTTON_GRID, VR_GUI_BUTTON_PHI_0 - 0 * VR_GUI_BUTTON_GRID,
                    VR_GUI_DISTANCE, VR_GUI_BUTTON_SIZE, 0, 512, ButtonAction::REWIND,
                    ButtonBehavior::AUTO_REPEAT, true),
        VRGuiButton(M_PI + 1 * VR_GUI_BUTTON_GRID, VR_GUI_BUTTON_PHI_0 - 0 * VR_GUI_BUTTON_GRID,
                    VR_GUI_DISTANCE, VR_GUI_BUTTON_SIZE, 256, 512, ButtonAction::PAUSE,
                    ButtonBehavior::DELAYED_TRIGGER, true)
};

static VRGuiProgressBar vrGuiProgressBar(
        0,
        3 * VR_GUI_BUTTON_GRID,
        6 * VR_GUI_BUTTON_GRID,
        0.5f * VR_GUI_BUTTON_GRID
);

static time_t vrGuiProgressBarHideAt;

Renderer::Renderer(JavaVM *vm,
                   jobject javaContextObj,
                   jobject javaAssetMgrObj,
                   jobject javaVideoTexturePlayerObj,
                   jobject javaControllerObj)
        : glInitialized(false),
          screenParamsChanged(false),
          deviceParamsChanged(false),
          frameCount(0),
          inputVideoMode{},
          inputVideoLayout{},
          outputMode{},
          eyeMeshes{},
          viewMatrix{},
          cardboardHeadTracker{},
          javaInterface(vm, javaContextObj, javaAssetMgrObj, javaVideoTexturePlayerObj,
                        javaControllerObj) {
    LOG_DEBUG("Renderer instance created");

    Cardboard_initializeAndroid(vm, javaContextObj);
    cardboardHeadTracker = CardboardHeadTrackerPointer(CardboardHeadTracker_create());

    SetOptions(InputVideoLayout::MONO, InputVideoMode::EQUIRECT_360, OutputMode::CARDBOARD_STEREO);
}

Renderer::~Renderer() {
    LOG_DEBUG("Renderer instance destroyed");
}

void Renderer::OnPause() {
    LOG_DEBUG("OnPause after %lu frames", frameCount);

    CardboardHeadTracker_pause(cardboardHeadTracker.get());
}

void Renderer::SetManualRotation(float yawValue, float pitchValue, float rollValue) {
    useManualRotation = true;
    manualYaw = yawValue;
    manualPitch = pitchValue;
    manualRoll = rollValue;
}

void Renderer::LookAtPoint(float yawDeg, float pitchDeg, float fovDeg, int durationMs) {
    useLookAtControl = true;

    startYaw = controlYaw;
    startPitch = controlPitch;
    startFov = controlFov;

    targetYaw = glm::radians(yawDeg);
    targetPitch = glm::radians(pitchDeg);
    targetFov = fovDeg;

    targetYaw = NormalizeAngle(targetYaw);
    targetPitch = glm::clamp(targetPitch, glm::radians(-85.0f), glm::radians(85.0f));
    targetFov = glm::clamp(targetFov, 35.0f, 120.0f);

    lookAtDurationMs = durationMs <= 0 ? 1 : static_cast<uint64_t>(durationMs);
    lookAtStartMs = GetBootTimeNano() / 1000000ULL;
    lookAtAnimating = true;

    /*
     * Новая команда отменяет предыдущее удержание.
     */
    gLookAtHolding = false;
    gLookAtHoldStartMs = 0;

    LOG_DEBUG(
            "LookAtPoint yawDeg=%.2f pitchDeg=%.2f fovDeg=%.2f durationMs=%d",
            yawDeg,
            pitchDeg,
            fovDeg,
            durationMs
    );
}

void Renderer::OnResume() {
    LOG_DEBUG("OnResume");

    frameCount = 0;

    deviceParamsChanged = true;

    CardboardHeadTracker_resume(cardboardHeadTracker.get());
}

void Renderer::SetScreenParams(int width, int height) {
    LOG_DEBUG("SetScreenParams(%d, %d)", width, height);

    screenWidth = width;
    screenHeight = height;
    screenAspect = (float) width / (float) height;
    screenParamsChanged = true;
}

static void initStaticTexture(JNIEnv *env,
                              jobject java_asset_mgr,
                              GLuint &textureId,
                              const std::string &path) {
    glGenTextures(1, &textureId);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, textureId);

    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

    if (!LoadPngFromAssetManager(env, java_asset_mgr, GL_TEXTURE_2D, path)) {
        LOG_ERROR("Couldn't load texture");
        return;
    }

    glGenerateMipmap(GL_TEXTURE_2D);

    CHECK_GL_ERROR("Texture load");
}

void Renderer::InitVideoTexture(JNIEnv *env, GLuint &textureId) {
    glGenTextures(1, &textureId);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, textureId);

    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

    if (!javaInterface.InitializePlayback(env, textureId)) {
        LOG_ERROR("Couldn't initialize video texture");
        return;
    }

    CHECK_GL_ERROR("Video texture init");
}

void Renderer::InitStaticTexture(JNIEnv *env, GLuint &textureId, const std::string &path) {
    glGenTextures(1, &textureId);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, textureId);

    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

    if (!javaInterface.LoadPngFromAssetManager(env, GL_TEXTURE_2D, path)) {
        LOG_ERROR("Couldn't load texture");
        return;
    }

    glGenerateMipmap(GL_TEXTURE_2D);

    CHECK_GL_ERROR("Texture load");
}


static void InitSolidRgbaTexture(GLuint &textureId,
                                 unsigned char r,
                                 unsigned char g,
                                 unsigned char b,
                                 unsigned char a) {
    const unsigned char pixel[4] = {r, g, b, a};

    if (textureId == 0) {
        glGenTextures(1, &textureId);
    }

    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, textureId);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);

    glTexImage2D(
            GL_TEXTURE_2D,
            0,
            GL_RGBA,
            1,
            1,
            0,
            GL_RGBA,
            GL_UNSIGNED_BYTE,
            pixel
    );

    CHECK_GL_ERROR("InitSolidRgbaTexture");
}

void Renderer::OnSurfaceCreated(JNIEnv *env) {
    LOG_DEBUG("OnSurfaceCreated");

    const GLuint vertexShader = LoadGLShader(GL_VERTEX_SHADER, kVertexShader);
    const GLuint fragmentShader = LoadGLShader(GL_FRAGMENT_SHADER, kFragmentShader);

    programVideo = glCreateProgram();
    glAttachShader(programVideo, vertexShader);
    glAttachShader(programVideo, fragmentShader);
    glLinkProgram(programVideo);
    glUseProgram(programVideo);
    CHECK_GL_ERROR("Video program");

    programVideoParamPosition = glGetAttribLocation(programVideo, "a_Position");
    programVideoParamUV = glGetAttribLocation(programVideo, "a_UV");
    programVideoParamMVPMatrix = glGetUniformLocation(programVideo, "u_MVP");
    programVideoParamColorMapMatrix = glGetUniformLocation(programVideo, "u_ColorMap");
    CHECK_GL_ERROR("Video program params");

    InitVideoTexture(env, videoTexture);

    const GLuint vertexShaderVRGui = LoadGLShader(GL_VERTEX_SHADER, kVertexShader);
    const GLuint fragmentShaderVRGui = LoadGLShader(GL_FRAGMENT_SHADER, kFragmentShaderVRGui);

    programVRGui = glCreateProgram();
    glAttachShader(programVRGui, vertexShaderVRGui);
    glAttachShader(programVRGui, fragmentShaderVRGui);
    glLinkProgram(programVRGui);
    glUseProgram(programVRGui);
    CHECK_GL_ERROR("VR Gui program");

    programVRGuiParamPosition = glGetAttribLocation(programVRGui, "a_Position");
    programVRGuiParamUV = glGetAttribLocation(programVRGui, "a_UV");
    programVRGuiParamMVPMatrix = glGetUniformLocation(programVRGui, "u_MVP");
    CHECK_GL_ERROR("VR Gui program params");

    InitStaticTexture(env, buttonTexture, "buttons-texture.png");

    // Текстура для точек и стрелок текстовых меток внутри сферы.
    InitSolidRgbaTexture(textMarkIndicatorTexture, 0, 230, 255, 255);

    const GLuint vertexShader2D = LoadGLShader(GL_VERTEX_SHADER, kVertexShader2D);
    const GLuint fragmentShader2D = LoadGLShader(GL_FRAGMENT_SHADER, kFragmentShader2D);

    program2D = glCreateProgram();
    glAttachShader(program2D, vertexShader2D);
    glAttachShader(program2D, fragmentShader2D);
    glLinkProgram(program2D);
    glUseProgram(program2D);
    CHECK_GL_ERROR("2D program");

    program2DParamPosition = glGetAttribLocation(program2D, "a_Position");
    CHECK_GL_ERROR("2D program params");
}

void Renderer::DrawFrame(float videoPosition, JNIEnv *env) {
    if (!UpdateDeviceParams()) {
        return;
    }

    UpdatePose(env);
    UpdateLookAtAnimation();

    int minEye, maxEye;
    GLsizei eyeWidth;

    switch (outputMode) {
        case OutputMode::MONO_LEFT:
            minEye = 0;
            maxEye = 0;
            eyeWidth = screenWidth;
            break;

        case OutputMode::MONO_RIGHT:
            minEye = 1;
            maxEye = 1;
            eyeWidth = screenWidth;
            break;

        case OutputMode::CARDBOARD_STEREO:
            minEye = 0;
            maxEye = 1;
            eyeWidth = screenWidth / 2;
            break;

        default:
            assert(false);
    }

    /*
     * Если Cardboard QR есть — рисуем оба глаза в framebuffer,
     * потом применяем Cardboard distortion.
     *
     * Если QR нет — рисуем сразу на экран двумя половинами.
     */
    if (outputMode == OutputMode::CARDBOARD_STEREO && gCardboardReady) {
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
    } else {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    CHECK_GL_ERROR("Framebuffer");

    glDisable(GL_DEPTH_TEST);

    /*
     * Камера находится внутри сферы.
     * CULL_FACE может отбрасывать внутреннюю сторону сферы и давать чёрный экран.
     */
    glDisable(GL_CULL_FACE);

    glDisable(GL_SCISSOR_TEST);
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    glClear(GL_COLOR_BUFFER_BIT);

    CHECK_GL_ERROR("Params");

    time_t now = time(nullptr);

    if (vrProgressBarShown) {
        if (now >= vrGuiProgressBarHideAt) {
            LOG_DEBUG("Hiding progress bar");
            vrProgressBarShown = false;
        } else {
            vrGuiProgressBar.setProgress(videoPosition);
        }
    }

    for (int eye = minEye; eye <= maxEye; ++eye) {
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, videoTexture);
        glUseProgram(programVideo);

        glViewport((eye - minEye) * eyeWidth, 0, eyeWidth, screenHeight);

        auto mvpMatrix = BuildMVPMatrix(eye);

        glUniformMatrix4fv(
                programVideoParamMVPMatrix,
                1,
                GL_FALSE,
                glm::value_ptr(mvpMatrix)
        );

        auto colorMapMatrix = BuildColorMapMatrix(eye);

        glUniformMatrix4fv(
                programVideoParamColorMapMatrix,
                1,
                GL_FALSE,
                glm::value_ptr(colorMapMatrix)
        );

        eyeMeshes[eye].Render(programVideoParamPosition, programVideoParamUV);

        CHECK_GL_ERROR("Render video");

        RenderTextMarks(mvpMatrix);
        CHECK_GL_ERROR("Render text marks");

        if (vrProgressBarShown) {
            glUseProgram(program2D);
            vrGuiProgressBar.render(program2DParamPosition);
            CHECK_GL_ERROR("Render progress bar");
        }

        if (vrGuiShown) {
            glUseProgram(programVRGui);
            glBindTexture(GL_TEXTURE_2D, buttonTexture);

            glm::mat4 guiMvpMatrix = glm::rotate(
                    mvpMatrix,
                    (float) M_PI - vrGuiCenterTheta,
                    Y_AXIS
            );

            glUniformMatrix4fv(
                    programVRGuiParamMVPMatrix,
                    1,
                    GL_FALSE,
                    glm::value_ptr(guiMvpMatrix)
            );

            for (const VRGuiButton &button: vrGuiButtons) {
                button.render(programVRGuiParamPosition, programVRGuiParamUV);
            }

            glUseProgram(program2D);
            RenderPointer();

            CHECK_GL_ERROR("Render GUI");
        }
    }

    /*
     * Cardboard distortion вызываем только если QR-параметры реально загружены.
     * Если QR нет, split-screen уже нарисован напрямую.
     */
    if (outputMode == OutputMode::CARDBOARD_STEREO && gCardboardReady) {
        CardboardDistortionRenderer_renderEyeToDisplay(
                cardboardDistortionRenderer.get(),
                0,
                0,
                0,
                screenWidth,
                screenHeight,
                &cardboardEyeTextureDescriptions[0],
                &cardboardEyeTextureDescriptions[1]
        );

        CHECK_GL_ERROR("Render cardboard");

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, screenWidth, screenHeight);
        glUseProgram(program2D);
        RenderCardboardAlignLine();

        CHECK_GL_ERROR("Align line");
    }

    ++frameCount;
}

void Renderer::RenderPointer() {
    glEnableVertexAttribArray(program2DParamPosition);
    glVertexAttribPointer(program2DParamPosition, 3, GL_FLOAT, GL_FALSE, 0, pointerCoords.data());

    glDrawElements(GL_POINTS, 1, GL_UNSIGNED_BYTE, trivial2DData);
}

void Renderer::RenderCardboardAlignLine() {
    glEnableVertexAttribArray(program2DParamPosition);
    glVertexAttribPointer(
            program2DParamPosition,
            3,
            GL_FLOAT,
            GL_FALSE,
            0,
            cardboardAlignLineCoords.data()
    );

    glDrawElements(GL_LINES, 2, GL_UNSIGNED_BYTE, trivial2DData);
}


static bool IsFinite3(float x, float y, float z) {
    return std::isfinite(x) && std::isfinite(y) && std::isfinite(z);
}

static glm::vec3 DirectionFromTextMarkPoint(float x,
                                            float y,
                                            float z,
                                            float yawDeg,
                                            float pitchDeg) {
    if (IsFinite3(x, y, z)) {
        glm::vec3 v(x, y, z);
        const float len = glm::length(v);
        if (len > 0.001f) {
            return glm::normalize(v);
        }
    }

    if (std::isfinite(yawDeg) && std::isfinite(pitchDeg)) {
        const float yawRad = glm::radians(yawDeg);
        const float pitchRad = glm::radians(pitchDeg);

        /*
         * Та же система координат, что и в серверном / index.html:
         * x = -r * sin(yaw) * cos(pitch)
         * y = -r * sin(pitch)
         * z = -r * cos(yaw) * cos(pitch)
         */
        glm::vec3 v(
                -std::sin(yawRad) * std::cos(pitchRad),
                -std::sin(pitchRad),
                -std::cos(yawRad) * std::cos(pitchRad)
        );
        return glm::normalize(v);
    }

    // Если сервер прислал x=0,y=0,z=0 без yaw/pitch — показываем прямо перед зрителем.
    return glm::vec3(0.0f, 0.0f, -1.0f);
}


static void TextMarkBasis(const glm::vec3 &direction,
                          glm::vec3 &dir,
                          glm::vec3 &right,
                          glm::vec3 &up) {
    dir = glm::normalize(direction);

    glm::vec3 worldUp(0.0f, 1.0f, 0.0f);
    if (std::fabs(glm::dot(worldUp, dir)) > 0.95f) {
        worldUp = glm::vec3(1.0f, 0.0f, 0.0f);
    }

    right = glm::normalize(glm::cross(worldUp, dir));
    up = glm::normalize(glm::cross(dir, right));
}

static void TextMarkSize(float markWidth,
                         float markHeight,
                         float &halfWidth,
                         float &halfHeight) {
    const float safeWidth = std::max(1.0f, markWidth);
    const float safeHeight = std::max(1.0f, markHeight);
    const float aspect = safeWidth / safeHeight;

    halfHeight = 0.16f;
    if (safeHeight > 160.0f) {
        halfHeight = 0.20f;
    }
    if (safeHeight > 260.0f) {
        halfHeight = 0.25f;
    }

    halfWidth = halfHeight * aspect;
    halfWidth = glm::clamp(halfWidth, 0.18f, 0.70f);
    halfHeight = glm::clamp(halfHeight, 0.10f, 0.32f);
}

static glm::vec3 TextMarkAnchor(const glm::vec3 &direction) {
    return glm::normalize(direction) * 0.82f;
}

static glm::vec3 TextMarkPanelCenter(const glm::vec3 &direction,
                                     float markWidth,
                                     float markHeight) {
    glm::vec3 dir;
    glm::vec3 right;
    glm::vec3 up;
    TextMarkBasis(direction, dir, right, up);

    float halfWidth;
    float halfHeight;
    TextMarkSize(markWidth, markHeight, halfWidth, halfHeight);

    /*
     * Сам текстовый блок не ставим прямо на точку.
     * Он сдвинут вправо по касательной, чтобы точка привязки была видна отдельно,
     * как на web-просмотрщике: точка -> указатель -> окно с текстом.
     */
    const float gap = 0.055f;
    return TextMarkAnchor(direction) + right * (halfWidth + gap);
}

static TexturedMesh BuildTextMarkMesh(float x,
                                      float y,
                                      float z,
                                      float yawDeg,
                                      float pitchDeg,
                                      float markWidth,
                                      float markHeight) {
    const glm::vec3 direction = DirectionFromTextMarkPoint(x, y, z, yawDeg, pitchDeg);

    glm::vec3 dir;
    glm::vec3 right;
    glm::vec3 up;
    TextMarkBasis(direction, dir, right, up);

    float halfWidth;
    float halfHeight;
    TextMarkSize(markWidth, markHeight, halfWidth, halfHeight);

    const glm::vec3 center = TextMarkPanelCenter(direction, markWidth, markHeight);

    const glm::vec3 p0 = center - right * halfWidth + up * halfHeight; // top-left
    const glm::vec3 p1 = center + right * halfWidth + up * halfHeight; // top-right
    const glm::vec3 p2 = center + right * halfWidth - up * halfHeight; // bottom-right
    const glm::vec3 p3 = center - right * halfWidth - up * halfHeight; // bottom-left

    std::unique_ptr<GLfloat[]> pos{new GLfloat[12]{
            p0.x, p0.y, p0.z,
            p1.x, p1.y, p1.z,
            p2.x, p2.y, p2.z,
            p3.x, p3.y, p3.z
    }};

    /*
     * Текстура создана из Android Bitmap. Верх Bitmap = v=0.
     * U развёрнут, потому что плоскость находится внутри сферы и видна с внутренней стороны.
     */
    std::unique_ptr<GLfloat[]> uv{new GLfloat[8]{
            1.0f, 0.0f,
            0.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 1.0f
    }};

    std::unique_ptr<GLushort[]> indices{new GLushort[6]{
            0, 2, 1,
            0, 3, 2
    }};

    return TexturedMesh(GL_TRIANGLES, 6, std::move(pos), std::move(uv), std::move(indices));
}

static TexturedMesh BuildTextMarkPointMesh(const glm::vec3 &direction) {
    glm::vec3 dir;
    glm::vec3 right;
    glm::vec3 up;
    TextMarkBasis(direction, dir, right, up);

    const glm::vec3 center = TextMarkAnchor(direction);

    /* Крупнее, чем раньше: на телефоне маленькая точка была почти незаметна. */
    const float half = 0.032f;

    const glm::vec3 p0 = center - right * half + up * half;
    const glm::vec3 p1 = center + right * half + up * half;
    const glm::vec3 p2 = center + right * half - up * half;
    const glm::vec3 p3 = center - right * half - up * half;

    std::unique_ptr<GLfloat[]> pos{new GLfloat[12]{
            p0.x, p0.y, p0.z,
            p1.x, p1.y, p1.z,
            p2.x, p2.y, p2.z,
            p3.x, p3.y, p3.z
    }};

    std::unique_ptr<GLfloat[]> uv{new GLfloat[8]{
            0.0f, 0.0f,
            1.0f, 0.0f,
            1.0f, 1.0f,
            0.0f, 1.0f
    }};

    std::unique_ptr<GLushort[]> indices{new GLushort[6]{
            0, 2, 1,
            0, 3, 2
    }};

    return TexturedMesh(GL_TRIANGLES, 6, std::move(pos), std::move(uv), std::move(indices));
}

static TexturedMesh BuildTextMarkConnectorMesh(const glm::vec3 &direction,
                                               float markWidth,
                                               float markHeight) {
    glm::vec3 dir;
    glm::vec3 right;
    glm::vec3 up;
    TextMarkBasis(direction, dir, right, up);

    float halfWidth;
    float halfHeight;
    TextMarkSize(markWidth, markHeight, halfWidth, halfHeight);

    const glm::vec3 anchor = TextMarkAnchor(direction);
    const glm::vec3 center = TextMarkPanelCenter(direction, markWidth, markHeight);

    /* Линия идёт от точки к левому краю окна. Сделана толще, чтобы явно связывать точку и текстовый блок. */
    const glm::vec3 end = center - right * halfWidth;
    const glm::vec3 line = end - anchor;

    if (glm::length(line) < 0.001f) {
        return TexturedMesh();
    }

    const glm::vec3 lineDir = glm::normalize(line);
    const glm::vec3 normal = glm::normalize(glm::cross(dir, lineDir));
    const float halfThickness = 0.014f;

    const glm::vec3 p0 = anchor + normal * halfThickness;
    const glm::vec3 p1 = end + normal * halfThickness;
    const glm::vec3 p2 = end - normal * halfThickness;
    const glm::vec3 p3 = anchor - normal * halfThickness;

    std::unique_ptr<GLfloat[]> pos{new GLfloat[12]{
            p0.x, p0.y, p0.z,
            p1.x, p1.y, p1.z,
            p2.x, p2.y, p2.z,
            p3.x, p3.y, p3.z
    }};

    std::unique_ptr<GLfloat[]> uv{new GLfloat[8]{
            0.0f, 0.0f,
            1.0f, 0.0f,
            1.0f, 1.0f,
            0.0f, 1.0f
    }};

    std::unique_ptr<GLushort[]> indices{new GLushort[6]{
            0, 2, 1,
            0, 3, 2
    }};

    return TexturedMesh(GL_TRIANGLES, 6, std::move(pos), std::move(uv), std::move(indices));
}

static glm::vec3 CurrentViewDirectionFromMatrix(const glm::mat4 &viewMatrix) {
    glm::vec4 v = viewMatrix * NEG_Z_AXIS;
    glm::vec3 dir(v.x, v.y, v.z);

    if (glm::length(dir) < 0.001f) {
        return glm::vec3(0.0f, 0.0f, -1.0f);
    }

    return glm::normalize(dir);
}

/*
 * Стрелка-подсказка направления.
 *
 * Она нужна, когда пользователь отвёл голову от точки:
 * сама метка уже вне поля зрения, но стрелка на краю текущего взгляда
 * показывает, в какую сторону надо повернуть голову, чтобы найти точку.
 */
static TexturedMesh BuildTextMarkDirectionIndicatorMesh(const glm::vec3 &targetDirection,
                                                        const glm::mat4 &viewMatrix,
                                                        float angleRad) {
    /*
     * Экранная навигационная стрелка внутри VR-вида.
     *
     * ВАЖНО:
     * Раньше стрелка строилась как обычный 3D-объект рядом с меткой.
     * Поэтому когда пользователь отворачивался, стрелка могла остаться в старом
     * месте и больше не вести себя как указатель направления.
     *
     * Теперь алгоритм другой:
     * 1) переводим направление на метку в текущие координаты камеры;
     * 2) по X/Y в координатах камеры понимаем, куда находится метка относительно экрана;
     * 3) строим стрелку в camera-space около края текущего взгляда;
     * 4) переводим вершины обратно через inverse(viewMatrix), чтобы после умножения
     *    на обычный MVP стрелка всегда оставалась в поле зрения и двигалась при повороте головы.
     */

    const glm::vec3 target = glm::normalize(targetDirection);

    glm::vec3 targetCam = glm::vec3(viewMatrix * glm::vec4(target, 0.0f));
    if (glm::length(targetCam) < 0.001f) {
        targetCam = glm::vec3(0.0f, 0.0f, -1.0f);
    }
    targetCam = glm::normalize(targetCam);

    /*
     * В camera-space пользователь смотрит примерно вдоль -Z.
     * X показывает вправо/влево, Y — вверх/вниз.
     */
    float sx = targetCam.x;
    float sy = targetCam.y;

    /*
     * Если метка почти строго за спиной, X/Y могут быть около нуля.
     * Тогда показываем разворот вправо как понятную подсказку.
     */
    if (std::fabs(sx) < 0.001f && std::fabs(sy) < 0.001f) {
        sx = 1.0f;
        sy = 0.0f;
    }

    glm::vec2 dir2 = glm::normalize(glm::vec2(sx, sy));

    const float startAngle = glm::radians(10.0f);
    const float endAngle = glm::radians(120.0f);
    const float t = glm::clamp((angleRad - startAngle) / (endAngle - startAngle), 0.0f, 1.0f);

    /*
     * Позиция стрелки в текущем экране.
     * Чем дальше точка от центра взгляда, тем ближе стрелка к краю.
     */
    const float screenRadius = 0.16f + 0.30f * t;
    glm::vec2 center2 = dir2 * screenRadius;

    /* Ограничиваем стрелку, чтобы она не вылезала за видимую область каждого глаза. */
    center2.x = glm::clamp(center2.x, -0.46f, 0.46f);
    center2.y = glm::clamp(center2.y, -0.34f, 0.34f);

    /*
     * Строим стрелку в camera-space на расстоянии перед камерой.
     * Потом переведём её в world-space через inverse(viewMatrix).
     */
    const float z = -0.72f;
    const glm::vec3 center(center2.x, center2.y, z);
    const glm::vec3 arrowDir(dir2.x, dir2.y, 0.0f);
    const glm::vec3 side(-dir2.y, dir2.x, 0.0f);

    /* Нормальная стрелка: хвост + широкий наконечник. */
    const float totalLength = 0.22f;
    const float shaftLength = 0.13f;
    const float shaftHalfWidth = 0.018f;
    const float headHalfWidth = 0.060f;

    const glm::vec3 tailCenter = center - arrowDir * (totalLength * 0.50f);
    const glm::vec3 shaftEndCenter = tailCenter + arrowDir * shaftLength;
    const glm::vec3 nose = center + arrowDir * (totalLength * 0.50f);

    glm::vec3 camVerts[7] = {
            tailCenter + side * shaftHalfWidth,
            shaftEndCenter + side * shaftHalfWidth,
            shaftEndCenter + side * headHalfWidth,
            nose,
            shaftEndCenter - side * headHalfWidth,
            shaftEndCenter - side * shaftHalfWidth,
            tailCenter - side * shaftHalfWidth
    };

    const glm::mat4 invView = glm::inverse(viewMatrix);
    glm::vec3 worldVerts[7];
    for (int i = 0; i < 7; ++i) {
        const glm::vec4 w = invView * glm::vec4(camVerts[i], 1.0f);
        worldVerts[i] = glm::vec3(w.x, w.y, w.z);
    }

    std::unique_ptr<GLfloat[]> pos{new GLfloat[21]{
            worldVerts[0].x, worldVerts[0].y, worldVerts[0].z,
            worldVerts[1].x, worldVerts[1].y, worldVerts[1].z,
            worldVerts[2].x, worldVerts[2].y, worldVerts[2].z,
            worldVerts[3].x, worldVerts[3].y, worldVerts[3].z,
            worldVerts[4].x, worldVerts[4].y, worldVerts[4].z,
            worldVerts[5].x, worldVerts[5].y, worldVerts[5].z,
            worldVerts[6].x, worldVerts[6].y, worldVerts[6].z
    }};

    std::unique_ptr<GLfloat[]> uv{new GLfloat[14]{
            0.0f, 0.5f,
            0.45f, 0.5f,
            0.55f, 0.0f,
            1.0f, 0.5f,
            0.55f, 1.0f,
            0.45f, 0.5f,
            0.0f, 0.5f
    }};

    std::unique_ptr<GLushort[]> indices{new GLushort[9]{
            0, 6, 1,
            1, 6, 5,
            2, 4, 3
    }};

    return TexturedMesh(GL_TRIANGLES, 9, std::move(pos), std::move(uv), std::move(indices));
}

void Renderer::RenderTextMarks(const glm::mat4 &mvpMatrix) {
    if (textMarks.empty()) {
        return;
    }

    const uint64_t nowMs = GetBootTimeNano() / 1000000ULL;
    const glm::vec3 currentForward = CurrentViewDirectionFromMatrix(viewMatrix);

    glUseProgram(programVRGui);
    glUniformMatrix4fv(
            programVRGuiParamMVPMatrix,
            1,
            GL_FALSE,
            glm::value_ptr(mvpMatrix)
    );

    for (TextMark3D &mark: textMarks) {
        if (!mark.visible) {
            continue;
        }

        if (mark.hideAtMs > 0 && nowMs >= mark.hideAtMs) {
            mark.visible = false;
            continue;
        }

        /*
         * 1) Рисуем указатель-точку и линию к окну.
         *    Линия/точка рисуются ДО текста, чтобы окно оставалось сверху,
         *    но сама точка больше не перекрывается окном, потому что окно сдвинуто вправо.
         */
        if (textMarkIndicatorTexture != 0) {
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, textMarkIndicatorTexture);
            mark.connectorMesh.Render(programVRGuiParamPosition, programVRGuiParamUV);
            mark.pointMesh.Render(programVRGuiParamPosition, programVRGuiParamUV);
        }

        /*
         * 2) Рисуем сам текст внутри сферы.
         */
        if (mark.texture != 0) {
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, mark.texture);
            mark.mesh.Render(programVRGuiParamPosition, programVRGuiParamUV);
        }

        /*
         * 3) Стрелка направления.
         *
         * Она появляется не только когда метка полностью пропала, а уже когда
         * пользователь заметно отвернулся от точки. Чем дальше метка от центра
         * взгляда, тем ближе стрелка к краю поля зрения.
         */
        const glm::vec3 markDir = glm::normalize(mark.direction);
        const float visibleDot = glm::clamp(glm::dot(currentForward, markDir), -1.0f, 1.0f);
        const float angleToMark = std::acos(visibleDot);

        if (angleToMark > glm::radians(12.0f) && textMarkIndicatorTexture != 0) {
            TexturedMesh indicatorMesh =
                    BuildTextMarkDirectionIndicatorMesh(markDir, viewMatrix, angleToMark);

            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, textMarkIndicatorTexture);
            indicatorMesh.Render(programVRGuiParamPosition, programVRGuiParamUV);
        }
    }
}

void Renderer::ShowTextMarkFromPixels(JNIEnv *env,
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
                                      int durationMs) {
    if (pixelsArgb == nullptr || bitmapWidth <= 0 || bitmapHeight <= 0) {
        LOG_ERROR("ShowTextMarkFromPixels: bad bitmap");
        return;
    }

    const jsize arrayLength = env->GetArrayLength(pixelsArgb);
    const int expectedLength = bitmapWidth * bitmapHeight;
    if (arrayLength < expectedLength) {
        LOG_ERROR("ShowTextMarkFromPixels: pixels array too small");
        return;
    }

    jint *src = env->GetIntArrayElements(pixelsArgb, nullptr);
    if (src == nullptr) {
        LOG_ERROR("ShowTextMarkFromPixels: GetIntArrayElements failed");
        return;
    }

    std::vector<unsigned char> rgba(static_cast<size_t>(expectedLength) * 4u);
    for (int i = 0; i < expectedLength; ++i) {
        const uint32_t c = static_cast<uint32_t>(src[i]); // Android ARGB: 0xAARRGGBB
        rgba[i * 4 + 0] = static_cast<unsigned char>((c >> 16) & 0xFF); // R
        rgba[i * 4 + 1] = static_cast<unsigned char>((c >> 8) & 0xFF);  // G
        rgba[i * 4 + 2] = static_cast<unsigned char>(c & 0xFF);         // B
        rgba[i * 4 + 3] = static_cast<unsigned char>((c >> 24) & 0xFF); // A
    }

    env->ReleaseIntArrayElements(pixelsArgb, src, JNI_ABORT);

    const std::string safeId = markId.empty()
                               ? std::string("mark_") + std::to_string(GetBootTimeNano())
                               : markId;

    TextMark3D *target = nullptr;
    for (TextMark3D &mark: textMarks) {
        if (mark.id == safeId) {
            target = &mark;
            break;
        }
    }

    if (target == nullptr) {
        textMarks.emplace_back();
        target = &textMarks.back();
        target->id = safeId;
    }

    if (target->texture == 0) {
        glGenTextures(1, &target->texture);
    }

    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, target->texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glTexImage2D(
            GL_TEXTURE_2D,
            0,
            GL_RGBA,
            bitmapWidth,
            bitmapHeight,
            0,
            GL_RGBA,
            GL_UNSIGNED_BYTE,
            rgba.data()
    );

    target->direction = DirectionFromTextMarkPoint(x, y, z, yawDeg, pitchDeg);
    target->mesh = BuildTextMarkMesh(x, y, z, yawDeg, pitchDeg, markWidth, markHeight);
    target->pointMesh = BuildTextMarkPointMesh(target->direction);
    target->connectorMesh = BuildTextMarkConnectorMesh(target->direction, markWidth, markHeight);
    target->visible = true;

    const uint64_t nowMs = GetBootTimeNano() / 1000000ULL;
    const int safeDuration = durationMs <= 0 ? 5000 : durationMs;
    target->hideAtMs = nowMs + static_cast<uint64_t>(safeDuration);

    LOG_DEBUG(
            "ShowTextMarkFromPixels: id=%s %dx%d point=(%.3f,%.3f,%.3f) yaw=%.2f pitch=%.2f duration=%d total=%zu",
            safeId.c_str(),
            bitmapWidth,
            bitmapHeight,
            x,
            y,
            z,
            yawDeg,
            pitchDeg,
            safeDuration,
            textMarks.size()
    );

    CHECK_GL_ERROR("ShowTextMarkFromPixels");
}

void Renderer::ClearTextMarks() {
    for (TextMark3D &mark: textMarks) {
        mark.visible = false;
        mark.hideAtMs = 0;
    }
    LOG_DEBUG("ClearTextMarks all");
}

bool Renderer::UpdateDeviceParams() {
    if (!screenParamsChanged && !deviceParamsChanged) {
        return true;
    }

    gCardboardReady = false;

    if (outputMode == OutputMode::CARDBOARD_STEREO) {
        uint8_t *cardboardQrCode = nullptr;
        int size = 0;
        bool mustDestroyQrCode = false;

        /*
         * Сначала пробуем загрузить QR-профиль очков, который пользователь
         * сканировал через пункт меню Switch Cardboard viewer.
         */
        CardboardQrCode_getSavedDeviceParams(&cardboardQrCode, &size);

        if (size > 0 && cardboardQrCode != nullptr) {
            mustDestroyQrCode = true;
            LOG_DEBUG("Cardboard saved params loaded, size=%d", size);
        } else {
            /*
             * Если QR-профиля нет, раньше включался простой split-screen без
             * линзовой формы. Из-за этого 360° выглядело как два прямоугольника.
             *
             * Теперь используем встроенный профиль Cardboard V1. Он даёт
             * нормальную Cardboard-distortion/маску под каждый глаз сразу после
             * установки приложения, даже без сканирования QR-кода.
             *
             * ВАЖНО: по документации CardboardQrCode_getCardboardV1DeviceParams
             * возвращает указатель, который НЕ нужно освобождать через destroy.
             */
            LOG_ERROR("Cardboard saved params not available. Using built-in Cardboard V1 params.");
            CardboardQrCode_getCardboardV1DeviceParams(&cardboardQrCode, &size);
            mustDestroyQrCode = false;
        }

        if (size > 0 && cardboardQrCode != nullptr) {
            LOG_DEBUG("Cardboard params ready, size=%d, destroy=%d", size, mustDestroyQrCode ? 1 : 0);

            cardboardLensDistortion = CardboardLensDistortionPointer(
                    CardboardLensDistortion_create(
                            cardboardQrCode,
                            size,
                            screenWidth,
                            screenHeight
                    )
            );

            if (mustDestroyQrCode) {
                CardboardQrCode_destroy(cardboardQrCode);
            }

            gCardboardReady = true;
        } else {
            LOG_ERROR("Cardboard params unavailable even after V1 fallback. Using simple stereo fallback.");
            gCardboardReady = false;
        }
    }

    GlSetup();

    if (outputMode == OutputMode::CARDBOARD_STEREO && gCardboardReady) {
        const CardboardOpenGlEsDistortionRendererConfig config{kGlTexture2D};

        cardboardDistortionRenderer = CardboardDistortionRendererPointer(
                CardboardOpenGlEs2DistortionRenderer_create(&config)
        );

        CardboardMesh leftMesh;
        CardboardMesh rightMesh;

        CardboardLensDistortion_getDistortionMesh(
                cardboardLensDistortion.get(),
                kLeft,
                &leftMesh
        );

        CardboardLensDistortion_getDistortionMesh(
                cardboardLensDistortion.get(),
                kRight,
                &rightMesh
        );

        CardboardDistortionRenderer_setMesh(
                cardboardDistortionRenderer.get(),
                &leftMesh,
                kLeft
        );

        CardboardDistortionRenderer_setMesh(
                cardboardDistortionRenderer.get(),
                &rightMesh,
                kRight
        );

        CardboardLensDistortion_getEyeFromHeadMatrix(
                cardboardLensDistortion.get(),
                kLeft,
                glm::value_ptr(cardboardEyeMatrices[0])
        );

        CardboardLensDistortion_getEyeFromHeadMatrix(
                cardboardLensDistortion.get(),
                kRight,
                glm::value_ptr(cardboardEyeMatrices[1])
        );

        CardboardLensDistortion_getProjectionMatrix(
                cardboardLensDistortion.get(),
                kLeft,
                kzNear,
                kzFar,
                glm::value_ptr(cardboardProjectionMatrices[0])
        );

        CardboardLensDistortion_getProjectionMatrix(
                cardboardLensDistortion.get(),
                kRight,
                kzNear,
                kzFar,
                glm::value_ptr(cardboardProjectionMatrices[1])
        );
    }

    if (outputMode == OutputMode::CARDBOARD_STEREO && !gCardboardReady) {
        /*
         * Fallback без Cardboard QR:
         * два глаза, две половины экрана, без distortion.
         */
        cardboardEyeMatrices[0] = glm::mat4(1.0f);
        cardboardEyeMatrices[1] = glm::mat4(1.0f);

        const float fallbackAspect = screenAspect * 0.5f;

        cardboardProjectionMatrices[0] = glm::perspective(
                glm::radians(useLookAtControl ? controlFov : 90.0f),
                fallbackAspect,
                kzNear,
                kzFar
        );

        cardboardProjectionMatrices[1] = glm::perspective(
                glm::radians(useLookAtControl ? controlFov : 90.0f),
                fallbackAspect,
                kzNear,
                kzFar
        );
    }

    screenParamsChanged = false;
    deviceParamsChanged = false;

    CHECK_GL_ERROR("UpdateDeviceParams");

    return true;
}

void Renderer::GlSetup() {
    LOG_DEBUG("GLSetup");

    if (glInitialized) {
        GlTeardown();
    }

    glInitialized = true;

    glGenTextures(1, &renderTexture);
    glBindTexture(GL_TEXTURE_2D, renderTexture);

    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    glTexImage2D(
            GL_TEXTURE_2D,
            0,
            GL_RGB,
            screenWidth,
            screenHeight,
            0,
            GL_RGB,
            GL_UNSIGNED_BYTE,
            nullptr
    );

    glGenFramebuffers(1, &framebuffer);
    glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, renderTexture, 0);

    CHECK_GL_ERROR("Create render buffer");

    cardboardEyeTextureDescriptions[0] = {
            .texture = renderTexture,
            .left_u = 0.0f,
            .right_u = 0.5f,
            .top_v = 1.0f,
            .bottom_v = 0.0f
    };

    cardboardEyeTextureDescriptions[1] = {
            .texture = renderTexture,
            .left_u = 0.5f,
            .right_u = 1.0f,
            .top_v = 1.0f,
            .bottom_v = 0.0f
    };

    CHECK_GL_ERROR("GlSetup");
}

void Renderer::GlTeardown() {
    if (!glInitialized) {
        return;
    }

    glInitialized = false;

    glDeleteFramebuffers(1, &framebuffer);
    framebuffer = 0;

    glDeleteTextures(1, &renderTexture);
    renderTexture = 0;

    CHECK_GL_ERROR("GlTeardown");
}

float Renderer::NormalizeAngle(float value) {
    while (value > glm::pi<float>()) {
        value -= glm::two_pi<float>();
    }

    while (value < -glm::pi<float>()) {
        value += glm::two_pi<float>();
    }

    return value;
}

float Renderer::ShortestAngleDelta(float from, float to) {
    return NormalizeAngle(to - from);
}

float Renderer::SmoothStep(float t) {
    t = glm::clamp(t, 0.0f, 1.0f);
    return t * t * (3.0f - 2.0f * t);
}

void Renderer::UpdateLookAtAnimation() {
    if (!useLookAtControl) {
        return;
    }

    const uint64_t nowMs = GetBootTimeNano() / 1000000ULL;

    /*
     * Стадия 2: анимация уже завершена, но точка ещё удерживается.
     * Это нужно, чтобы поворот был видимым и не сбрасывался мгновенно.
     */
    if (!lookAtAnimating && gLookAtHolding) {
        const uint64_t holdElapsed =
                nowMs > gLookAtHoldStartMs ? nowMs - gLookAtHoldStartMs : 0;

        if (holdElapsed >= kLookAtHoldMs) {
            gLookAtHolding = false;

            /*
             * Сбрасываем серверную базовую матрицу.
             * После этого движение головой снова идёт без учёта последней точки сервера.
             */
            useLookAtControl = false;
            controlFov = 90.0f;

            LOG_DEBUG("LookAtPoint hold finished: reset server base rotation");
        }

        return;
    }

    if (!lookAtAnimating) {
        return;
    }

    /*
     * Стадия 1: плавный поворот к targetYaw/targetPitch/targetFov.
     */
    const uint64_t elapsedMs =
            nowMs > lookAtStartMs ? nowMs - lookAtStartMs : 0;

    float t = static_cast<float>(elapsedMs) / static_cast<float>(lookAtDurationMs);
    t = SmoothStep(t);

    const float yawDelta = ShortestAngleDelta(startYaw, targetYaw);

    controlYaw = NormalizeAngle(startYaw + yawDelta * t);
    controlPitch = startPitch + (targetPitch - startPitch) * t;
    controlFov = startFov + (targetFov - startFov) * t;

    if (elapsedMs >= lookAtDurationMs) {
        controlYaw = NormalizeAngle(targetYaw);
        controlPitch = targetPitch;
        controlFov = targetFov;
        lookAtAnimating = false;

        gLookAtHolding = true;
        gLookAtHoldStartMs = nowMs;

        LOG_DEBUG("LookAtPoint animation finished: start hold");
    }
}

glm::mat4 Renderer::BuildControlledViewMatrix() const {
    /*
     * Если серверный yaw будет зеркалить,
     * поменяй -controlYaw на controlYaw.
     */
    glm::mat4 yawMatrix = glm::rotate(
            glm::mat4(1.0f),
            -controlYaw,
            glm::vec3(0.0f, 1.0f, 0.0f)
    );

    /*
     * Если серверный pitch будет зеркалить,
     * поменяй -controlPitch на controlPitch.
     */
    glm::mat4 pitchMatrix = glm::rotate(
            glm::mat4(1.0f),
            -controlPitch,
            glm::vec3(1.0f, 0.0f, 0.0f)
    );

    return pitchMatrix * yawMatrix;
}

glm::mat4 Renderer::BuildMVPMatrix(int eye) {
    if (inputVideoMode == InputVideoMode::PLAIN_FOV && isOutputModeMono(outputMode)) {
        const float xScale = screenAspect > 1.0f ? 1.0f : screenAspect;
        const float yScale = screenAspect > 1.0f ? screenAspect : 1.0f;

        return glm::diagonal4x4(glm::vec4(
                xScale,
                yScale,
                1.0f,
                1.0f
        ));
    }

    glm::mat4 projection;
    glm::mat4 view;

    switch (outputMode) {
        case OutputMode::MONO_LEFT:
        case OutputMode::MONO_RIGHT:
            projection = glm::perspective(
                    glm::radians(useLookAtControl ? controlFov : 90.0f) / screenAspect,
                    screenAspect,
                    kzNear,
                    kzFar
            );

            if (useLookAtControl) {
                /*
                 * Сервер задаёт базовое направление,
                 * живая голова работает поверх него.
                 */
                view = BuildControlledViewMatrix() * viewMatrix;
            } else {
                view = viewMatrix;
            }

            break;

        case OutputMode::CARDBOARD_STEREO:
            projection = cardboardProjectionMatrices[eye];

            if (useLookAtControl) {
                view = cardboardEyeMatrices[eye] * BuildControlledViewMatrix() * viewMatrix;
            } else {
                view = cardboardEyeMatrices[eye] * viewMatrix;
            }

            break;

        default:
            assert(false);
    }

    return projection * view;
}

glm::mat4 Renderer::BuildColorMapMatrix(int eye) {
    if (inputVideoLayout != InputVideoLayout::ANAGLYPH_RED_CYAN) {
        return glm::mat4(1.0f);
    }

    assert(eye >= 0 && eye <= 1);

    switch (eye) {
        case 0:
            return {
                    1.0f, 1.0f, 1.0f, 0.0f,
                    0.0f, 0.0f, 0.0f, 0.0f,
                    0.0f, 0.0f, 0.0f, 0.0f,
                    0.0f, 0.0f, 0.0f, 1.0f,
            };

        case 1:
            return {
                    0.0f, 0.0f, 0.0f, 0.0f,
                    0.0f, 1.0f, 0.0f, 0.0f,
                    0.0f, 0.0f, 1.0f, 0.0f,
                    0.0f, 0.0f, 0.0f, 1.0f,
            };

        default:
            std::abort();
    }
}

void Renderer::SetOptions(InputVideoLayout requestedInputLayout,
                          InputVideoMode requestedInputMode,
                          OutputMode requestedOutputMode) {
    LOG_DEBUG(
            "SetOptions(%d, %d, %d)",
            requestedInputLayout,
            requestedInputMode,
            requestedOutputMode
    );

    deviceParamsChanged |= requestedOutputMode != this->outputMode;

    this->inputVideoLayout = requestedInputLayout;
    this->inputVideoMode = requestedInputMode;
    this->outputMode = requestedOutputMode;

    ComputeMesh();
}

void Renderer::ScanCardboardQr() {
    LOG_DEBUG("ScanCardboardQr");
    CardboardQrCode_scanQrCodeAndSaveDeviceParams();
}

void Renderer::ShowProgressBar() {
    LOG_DEBUG("ShowProgressBar");
    vrProgressBarShown = true;
    vrGuiProgressBarHideAt = time(nullptr) + PROGRESS_BAR_SHOW_TIME;
}

static TexturedMesh BuildUvSphereMesh(int n_slices,
                                      int n_stacks,
                                      float minTheta,
                                      float maxTheta,
                                      float uvLeft,
                                      float uvTop,
                                      float uvRight,
                                      float uvBottom) {
    TexturedMesh::Builder meshBuilder;

    float uvWidth = uvRight - uvLeft;
    float uvHeight = uvBottom - uvTop;
    float thetaRange = maxTheta - minTheta;

    for (int i = 0; i <= n_stacks; i++) {
        auto vFrac = float(i) / float(n_stacks);
        auto phi = float(M_PI) * vFrac;
        auto v = vFrac * uvHeight + uvTop;

        for (int j = 0; j <= n_slices; j++) {
            auto uFrac = float(j) / float(n_slices);
            auto theta = -(minTheta + thetaRange * uFrac);
            auto u = uFrac * uvWidth + uvLeft;

            auto x = std::sinf(phi) * std::sinf(theta);
            auto y = std::cosf(phi);
            auto z = std::sinf(phi) * std::cosf(theta);

            if ((i == 0) || (i == n_stacks)) {
                u += 1.0f / float(n_slices);
                if (u > 1.0f) {
                    u -= 1.0f;
                }
            }

            meshBuilder.add_vertex(x, y, z, u, v);
        }
    }

    for (int j = 0; j < n_stacks; j++) {
        auto j0 = j * (n_slices + 1);
        auto j1 = (j + 1) * (n_slices + 1);

        for (int i = 0; i < n_slices; i++) {
            auto i0 = j0 + i;
            auto i1 = j0 + (i + 1);
            auto i2 = j1 + (i + 1);
            auto i3 = j1 + i;

            if (j == 0) {
                meshBuilder.add_triangle(i0, i2, i3);
            } else if (j == (n_stacks - 1)) {
                meshBuilder.add_triangle(i0, i2, i1);
            } else {
                meshBuilder.add_quad(i0, i1, i2, i3);
            }
        }
    }

    return meshBuilder.build();
}

static TexturedMesh BuildCylindricalMesh(int n_slices,
                                         float minTheta,
                                         float maxTheta,
                                         float uvLeft,
                                         float uvTop,
                                         float uvRight,
                                         float uvBottom) {
    TexturedMesh::Builder meshBuilder;

    float uvWidth = uvRight - uvLeft;
    float thetaRange = maxTheta - minTheta;

    for (int i = 0; i <= n_slices; i++) {
        auto uFrac = float(i) / float(n_slices);
        auto theta = -(minTheta + thetaRange * uFrac);
        auto u = uFrac * uvWidth + uvLeft;

        auto x = std::sinf(theta);
        auto z = std::cosf(theta);

        auto i1 = meshBuilder.add_vertex(x, 1, z, u, uvTop);
        auto i2 = meshBuilder.add_vertex(x, -1, z, u, uvBottom);

        if (i > 0) {
            meshBuilder.add_quad(i1 - 2, i1, i2, i2 - 2);
        }
    }

    return meshBuilder.build();
}

void Renderer::ComputeMesh() {
    for (int eye = 0; eye < 2; ++eye) {
        float uvLeft, uvTop, uvRight, uvBottom;

        switch (inputVideoLayout) {
            case InputVideoLayout::MONO:
            case InputVideoLayout::ANAGLYPH_RED_CYAN:
                uvLeft = 0.0f;
                uvRight = 1.0f;
                uvTop = 0.0f;
                uvBottom = 1.0f;
                break;

            case InputVideoLayout::STEREO_HORIZ:
                uvLeft = 0.5f * static_cast<float>(eye);
                uvRight = 0.5f * static_cast<float>(eye + 1);
                uvTop = 0.0f;
                uvBottom = 1.0f;
                break;

            case InputVideoLayout::STEREO_VERT:
                uvLeft = 0.0f;
                uvRight = 1.0f;
                uvTop = 0.5f * static_cast<float>(eye);
                uvBottom = 0.5f * static_cast<float>(eye + 1);
                break;

            default:
                assert(false);
        }

        switch (inputVideoMode) {
            case InputVideoMode::PLAIN_FOV: {
                const float xScale = videoAspect > 1.0f ? 1.0f : (1.0f / videoAspect);
                const float yScale = videoAspect > 1.0f ? (1.0f / videoAspect) : 1.0f;

                std::unique_ptr<GLfloat[]> pos{new GLfloat[12]{
                        -xScale, +yScale, PLAIN_FOV_Z,
                        +xScale, +yScale, PLAIN_FOV_Z,
                        +xScale, -yScale, PLAIN_FOV_Z,
                        -xScale, -yScale, PLAIN_FOV_Z
                }};

                std::unique_ptr<GLfloat[]> uv{new GLfloat[8]{
                        uvLeft, uvTop,
                        uvRight, uvTop,
                        uvRight, uvBottom,
                        uvLeft, uvBottom
                }};

                std::unique_ptr<GLushort[]> indices{new GLushort[6]{
                        0, 2, 1,
                        0, 3, 2
                }};

                eyeMeshes[eye] =
                        TexturedMesh(
                                GL_TRIANGLES,
                                6,
                                std::move(pos),
                                std::move(uv),
                                std::move(indices)
                        );

                break;
            }

            case InputVideoMode::EQUIRECT_180:
                eyeMeshes[eye] = BuildUvSphereMesh(
                        20,
                        20,
                        M_PI_2,
                        M_PI * 1.5f,
                        uvLeft,
                        uvTop,
                        uvRight,
                        uvBottom
                );
                break;

            case InputVideoMode::EQUIRECT_360:
                eyeMeshes[eye] = BuildUvSphereMesh(
                        40,
                        20,
                        0,
                        M_PI * 2.0f,
                        uvLeft,
                        uvTop,
                        uvRight,
                        uvBottom
                );
                break;

            case InputVideoMode::PANORAMA_180:
                eyeMeshes[eye] = BuildCylindricalMesh(
                        20,
                        M_PI_2,
                        M_PI * 1.5f,
                        uvLeft,
                        uvTop,
                        uvRight,
                        uvBottom
                );
                break;

            case InputVideoMode::PANORAMA_360:
                eyeMeshes[eye] = BuildCylindricalMesh(
                        40,
                        0,
                        M_PI * 2.0f,
                        uvLeft,
                        uvTop,
                        uvRight,
                        uvBottom
                );
                break;

            default: {
                std::unique_ptr<GLfloat[]> pos{new GLfloat[]{
                        -1.0, -1.0, -1.0,
                        +1.0, -1.0, -1.0,
                        +1.0, +1.0, -1.0,
                        -1.0, +1.0, -1.0,
                        -1.0, -1.0, +1.0,
                        +1.0, -1.0, +1.0,
                        +1.0, +1.0, +1.0,
                        -1.0, +1.0, +1.0
                }};

                std::unique_ptr<GLfloat[]> uv{new GLfloat[]{
                        0.0f, 0.0f,
                        1.0f, 0.0f,
                        1.0f, 1.0f,
                        0.0f, 1.0f,
                        0.0f, 0.0f,
                        1.0f, 0.0f,
                        1.0f, 1.0f,
                        0.0f, 1.0f,
                }};

                std::unique_ptr<GLushort[]> indices{new GLushort[]{
                        0, 5, 4, 0, 1, 5,
                        1, 6, 5, 1, 2, 6,
                        2, 7, 6, 2, 3, 7,
                        3, 4, 7, 3, 0, 4,
                        4, 6, 7, 4, 5, 6,
                        3, 1, 0, 3, 2, 1,
                }};

                eyeMeshes[eye] =
                        TexturedMesh(
                                GL_TRIANGLES,
                                36,
                                std::move(pos),
                                std::move(uv),
                                std::move(indices)
                        );
                break;
            }
        }
    }
}

void Renderer::UpdatePose(JNIEnv *env) {
    /*
     * ВАЖНО:
     * Для любого режима двух глаз используем CardboardHeadTracker.
     *
     * gCardboardReady отвечает только за distortion / коррекцию линз.
     * Если QR нет, DrawFrame рисует простой split-screen,
     * но поворот головы всё равно должен идти через CardboardHeadTracker.
     */
    const bool useCardboardTracker =
            outputMode == OutputMode::CARDBOARD_STEREO;

    if (useCardboardTracker) {
        glm::quat headOrientationQuat;
        glm::vec3 headPosition;

        CardboardHeadTracker_getPose(
                cardboardHeadTracker.get(),
                static_cast<int64_t>(GetBootTimeNano() + kPredictionTimeWithoutVsyncNanos),
                kLandscapeLeft,
                glm::value_ptr(headPosition),
                glm::value_ptr(headOrientationQuat)
        );

        viewMatrix = glm::toMat4(headOrientationQuat);

        const glm::vec4 pointVector = viewMatrix * NEG_Z_AXIS;

        pitch = asinf(pointVector.y);

        if (pitch > 1.55f) {
            /*
             * Слишком вертикально — оставляем предыдущий yaw.
             */
        } else {
            yaw = -atan2f(pointVector.x, pointVector.z);
        }

        if (isHeadGesturingUp) {
            if (pitch < HEAD_GESTURE_PITCH_LIMIT_RETURN) {
                isHeadGesturingUp = false;
                LOG_DEBUG("UpdatePose: Moved from up to not-up");
            }
        } else {
            if (pitch > HEAD_GESTURE_PITCH_LIMIT) {
                isHeadGesturingUp = true;
                vrGuiShown = !vrGuiShown;
                vrGuiCenterTheta = yaw;
                LOG_DEBUG("UpdatePose: Moved from not-up to up");
            }
        }

        if (vrGuiShown) {
            for (VRGuiButton &button: vrGuiButtons) {
                ButtonAction hitAction = button.evaluatePossibleHit(
                        M_PI + yaw - vrGuiCenterTheta,
                        pitch
                );

                if (hitAction != ButtonAction::NONE) {
                    this->ExecuteButtonAction(hitAction, env);
                }
            }
        }

        return;
    }

    /*
     * Android SensorManager fallback оставляем только для MonoLeft / MonoRight.
     * Для двух глаз он больше не используется.
     */
    if (useManualRotation) {
        glm::mat4 yawMatrix = glm::rotate(
                glm::mat4(1.0f),
                manualYaw,
                glm::vec3(0.0f, 1.0f, 0.0f)
        );

        glm::mat4 pitchMatrix = glm::rotate(
                glm::mat4(1.0f),
                manualPitch,
                glm::vec3(1.0f, 0.0f, 0.0f)
        );

        glm::mat4 rollMatrix = glm::mat4(1.0f);

        viewMatrix = rollMatrix * pitchMatrix * yawMatrix;

        const glm::vec4 pointVector = viewMatrix * NEG_Z_AXIS;

        pitch = asinf(pointVector.y);

        if (pitch <= 1.55f) {
            yaw = -atan2f(pointVector.x, pointVector.z);
        }

        return;
    }

    viewMatrix = glm::mat4(1.0f);
    yaw = 0.0f;
    pitch = 0.0f;
}

void Renderer::OnVideoSizeChanged(int width, int height) {
    videoWidth = width;
    videoHeight = height;
    videoAspect = (float) videoWidth / (float) videoHeight;
    screenParamsChanged = true;
}

void Renderer::ExecuteButtonAction(const ButtonAction action, JNIEnv *env) {
    switch (action) {
        case ButtonAction::NONE:
            break;

        case ButtonAction::RECENTER_YAW:
            CardboardHeadTracker_recenter(cardboardHeadTracker.get());
            break;

        case ButtonAction::RECENTER_2D:
            break;

        default:
            javaInterface.ExecuteButtonAction(env, action);
            break;
    }
}