// Android-only settings and control presets (built only on Android: android/cmake/android.cmake).
//
// - The settings tabs: Video, Input, Audio and Gameplay, plus Configure Inputs and Mods. The
//   General config still exists (and keeps its file) but its options are shown on the Input
//   tab, and the touch settings live in a hidden "touch" config shown there too.
// - Controller defaults that put each N64 button on the controller button with the same
//   letter, and the Classic / Modern presets (Configure Inputs' footer, Gameplay >
//   Experience, and the first-launch question).
// - The state the on-screen controls poll (TouchInput.nativeUiState) and their settings.

#include <atomic>
#include <cstdio>
#include <variant>
#include <filesystem>
#include <fstream>
#include <string>
#include <vector>

#include <jni.h>

#include "SDL2/SDL.h"

#include "librecomp/config.hpp"
#include "librecomp/game.hpp"
#include "recompinput/input_mapping.h"
#include "recompinput/profiles.h"
#include "recompinput/recompinput.h"
#include "recompui/config.h"
#include "recompui/recompui.h"
#include "ultramodern/config.hpp"
#include "ultramodern/ultramodern.hpp"

#include "../android/jni/gpu_driver.h"
#include "android_config.hpp"

extern "C" void bt_android_set_touch_controls_mode(int mode);   // android/jni/touch_controller.cpp
extern "C" void bt_android_edit_touch_layout();                  // android/jni/touch_controller.cpp
extern "C" void bt_android_set_bottom_screen(int enabled);        // android/jni/touch_controller.cpp
extern "C" void rt64_android_set_ram_copy_mode(int mode);  // rt64-android.patch
extern "C" void rt64_android_set_auto_framerate(int enabled);  // rt64-android.patch
extern "C" void bt_android_set_live_log(int on);              // android/jni/perf_monitor.cpp


namespace {
    using EnumOptionVector = const std::vector<recomp::config::ConfigOptionEnumOption>;

    const std::string touch_config_id = "touch";
    const std::string experience_option = "android_experience";

    std::atomic<bool> drag_camera{ true };
    std::atomic<int> drag_camera_speed{ 3 };
    std::atomic<int> touch_feedback{ 2 };   // 0 off, 1 light, 2 medium, 3 strong
    std::atomic<int> bottom_buttons{ 1 };   // 0 off, 1 always, 2 slide in while Z is held
    std::atomic<bool> show_fps{ false };

    // Video > Frame Copy (test): passed to RT64 (rt64-android.patch).
    void apply_frame_copy(uint32_t mode) {
        rt64_android_set_ram_copy_mode(static_cast<int>(mode <= 2u ? mode : 0u));
        std::printf("Frame Copy: %s\n", mode == 1u ? "Wait" : mode == 2u ? "Off" : "Late");
        std::fflush(stdout);
    }
    // Video > Live Debug Log: a once-a-second status line for adb logcat (perf_monitor.cpp).
    void apply_live_log(uint32_t mode) {
        bt_android_set_live_log(mode == 1u ? 1 : 0);
    }

    // Video > 60 FPS Mode: Auto drops to a steady 30 while the device can't keep 60.
    void apply_auto_framerate(uint32_t mode) {
        rt64_android_set_auto_framerate(mode == 0u ? 1 : 0);
        std::printf("60 FPS Mode: %s\n", mode == 0u ? "Auto" : "Always");
        std::fflush(stdout);
    }
    std::atomic<bool> cutscene_dim{ true };

    std::filesystem::path config_file(const char* name) {
        return recomp::get_config_path() / name;
    }

    void save_touch_config(recomp::config::OptionChangeContext context) {
        if (context == recomp::config::OptionChangeContext::Permanent) {
            recompui::config::get_config(touch_config_id).save_config();
        }
    }

