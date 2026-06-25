cmake_minimum_required(VERSION 3.22.1)

project(openxr_quest_player LANGUAGES C CXX)

add_library(
    openxr_quest_player
    SHARED
    ${ANDROID_NDK}/sources/android/native_app_glue/android_native_app_glue.c
    GlUtils.cpp
    JavaVideoBridge.cpp
    JniBridge.cpp
    MathUtils.cpp
    SceneRenderer.cpp
    TexturedMesh.cpp
    main.cpp
    OpenXrApp.cpp
)

find_library(android-lib android)
find_library(dl-lib dl)
find_library(egl-lib EGL)
find_library(glesv3-lib GLESv3)
find_library(log-lib log)

target_include_directories(
    openxr_quest_player
    PRIVATE
    ${ANDROID_NDK}/sources/android/native_app_glue
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/openxr/include
)

target_compile_features(openxr_quest_player PRIVATE cxx_std_20)

target_link_options(
    openxr_quest_player
    PRIVATE
    "-uANativeActivity_onCreate"
)

target_link_libraries(
    openxr_quest_player
    PRIVATE
    ${android-lib}
    ${dl-lib}
    ${egl-lib}
    ${glesv3-lib}
    ${log-lib}
)
