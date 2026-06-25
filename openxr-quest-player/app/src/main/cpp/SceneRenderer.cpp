#include "SceneRenderer.h"

#include "GlUtils.h"
#include "MathUtils.h"

#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>
#include <android/log.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <memory>
#include <string>
#include <vector>

namespace {

constexpr float kPi = 3.14159265358979323846f;
constexpr float kHalfPi = kPi * 0.5f;
constexpr float kTwoPi = kPi * 2.0f;
constexpr float kPlainFovZ = -1.0f;
constexpr float kUvInset = 0.001f;

constexpr const char* kVertexShader = R"glsl(
#version 300 es
uniform mat4 uMvp;
in vec4 aPosition;
in vec2 aUv;
out vec2 vUv;

void main() {
    vUv = aUv;
    gl_Position = uMvp * aPosition;
}
)glsl";

constexpr const char* kVertexShaderEs2 = R"glsl(
attribute vec4 aPosition;
attribute vec2 aUv;
uniform mat4 uMvp;
varying vec2 vUv;

void main() {
    vUv = aUv;
    gl_Position = uMvp * aPosition;
}
)glsl";

constexpr const char* kFragmentShader = R"glsl(
#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
uniform samplerExternalOES uTexture;
in vec2 vUv;
out vec4 fragColor;

void main() {
    fragColor = texture(uTexture, vUv);
}
)glsl";

constexpr const char* kFragmentShaderEs2 = R"glsl(
#extension GL_OES_EGL_image_external : require
precision mediump float;
uniform samplerExternalOES uTexture;
varying vec2 vUv;

void main() {
    gl_FragColor = texture2D(uTexture, vUv);
}
)glsl";

constexpr const char* kTextMarkFragmentShader = R"glsl(
#version 300 es
precision mediump float;
uniform sampler2D uTexture;
uniform float uAlpha;
in vec2 vUv;
out vec4 fragColor;

void main() {
    fragColor = texture(uTexture, vUv);
    fragColor.a *= uAlpha;
}
)glsl";

constexpr const char* kColorVertexShader = R"glsl(
#version 300 es
uniform mat4 uMvp;
in vec4 aPosition;

void main() {
    gl_Position = uMvp * aPosition;
}
)glsl";

constexpr const char* kColorFragmentShader = R"glsl(
#version 300 es
precision mediump float;
uniform vec4 uColor;
out vec4 fragColor;

void main() {
    fragColor = uColor;
}
)glsl";

constexpr float kTextMarkDistance = 3.0f;

struct Vec3 {
    float x;
    float y;
    float z;
};

float Length(const Vec3& v) {
    return std::sqrt(v.x * v.x + v.y * v.y + v.z * v.z);
}

Vec3 Normalize(const Vec3& v) {
    const float len = Length(v);
    if (len <= 0.0001f) {
        return {0.0f, 0.0f, -1.0f};
    }
    return {v.x / len, v.y / len, v.z / len};
}

bool IsFinite(float value) {
    return std::isfinite(value);
}

float ToNdcX(float pixelX, int width) {
    return (pixelX / std::max(width, 1)) * 2.0f - 1.0f;
}

float ToNdcY(float pixelY, int height) {
    return 1.0f - (pixelY / std::max(height, 1)) * 2.0f;
}

void DrawTriangles(GLint positionLocation, const GLfloat* positions, GLsizei vertexCount) {
    glEnableVertexAttribArray(positionLocation);
    glVertexAttribPointer(positionLocation, 3, GL_FLOAT, GL_FALSE, 0, positions);
    glDrawArrays(GL_TRIANGLES, 0, vertexCount);
    glDisableVertexAttribArray(positionLocation);
}

void DrawLine(GLint positionLocation, const GLfloat* positions) {
    glEnableVertexAttribArray(positionLocation);
    glVertexAttribPointer(positionLocation, 3, GL_FLOAT, GL_FALSE, 0, positions);
    glDrawArrays(GL_LINES, 0, 2);
    glDisableVertexAttribArray(positionLocation);
}

void DrawTexturedQuad(
    GLint positionLocation,
    GLint uvLocation,
    const GLfloat* positions,
    const GLfloat* uvs
) {
    static constexpr GLushort indices[] = {0, 1, 2, 0, 2, 3};
    glEnableVertexAttribArray(positionLocation);
    glVertexAttribPointer(positionLocation, 3, GL_FLOAT, GL_FALSE, 0, positions);
    glEnableVertexAttribArray(uvLocation);
    glVertexAttribPointer(uvLocation, 2, GL_FLOAT, GL_FALSE, 0, uvs);
    glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_SHORT, indices);
    glDisableVertexAttribArray(positionLocation);
    glDisableVertexAttribArray(uvLocation);
}