    // Input > Touch.
    void add_touch_options(recomp::config::Config& config) {
        static EnumOptionVector touch_controls_options = {
            {0u, "Auto", "Auto"},
            {1u, "On", "On"},
            {2u, "Off", "Off"},
        };
        config.add_enum_option(
            "android_touch_controls",
            "Touch Controls",
            "On-screen buttons over the game. <b>Auto</b> shows them only when no controller is connected.",
            touch_controls_options,
            0u
        );
        config.add_option_change_callback("android_touch_controls",
            [](recomp::config::ConfigValueVariant cur, recomp::config::ConfigValueVariant, recomp::config::OptionChangeContext context) {
                if (context != recomp::config::OptionChangeContext::Temporary) {
                    bt_android_set_touch_controls_mode(static_cast<int>(std::get<uint32_t>(cur)));
                }
                save_touch_config(context);
            });

        static EnumOptionVector on_off = {
            {0u, "Off", "Off"},
            {1u, "On", "On"},
        };
        config.add_enum_option(
            "android_drag_camera",
            "Drag to Move Camera",
            "With the touch controls on screen, drag a finger anywhere away from the buttons to turn the "
            "camera left and right (it turns the way C-Left and C-Right do).",
            on_off,
            1u
        );
        config.add_option_change_callback("android_drag_camera",
            [](recomp::config::ConfigValueVariant cur, recomp::config::ConfigValueVariant, recomp::config::OptionChangeContext context) {
                drag_camera.store(std::get<uint32_t>(cur) == 1u);
                save_touch_config(context);
            });

        static EnumOptionVector speed_options = {
            {1u, "Slowest", "Slowest"},
            {2u, "Slow", "Slow"},
            {3u, "Normal", "Normal"},
            {4u, "Fast", "Fast"},
            {5u, "Fastest", "Fastest"},
        };
        config.add_enum_option(
            "android_drag_camera_speed",
            "Camera Drag Speed",
            "How far a finger has to move to turn the camera one step.",
            speed_options,
            3u
        );
        config.add_option_change_callback("android_drag_camera_speed",
            [](recomp::config::ConfigValueVariant cur, recomp::config::ConfigValueVariant, recomp::config::OptionChangeContext context) {
                drag_camera_speed.store(static_cast<int>(std::get<uint32_t>(cur)));
                save_touch_config(context);
            });

        static EnumOptionVector feedback_options = {
            {0u, "Off", "Off"},
            {1u, "Light", "Light"},
            {2u, "Medium", "Medium"},
            {3u, "Strong", "Strong"},
        };
        config.add_enum_option(
            "android_touch_feedback",
            "Touch Feedback",
            "A short vibration when you press a touch button, and how strong it is.",
            feedback_options,
            2u
        );
        config.add_option_change_callback("android_touch_feedback",
            [](recomp::config::ConfigValueVariant cur, recomp::config::ConfigValueVariant, recomp::config::OptionChangeContext context) {
                touch_feedback.store(static_cast<int>(std::get<uint32_t>(cur)));
                save_touch_config(context);
            });

        // Dual Screen (a second display, such as the AYN Thor's lower screen).
        config.add_enum_option(
            "android_bottom_screen",
            "Bottom Screen",
            "On a device with a second screen (like the AYN Thor), show the progress in the world you're in "
            "(Jiggies, notes and the rest) and the Z + C move buttons there.",
            on_off,
            1u
        );
        config.add_option_change_callback("android_bottom_screen",
            [](recomp::config::ConfigValueVariant cur, recomp::config::ConfigValueVariant, recomp::config::OptionChangeContext context) {
                if (context != recomp::config::OptionChangeContext::Temporary) {
                    bt_android_set_bottom_screen(std::get<uint32_t>(cur) == 1u ? 1 : 0);
                }
                save_touch_config(context);
            });
        static EnumOptionVector button_modes = {
            {0u, "Off", "Off"},
            {1u, "Always", "Always"},
            {2u, "HoldZ", "Hold Z"},
        };
        config.add_enum_option(
            "android_bottom_buttons",
            "Bottom Screen Buttons",
            "Buttons on the bottom screen for the moves done with Z held and a C button: firing and laying "
            "eggs, Talon Trot and Wonderwing. Always: shown all the time. Hold Z: hidden until you hold Z, "
            "then they slide in from the right, and slide out when you let go.",
            button_modes,
            1u
        );
        config.add_option_change_callback("android_bottom_buttons",
            [](recomp::config::ConfigValueVariant cur, recomp::config::ConfigValueVariant, recomp::config::OptionChangeContext context) {
                bottom_buttons.store(static_cast<int>(std::get<uint32_t>(cur)));
                save_touch_config(context);
            });
        config.add_enum_option(
            "android_cutscene_dim",
            "Dim During Cutscenes",
            "The bottom screen dims while a cutscene plays, and comes back when it ends.",
            on_off,
            1u
        );
        config.add_option_change_callback("android_cutscene_dim",
            [](recomp::config::ConfigValueVariant cur, recomp::config::ConfigValueVariant, recomp::config::OptionChangeContext context) {
                cutscene_dim.store(std::get<uint32_t>(cur) == 1u);
                save_touch_config(context);
            });
    }

