// Optional custom Vulkan driver (e.g. Mesa Turnip for Adreno) for the renderer.
//
// The player installs a driver package in the app (GpuDriverActivity / GpuDrivers.java), which
// unpacks it into the app's private files. When the renderer starts, plume asks
// bt_android_vulkan_loader() (plume Android patch) for a vkGetInstanceProcAddr; this
// loads the chosen driver through libadrenotools, which redirects the system Vulkan loader
// to it. With no driver chosen, or if loading fails, it returns null and the system driver
// is used as usual.

#include <SDL.h>
#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>

#include <atomic>
#include <string>
#include <vector>

#include "gpu_driver.h"

#if BT_HAVE_ADRENOTOOLS
#include <adrenotools/driver.h>
#endif

namespace {
    constexpr const char* log_tag = "BTRecomp";
    std::string active_driver = "System driver";

    // Calls a BTActivity method taking/returning strings (nullptr arg: no argument).
    std::string call_activity(const char* name, const char* signature, const std::string* arg) {
        std::string result;
        JNIEnv* env = static_cast<JNIEnv*>(SDL_AndroidGetJNIEnv());
        jobject activity = static_cast<jobject>(SDL_AndroidGetActivity());
        if (env == nullptr || activity == nullptr) {
            return result;
        }
        jclass activity_class = env->GetObjectClass(activity);
        jmethodID method = env->GetMethodID(activity_class, name, signature);
        if (method == nullptr) {
            env->ExceptionClear();
        }
        else {
            jstring jarg = arg != nullptr ? env->NewStringUTF(arg->c_str()) : nullptr;
            jobject value = arg != nullptr ? env->CallObjectMethod(activity, method, jarg) : env->CallObjectMethod(activity, method);
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
            }
            else if (value != nullptr) {
                const char* chars = env->GetStringUTFChars(static_cast<jstring>(value), nullptr);
                result = chars;
                env->ReleaseStringUTFChars(static_cast<jstring>(value), chars);
                env->DeleteLocalRef(value);
            }
            if (jarg != nullptr) {
                env->DeleteLocalRef(jarg);
            }
        }
        env->DeleteLocalRef(activity_class);
        env->DeleteLocalRef(activity);
        return result;
    }

    std::vector<std::string> split(const std::string& all, char sep) {
        std::vector<std::string> parts;
        size_t start = 0;
        while (start <= all.size()) {
            size_t end = all.find(sep, start);
            if (end == std::string::npos) {
                end = all.size();
            }
            parts.emplace_back(all.substr(start, end - start));
            start = end + 1;
        }
        return parts;
    }

    // Asks BTActivity.getGpuDriver(): null for the system driver, otherwise four lines:
    // driver directory, driver library name, the app's native library directory (where the
    // adrenotools hooks are) and a writable temporary directory.
    std::vector<std::string> driver_from_activity() {
        std::vector<std::string> parts;
        JNIEnv* env = static_cast<JNIEnv*>(SDL_AndroidGetJNIEnv());
        jobject activity = static_cast<jobject>(SDL_AndroidGetActivity());
        if (env == nullptr || activity == nullptr) {
            return parts;
        }
        jclass activity_class = env->GetObjectClass(activity);
        jmethodID method = env->GetMethodID(activity_class, "getGpuDriver", "()Ljava/lang/String;");
        if (method == nullptr) {
            env->ExceptionClear();
        }
        else {
            auto value = static_cast<jstring>(env->CallObjectMethod(activity, method));
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
            }
            else if (value != nullptr) {
                const char* chars = env->GetStringUTFChars(value, nullptr);
                std::string all = chars;
                env->ReleaseStringUTFChars(value, chars);
                env->DeleteLocalRef(value);
                size_t start = 0;
                while (start <= all.size()) {
                    size_t end = all.find('\n', start);
                    if (end == std::string::npos) {
                        end = all.size();
                    }
                    parts.emplace_back(all.substr(start, end - start));
                    start = end + 1;
                }
            }
        }
        env->DeleteLocalRef(activity_class);
        env->DeleteLocalRef(activity);
        return parts;
    }
}

