#include "GlUtils.h"

#include <android/log.h>

#include <cstdlib>
#include <vector>

namespace {
constexpr const char* kTag = "OpenXRQuestPlayer";
}

GLuint LoadGlShader(GLenum type, const char* shaderSource) {
    const GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &shaderSource, nullptr);
    glCompileShader(shader);

    GLint compiled = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (compiled == GL_TRUE) {
        return shader;
    }

    GLint logLength = 0;
    glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &logLength);
    std::vector<char> log(static_cast<size_t>(logLength > 0 ? logLength : 1));
    glGetShaderInfoLog(shader, logLength, nullptr, log.data());
    __android_log_print(ANDROID_LOG_ERROR, kTag, "Shader compile failed: %s", log.data());
    glDeleteShader(shader);
    return 0;
}

GLuint LinkGlProgram(GLuint vertexShader, GLuint fragmentShader) {
    if (vertexShader == 0 || fragmentShader == 0) {
        __android_log_print(
            ANDROID_LOG_ERROR,
            kTag,
            "Program link skipped because shader compile failed: vertex=%u fragment=%u",
            vertexShader,
            fragmentShader
        );
        return 0;
    }

    const GLuint program = glCreateProgram();
    glAttachShader(program, vertexShader);
    glAttachShader(program, fragmentShader);
    glLinkProgram(program);

    GLint linked = GL_FALSE;
    glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (linked == GL_TRUE) {
        return program;
    }

    GLint logLength = 0;
    glGetProgramiv(program, GL_INFO_LOG_LENGTH, &logLength);
    std::vector<char> log(static_cast<size_t>(logLength > 0 ? logLength : 1));
    glGetProgramInfoLog(program, logLength, nullptr, log.data());
    __android_log_print(ANDROID_LOG_ERROR, kTag, "Program link failed: %s", log.data());
    glDeleteProgram(program);
    return 0;
}

bool CheckGlError(const char* file, int line, const char* label) {
    bool hasError = false;
    for (GLenum error = glGetError(); error != GL_NO_ERROR; error = glGetError()) {
        hasError = true;
        __android_log_print(
            ANDROID_LOG_ERROR,
            kTag,
            "%s:%d GL error at %s: 0x%x",
            file,
            line,
            label,
            error
        );
    }
    return hasError;
}

void ClearGlErrors() {
    while (glGetError() != GL_NO_ERROR) {
    }
}