    // Each N64 button goes on the controller button with the same letter, so the game's own
    // button prompts read naturally:
    //   A -> A (jump), B -> B (attack), Z -> left trigger (crouch; Z + C for Kazooie's moves),
    //   L -> left shoulder (unused, for mods), R -> right shoulder and right trigger,
    //   Start -> Start, D-pad -> D-pad,
    //   C buttons -> the right stick (C-Left/Right turn the camera, C-Up looks around / aims eggs,
    //   C-Down zooms), plus X = C-Up and Y = C-Left (Classic).
    // Menus: A accepts, B backs out.
    void set_control_defaults() {
        using namespace recompinput;
        set_default_mapping_for_controller(GameInput::A, { InputField::controller_digital(SDL_CONTROLLER_BUTTON_A) });
        set_default_mapping_for_controller(GameInput::B, { InputField::controller_digital(SDL_CONTROLLER_BUTTON_B) });
        set_default_mapping_for_controller(GameInput::Z, { InputField::controller_analog(SDL_CONTROLLER_AXIS_TRIGGERLEFT, true) });
        set_default_mapping_for_controller(GameInput::R, {
            InputField::controller_digital(SDL_CONTROLLER_BUTTON_RIGHTSHOULDER),
            InputField::controller_analog(SDL_CONTROLLER_AXIS_TRIGGERRIGHT, true) });
        set_default_mapping_for_controller(GameInput::L, { InputField::controller_digital(SDL_CONTROLLER_BUTTON_LEFTSHOULDER) });
        set_default_mapping_for_controller(GameInput::START, { InputField::controller_digital(SDL_CONTROLLER_BUTTON_START) });
        set_default_mapping_for_controller(GameInput::C_UP, {
            InputField::controller_analog(SDL_CONTROLLER_AXIS_RIGHTY, false),
            InputField::controller_digital(SDL_CONTROLLER_BUTTON_X) });
        set_default_mapping_for_controller(GameInput::C_LEFT, {
            InputField::controller_analog(SDL_CONTROLLER_AXIS_RIGHTX, false),
            InputField::controller_digital(SDL_CONTROLLER_BUTTON_Y) });
        set_default_mapping_for_controller(GameInput::C_DOWN, { InputField::controller_analog(SDL_CONTROLLER_AXIS_RIGHTY, true) });
        set_default_mapping_for_controller(GameInput::C_RIGHT, { InputField::controller_analog(SDL_CONTROLLER_AXIS_RIGHTX, true) });
        set_default_mapping_for_controller(GameInput::DPAD_UP, { InputField::controller_digital(SDL_CONTROLLER_BUTTON_DPAD_UP) });
        set_default_mapping_for_controller(GameInput::DPAD_DOWN, { InputField::controller_digital(SDL_CONTROLLER_BUTTON_DPAD_DOWN) });
        set_default_mapping_for_controller(GameInput::DPAD_LEFT, { InputField::controller_digital(SDL_CONTROLLER_BUTTON_DPAD_LEFT) });
        set_default_mapping_for_controller(GameInput::DPAD_RIGHT, { InputField::controller_digital(SDL_CONTROLLER_BUTTON_DPAD_RIGHT) });
        set_default_mapping_for_controller(GameInput::ACCEPT_MENU, { InputField::controller_digital(SDL_CONTROLLER_BUTTON_A) });
        set_default_mapping_for_controller(GameInput::BACK_MENU, { InputField::controller_digital(SDL_CONTROLLER_BUTTON_B) });
    }

