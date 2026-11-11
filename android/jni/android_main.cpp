// Android entry point for Banjo-Tooie: Recompiled.
//
// SDLActivity loads libmain.so and calls SDL_main() on its own native thread.
// This shim prepares the environment the desktop code expects, then calls the
// unmodified desktop main() (renamed to bt_main at compile time, see
// android/cmake/android.cmake).

#include <SDL.h>
#include <SDL_system.h>
#include <android/log.h>
#include <jni.h>
#include <sys/stat.h>

#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <string>
#include <thread>
#include <unistd.h>
#include <sched.h>
#include <fstream>
#include <vector>

int bt_main(int argc, char** argv);

namespace {
    constexpr const char* log_tag = "BTRecomp";

    // Forward stdout/stderr to logcat so the runtime's printf diagnostics show up in
    // `adb logcat -s BTRecomp`.
    void redirect_stdio_to_logcat() {
        static int pipe_fds[2];
        setvbuf(stdout, nullptr, _IOLBF, 0);
        setvbuf(stderr, nullptr, _IONBF, 0);
        if (pipe(pipe_fds) != 0) {
            return;
        }
        dup2(pipe_fds[1], STDOUT_FILENO);
        dup2(pipe_fds[1], STDERR_FILENO);

        // Also keep this session's output in <internal>/logs/session.txt, so testers can share
        // it from the app (Diagnostics.java rotates the previous one before the game starts).
        FILE* session_log = nullptr;
        if (const char* internal = SDL_AndroidGetInternalStoragePath()) {
            std::string dir = std::string{internal} + "/logs";
            mkdir(dir.c_str(), 0700);
            session_log = fopen((dir + "/session.txt").c_str(), "a");
        }

        std::thread([session_log] {
            char buffer[1024];
            ssize_t size;
            while ((size = read(pipe_fds[0], buffer, sizeof(buffer) - 1)) > 0) {
                if (session_log != nullptr) {
                    fwrite(buffer, 1, size_t(size), session_log);
                    fflush(session_log);
                }
                if (buffer[size - 1] == '\n') {
                    size--;
                }
                buffer[size] = '\0';
                __android_log_write(ANDROID_LOG_INFO, log_tag, buffer);
            }
        }).detach();
    }

    // Asks BTActivity.getDataDirectory() where saves, config, mods and the ROM go. On
    // first launch that shows the storage choice (and any permission prompt) and blocks
    // until the user answers, which is why it runs before the game creates its window.
    std::string data_directory_from_activity() {
        JNIEnv* env = static_cast<JNIEnv*>(SDL_AndroidGetJNIEnv());
        jobject activity = static_cast<jobject>(SDL_AndroidGetActivity());
        if (env == nullptr || activity == nullptr) {
            return {};
        }

        std::string result;
        jclass activity_class = env->GetObjectClass(activity);
        jmethodID method = env->GetMethodID(activity_class, "getDataDirectory", "()Ljava/lang/String;");
        if (method == nullptr) {
            env->ExceptionClear();
        }
        else {
            auto path = static_cast<jstring>(env->CallObjectMethod(activity, method));
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
            }
            else if (path != nullptr) {
                const char* chars = env->GetStringUTFChars(path, nullptr);
                result = chars;
                env->ReleaseStringUTFChars(path, chars);
                env->DeleteLocalRef(path);
            }
        }
        env->DeleteLocalRef(activity_class);
        env->DeleteLocalRef(activity);
        return result;
    }
}

extern "C" void bt_android_game_closing(); // gpu_driver.cpp

