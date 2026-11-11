// Read-only access to Banjo-Tooie's game state for the Android second screen. Values are
// copied out of the emulated N64 RDRAM; nothing is ever written.
//
// Only addresses that have been verified are read. So far that's the port's own findings
// (src/recomp_api.cpp): the game's frame counter and game mode. Everything else the bottom
// screen will show (the character, collectibles, pause, cutscenes) is still being found, with
// the TEMPORARY probe below: it watches all of RDRAM and, each time the player taps Mark on
// the bottom screen, logs the bytes that changed only a few times, by a small step, since the
// previous Mark. Collecting one thing between two Marks narrows that thing's counter down to
// a few candidates. Logcat tag BTProbe.

#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "ultramodern/ultramodern.hpp"

namespace {
    std::atomic<uint8_t*> g_rdram{nullptr};

    // N64 addresses (US), verified by the port (src/recomp_api.cpp).
    constexpr uint32_t kGameMode = 0x80043380u;        // u32
    constexpr uint32_t kGameFrameCount = 0x800459C8u;  // u32, counts game frames

    constexpr size_t kRdramSize = 8 * 1024 * 1024;

    // The recompiler stores RDRAM as host-endian 32-bit words (see MEM_BU / MEM_HU in
    // N64Recomp's recomp.h): a guest byte address is XORed with 3.
    uint32_t read_u32(const uint8_t* rdram, uint32_t address) {
        return *reinterpret_cast<const uint32_t*>(rdram + (address - 0x80000000u));
    }

    // ---- TEMPORARY probe ----------------------------------------------------------------

    constexpr const char* kProbeTag = "BTProbe";
    std::mutex g_probe_mutex;
    std::vector<uint8_t> g_first, g_prev, g_count;   // per host byte, since the last Mark
    int g_marks = 0;
    std::atomic<bool> g_probe_enabled{true};
    std::atomic<bool> g_probe_running{false};

    void probe_reset_locked(const uint8_t* rdram) {
        g_first.assign(rdram, rdram + kRdramSize);
        g_prev = g_first;
        g_count.assign(kRdramSize, 0);
    }

    // Counts changes per byte. Compares 64 bytes at a time and only walks the blocks that differ.
    void probe_sample_locked(const uint8_t* rdram) {
        if (g_prev.size() != kRdramSize) {
            probe_reset_locked(rdram);
            return;
        }
        constexpr size_t kBlock = 64;
        for (size_t block = 0; block < kRdramSize; block += kBlock) {
            if (std::memcmp(rdram + block, g_prev.data() + block, kBlock) == 0) {
                continue;
            }
            for (size_t i = block; i < block + kBlock; i++) {
                const uint8_t v = rdram[i];
                if (v != g_prev[i]) {
                    g_prev[i] = v;
                    if (g_count[i] < 255) {
                        g_count[i]++;
                    }
                }
            }
        }
    }

    struct Candidate {
        uint32_t address;
        uint8_t first, now, changes;
    };

    void probe_report_locked(const uint8_t* rdram, int mark) {
        std::vector<Candidate> found;
        for (size_t i = 0; i < kRdramSize; i++) {
            const uint8_t n = g_count[i];
            if (n == 0 || n > 4) {
                continue;
            }
            const uint8_t first = g_first[i], now = rdram[i];
            if (first == now) {
                continue;
            }
            const int delta = static_cast<int>(now) - static_cast<int>(first);
            // Counters and small state values: a small step, or a small value either side.
            if (std::abs(delta) > 10 && first > 20 && now > 20) {
                continue;
            }
            found.push_back({ 0x80000000u + static_cast<uint32_t>(i ^ 3), first, now, n });
        }
        std::sort(found.begin(), found.end(), [](const Candidate& a, const Candidate& b) { return a.address < b.address; });
        const uint32_t mode = read_u32(rdram, kGameMode);
        const uint32_t frame = read_u32(rdram, kGameFrameCount);
        __android_log_print(ANDROID_LOG_INFO, kProbeTag, "MARK %d: %zu candidates (game mode %u, frame %u)",
            mark, found.size(), mode, frame);
        // Globals (below the heap) first: the likeliest home of counters and state.
        int lines = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (const Candidate& c : found) {
                const bool global = c.address < 0x80200000u;
                if ((pass == 0) != global || lines >= 400) {
                    continue;
                }
                __android_log_print(ANDROID_LOG_INFO, kProbeTag, "M%d %08X %u->%u (%+d, %u changes)%s",
                    mark, c.address, c.first, c.now, static_cast<int>(c.now) - static_cast<int>(c.first), c.changes,
                    global ? "" : " heap");
                lines++;
            }
        }
        if (static_cast<int>(found.size()) > lines) {
            __android_log_print(ANDROID_LOG_INFO, kProbeTag, "M%d (%zu more not shown)", mark, found.size() - lines);
        }
    }

    void probe_thread() {
        uint32_t last_mode = 0xFFFFFFFFu;
        while (true) {
            std::this_thread::sleep_for(std::chrono::milliseconds(100));
            uint8_t* rdram = g_rdram.load();
            if (rdram == nullptr) {
                continue;
            }
            const uint32_t mode = read_u32(rdram, kGameMode);
            if (mode != last_mode) {
                __android_log_print(ANDROID_LOG_INFO, kProbeTag, "game mode %u -> %u (frame %u)",
                    last_mode, mode, read_u32(rdram, kGameFrameCount));
                last_mode = mode;
            }
            if (!g_probe_enabled.load() || !ultramodern::is_game_started()) {
                continue;
            }
            std::lock_guard lock{ g_probe_mutex };
            probe_sample_locked(rdram);
        }
    }

    void start_probe() {
        if (!g_probe_running.exchange(true)) {
            std::thread([] {
                pthread_setname_np(pthread_self(), "BT probe");
                probe_thread();
            }).detach();
        }
    }
}

// Called by RecompFrontend's renderer setup (Android patch) with the game's RDRAM.
extern "C" void bt_android_set_rdram(uint8_t* rdram) {
    g_rdram.store(rdram);
    start_probe();
}

extern "C" {

// The game's frame counter, or -1 before the game runs.
JNIEXPORT jint JNICALL
Java_io_github_bownz_btrecomp_GameState_nativeFrameCount(JNIEnv*, jclass) {
    uint8_t* rdram = g_rdram.load();
    if (rdram == nullptr || !ultramodern::is_game_started()) {
        return -1;
    }
    return static_cast<jint>(read_u32(rdram, kGameFrameCount) & 0x7FFFFFFF);
}

// Mark on the bottom screen: logs what changed since the previous Mark, then starts over.
JNIEXPORT jint JNICALL
Java_io_github_bownz_btrecomp_GameState_nativeProbeMark(JNIEnv*, jclass) {
    uint8_t* rdram = g_rdram.load();
    if (rdram == nullptr) {
        return -1;
    }
    std::lock_guard lock{ g_probe_mutex };
    const int mark = ++g_marks;
    if (g_prev.size() == kRdramSize) {
        probe_sample_locked(rdram);
        probe_report_locked(rdram, mark);
    } else {
        __android_log_print(ANDROID_LOG_INFO, kProbeTag, "MARK %d: start", mark);
    }
    probe_reset_locked(rdram);
    return mark;
}

} // extern "C"
