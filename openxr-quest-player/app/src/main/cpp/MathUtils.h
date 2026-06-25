#pragma once

#include <array>

namespace math {

using Mat4 = std::array<float, 16>;

Mat4 Identity();
Mat4 Multiply(const Mat4& a, const Mat4& b);
Mat4 Perspective(float fovYRadians, float aspect, float zNear, float zFar);
Mat4 PerspectiveFromFov(float angleLeft, float angleRight, float angleUp, float angleDown, float zNear, float zFar);
Mat4 FromQuaternion(float x, float y, float z, float w);
Mat4 RotationX(float radians);
Mat4 RotationY(float radians);

}  // namespace math