TexturedMesh buildUvSphereMesh(
    int sliceCount,
    int stackCount,
    float minTheta,
    float maxTheta,
    float uvLeft,
    float uvTop,
    float uvRight,
    float uvBottom
) {
    TexturedMesh::Builder builder;
    const float uvWidth = uvRight - uvLeft;
    const float uvHeight = uvBottom - uvTop;
    const float thetaRange = maxTheta - minTheta;

    for (int stack = 0; stack <= stackCount; ++stack) {
        const float vFrac = static_cast<float>(stack) / static_cast<float>(stackCount);
        const float phi = kPi * vFrac;
        float v = vFrac * uvHeight + uvTop;

        for (int slice = 0; slice <= sliceCount; ++slice) {
            const float uFrac = static_cast<float>(slice) / static_cast<float>(sliceCount);
            const float theta = -(minTheta + thetaRange * uFrac);
            float u = uFrac * uvWidth + uvLeft;

            const float x = std::sin(phi) * std::sin(theta);
            const float y = std::cos(phi);
            const float z = std::sin(phi) * std::cos(theta);

            if (stack == 0 || stack == stackCount) {
                u += 1.0f / static_cast<float>(sliceCount);
                if (u > 1.0f) {
                    u -= 1.0f;
                }
            }

            builder.addVertex(x, y, z, u, v);
        }
    }

    for (int stack = 0; stack < stackCount; ++stack) {
        const auto row0 = stack * (sliceCount + 1);
        const auto row1 = (stack + 1) * (sliceCount + 1);

        for (int slice = 0; slice < sliceCount; ++slice) {
            const auto i0 = static_cast<GLushort>(row0 + slice);
            const auto i1 = static_cast<GLushort>(row0 + slice + 1);
            const auto i2 = static_cast<GLushort>(row1 + slice + 1);
            const auto i3 = static_cast<GLushort>(row1 + slice);

            if (stack == 0) {
                builder.addTriangle(i0, i2, i3);
            } else if (stack == stackCount - 1) {
                builder.addTriangle(i0, i2, i1);
            } else {
                builder.addQuad(i0, i1, i2, i3);
            }
        }
    }

    return builder.build();
}

TexturedMesh buildDualFisheye180Mesh(
    int sliceCount,
    int stackCount,
    float uvLeft,
    float uvTop,
    float uvRight,
    float uvBottom
) {
    TexturedMesh::Builder builder;
    const float minTheta = kHalfPi;
    const float maxTheta = kPi * 1.5f;
    const float thetaRange = maxTheta - minTheta;
    const float centerU = 0.5f * (uvLeft + uvRight);
    const float centerV = 0.5f * (uvTop + uvBottom);
    const float radiusU = 0.5f * (uvRight - uvLeft);
    const float radiusV = 0.5f * (uvBottom - uvTop);

    for (int stack = 0; stack <= stackCount; ++stack) {
        const float vFrac = static_cast<float>(stack) / static_cast<float>(stackCount);
        const float phi = kPi * vFrac;

        for (int slice = 0; slice <= sliceCount; ++slice) {
            const float uFrac = static_cast<float>(slice) / static_cast<float>(sliceCount);
            const float theta = -(minTheta + thetaRange * uFrac);

            const float x = std::sin(phi) * std::sin(theta);
            const float y = std::cos(phi);
            const float z = std::sin(phi) * std::cos(theta);

            const float dirLen = std::sqrt(x * x + y * y + z * z);
            const float nx = x / dirLen;
            const float ny = y / dirLen;
            const float nz = z / dirLen;
            const float forwardDot = std::clamp(-nz, -1.0f, 1.0f);
            const float angle = std::acos(forwardDot);
            const float radial = std::clamp(angle / kHalfPi, 0.0f, 1.0f);
            const float planeLen = std::sqrt(nx * nx + ny * ny);

            float u = centerU;
            float v = centerV;
            if (planeLen > 0.0001f) {
                u += (nx / planeLen) * radial * radiusU;
                v += (-ny / planeLen) * radial * radiusV;
            }

            builder.addVertex(x, y, z, u, v);
        }
    }

    for (int stack = 0; stack < stackCount; ++stack) {
        const auto row0 = stack * (sliceCount + 1);
        const auto row1 = (stack + 1) * (sliceCount + 1);

        for (int slice = 0; slice < sliceCount; ++slice) {
            const auto i0 = static_cast<GLushort>(row0 + slice);
            const auto i1 = static_cast<GLushort>(row0 + slice + 1);
            const auto i2 = static_cast<GLushort>(row1 + slice + 1);
            const auto i3 = static_cast<GLushort>(row1 + slice);

            if (stack == 0) {
                builder.addTriangle(i0, i2, i3);
            } else if (stack == stackCount - 1) {
                builder.addTriangle(i0, i2, i1);
            } else {
                builder.addQuad(i0, i1, i2, i3);
            }
        }
    }

    return builder.build();
}

