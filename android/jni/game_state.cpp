// Read-only access to Banjo-Tooie's game state for the Android second screen. Values are
// copied out of the emulated N64 RDRAM; nothing is ever written.
//
// Only addresses that have been verified are read. So far that's the port's own findings
// (src/recomp_api.cpp): the game's frame counter and game mode. Everything else the bottom
// screen will show (the character, collectibles, pause, cutscenes) is still being found, with
// the TEMPORARY probe below: it watches all of RDRAM and, each time the player taps Mark on
// the bottom screen, logs the bytes that changed only a few times, by a small step, since the
// previous Mark. Collecting one thing between two Marks narrows that thing's counter down to
// a few candidates. It also looks for positions (for the bottom screen's map): 32-bit floats
// that moved a fair way, many times, since the previous Mark (walk between two Marks), in the
// globals and in the structures the player-state slots point to; and 16-bit values that
// changed once or twice (going through a door: the area number). Logcat tag BTProbe, and
// "[BTProbe]" lines in the session log (so Share Log includes them).

#include <android/log.h>
#include <cstdarg>
#include <dirent.h>
#include <sys/stat.h>
#include <zlib.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdio>
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
    constexpr uint32_t kPlayerStateSlotTable = 0x80135490u;  // 8 pointers
    constexpr uint32_t kPlayerStateSlotCount = 8u;

    constexpr size_t kRdramSize = 8 * 1024 * 1024;

    // The recompiler stores RDRAM as host-endian 32-bit words (see MEM_BU / MEM_HU in
    // N64Recomp's recomp.h): a guest byte address is XORed with 3.
    uint32_t read_u32(const uint8_t* rdram, uint32_t address) {
        return *reinterpret_cast<const uint32_t*>(rdram + (address - 0x80000000u));
    }

    // ---- TEMPORARY probe ----------------------------------------------------------------

    constexpr const char* kProbeTag = "BTProbe";

    // To logcat and to the session log (stderr), so Share Log carries it.
    __attribute__((format(printf, 1, 2))) void probe_log(const char* format, ...) {
        char line[256];
        va_list args;
        va_start(args, format);
        std::vsnprintf(line, sizeof(line), format, args);
        va_end(args);
        __android_log_write(ANDROID_LOG_INFO, kProbeTag, line);
        std::fprintf(stderr, "[BTProbe] %s\n", line);
    }
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

    void probe_report_halfwords_locked(const uint8_t* rdram, int mark);
    void probe_report_floats_locked(const uint8_t* rdram, int mark);

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
        probe_log("MARK %d: %zu candidates (game mode %u, frame %u)",
            mark, found.size(), mode, frame);
        // Globals (below the heap) first: the likeliest home of counters and state. Since 0.4.2
        // the snapshots carry everything, so only a few lines go to the log.
        int lines = 0;
        constexpr int kMaxByteLines = 40;
        for (int pass = 0; pass < 2; pass++) {
            for (const Candidate& c : found) {
                const bool global = c.address < 0x80200000u;
                if ((pass == 0) != global || lines >= kMaxByteLines) {
                    continue;
                }
                probe_log("M%d %08X %u->%u (%+d, %u changes)%s",
                    mark, c.address, c.first, c.now, static_cast<int>(c.now) - static_cast<int>(c.first), c.changes,
                    global ? "" : " heap");
                lines++;
            }
        }
        if (static_cast<int>(found.size()) > lines) {
            probe_log("M%d (%zu more not shown)", mark, found.size() - lines);
        }
        probe_report_halfwords_locked(rdram, mark);
        probe_report_floats_locked(rdram, mark);
        std::fflush(stderr);
    }

    // How often the 4 bytes at a (word-aligned) host offset changed: the most of any of them.
    unsigned word_changes(size_t offset) {
        return std::max(std::max(g_count[offset], g_count[offset + 1]), std::max(g_count[offset + 2], g_count[offset + 3]));
    }

    // Area numbers and other 16-bit state in the globals: changed 1-2 times, any step.
    void probe_report_halfwords_locked(const uint8_t* rdram, int mark) {
        int lines = 0;
        for (uint32_t guest = 0x80000000u; guest < 0x80200000u && lines < 40; guest += 2) {
            // A guest halfword at `guest` is host bytes (guest ^ 2) .. +1.
            const size_t host = (guest - 0x80000000u) ^ 2;
            const unsigned n = std::max(g_count[host], g_count[host + 1]);
            if (n == 0 || n > 2) {
                continue;
            }
            uint16_t first, now;
            std::memcpy(&first, g_first.data() + host, 2);
            std::memcpy(&now, rdram + host, 2);
            if (first == now || now > 0x1000 || first > 0x1000) {
                continue;
            }
            probe_log("M%d H %08X 0x%X->0x%X (%u changes)", mark, guest, first, now, n);
            lines++;
        }
    }

    bool plausible_coordinate(float v) {
        return std::isfinite(v) && std::fabs(v) < 30000.0f;
    }

    // Positions: floats that moved by 20-6000 units with many changes since the last Mark.
    void probe_report_float_range_locked(const uint8_t* rdram, int mark, uint32_t begin, uint32_t end,
                                         const char* where, int& lines) {
        for (uint32_t guest = begin; guest + 4 <= end && lines < 160; guest += 4) {
            const size_t host = guest - 0x80000000u;
            if (host + 4 > kRdramSize || word_changes(host) < 8) {
                continue;
            }
            float first, now;
            std::memcpy(&first, g_first.data() + host, 4);
            std::memcpy(&now, rdram + host, 4);
            if (!plausible_coordinate(first) || !plausible_coordinate(now)) {
                continue;
            }
            const float moved = std::fabs(now - first);
            if (moved < 20.0f || moved > 6000.0f) {
                continue;
            }
            probe_log("M%d F %08X %.1f->%.1f (%u changes) %s",
                mark, guest, first, now, word_changes(host), where);
            lines++;
        }
    }

    void probe_report_floats_locked(const uint8_t* rdram, int mark) {
        int lines = 0;
        // The player-state slots' structures first.
        for (uint32_t i = 0; i < kPlayerStateSlotCount; i++) {
            const uint32_t ptr = read_u32(rdram, kPlayerStateSlotTable + i * 4);
            probe_log("M%d slot %u -> %08X", mark, i, ptr);
            if (ptr >= 0x80000000u && ptr < 0x80800000u - 0x800) {
                char where[32];
                std::snprintf(where, sizeof(where), "slot%u+%X", i, 0);
                probe_report_float_range_locked(rdram, mark, ptr, ptr + 0x800, where, lines);
            }
        }
        probe_report_float_range_locked(rdram, mark, 0x80000000u, 0x80200000u, "global", lines);
    }

    // Each Mark also saves all of RDRAM (host order, gzip) to <dir>/mark-NN.bin.gz, with a line
    // in <dir>/marks.txt, so the marks can be compared offline (pull-probe.bat). The first Mark
    // of a run clears the previous run's files.
    std::string g_snapshot_dir;
    bool g_snapshot_cleared = false;

    void snapshot_clear_locked(const std::string& dir) {
        mkdir(dir.c_str(), 0775);
        if (DIR* d = opendir(dir.c_str())) {
            while (dirent* e = readdir(d)) {
                const std::string name = e->d_name;
                if (name.rfind("mark-", 0) == 0 || name == "marks.txt") {
                    std::remove((dir + "/" + name).c_str());
                }
            }
            closedir(d);
        }
    }

    void snapshot_save(const std::string& dir, int mark, std::vector<uint8_t> copy, uint32_t mode, uint32_t frame) {
        std::thread([dir, mark, copy = std::move(copy), mode, frame] {
            char name[64];
            std::snprintf(name, sizeof(name), "/mark-%02d.bin.gz", mark);
            const std::string path = dir + name;
            gzFile f = gzopen(path.c_str(), "wb1");
            bool ok = f != nullptr && gzwrite(f, copy.data(), static_cast<unsigned>(copy.size())) == static_cast<int>(copy.size());
            if (f != nullptr) {
                ok = gzclose(f) == Z_OK && ok;
            }
            if (FILE* m = std::fopen((dir + "/marks.txt").c_str(), "a")) {
                std::fprintf(m, "%d frame=%u mode=%u file=%s %s\n", mark, frame, mode, name + 1, ok ? "ok" : "FAILED");
                std::fclose(m);
            }
            probe_log("MARK %d saved to %s%s", mark, path.c_str(), ok ? "" : " (FAILED)");
        }).detach();
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
                probe_log("game mode %u -> %u (frame %u)",
                    last_mode, mode, read_u32(rdram, kGameFrameCount));
                std::fflush(stderr);
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
Java_io_github_bownz_btrecomp_GameState_nativeProbeMark(JNIEnv* env, jclass, jstring dir) {
    uint8_t* rdram = g_rdram.load();
    if (rdram == nullptr) {
        return -1;
    }
    std::lock_guard lock{ g_probe_mutex };
    const int mark = ++g_marks;
    if (dir != nullptr) {
        const char* chars = env->GetStringUTFChars(dir, nullptr);
        g_snapshot_dir = chars != nullptr ? chars : "";
        if (chars != nullptr) {
            env->ReleaseStringUTFChars(dir, chars);
        }
    }
    if (!g_snapshot_dir.empty()) {
        if (!g_snapshot_cleared) {
            snapshot_clear_locked(g_snapshot_dir);
            g_snapshot_cleared = true;
        }
        snapshot_save(g_snapshot_dir, mark, std::vector<uint8_t>(rdram, rdram + kRdramSize),
            read_u32(rdram, kGameMode), read_u32(rdram, kGameFrameCount));
    }
    if (g_prev.size() == kRdramSize) {
        probe_sample_locked(rdram);
        probe_report_locked(rdram, mark);
    } else {
        probe_log("MARK %d: start", mark);
        std::fflush(stderr);
    }
    probe_reset_locked(rdram);
    return mark;
}

} // extern "C"
