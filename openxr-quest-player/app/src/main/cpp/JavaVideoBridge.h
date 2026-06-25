#pragma once

#include <GLES2/gl2.h>
#include <jni.h>

namespace javabridge {

void SetJavaVm(JavaVM* vm);
void SetVideoPlayer(JNIEnv* env, jobject videoPlayer);
bool InitializeVideoPlayback(GLuint textureId);
void UpdateVideoTextureIfNeeded();

}  // namespace javabridge