TexturedMesh buildCylindricalMesh(
    int sliceCount,
    float minTheta,
    float maxTheta,
    float uvLeft,
    float uvTop,
    float uvRight,
    float uvBottom
) {
    TexturedMesh::Builder builder;
    const float uvWidth = uvRight - uvLeft;
    const float thetaRange = maxTheta - minTheta;

    for (int slice = 0; slice <= sliceCount; ++slice) {
        const float uFrac = static_cast<float>(slice) / static_cast<float>(sliceCount);
        const float theta = -(minTheta + thetaRange * uFrac);
        const float u = uFrac * uvWidth + uvLeft;

        const float x = std::sin(theta);
        const float z = std::cos(theta);

        const auto top = builder.addVertex(x, 1.0f, z, u, uvTop);
        const auto bottom = builder.addVertex(x, -1.0f, z, u, uvBottom);

        if (slice > 0) {
            builder.addQuad(
                static_cast<GLushort>(top - 2),
                top,
                bottom,
                static_cast<GLushort>(bottom - 2)
            );
        }
    }

    return builder.build();
}

TexturedMesh buildPlainFovMesh(float videoAspect, float uvLeft, float uvTop, float uvRight, float uvBottom) {
    const float xScale = videoAspect > 1.0f ? 1.0f : (1.0f / videoAspect);
    const float yScale = videoAspect > 1.0f ? (1.0f / videoAspect) : 1.0f;

    auto positions = std::make_unique<GLfloat[]>(12);
    auto uvs = std::make_unique<GLfloat[]>(8);
    auto indices = std::make_unique<GLushort[]>(6);

    const GLfloat positionData[12] = {
        -xScale,  yScale,  kPlainFovZ,
         xScale,  yScale,  kPlainFovZ,
         xScale, -yScale,  kPlainFovZ,
        -xScale, -yScale,  kPlainFovZ
    };
    const GLfloat uvData[8] = {
        uvLeft,  uvTop,
        uvRight, uvTop,
        uvRight, uvBottom,
        uvLeft,  uvBottom
    };
    const GLushort indexData[6] = {0, 2, 1, 0, 3, 2};

    std::copy(std::begin(positionData), std::end(positionData), positions.get());
    std::copy(std::begin(uvData), std::end(uvData), uvs.get());
    std::copy(std::begin(indexData), std::end(indexData), indices.get());

    return TexturedMesh(GL_TRIANGLES, 6, std::move(positions), std::move(uvs), std::move(indices));
}

}  // namespace

SceneRenderer::SceneRenderer() = default;

SceneRenderer::~SceneRenderer() {
    shutdown();
}

void SceneRenderer::initialize() {
    if (initialized_) {
        return;
    }

    ClearGlErrors();

    GLuint vertexShader = LoadGlShader(GL_VERTEX_SHADER, kVertexShader);
    GLuint fragmentShader = LoadGlShader(GL_FRAGMENT_SHADER, kFragmentShader);
    program_ = LinkGlProgram(vertexShader, fragmentShader);
    glDeleteShader(vertexShader);
    glDeleteShader(fragmentShader);

    if (program_ == 0) {
        __android_log_print(
            ANDROID_LOG_WARN,
            "OpenXRQuestPlayer",
            "Falling back to GLES2-style external texture shader"
        );
        vertexShader = LoadGlShader(GL_VERTEX_SHADER, kVertexShaderEs2);
        fragmentShader = LoadGlShader(GL_FRAGMENT_SHADER, kFragmentShaderEs2);
        program_ = LinkGlProgram(vertexShader, fragmentShader);
        glDeleteShader(vertexShader);
        glDeleteShader(fragmentShader);
    }

    positionLocation_ = glGetAttribLocation(program_, "aPosition");
    uvLocation_ = glGetAttribLocation(program_, "aUv");
    mvpLocation_ = glGetUniformLocation(program_, "uMvp");
    textureLocation_ = glGetUniformLocation(program_, "uTexture");

    const GLuint textMarkVertexShader = LoadGlShader(GL_VERTEX_SHADER, kVertexShader);
    const GLuint textMarkFragmentShader = LoadGlShader(GL_FRAGMENT_SHADER, kTextMarkFragmentShader);
    textMarkProgram_ = LinkGlProgram(textMarkVertexShader, textMarkFragmentShader);
    glDeleteShader(textMarkVertexShader);
    glDeleteShader(textMarkFragmentShader);

    textMarkPositionLocation_ = glGetAttribLocation(textMarkProgram_, "aPosition");
    textMarkUvLocation_ = glGetAttribLocation(textMarkProgram_, "aUv");
    textMarkMvpLocation_ = glGetUniformLocation(textMarkProgram_, "uMvp");
    textMarkTextureLocation_ = glGetUniformLocation(textMarkProgram_, "uTexture");
    textMarkAlphaLocation_ = glGetUniformLocation(textMarkProgram_, "uAlpha");

    const GLuint colorVertexShader = LoadGlShader(GL_VERTEX_SHADER, kColorVertexShader);
    const GLuint colorFragmentShader = LoadGlShader(GL_FRAGMENT_SHADER, kColorFragmentShader);
    colorProgram_ = LinkGlProgram(colorVertexShader, colorFragmentShader);
    glDeleteShader(colorVertexShader);
    glDeleteShader(colorFragmentShader);

    colorPositionLocation_ = glGetAttribLocation(colorProgram_, "aPosition");
    colorMvpLocation_ = glGetUniformLocation(colorProgram_, "uMvp");
    colorValueLocation_ = glGetUniformLocation(colorProgram_, "uColor");

    if (program_ == 0 || textMarkProgram_ == 0 || colorProgram_ == 0) {
        __android_log_print(
            ANDROID_LOG_ERROR,
            "OpenXRQuestPlayer",
            "SceneRenderer initialize failed: program=%u textMarkProgram=%u colorProgram=%u",
            program_,
            textMarkProgram_,
            colorProgram_
        );
        CheckGlError(__FILE__, __LINE__, "SceneRenderer::initialize programs");
        return;
    }

    ensureVideoTexture();
    buildMeshes();
    initialized_ = true;
}

