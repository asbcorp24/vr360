#pragma once

#include <GLES3/gl3.h>

GLuint LoadGlShader(GLenum type, const char* shaderSource);
GLuint LinkGlProgram(GLuint vertexShader, GLuint fragmentShader);
bool CheckGlError(const char* file, int line, const char* label);
void ClearGlErrors();

#define OXR_CHECK_GL(label) CheckGlError(__FILE__, __LINE__, label)