    void set_control_descriptions() {
        using recompinput::GameInput;
        recompinput::set_game_input_description(GameInput::A, "Jump, and select options in menus.");
        recompinput::set_game_input_description(GameInput::B, "Attack. Changes with movement: rolls, the Rat-a-tat Rap in the air, Beak Barge while crouching.");
        recompinput::set_game_input_description(GameInput::Z, "Crouch. With Z held, A, B and the C buttons do Kazooie's moves (Flap Flip, Beak Barge, eggs, Talon Trot...).");
        recompinput::set_game_input_description(GameInput::L, "Unused by the game. Mods may use it.");
        recompinput::set_game_input_description(GameInput::R, "Centre the camera behind the player; Shock Spring Jump and other pad moves.");
        recompinput::set_game_input_description(GameInput::START, "Pause, and skip some cutscenes.");
        recompinput::set_game_input_description(GameInput::C_UP, "Look around in first person, and aim eggs.");
        recompinput::set_game_input_description(GameInput::C_DOWN, "Change the camera's distance; with Z, lay eggs behind.");
        recompinput::set_game_input_description(GameInput::C_LEFT, "Turn the camera; with Z, Talon Trot and other moves.");
        recompinput::set_game_input_description(GameInput::C_RIGHT, "Turn the camera; with Z, Wonderwing and other moves.");
    }

    // First launch: Classic or Modern? (Gameplay > Experience later.)
    void ask_for_experience() {
        if (bt_android_experience_chosen()) {
            return;
        }
        recompui::open_choice_prompt(
            "How do you want to play?",
            "Modern: the right stick turns the camera (press it in to look around in first person), with "
            "sharp full-resolution widescreen graphics and anti-aliasing. "
            "Classic: the N64 as it was, with the original controls and picture. "
            "You can change this, or any single option, in Settings.",
            "Modern",
            "Classic",
            []() { bt_android_apply_experience(1); },
            []() { bt_android_apply_experience(0); },
            recompui::ButtonStyle::Primary,
            recompui::ButtonStyle::Secondary,
            false
        );
    }

    bool applying_experience = false;
}

// ---- Control presets ------------------------------------------------------------------------
//
// Classic: the N64 way. The right stick works the C buttons (all four directions); X is C-Up
//          and Y is C-Left.
// Modern:  the right stick still turns and zooms the camera (C-Left/Right/Down), but C-Up
//          (first person) moves to pressing the stick in, so pushing the camera up doesn't
//          drop into first person. X and Y are left free.
// Chosen on first launch and from the Configure Inputs footer (RecompFrontend patch).

extern "C" int bt_android_control_preset() {
    std::ifstream in{ config_file("controls_preset.txt") };
    std::string value;
    if (!(in >> value)) {
        return -1;   // not chosen yet
    }
    return value == "modern" ? 1 : 0;
}

extern "C" void bt_android_apply_control_preset(int modern) {
    using namespace recompinput;
    const int profile = profiles::get_sp_controller_profile_index();
    if (profile >= 0) {
        profiles::reset_profile_bindings(profile, InputDevice::Controller);
        if (modern) {
            for (size_t b = 0; b < num_bindings_per_input; b++) {
                if (profiles::get_input_binding(profile, GameInput::C_UP, b).input_type == InputType::ControllerAnalog) {
                    profiles::set_input_binding(profile, GameInput::C_UP, b, InputField::controller_digital(SDL_CONTROLLER_BUTTON_RIGHTSTICK));
                }
            }
            const InputField x = InputField::controller_digital(SDL_CONTROLLER_BUTTON_X);
            const InputField y = InputField::controller_digital(SDL_CONTROLLER_BUTTON_Y);
            for (int i = 0; i < static_cast<int>(GameInput::COUNT); i++) {
                const GameInput input = static_cast<GameInput>(i);
                if (get_game_input_is_menu(input)) {
                    continue;
                }
                for (size_t b = 0; b < num_bindings_per_input; b++) {
                    const InputField field = profiles::get_input_binding(profile, input, b);
                    if (field == x || field == y) {
                        profiles::set_input_binding(profile, input, b, InputField{});
                    }
                }
            }
        }
        profiles::save_controls_config(recomp::get_config_path() / (recompui::config::controls::id + ".json"));
    }
    std::ofstream out{ config_file("controls_preset.txt") };
    out << (modern ? "modern" : "classic") << "\n";
}

