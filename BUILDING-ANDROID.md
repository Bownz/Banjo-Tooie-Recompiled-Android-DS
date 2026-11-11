# Building for Android

The Android port builds the same top-level `CMakeLists.txt` as the desktop versions,
driven by Gradle and the NDK. The result is an `arm64-v8a` APK that runs the game through
SDL2 and RT64's Vulkan renderer. It needs Android 9 (API 28) or newer and a Vulkan 1.1 GPU.

**No ROM is needed to build.** The recompiled game code is committed (`RecompiledFuncs/`,
`rsp/n_aspMain.cpp`; see AGENTS.md), and the player picks their own ROM on the device.

The port follows the DK64 Android port
([Bownz/Donkey-Kong-64-Recompiled-Android-DS](https://github.com/Bownz/Donkey-Kong-64-Recompiled-Android-DS),
branch `android-port`), which runs on the same stack.

## How the port is put together

| Piece | Where | What it does |
|---|---|---|
| Gradle project | `settings.gradle`, `build.gradle`, `app/build.gradle` | Downloads SDL2, FreeType and libadrenotools, applies the patches, builds the CMake project with the NDK, packages the APK |
| Manifest | `app/src/main/AndroidManifest.xml` | One landscape SDL activity, requires Vulkan 1.1 |
| Activity | `app/src/main/java/.../BTActivity.java` | Extends `SDLActivity`: lifecycle, immersive fullscreen, asset extraction, file picker, 60 Hz request |
| Storage | `StorageAccess.java`, `StorageDialog.java` | First-launch choice of shared folder vs app storage; Settings > Gameplay > Game Files Location moves the files later |
| Graphics driver | `GpuDrivers.java`, `GpuDriverActivity.java`, `android/jni/gpu_driver.cpp` | Custom Vulkan driver packages (e.g. Turnip) through libadrenotools |
| Diagnostics | `Diagnostics.java`, `LogProvider.java`, `android/jni/perf_monitor.cpp` | Crash detection, session log, Share Log, `Perf` lines in the log |
| Native entry | `android/jni/android_main.cpp` | `SDL_main` → sets paths and the working directory → calls the desktop `main` |
| File dialogs | `android/jni/nfd_android.cpp` | Replaces nativefiledialog-extended with Android's document picker |
| CMake glue | `android/cmake/android.cmake` | Builds SDL2 and FreeType from source, makes the game target `libmain.so` |
| Submodule patches | `android/patches/*.patch` | Android changes to RT64, plume, N64ModernRuntime, RecompFrontend and SDL2 |
| CI | `.github/workflows/android.yml` | Builds and signs the APK on GitHub Actions |

Desktop builds are unaffected: everything Android-specific is behind `if (ANDROID)` or
`__ANDROID__`.

### Patches come in two layers

1. `patches/*.patch`: the port's own runtime fixes, required on every platform (AGENTS.md
   §6). The desktop build applies them with `tools/setup_deps.py`.
2. `android/patches/*.patch`: the Android changes, written against the tree **after**
   layer 1.

Gradle (`app/build.gradle`) and `android/build-native.sh` apply both, in that order, and
skip what's already applied. To change an Android patch, edit the submodule with both
layers applied, then regenerate the Android patch as the difference from layer 1 only
(for example in a scratch copy where layer 1 is committed).

## Building

### GitHub Actions (what the releases use)

`.github/workflows/android.yml` runs on every push to `android-port` and on demand. The
APK is in the run's **Artifacts** as `BanjoTooieRecompiled-Android-arm64-v8a`.

For a stable signature (so each build installs over the last one and keeps the player's
saves in app storage), add these repository secrets:
- `ANDROID_KEYSTORE_BASE64`: the keystore, base64-encoded. In PowerShell:
  `[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\keys\release.jks")) | Set-Clipboard`
- `ANDROID_KEYSTORE_PASSWORD`
- optionally `ANDROID_KEY_ALIAS` (default `dk64`, the alias of the keystore shared with the
  DK64 port) and `ANDROID_KEY_PASSWORD` (default: the keystore password)

Without them the APK is signed with a throwaway debug key. The workflow checks that the
APK carries the release certificate and prints its SHA-256.

### Android Studio or the command line

Install the **NDK 30.0.16248370**, **CMake 4.1.2** and the **Android 37** SDK platform,
then:

```
git clone --recurse-submodules -b android-port https://github.com/Bownz/Banjo-Tooie-Recompiled-Android-DS.git btrecomp
cd btrecomp
./gradlew assembleRelease
```

On Windows, clone into a short path and enable long paths
(`git config --global core.longpaths true`). The APK is
`app/build/outputs/apk/release/app-release.apk`. Release signing for local builds comes
from `BT_KEYSTORE_FILE`, `BT_KEYSTORE_PASSWORD`, `BT_KEY_ALIAS` and `BT_KEY_PASSWORD` in
`~/.gradle/gradle.properties` or the environment.

### Native libraries only

```bash
ANDROID_NDK_HOME=/path/to/android-ndk-r30 android/build-native.sh
```

does the same preparation as Gradle and leaves stripped `libmain.so`, `libSDL2.so` and
`libc++_shared.so` in `build/android-libs/arm64-v8a/`. Needs `cmake`, `ninja` and `git`.

## On the device

- **First launch** asks where to keep saves, settings, mods and the ROM: a shared
  `BanjoTooieRecompiled` folder (reachable from a PC, kept on uninstall; needs "All files
  access" on Android 11+) or app storage (`Android/data/io.github.bownz.btrecomp/files`).
  Settings > Gameplay > Game Files Location moves them later.
- **Select ROM** in the launcher opens Android's file picker. Use the US Banjo-Tooie ROM.
- **Graphics driver**: Settings > Video > Graphics Driver (main menu only; the app restarts).
- **Logs**: `adb logcat -s BTRecomp`, or Settings > Gameplay > Share Log. After a crash the
  next start offers to share the log.