void SceneRenderer::shutdown() {
    clearTextMarks();
    if (videoTexture_ != 0) {
        glDeleteTextures(1, &videoTexture_);
        videoTexture_ = 0;
    }
    if (textMarkProgram_ != 0) {
        glDeleteProgram(textMarkProgram_);
        textMarkProgram_ = 0;
    }
    if (colorProgram_ != 0) {
        glDeleteProgram(colorProgram_);
        colorProgram_ = 0;
    }
    if (program_ != 0) {
        glDeleteProgram(program_);
        program_ = 0;
    }
    initialized_ = false;
}

void SceneRenderer::resize(int width, int height) {
    width_ = std::max(width, 1);
    height_ = std::max(height, 1);
}

void SceneRenderer::updateTime(float seconds) {
    timeSeconds_ = seconds;

    if (!animatingLookAt_) {
        return;
    }

    const float duration = std::max(animationDurationSeconds_, 0.001f);
    const float t = std::clamp((timeSeconds_ - animationStartSeconds_) / duration, 0.0f, 1.0f);
    const float eased = smoothStep(t);

    currentYawRadians_ = normalizeAngle(
        startYawRadians_ + shortestAngleDelta(startYawRadians_, targetYawRadians_) * eased
    );
    currentPitchRadians_ = startPitchRadians_ + (targetPitchRadians_ - startPitchRadians_) * eased;

    if (t >= 1.0f) {
        currentYawRadians_ = targetYawRadians_;
        currentPitchRadians_ = targetPitchRadians_;
        animatingLookAt_ = false;
    }
}

void SceneRenderer::render() {
    if (!initialized_) {
        return;
    }

    glDisable(GL_CULL_FACE);
    glEnable(GL_DEPTH_TEST);
    glDepthFunc(GL_LEQUAL);

    const float aspect = static_cast<float>(width_) / static_cast<float>(height_);
    const auto projection = math::Perspective(90.0f * kPi / 180.0f, aspect, 0.1f, 20.0f);
    const auto baseYaw = math::RotationY(currentYawRadians_);
    const auto basePitch = math::RotationX(currentPitchRadians_);
    const auto headYaw = math::RotationY(headYawRadians_);
    const auto headPitch = math::RotationX(headPitchRadians_);
    const auto view = math::Multiply(math::Multiply(basePitch, baseYaw), math::Multiply(headPitch, headYaw));
    const auto mvp = math::Multiply(projection, view);

    glUseProgram(program_);
    glUniformMatrix4fv(mvpLocation_, 1, GL_FALSE, mvp.data());
    glUniform1i(textureLocation_, 0);

    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, videoTexture_);
    meshes_[0].render(positionLocation_, uvLocation_);
    renderTextMarkOverlay(projection, view, width_, height_);
}