// ---- Experience -----------------------------------------------------------------------------
//
// One choice that sets several things at once (Gameplay > Experience; also asked on first launch):
//   Classic: Classic controls, the original resolution and 4:3 picture, no anti-aliasing.
//   Modern:  Modern controls, the screen's resolution, widescreen, 2x MSAA.
//   Custom:  whatever has been set by hand (nothing is changed).

extern "C" int bt_android_experience_chosen() {
    return std::filesystem::exists(config_file("experience.txt")) ? 1 : 0;
}

extern "C" void bt_android_apply_experience(int modern) {
    if (applying_experience) {
        return;
    }
    applying_experience = true;

    bt_android_apply_control_preset(modern);

    recomp::config::Config& graphics = recompui::config::get_graphics_config();
    graphics.update_option_value(recompui::config::graphics::options::res_option,
        static_cast<uint32_t>(modern ? ultramodern::renderer::Resolution::Auto : ultramodern::renderer::Resolution::Original));
    graphics.update_option_value(recompui::config::graphics::options::msaa_option,
        static_cast<uint32_t>(modern ? ultramodern::renderer::Antialiasing::MSAA2X : ultramodern::renderer::Antialiasing::None));
    graphics.update_option_value(recompui::config::graphics::options::ar_option,
        static_cast<uint32_t>(modern ? ultramodern::renderer::AspectRatio::Expand : ultramodern::renderer::AspectRatio::Original));
    graphics.update_option_value(recompui::config::graphics::options::hr_option,
        static_cast<uint32_t>(modern ? ultramodern::renderer::HUDRatioMode::Clamp16x9 : ultramodern::renderer::HUDRatioMode::Original));
    graphics.save_config();

    recomp::config::Config& general = recompui::config::get_general_config();
    general.update_option_value(experience_option, static_cast<uint32_t>(modern ? 1u : 0u));
    general.save_config();

    std::ofstream out{ config_file("experience.txt") };
    out << (modern ? "modern" : "classic") << "\n";
    applying_experience = false;
}

namespace recompui::config {
    // Called by create_general_tab (RecompFrontend Android patch): the Experience option.
    void bt_android_add_experience_option(recomp::config::Config& config) {
        static EnumOptionVector experience_options = {
            {0u, "Classic", "Classic"},
            {1u, "Modern", "Modern"},
            {2u, "Custom", "Custom"},
        };
        config.add_enum_option(
            experience_option,
            "Experience",
            "Sets several options at once.<br />"
            "<recomp-color primary>Classic</recomp-color>: the N64 as it was. Classic controls (the right stick works the "
            "C buttons), the original resolution and 4:3 picture, no anti-aliasing.<br />"
            "<recomp-color primary>Modern</recomp-color>: the right stick turns the camera and pressing it in looks around, "
            "the device's resolution, widescreen and anti-aliasing.<br />"
            "<recomp-color primary>Custom</recomp-color>: keeps whatever you've set yourself.<br />"
            "Each option can still be changed afterwards.",
            experience_options,
            2u
        );
        config.add_option_change_callback(experience_option,
            [](recomp::config::ConfigValueVariant cur, recomp::config::ConfigValueVariant, recomp::config::OptionChangeContext context) {
                const uint32_t value = std::get<uint32_t>(cur);
                if (context == recomp::config::OptionChangeContext::Permanent && !applying_experience && value < 2u) {
                    bt_android_apply_experience(static_cast<int>(value));
                }
            });
    }
}