extern "C" void* bt_android_vulkan_loader() {
#if BT_HAVE_ADRENOTOOLS
    std::vector<std::string> driver = driver_from_activity();
    if (driver.size() < 4 || driver[0].empty() || driver[1].empty()) {
        return nullptr; // System driver.
    }
    std::string driver_dir = driver[0];
    if (driver_dir.back() != '/') {
        driver_dir += '/';
    }
    std::string hook_dir = driver[2];
    if (!hook_dir.empty() && hook_dir.back() != '/') {
        hook_dir += '/';
    }
    __android_log_print(ANDROID_LOG_INFO, log_tag, "GPU driver: loading %s from %s", driver[1].c_str(), driver_dir.c_str());
    void* handle = adrenotools_open_libvulkan(RTLD_NOW, ADRENOTOOLS_DRIVER_CUSTOM, driver[3].c_str(), hook_dir.c_str(),
        driver_dir.c_str(), driver[1].c_str(), nullptr, nullptr);
    if (handle == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, log_tag, "GPU driver: could not load the custom driver; using the system driver");
        return nullptr;
    }
    void* get_instance_proc_addr = dlsym(handle, "vkGetInstanceProcAddr");
    if (get_instance_proc_addr == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, log_tag, "GPU driver: custom driver has no vkGetInstanceProcAddr; using the system driver");
        return nullptr;
    }
    __android_log_print(ANDROID_LOG_INFO, log_tag, "GPU driver: custom driver loaded");
    active_driver = driver.size() > 4 && !driver[4].empty() ? driver[4] : driver[1];
    return get_instance_proc_addr;
#else
    return nullptr;
#endif
}

namespace bt::android {
    std::vector<GpuDriverChoice> list_gpu_drivers(std::string& selected_id) {
        // Lines: selected id, then "id<TAB>label" per installed driver.
        std::vector<GpuDriverChoice> choices{ { "", "System driver" } };
        std::vector<std::string> lines = split(call_activity("getGpuDriverList", "()Ljava/lang/String;", nullptr), '\n');
        selected_id = lines.empty() ? "" : lines[0];
        for (size_t i = 1; i < lines.size(); i++) {
            std::vector<std::string> fields = split(lines[i], '\t');
            if (fields.size() >= 2 && !fields[0].empty()) {
                choices.push_back({ fields[0], fields[1] });
            }
        }
        return choices;
    }

    void select_gpu_driver(const std::string& id) {
        call_activity("selectGpuDriver", "(Ljava/lang/String;)Ljava/lang/String;", &id);
    }

    void open_gpu_driver_manager() {
        call_activity("openGpuDriverManager", "()Ljava/lang/String;", nullptr);
    }

    std::string active_gpu_driver() {
        return active_driver;
    }

    void change_storage_location() {
        call_activity("changeStorageLocation", "()Ljava/lang/String;", nullptr);
    }

    std::string storage_description() {
        return call_activity("getStorageDescription", "()Ljava/lang/String;", nullptr);
    }

    void share_log() {
        call_activity("shareLog", "()Ljava/lang/String;", nullptr);
    }

    std::string app_version() {
        return call_activity("getAppVersion", "()Ljava/lang/String;", nullptr);
    }

}

// Whether the app is in front (BTActivity onResume/onPause), for the audio watchdog
// (src/main.cpp): Android pauses audio in the background on purpose.
static std::atomic<int> app_in_foreground{1};

extern "C" int bt_android_app_in_foreground() {
    return app_in_foreground.load(std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_bownz_btrecomp_BTActivity_nativeSetForeground(JNIEnv*, jclass, jboolean foreground) {
    app_in_foreground.store(foreground ? 1 : 0, std::memory_order_relaxed);
}

// The game is shutting down (quit from its menu, or SDL told to quit): tell BTActivity, so
// a crash while closing isn't offered as a crash report on the next start.
namespace {
    int on_sdl_event(void*, SDL_Event* event) {
        if (event->type == SDL_QUIT || event->type == SDL_APP_TERMINATING) {
            call_activity("gameClosing", "()Ljava/lang/String;", nullptr);
        }
        return 0;
    }
}

extern "C" void bt_android_game_closing() {
    call_activity("gameClosing", "()Ljava/lang/String;", nullptr);
}

// Called once after SDL_Init (src/main.cpp).
extern "C" void bt_android_watch_quit() {
    SDL_AddEventWatch(on_sdl_event, nullptr);
}

// "1" if the game crashed or froze last time (the activity checks before the game starts).
extern "C" int bt_android_last_run_crashed() {
    return call_activity("lastRunCrashed", "()Ljava/lang/String;", nullptr) == "1" ? 1 : 0;
}

// QUIT > Main Menu (RecompFrontend Android patch): restart the app on the launcher.
extern "C" void bt_android_restart_to_launcher() {
    call_activity("restartToLauncher", "()Ljava/lang/String;", nullptr);
}
