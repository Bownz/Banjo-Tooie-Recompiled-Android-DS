// Android-only settings and control presets (src/android_config.cpp).
#pragma once

#include "recompui/config.h"

namespace bt::android {
    // Creates the Android settings tabs (Video, Input, Audio, Gameplay, Configure Inputs, Mods)
    // and the controller defaults. Call where the desktop build creates its tabs.
    void initialize_config(const recompui::config::GeneralTabOptions& general_options);
    // After recompui::config::finalize(): one-off migrations of the player's settings.
    void after_config_loaded();
    // The launcher's first update: asks Classic or Modern on first launch.
    void on_launcher_first_update();
}

// Classic (0) or Modern (1) controls / experience; -1 or 0 when not chosen yet.
extern "C" int bt_android_control_preset();
extern "C" void bt_android_apply_control_preset(int modern);
extern "C" int bt_android_experience_chosen();
extern "C" void bt_android_apply_experience(int modern);