// ---- Settings tabs --------------------------------------------------------------------------

void bt::android::initialize_config(const recompui::config::GeneralTabOptions& general_options) {
    using Entry = recompui::config::LayoutEntry;
    using Kind = Entry::Kind;

    recompui::config::create_general_tab(general_options);
    recompui::config::set_tab_visible(recompui::config::general::id, false);

    // Video > Graphics Driver: a button that opens the driver menu (pick, install, remove).
    // The driver loads when the app starts, so changing it restarts the app, which is only
    // offered on the main menu.
    auto& graphics_config = recompui::config::create_graphics_tab("Video");
    {
        Entry driver;
        driver.kind = Kind::Button;
        driver.text = "Graphics Driver";
        driver.description = "Choose the Vulkan driver used for rendering: the device's own, or a custom one such as Turnip. "
            "Install and remove drivers here too. Changing the driver restarts the app, so it can only be done from the main menu. "
            "In use now: " + active_gpu_driver() + ". App version: " + app_version() + ".";
        driver.on_press = []() {
            if (ultramodern::is_game_started()) {
                recompui::open_info_prompt("Graphics Driver",
                    "The graphics driver can only be changed from the main menu, because the app restarts to use it. "
                    "Use QUIT > Main Menu, then open Settings > Video > Graphics Driver.",
                    "OK", []() {});
                return;
            }
            open_gpu_driver_manager();
        };
        recompui::config::add_page_button(graphics_config, std::move(driver));
    }
    {
        static EnumOptionVector fps_options = {
            {0u, "Off", "Off"},
            {1u, "On", "On"},
        };
        graphics_config.add_enum_option(
            "android_show_fps",
            "Show FPS",
            "Shows how many frames per second the game is running at, in the top-left corner. "
            "Banjo-Tooie itself runs at up to 30 (often lower in busy places); 60 FPS mode adds frames in between.",
            fps_options,
            0u
        );
        graphics_config.add_option_change_callback("android_show_fps",
            [](recomp::config::ConfigValueVariant cur, recomp::config::ConfigValueVariant, recomp::config::OptionChangeContext) {
                show_fps.store(std::get<uint32_t>(cur) == 1u);
            });
    }

    {
        // TEMPORARY test switch: how the renderer's copy of each finished frame reaches the
        // game's memory (rt64-android.patch, rt64_android_set_ram_copy_mode). Late is the
        // DK64 speed-up; Wait is upstream; Off skips the copy (fastest, may break effects).
        static EnumOptionVector copy_options = {
            {0u, "Late", "Late (fast)"},
            {1u, "Wait", "Wait (safe)"},
            {2u, "Off", "Off (test)"},
        };
        graphics_config.add_enum_option(
            "android_frame_copy",
            "Frame Copy (test)",
            "How each finished frame is copied back to the game, which uses it for some effects. "
            "Late: fastest, copied while the game carries on. Wait: the game waits for the copy, as on PC. "
            "Off: no copy at all; much faster, but effects that use the last frame may look wrong. "
            "For testing flicker and speed.",
            copy_options,
            0u
        );
        graphics_config.add_option_change_callback("android_frame_copy",
            [](recomp::config::ConfigValueVariant cur, recomp::config::ConfigValueVariant, recomp::config::OptionChangeContext) {
                apply_frame_copy(std::get<uint32_t>(cur));
            });
    }

    {
        static EnumOptionVector auto_options = {
            {0u, "Auto", "Auto"},
            {1u, "Always", "Always"},
        };
        graphics_config.add_enum_option(
            "android_auto_framerate",
            "60 FPS Mode",
            "With Framerate at 60. Auto: in busy scenes the device can't draw every in-between frame, "
            "so it drops to a steady 30 and goes back to 60 once things are lighter; a steady 30 looks "
            "smoother than an uneven 40-55. Always: 60 whenever it can, skipping frames when it can't.",
            auto_options,
            0u
        );
        graphics_config.add_option_change_callback("android_auto_framerate",
            [](recomp::config::ConfigValueVariant cur, recomp::config::ConfigValueVariant, recomp::config::OptionChangeContext) {
                apply_auto_framerate(std::get<uint32_t>(cur));
            });
        static EnumOptionVector live_options = {
            {0u, "Off", "Off"},
            {1u, "On", "On"},
        };
        graphics_config.add_enum_option(
            "android_live_log",
            "Live Debug Log",
            "For testing: once a second, writes the frame rate, CPU and GPU load, clocks, memory, "
            "temperature and where you are in the game to the Android log (tag BTLive), so a computer "
            "connected with adb can follow it live (bt-live.bat). Costs almost nothing; leave it off "
            "when you're not testing.",
            live_options,
            0u
        );
        graphics_config.add_option_change_callback("android_live_log",
            [](recomp::config::ConfigValueVariant cur, recomp::config::ConfigValueVariant, recomp::config::OptionChangeContext) {
                apply_live_log(std::get<uint32_t>(cur));
            });
    }

    auto& touch_config = recompui::config::create_config_tab("Touch", touch_config_id, false);
    add_touch_options(touch_config);
    recompui::config::set_tab_visible(touch_config_id, false);

    const std::string g = recompui::config::general::id;
    const std::string t = touch_config_id;
    namespace gopts = recompui::config::general::options;
    auto header = [](const std::string& text) { Entry e; e.kind = Kind::Header; e.text = text; return e; };
    auto option = [](const std::string& cfg, const std::string& id) { Entry e; e.kind = Kind::Option; e.config_id = cfg; e.option_id = id; return e; };

    Entry configure;
    configure.kind = Kind::Button;
    configure.text = "Configure Inputs";
    configure.description = "Choose which button does what on your controller, or pick the Classic or Modern layout.";
    configure.on_press = []() { recompui::config::set_tab(recompui::config::controls::id); };

    Entry edit_touch;
    edit_touch.kind = Kind::Button;
    edit_touch.text = "Edit Touch Layout";
    edit_touch.description = "Move and resize the on-screen buttons: drag one to move it, pinch or use - and + to resize it. "
        "Add and remove buttons, and save layouts to load later. Each screen shape (landscape, portrait, square) keeps its own layout.";
    edit_touch.on_press = []() { bt_android_edit_touch_layout(); };

    recompui::config::create_layout_tab("Input", "input", {
        header("Inputs"),
        configure,
        option(g, gopts::joystick_deadzone),
        option(g, gopts::rumble_strength),
        header("Touch"),
        option(t, "android_touch_controls"),
        edit_touch,
        option(t, "android_drag_camera"),
        option(t, "android_drag_camera_speed"),
        option(t, "android_touch_feedback"),
        header("Dual Screen"),
        option(t, "android_bottom_screen"),
        option(t, "android_bottom_buttons"),
        option(t, "android_cutscene_dim"),
    }, { g, t });

    recompui::config::create_sound_tab("Audio");

    // Where saves, settings, mods and the ROM are kept: app storage or a shared folder.
    // Moving them restarts the app, so (like the graphics driver) only from the main menu.
    Entry files;
    files.kind = Kind::Button;
    files.text = "Game Files Location";
    {
        const std::string where = storage_description();
        files.description = "Move your saves, settings, mods and ROM between app storage and a shared folder "
            "(easy to reach from a PC, and kept if the app is uninstalled). Everything is copied, then the app restarts; "
            "only from the main menu."
            + (where.empty() ? std::string() : "<br />Now in " + where + ".");
    }
    files.on_press = []() {
        if (ultramodern::is_game_started()) {
            recompui::open_info_prompt("Game Files Location",
                "The game files can only be moved from the main menu, because the app restarts to use the new place. "
                "Use QUIT > Main Menu, then open Settings > Gameplay > Game Files Location.",
                "OK", []() {});
            return;
        }
        change_storage_location();
    };

    Entry share;
    share.kind = Kind::Button;
    share.text = "Share Log";
    share.description = "Send a log report (app version, device, graphics driver and the game's output from this "
        "and the previous session) to fix a problem. Nothing personal is included. App version: "
        + app_version() + ".";
    share.on_press = []() { share_log(); };

    recompui::config::create_layout_tab("Gameplay", "gameplay", {
        option(g, experience_option),
        header("Storage"),
        files,
        header("Help"),
        share,
    }, { g });

    set_control_defaults();
    set_control_descriptions();
    recompui::config::create_controls_tab("Configure Inputs");
    recompui::config::create_mods_tab();
}

