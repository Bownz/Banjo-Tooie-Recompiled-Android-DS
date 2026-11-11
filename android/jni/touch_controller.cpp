// Native side of the on-screen touch controller.
//
// The Java overlay (TouchControllerView) reports button and stick state through the
// JNI functions below. They drive an SDL *virtual gamepad*, so the game sees an ordinary
// controller: recompinput's default bindings, its remapping menu and its single-player
// "all controllers drive player 1" behaviour all apply to touch input unchanged.
//
// Button and axis numbers are SDL_GameControllerButton / SDL_GameControllerAxis values.
// With every button and axis declared and no masks set, SDL maps virtual joystick
// button i to gamepad button i and axes in their standard order.
//
// Calls arrive on Android's UI thread while the game runs SDL on its own thread; the
// SDL virtual-joystick functions take SDL's joystick lock, and the mutex here serialises
// attach/detach against state updates.

#include <SDL.h>
#include <android/log.h>
#include <jni.h>

#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>


namespace {
    // N64 buttons (recompinput bit values) held by the on-screen buttons. These go straight
    // into the game's input, so they work whatever the controller mappings are (e.g. with C
    // unmapped from the physical pad).
    std::atomic<uint16_t> direct_n64_buttons{0};

    constexpr const char* log_tag = "BTRecomp";

    std::mutex state_mutex;
    bool wanted = false;               // Should the virtual pad exist right now?
    SDL_Joystick* joystick = nullptr;  // Open handle while it exists.

    void detach_locked() {
        if (joystick == nullptr) {
            return;
        }
        const SDL_JoystickID id = SDL_JoystickInstanceID(joystick);
        SDL_JoystickClose(joystick);
        joystick = nullptr;

        for (int i = 0; i < SDL_NumJoysticks(); i++) {
            if (SDL_JoystickGetDeviceInstanceID(i) == id) {
                SDL_JoystickDetachVirtual(i);
                break;
            }
        }
        __android_log_write(ANDROID_LOG_INFO, log_tag, "Touch controller detached");
    }

    // Brings the virtual pad in line with `wanted`. It can only exist once the game has
    // initialised SDL's joystick subsystem, so this is retried on every update.
    bool sync_locked() {
        if (!SDL_WasInit(SDL_INIT_JOYSTICK)) {
            joystick = nullptr; // SDL was shut down (or not started); any handle is gone.
            return false;
        }

        if (!wanted) {
            detach_locked();
            return false;
        }

        if (joystick != nullptr) {
            return true;
        }

        SDL_VirtualJoystickDesc desc;
        SDL_zero(desc);
        desc.version = SDL_VIRTUAL_JOYSTICK_DESC_VERSION;
        desc.type = SDL_JOYSTICK_TYPE_GAMECONTROLLER;
        desc.naxes = SDL_CONTROLLER_AXIS_MAX;
        desc.nbuttons = SDL_CONTROLLER_BUTTON_MAX;
        desc.name = "Touch Controller";

        const int device_index = SDL_JoystickAttachVirtualEx(&desc);
        if (device_index < 0) {
            __android_log_print(ANDROID_LOG_ERROR, log_tag, "Touch controller attach failed: %s", SDL_GetError());
            return false;
        }

        joystick = SDL_JoystickOpen(device_index);
        if (joystick == nullptr) {
            __android_log_print(ANDROID_LOG_ERROR, log_tag, "Touch controller open failed: %s", SDL_GetError());
            SDL_JoystickDetachVirtual(device_index);
            return false;
        }

        __android_log_write(ANDROID_LOG_INFO, log_tag, "Touch controller attached");
        return true;
    }