void SceneRenderer::renderEye(const math::Mat4& projection, const math::Mat4& eyeView, int width, int height) {
    if (!initialized_) {
        return;
    }

    glViewport(0, 0, width, height);
    glDisable(GL_CULL_FACE);
    glEnable(GL_DEPTH_TEST);
    glDepthFunc(GL_LEQUAL);

    const auto baseYaw = math::RotationY(currentYawRadians_);
    const auto basePitch = math::RotationX(currentPitchRadians_);
    const auto baseView = math::Multiply(basePitch, baseYaw);
    const auto composedView = math::Multiply(baseView, eyeView);
    const auto mvp = math::Multiply(projection, composedView);

    glUseProgram(program_);
    glUniformMatrix4fv(mvpLocation_, 1, GL_FALSE, mvp.data());
    glUniform1i(textureLocation_, 0);

    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, videoTexture_);
    meshes_[0].render(positionLocation_, uvLocation_);
    renderTextMarkOverlay(projection, composedView, width, height);
}

void SceneRenderer::setVideoMode(InputVideoLayout layout, InputVideoMode mode, int outputMode) {
    inputLayout_ = layout;
    inputMode_ = mode;
    outputMode_ = outputMode;
    if (initialized_) {
        buildMeshes();
    }
}

void SceneRenderer::setLookAt(float yawDeg, float pitchDeg, float, int durationMs) {
    startYawRadians_ = currentYawRadians_;
    startPitchRadians_ = currentPitchRadians_;
    targetYawRadians_ = normalizeAngle(yawDeg * kPi / 180.0f);
    targetPitchRadians_ = std::clamp(pitchDeg * kPi / 180.0f, -1.4835298f, 1.4835298f);
    animationStartSeconds_ = timeSeconds_;
    animationDurationSeconds_ = std::max(durationMs, 1) / 1000.0f;
    animatingLookAt_ = true;
}

void SceneRenderer::setHeadPose(float yawRadians, float pitchRadians) {
    headYawRadians_ = yawRadians;
    headPitchRadians_ = pitchRadians;
}

void SceneRenderer::clearTextMarks() {
    for (auto& textMark : textMarks_) {
        if (textMark.texture != 0) {
            glDeleteTextures(1, &textMark.texture);
            textMark.texture = 0;
        }
    }
    textMarks_.clear();
}