void bt::android::after_config_loaded() {
    {
        const recomp::config::ConfigValueVariant mode = recompui::config::get_graphics_config().get_option_value("android_frame_copy");
        if (const uint32_t* value = std::get_if<uint32_t>(&mode)) {
            apply_frame_copy(*value);
        }
    }
    {
        const recomp::config::ConfigValueVariant mode = recompui::config::get_graphics_config().get_option_value("android_auto_framerate");
        if (const uint32_t* value = std::get_if<uint32_t>(&mode)) {
            apply_auto_framerate(*value);
        }
    }
    {
        const recomp::config::ConfigValueVariant mode = recompui::config::get_graphics_config().get_option_value("android_live_log");
        if (const uint32_t* value = std::get_if<uint32_t>(&mode)) {
            apply_live_log(*value);
        }
    }
    // Once: set-ups made before B backed out of menus get Back (Menu) on B.
    const std::filesystem::path marker = config_file("menu_back_b.txt");
    if (!std::filesystem::exists(marker)) {
        using namespace recompinput;
        const int profile = profiles::get_sp_controller_profile_index();
        const InputField b = InputField::controller_digital(SDL_CONTROLLER_BUTTON_B);
        bool has_b = false;
        for (size_t i = 0; profile >= 0 && i < num_bindings_per_input; i++) {
            has_b = has_b || profiles::get_input_binding(profile, GameInput::BACK_MENU, i) == b;
        }
        if (profile >= 0 && !has_b) {
            profiles::set_input_binding(profile, GameInput::BACK_MENU, 0, b);
            profiles::save_controls_config(recomp::get_config_path() / (recompui::config::controls::id + ".json"));
        }
        std::ofstream out{ marker };
        out << "done\n";
    }
}

