#pragma once

#include <string>
#include <vector>

#include "MathUtils.h"
#include "TexturedMesh.h"

enum class InputVideoLayout {
    Mono = 1,
    StereoHorizontal = 2,
    StereoVertical = 3,
    AnaglyphRedCyan = 4,
};

enum class InputVideoMode {
    PlainFov = 1,
    Equirect180 = 2,
    Equirect360 = 3,
    CubeMap = 4,
    EquiangularCubeMap = 5,
    Pyramid = 6,
    Panorama180 = 7,
    Panorama360 = 8,
    DualFisheye180 = 9,
};

class SceneRenderer {
public:
    SceneRenderer();
    ~SceneRenderer();

    void initialize();
    void shutdown();
    void resize(int width, int height);
    void updateTime(float seconds);
    void render();
    void renderEye(const math::Mat4& projection, const math::Mat4& eyeView, int width, int height);

    void setVideoMode(InputVideoLayout layout, InputVideoMode mode, int outputMode);
    void setLookAt(float yawDeg, float pitchDeg, float fovDeg, int durationMs);
    void setHeadPose(float yawRadians, float pitchRadians);
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
    GLuint videoTextureId() const;

private:
    void buildMeshes();
    void ensureVideoTexture();
    void renderTextMarkOverlay(
        const math::Mat4& projection,
        const math::Mat4& view,
        int width,
        int height
    );
    static float normalizeAngle(float value);
    static float shortestAngleDelta(float from, float to);
    static float smoothStep(float t);

    GLuint program_ = 0;
    GLint positionLocation_ = -1;
    GLint uvLocation_ = -1;
    GLint mvpLocation_ = -1;
    GLint textureLocation_ = -1;

    GLuint textMarkProgram_ = 0;
    GLint textMarkPositionLocation_ = -1;
    GLint textMarkUvLocation_ = -1;
    GLint textMarkMvpLocation_ = -1;
    GLint textMarkTextureLocation_ = -1;
    GLint textMarkAlphaLocation_ = -1;

    GLuint colorProgram_ = 0;
    GLint colorPositionLocation_ = -1;
    GLint colorMvpLocation_ = -1;
    GLint colorValueLocation_ = -1;

    GLuint videoTexture_ = 0;
    bool initialized_ = false;
    int width_ = 1;
    int height_ = 1;
    float timeSeconds_ = 0.0f;

    InputVideoLayout inputLayout_ = InputVideoLayout::Mono;
    InputVideoMode inputMode_ = InputVideoMode::Equirect360;
    float videoAspect_ = 1.0f;
    int outputMode_ = 3;

    float currentYawRadians_ = 0.0f;
    float currentPitchRadians_ = 0.0f;
    float headYawRadians_ = 0.0f;
    float headPitchRadians_ = 0.0f;
    float startYawRadians_ = 0.0f;
    float startPitchRadians_ = 0.0f;
    float targetYawRadians_ = 0.0f;
    float targetPitchRadians_ = 0.0f;
    float animationStartSeconds_ = 0.0f;
    float animationDurationSeconds_ = 0.0f;
    bool animatingLookAt_ = false;

    TexturedMesh meshes_[2];

    struct TextMarkState {
        std::string id{};
        GLuint texture = 0;
        int bitmapWidth = 0;
        int bitmapHeight = 0;
        float direction[3] = {0.0f, 0.0f, -1.0f};
        float expiresAtSeconds = 0.0f;
        bool visible = false;
    };

    std::vector<TextMarkState> textMarks_{};
};