// Keeps the game off the slowest CPU cores. Phones and handhelds mix fast cores with slow,
// power-saving ones (the Thor's Snapdragon: 1 prime + 4 performance + 3 efficiency), and a
// game thread scheduled onto an efficiency core stalls the frame. Set on this thread before
// the game starts, the mask is inherited by every thread it creates (game, renderer, audio).
static void prefer_fast_cores() {
    const long count = sysconf(_SC_NPROCESSORS_CONF);
    if (count <= 1 || count > CPU_SETSIZE) {
        return;
    }
    std::vector<long> max_freq(count, 0);
    long slowest = 0, fastest = 0;
    for (long i = 0; i < count; i++) {
        std::ifstream f("/sys/devices/system/cpu/cpu" + std::to_string(i) + "/cpufreq/cpuinfo_max_freq");
        long khz = 0;
        if (f >> khz) {
            max_freq[i] = khz;
            slowest = slowest == 0 ? khz : std::min(slowest, khz);
            fastest = std::max(fastest, khz);
        }
    }
    if (slowest == 0 || slowest == fastest) {
        return; // unknown, or all cores alike
    }
    cpu_set_t set;
    CPU_ZERO(&set);
    int chosen = 0;
    for (long i = 0; i < count; i++) {
        if (max_freq[i] > slowest) {
            CPU_SET(i, &set);
            chosen++;
        }
    }
    if (chosen >= 2 && sched_setaffinity(0, sizeof(set), &set) == 0) {
        __android_log_print(ANDROID_LOG_INFO, "BTRecomp", "Running on the %d faster CPU cores (of %ld)", chosen, count);
    }
}

extern "C" int SDL_main(int argc, char* argv[]) {
    redirect_stdio_to_logcat();

    // Internal storage: private to the app. BTActivity extracts the UI assets (fonts,
    // stylesheet, icons, wallpaper) from the APK into <internal>/game.
    const char* internal_path = SDL_AndroidGetInternalStoragePath();
    // User data: either a shared "BanjoTooieRecompiled" folder (with the storage permission) or
    // the app's own external folder (Android/data/<package>/files). Both are reachable over
    // USB and in file managers. BTActivity decides; this is the fallback.
    const std::string chosen_data_path = data_directory_from_activity();
    const char* external_path = SDL_AndroidGetExternalStoragePath();

    if (internal_path == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, log_tag, "No internal storage path: %s", SDL_GetError());
        return EXIT_FAILURE;
    }

    const std::filesystem::path game_dir = std::filesystem::path{internal_path} / "game";
    const std::filesystem::path data_dir = !chosen_data_path.empty() ? std::filesystem::path{chosen_data_path}
        : external_path != nullptr ? std::filesystem::path{external_path}
        : std::filesystem::path{internal_path} / "data";

    std::error_code ec;
    std::filesystem::create_directories(data_dir, ec);

    // recompui::file::get_app_folder_path() honours APP_FOLDER_PATH on Linux-like
    // platforms (Android defines __linux__), and RT64's user paths fall back to HOME.
    setenv("APP_FOLDER_PATH", data_dir.c_str(), 1);
    setenv("HOME", data_dir.c_str(), 1);
    setenv("XDG_CONFIG_HOME", (data_dir / ".config").c_str(), 1);
    setenv("XDG_DATA_HOME", (data_dir / ".local" / "share").c_str(), 1);

    // The UI loads "assets/..." relative to the working directory, exactly like the desktop
    // build (on Linux, run.sh changes to the executable's folder for the same reason).
    if (chdir(game_dir.c_str()) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, log_tag, "Could not enter %s", game_dir.c_str());
        return EXIT_FAILURE;
    }

    // Prefer AAudio, Android's current audio API, over SDL's default OpenSL ES. On an AYN Thor
    // the OpenSL ES player was stopped and restarted ~80 times a second, flooding the system
    // audio and Bluetooth services. OpenSL ES stays as the fallback. An explicit
    // SDL_AUDIODRIVER in the environment still wins.
    SDL_SetHintWithPriority(SDL_HINT_AUDIODRIVER, "aaudio,openslES", SDL_HINT_DEFAULT);

    __android_log_print(ANDROID_LOG_INFO, log_tag, "Assets: %s", game_dir.c_str());
    __android_log_print(ANDROID_LOG_INFO, log_tag, "User data: %s", data_dir.c_str());

    prefer_fast_cores();
    const int result = bt_main(argc, argv);
    bt_android_game_closing();
    return result;
}
