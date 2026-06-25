# OpenXR Quest Player

Новый отдельный Android/NDK-проект под OpenXR для устройств Meta Quest / Oculus.

## Что уже есть

- отдельный Gradle-проект в своей папке;
- `NativeActivity` + `android_native_app_glue`;
- EGL bootstrap для жизненного цикла native-приложения;
- каркас `OpenXrApp`, в который можно переносить текущий renderer;
- вынесенный `SceneRenderer` с портированной генерацией mesh для `180/360 equirect`, `180/360 panorama` и `dual-fisheye 180`;
- procedural debug texture, чтобы сразу видеть VR-геометрию даже до подключения видеодекодера;
- манифест и build-настройки, ориентированные на standalone VR-приложение.

## Что это пока не делает

Это еще не полный перенос старого Cardboard-плеера. Сейчас это чистая точка старта под Quest/OpenXR:

- без перенесенного декодирования видео;
- без sphere/panorama mesh из старого проекта;
- без реального создания `XrInstance` / `XrSession`, если OpenXR headers пока не подключены локально.

## Как запускать дальше

Если wrapper в этой папке еще не добавлен, можно собирать из корня workspace:

```powershell
.\gradlew.bat -p openxr-quest-player assembleDebug
```

## Следующий перенос

1. Вынести из старого проекта геометрию 180/360 и код отрисовки в отдельный `SceneRenderer`.
2. Заменить текущую временную orbit-камеру на `xrLocateViews`.
3. Заменить обычный window EGL render path на OpenXR swapchains.
4. Перенести `SurfaceTexture`/видеодекодирование и `samplerExternalOES`.
5. Подключить UI и управление воспроизведением поверх OpenXR pose pipeline.