void SceneRenderer::showTextMark(
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
    if (!initialized_ || pixelsArgb == nullptr || bitmapWidth <= 0 || bitmapHeight <= 0) {
        return;
    }

    std::vector<uint8_t> rgbaPixels(static_cast<size_t>(bitmapWidth) * static_cast<size_t>(bitmapHeight) * 4u);
    for (int i = 0; i < bitmapWidth * bitmapHeight; ++i) {
        const uint32_t argb = static_cast<uint32_t>(pixelsArgb[i]);
        rgbaPixels[static_cast<size_t>(i) * 4u + 0u] = static_cast<uint8_t>((argb >> 16) & 0xFFu);
        rgbaPixels[static_cast<size_t>(i) * 4u + 1u] = static_cast<uint8_t>((argb >> 8) & 0xFFu);
        rgbaPixels[static_cast<size_t>(i) * 4u + 2u] = static_cast<uint8_t>(argb & 0xFFu);
        rgbaPixels[static_cast<size_t>(i) * 4u + 3u] = static_cast<uint8_t>((argb >> 24) & 0xFFu);
    }

    TextMarkState* textMark = nullptr;
    const std::string idString = id != nullptr ? id : "";
    for (auto& existingMark : textMarks_) {
        if (existingMark.id == idString) {
            textMark = &existingMark;
            break;
        }
    }
    if (textMark == nullptr) {
        textMarks_.push_back(TextMarkState{});
        textMark = &textMarks_.back();
        textMark->id = idString;
    }

    if (textMark->texture != 0) {
        glDeleteTextures(1, &textMark->texture);
        textMark->texture = 0;
    }

    glGenTextures(1, &textMark->texture);
    glBindTexture(GL_TEXTURE_2D, textMark->texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexImage2D(
        GL_TEXTURE_2D,
        0,
        GL_RGBA,
        bitmapWidth,
        bitmapHeight,
        0,
        GL_RGBA,
        GL_UNSIGNED_BYTE,
        rgbaPixels.data()
    );

    Vec3 direction{};
    if (IsFinite(x) && IsFinite(y) && IsFinite(z)) {
        direction = Normalize({x, y, z});
    } else {
        const float yawRadians = IsFinite(yawDeg) ? yawDeg * kPi / 180.0f : 0.0f;
        const float pitchRadians = IsFinite(pitchDeg) ? pitchDeg * kPi / 180.0f : 0.0f;
        const float cosPitch = std::cos(pitchRadians);
        direction = Normalize({
            -std::sin(yawRadians) * cosPitch,
            -std::sin(pitchRadians),
            -std::cos(yawRadians) * cosPitch
        });
    }

    textMark->bitmapWidth = std::max(bitmapWidth, static_cast<int>(std::max(markWidth, 1.0f)));
    textMark->bitmapHeight = std::max(bitmapHeight, static_cast<int>(std::max(markHeight, 1.0f)));
    textMark->direction[0] = direction.x;
    textMark->direction[1] = direction.y;
    textMark->direction[2] = direction.z;
    textMark->expiresAtSeconds = timeSeconds_ + std::max(durationMs, 500) / 1000.0f;
    textMark->visible = true;
}

void SceneRenderer::setVideoSize(int width, int height) {
    if (width > 0 && height > 0) {
        videoAspect_ = static_cast<float>(width) / static_cast<float>(height);
        if (initialized_) {
            buildMeshes();
        }
    }
}

GLuint SceneRenderer::videoTextureId() const {
    return videoTexture_;
}

void SceneRenderer::buildMeshes() {
    for (int eye = 0; eye < 2; ++eye) {
        float uvLeft = 0.0f;
        float uvTop = 0.0f;
        float uvRight = 1.0f;
        float uvBottom = 1.0f;

        switch (inputLayout_) {
            case InputVideoLayout::Mono:
            case InputVideoLayout::AnaglyphRedCyan:
                break;
            case InputVideoLayout::StereoHorizontal:
                uvLeft = 0.5f * static_cast<float>(eye);
                uvRight = 0.5f * static_cast<float>(eye + 1);
                break;
            case InputVideoLayout::StereoVertical:
                uvTop = 0.5f * static_cast<float>(eye);
                uvBottom = 0.5f * static_cast<float>(eye + 1);
                break;
        }

        if ((uvRight - uvLeft) > (kUvInset * 2.0f) && (uvBottom - uvTop) > (kUvInset * 2.0f)) {
            uvLeft += kUvInset;
            uvRight -= kUvInset;
            uvTop += kUvInset;
            uvBottom -= kUvInset;
        }

        switch (inputMode_) {
            case InputVideoMode::PlainFov:
                meshes_[eye] = buildPlainFovMesh(videoAspect_, uvLeft, uvTop, uvRight, uvBottom);
                break;
            case InputVideoMode::Equirect180:
                meshes_[eye] = buildUvSphereMesh(20, 20, kHalfPi, kPi * 1.5f, uvLeft, uvTop, uvRight, uvBottom);
                break;
            case InputVideoMode::DualFisheye180:
                meshes_[eye] = buildDualFisheye180Mesh(40, 20, uvLeft, uvTop, uvRight, uvBottom);
                break;
            case InputVideoMode::Equirect360:
                meshes_[eye] = buildUvSphereMesh(40, 20, 0.0f, kTwoPi, uvLeft, uvTop, uvRight, uvBottom);
                break;
            case InputVideoMode::Panorama180:
                meshes_[eye] = buildCylindricalMesh(20, kHalfPi, kPi * 1.5f, uvLeft, uvTop, uvRight, uvBottom);
                break;
            case InputVideoMode::Panorama360:
                meshes_[eye] = buildCylindricalMesh(40, 0.0f, kTwoPi, uvLeft, uvTop, uvRight, uvBottom);
                break;
            default:
                meshes_[eye] = buildUvSphereMesh(40, 20, 0.0f, kTwoPi, uvLeft, uvTop, uvRight, uvBottom);
                break;
        }
    }
}

void SceneRenderer::renderTextMarkOverlay(
    const math::Mat4& projection,
    const math::Mat4& view,
    int width,
    int height
) {
    if (textMarks_.empty()) {
        return;
    }
    if (textMarkProgram_ == 0 || colorProgram_ == 0) {
        return;
    }

    const auto overlayMvp = math::Identity();
    const auto viewProjection = math::Multiply(projection, view);

    glDisable(GL_DEPTH_TEST);
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

    glUseProgram(colorProgram_);
    glUniformMatrix4fv(colorMvpLocation_, 1, GL_FALSE, overlayMvp.data());

    auto drawRect = [&](float x0, float y0, float w0, float h0, float r, float g, float b, float a) {
        const GLfloat quad[] = {
            ToNdcX(x0, width), ToNdcY(y0, height), 0.0f,
            ToNdcX(x0 + w0, width), ToNdcY(y0, height), 0.0f,
            ToNdcX(x0 + w0, width), ToNdcY(y0 + h0, height), 0.0f,
            ToNdcX(x0, width), ToNdcY(y0, height), 0.0f,
            ToNdcX(x0 + w0, width), ToNdcY(y0 + h0, height), 0.0f,
            ToNdcX(x0, width), ToNdcY(y0 + h0, height), 0.0f
        };
        glUniform4f(colorValueLocation_, r, g, b, a);
        DrawTriangles(colorPositionLocation_, quad, 6);
    };

    glUseProgram(textMarkProgram_);
    glUniformMatrix4fv(textMarkMvpLocation_, 1, GL_FALSE, overlayMvp.data());
    glUniform1i(textMarkTextureLocation_, 0);

    const GLfloat uvs[] = {
        0.0f, 0.0f,
        1.0f, 0.0f,
        1.0f, 1.0f,
        0.0f, 1.0f
    };

    auto drawSkewedQuad = [&](float x0, float y0, float w0, float h0, float sx, float sy, float alpha) {
        const GLfloat positions[] = {
            ToNdcX(x0 + sx, width), ToNdcY(y0 + sy, height), 0.0f,
            ToNdcX(x0 + w0 - sx, width), ToNdcY(y0 - sy, height), 0.0f,
            ToNdcX(x0 + w0 + sx, width), ToNdcY(y0 + h0 + sy, height), 0.0f,
            ToNdcX(x0 - sx, width), ToNdcY(y0 + h0 - sy, height), 0.0f
        };
        glUniform1f(textMarkAlphaLocation_, alpha);
        DrawTexturedQuad(textMarkPositionLocation_, textMarkUvLocation_, positions, uvs);
    };

    for (auto it = textMarks_.begin(); it != textMarks_.end();) {
        if (!it->visible || timeSeconds_ >= it->expiresAtSeconds) {
            if (it->texture != 0) {
                glDeleteTextures(1, &it->texture);
            }
            it = textMarks_.erase(it);
            continue;
        }
        if (it->texture == 0) {
            ++it;
            continue;
        }

        const Vec3 worldPoint{
            it->direction[0] * kTextMarkDistance,
            it->direction[1] * kTextMarkDistance,
            it->direction[2] * kTextMarkDistance
        };
        const float x = worldPoint.x;
        const float y = worldPoint.y;
        const float z = worldPoint.z;
        const float clipX =
            viewProjection[0] * x + viewProjection[4] * y + viewProjection[8] * z + viewProjection[12];
        const float clipY =
            viewProjection[1] * x + viewProjection[5] * y + viewProjection[9] * z + viewProjection[13];
        const float clipW =
            viewProjection[3] * x + viewProjection[7] * y + viewProjection[11] * z + viewProjection[15];

        const bool inFront = clipW > 0.001f;
        const float safeW = inFront ? clipW : 0.001f;
        float pointX = width * 0.5f + (clipX / safeW) * width * 0.5f;
        float pointY = height * 0.5f - (clipY / safeW) * height * 0.5f;

        if (!inFront) {
            float dx = pointX - width * 0.5f;
            float dy = pointY - height * 0.5f;
            if (std::fabs(dx) < 0.001f && std::fabs(dy) < 0.001f) {
                dx = 1.0f;
            }
            const float scaleX = dx > 0.0f
                ? (width - 18.0f - width * 0.5f) / dx
                : (18.0f - width * 0.5f) / dx;
            const float scaleY = dy > 0.0f
                ? (height - 18.0f - height * 0.5f) / dy
                : (18.0f - height * 0.5f) / dy;
            const float scale = std::min(std::fabs(scaleX), std::fabs(scaleY));
            pointX = width * 0.5f + dx * scale;
            pointY = height * 0.5f + dy * scale;
        }

        pointX = std::clamp(pointX, 18.0f, static_cast<float>(width - 18));
        pointY = std::clamp(pointY, 18.0f, static_cast<float>(height - 18));

        const float screenCx = width * 0.5f;
        const float screenCy = height * 0.5f;
        const float relX = (pointX - screenCx) / std::max(1.0f, screenCx);
        const float relY = (pointY - screenCy) / std::max(1.0f, screenCy);
        const float edge = std::min(1.0f, std::sqrt(relX * relX + relY * relY));
        const float depthScale = inFront ? (1.0f - edge * 0.16f) : 0.78f;

        float boxW = static_cast<float>(it->bitmapWidth) * depthScale;
        float boxH = static_cast<float>(it->bitmapHeight) * depthScale;
        float boxX = pointX + 34.0f;
        float boxY = pointY - boxH * 0.5f;
        if (boxX + boxW > width - 12.0f) {
            boxX = pointX - boxW - 34.0f;
        }
        boxX = std::clamp(boxX, 12.0f, std::max(12.0f, width - boxW - 12.0f));
        boxY = std::clamp(boxY, 12.0f, std::max(12.0f, height - boxH - 12.0f));

        const float skewX = relX * 12.0f;
        const float skewY = relY * 7.0f;
        const float boxCenterX = boxX + boxW * 0.5f;
        const float boxCenterY = boxY + boxH * 0.5f;
        const float dx = pointX - boxCenterX;
        const float dy = pointY - boxCenterY;
        float lineStartX = boxCenterX;
        float lineStartY = boxCenterY;
        if (std::fabs(dx) > 0.001f || std::fabs(dy) > 0.001f) {
            const float halfW = boxW * 0.5f;
            const float halfH = boxH * 0.5f;
            const float scaleX = halfW / std::max(0.001f, std::fabs(dx));
            const float scaleY = halfH / std::max(0.001f, std::fabs(dy));
            const float scale = std::min(scaleX, scaleY);
            lineStartX = boxCenterX + dx * scale;
            lineStartY = boxCenterY + dy * scale;
        }

        {
            float vx = pointX - lineStartX;
            float vy = pointY - lineStartY;
            const float len = std::sqrt(vx * vx + vy * vy);
            if (len > 1.0f) {
                vx /= len;
                vy /= len;
                const float px = -vy;
                const float py = vx;
                const float tailHalfWidth = 12.0f * depthScale;
                const float base1X = lineStartX + px * tailHalfWidth;
                const float base1Y = lineStartY + py * tailHalfWidth;
                const float base2X = lineStartX - px * tailHalfWidth;
                const float base2Y = lineStartY - py * tailHalfWidth;
                const float tipX = pointX - vx * 3.0f;
                const float tipY = pointY - vy * 3.0f;

                const GLfloat shadowTriangle[] = {
                    ToNdcX(base1X + px * 2.5f, width), ToNdcY(base1Y + py * 2.5f, height), 0.0f,
                    ToNdcX(base2X - px * 2.5f, width), ToNdcY(base2Y - py * 2.5f, height), 0.0f,
                    ToNdcX(tipX, width), ToNdcY(tipY, height), 0.0f
                };
                glUniform4f(colorValueLocation_, 0.02f, 0.02f, 0.02f, inFront ? 0.90f : 0.35f);
                DrawTriangles(colorPositionLocation_, shadowTriangle, 3);

                const GLfloat triangle[] = {
                    ToNdcX(base1X, width), ToNdcY(base1Y, height), 0.0f,
                    ToNdcX(base2X, width), ToNdcY(base2Y, height), 0.0f,
                    ToNdcX(tipX, width), ToNdcY(tipY, height), 0.0f
                };
                glUniform4f(colorValueLocation_, 1.0f, 1.0f, 0.96f, inFront ? 0.88f : 0.32f);
                DrawTriangles(colorPositionLocation_, triangle, 3);
            } else {
                const GLfloat line[] = {
                    ToNdcX(lineStartX, width), ToNdcY(lineStartY, height), 0.0f,
                    ToNdcX(pointX, width), ToNdcY(pointY, height), 0.0f
                };
                glLineWidth(2.0f);
                glUniform4f(colorValueLocation_, 0.25f, 0.75f, 1.0f, inFront ? 0.95f : 0.45f);
                DrawLine(colorPositionLocation_, line);
            }
        }

        drawRect(pointX - 6.0f, pointY - 6.0f, 12.0f, 12.0f, 0.0f, 0.0f, 0.0f, inFront ? 0.45f : 0.20f);
        drawRect(pointX - 4.0f, pointY - 4.0f, 8.0f, 8.0f, 0.0f, 0.85f, 1.0f, inFront ? 1.0f : 0.45f);
        drawRect(pointX - 1.5f, pointY - 1.5f, 3.0f, 3.0f, 1.0f, 1.0f, 1.0f, inFront ? 0.9f : 0.35f);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, it->texture);
        drawSkewedQuad(
            boxX + 8.0f + relX * 6.0f,
            boxY + 10.0f + relY * 4.0f,
            boxW,
            boxH,
            skewX * 1.1f,
            skewY * 1.1f,
            inFront ? 0.22f : 0.10f
        );
        drawSkewedQuad(
            boxX,
            boxY,
            boxW,
            boxH,
            skewX,
            skewY,
            inFront ? 1.0f : 0.72f
        );

        ++it;
    }

    glDisable(GL_BLEND);
    glEnable(GL_DEPTH_TEST);
}

float SceneRenderer::normalizeAngle(float value) {
    while (value <= -kPi) value += kTwoPi;
    while (value > kPi) value -= kTwoPi;
    return value;
}

float SceneRenderer::shortestAngleDelta(float from, float to) {
    return normalizeAngle(to - from);
}

float SceneRenderer::smoothStep(float t) {
    return t * t * (3.0f - 2.0f * t);
}

void SceneRenderer::ensureVideoTexture() {
    if (videoTexture_ != 0) {
        return;
    }

    ClearGlErrors();
    glGenTextures(1, &videoTexture_);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, videoTexture_);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    OXR_CHECK_GL("ensureVideoTexture");
}