    Sint16 to_axis_value(int axis, float value) {
        if (axis == SDL_CONTROLLER_AXIS_TRIGGERLEFT || axis == SDL_CONTROLLER_AXIS_TRIGGERRIGHT) {
            value = SDL_clamp(value, 0.0f, 1.0f);
            return static_cast<Sint16>(value * SDL_JOYSTICK_AXIS_MAX);
        }
        value = SDL_clamp(value, -1.0f, 1.0f);
        return static_cast<Sint16>(value < 0.0f ? value * -SDL_JOYSTICK_AXIS_MIN : value * SDL_JOYSTICK_AXIS_MAX);
    }
}

extern "C" {

JNIEXPORT void JNICALL
Java_io_github_bownz_btrecomp_TouchInput_nativeSetEnabled(JNIEnv*, jclass, jboolean enabled) {
    std::lock_guard lock{state_mutex};
    wanted = enabled;
    sync_locked();
}

JNIEXPORT void JNICALL
Java_io_github_bownz_btrecomp_TouchInput_nativeSetButton(JNIEnv*, jclass, jint button, jboolean pressed) {
    if (button < 0 || button >= SDL_CONTROLLER_BUTTON_MAX) {
        return;
    }
    std::lock_guard lock{state_mutex};
    if (sync_locked()) {
        SDL_JoystickSetVirtualButton(joystick, button, pressed ? SDL_PRESSED : SDL_RELEASED);
    }
}

JNIEXPORT void JNICALL
Java_io_github_bownz_btrecomp_TouchInput_nativeSetAxis(JNIEnv*, jclass, jint axis, jfloat value) {
    if (axis < 0 || axis >= SDL_CONTROLLER_AXIS_MAX) {
        return;
    }
    std::lock_guard lock{state_mutex};
    if (sync_locked()) {
        SDL_JoystickSetVirtualAxis(joystick, axis, to_axis_value(axis, value));
    }
}

// --- Buttons straight into the game ------------------------------------------------------

// Read by recompinput's get_n64_input (RecompFrontend Android patch) for player 1.
uint16_t bt_android_touch_n64_buttons() {
    return direct_n64_buttons.load(std::memory_order_relaxed);
}

JNIEXPORT void JNICALL
Java_io_github_bownz_btrecomp_TouchInput_nativeSetN64Buttons(JNIEnv*, jclass, jint buttons) {
    direct_n64_buttons.store(static_cast<uint16_t>(buttons), std::memory_order_relaxed);
}

} // extern "C"

// ---- Calls from the game's settings into the activity ------------------------------------

namespace {
    void call_activity(const char* name, const char* arg) {
        JNIEnv* env = static_cast<JNIEnv*>(SDL_AndroidGetJNIEnv());
        jobject activity = static_cast<jobject>(SDL_AndroidGetActivity());
        if (env == nullptr || activity == nullptr) {
            return;
        }
        jclass activity_class = env->GetObjectClass(activity);
        jmethodID method = env->GetMethodID(activity_class, name, "(Ljava/lang/String;)Ljava/lang/String;");
        if (method == nullptr) {
            env->ExceptionClear();
        } else {
            jstring jarg = env->NewStringUTF(arg);
            jobject result = env->CallObjectMethod(activity, method, jarg);
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
            }
            if (result != nullptr) {
                env->DeleteLocalRef(result);
            }
            env->DeleteLocalRef(jarg);
        }
        env->DeleteLocalRef(activity_class);
        env->DeleteLocalRef(activity);
    }
}

// Input > Touch > Touch Controls: 0 auto (only without a controller), 1 always, 2 never.
extern "C" void bt_android_set_touch_controls_mode(int mode) {
    const std::string arg = std::to_string(mode);
    call_activity("touchControlsMode", arg.c_str());
}

// Input > Touch > Edit Touch Layout: the layout editor (TouchControllerView).
extern "C" void bt_android_edit_touch_layout() {
    call_activity("editTouchLayout", "");
}

// Input > Dual Screen > Bottom Screen: shows or hides the second-screen view.
extern "C" void bt_android_set_bottom_screen(int enabled) {
    call_activity("setBottomScreen", enabled ? "1" : "0");
}
