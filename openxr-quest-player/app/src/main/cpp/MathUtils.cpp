#include "MathUtils.h"

#include <cmath>

namespace math {

Mat4 Identity() {
    return {
        1.0f, 0.0f, 0.0f, 0.0f,
        0.0f, 1.0f, 0.0f, 0.0f,
        0.0f, 0.0f, 1.0f, 0.0f,
        0.0f, 0.0f, 0.0f, 1.0f
    };
}

Mat4 Multiply(const Mat4& a, const Mat4& b) {
    Mat4 out{};
    for (int col = 0; col < 4; ++col) {
        for (int row = 0; row < 4; ++row) {
            float value = 0.0f;
            for (int k = 0; k < 4; ++k) {
                value += a[k * 4 + row] * b[col * 4 + k];
            }
            out[col * 4 + row] = value;
        }
    }
    return out;
}

Mat4 Perspective(float fovYRadians, float aspect, float zNear, float zFar) {
    const float f = 1.0f / std::tan(fovYRadians * 0.5f);
    Mat4 out{};
    out[0] = f / aspect;
    out[5] = f;
    out[10] = (zFar + zNear) / (zNear - zFar);
    out[11] = -1.0f;
    out[14] = (2.0f * zFar * zNear) / (zNear - zFar);
    return out;
}

Mat4 PerspectiveFromFov(float angleLeft, float angleRight, float angleUp, float angleDown, float zNear, float zFar) {
    const float tanLeft = std::tan(angleLeft);
    const float tanRight = std::tan(angleRight);
    const float tanDown = std::tan(angleDown);
    const float tanUp = std::tan(angleUp);
    const float tanWidth = tanRight - tanLeft;
    const float tanHeight = tanUp - tanDown;

    Mat4 out{};
    out[0] = 2.0f / tanWidth;
    out[5] = 2.0f / tanHeight;
    out[8] = (tanRight + tanLeft) / tanWidth;
    out[9] = (tanUp + tanDown) / tanHeight;
    out[10] = -(zFar + zNear) / (zFar - zNear);
    out[11] = -1.0f;
    out[14] = -(2.0f * zFar * zNear) / (zFar - zNear);
    return out;
}

Mat4 FromQuaternion(float x, float y, float z, float w) {
    const float xx = x * x;
    const float yy = y * y;
    const float zz = z * z;
    const float xy = x * y;
    const float xz = x * z;
    const float yz = y * z;
    const float wx = w * x;
    const float wy = w * y;
    const float wz = w * z;

    return {
        1.0f - 2.0f * (yy + zz), 2.0f * (xy + wz),        2.0f * (xz - wy),        0.0f,
        2.0f * (xy - wz),        1.0f - 2.0f * (xx + zz), 2.0f * (yz + wx),        0.0f,
        2.0f * (xz + wy),        2.0f * (yz - wx),        1.0f - 2.0f * (xx + yy), 0.0f,
        0.0f,                    0.0f,                    0.0f,                    1.0f
    };
}

Mat4 RotationX(float radians) {
    const float c = std::cos(radians);
    const float s = std::sin(radians);
    return {
        1.0f, 0.0f, 0.0f, 0.0f,
        0.0f, c,    s,    0.0f,
        0.0f, -s,   c,    0.0f,
        0.0f, 0.0f, 0.0f, 1.0f
    };
}

Mat4 RotationY(float radians) {
    const float c = std::cos(radians);
    const float s = std::sin(radians);
    return {
        c,    0.0f, -s,   0.0f,
        0.0f, 1.0f, 0.0f, 0.0f,
        s,    0.0f, c,    0.0f,
        0.0f, 0.0f, 0.0f, 1.0f
    };
}

}  // namespace math