void bt::android::on_launcher_first_update() {
    ask_for_experience();
}

// ---- State for the on-screen controls (TouchInput.java) ----------------------------------

// Bit 1: a menu (the launcher, the menu bar, settings) is taking input, so the touch buttons
// are hidden and touches go to it. Bit 2: reserved for a camera mode with its own layout.
// Bit 4: Drag to Move Camera is on. Bit 16: the game has started (not just the launcher).
extern "C" JNIEXPORT jint JNICALL
Java_io_github_bownz_btrecomp_TouchInput_nativeUiState(JNIEnv*, jclass) {
    jint bits = 0;
    if (recompui::is_context_capturing_input()) {
        bits |= 1;
    }
    if (drag_camera.load()) {
        bits |= 4;
    }
    if (ultramodern::is_game_started()) {
        bits |= 16;
    }
    return bits;
}

// Camera Drag Speed, 1 (slowest) to 5 (fastest).
extern "C" JNIEXPORT jint JNICALL
Java_io_github_bownz_btrecomp_TouchInput_nativeDragSpeed(JNIEnv*, jclass) {
    return drag_camera_speed.load();
}

// Touch Feedback: 0 off, 1 light, 2 medium, 3 strong.
extern "C" JNIEXPORT jint JNICALL
Java_io_github_bownz_btrecomp_TouchInput_nativeTouchFeedback(JNIEnv*, jclass) {
    return touch_feedback.load();
}

// Bit 1 Bottom Screen Buttons, bit 2 Dim During Cutscenes, bit 4 Video > Show FPS.
extern "C" JNIEXPORT jint JNICALL
Java_io_github_bownz_btrecomp_GameState_nativeBottomSettings(JNIEnv*, jclass) {
    const int buttons = bottom_buttons.load();
    return (buttons != 0 ? 1 : 0) | (cutscene_dim.load() ? 2 : 0) | (show_fps.load() ? 4 : 0) | (buttons == 2 ? 8 : 0);
}
