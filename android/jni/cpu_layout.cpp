// Which CPU cores each kind of thread runs on.
//
// Phones mix core types: e.g. the AYN Thor (Snapdragon 8 Gen 2) has 1 prime core, 4 mid cores
// and 3 small ones; a Snapdragon 8 Elite has 2 prime and 6 mid. At startup the whole game is
// kept off the small cores (android_main.cpp, prefer_fast_cores). On top of that:
//
//   * the game's own thread (the one that finishes each game frame) runs on the prime core(s),
//     so it isn't moved around or made to share with the render threads;
//   * background work (RT64's shader compiling and texture streaming, the perf monitor) runs on
//     the small cores when there are any, otherwise on the non-prime cores, so it can't take
//     time from a frame;
//   * everything else (RT64's workload/present/texture threads, the SP task thread, audio...)
//     keeps the fast cores.
//
// Cluster speeds come from cpuinfo_max_freq. Nothing changes on phones where all cores are alike.

#include <android/log.h>
#include <sched.h>
#include <unistd.h>

#include <algorithm>
#include <fstream>
#include <mutex>
#include <string>
#include <vector>

namespace {
    constexpr const char* log_tag = "BTRecomp";

    struct Layout {
        bool valid = false;
        cpu_set_t prime{}, background{};
        int prime_count = 0, background_count = 0;
    };

    Layout compute() {
        Layout layout;
        const long count = sysconf(_SC_NPROCESSORS_CONF);
        if (count <= 1 || count > CPU_SETSIZE) {
            return layout;
        }
        std::vector<long> freq(count, 0);
        long slowest = 0, fastest = 0;
        for (long i = 0; i < count; i++) {
            std::ifstream f("/sys/devices/system/cpu/cpu" + std::to_string(i) + "/cpufreq/cpuinfo_max_freq");
            long khz = 0;
            if (f >> khz) {
                freq[i] = khz;
                slowest = slowest == 0 ? khz : std::min(slowest, khz);
                fastest = std::max(fastest, khz);
            }
        }
        if (slowest == 0 || slowest == fastest) {
            return layout;
        }
        CPU_ZERO(&layout.prime);
        CPU_ZERO(&layout.background);
        int fast_count = 0;
        for (long i = 0; i < count; i++) {
            if (freq[i] == fastest) {
                CPU_SET(i, &layout.prime);
                layout.prime_count++;
            }
            if (freq[i] > slowest) {
                fast_count++;
            }
        }
        // Background: the small cores, unless the "small" cores are most of the phone's speed
        // (only two kinds of core, e.g. 2 prime + 6 mid): then everything but the prime cores.
        const bool has_small = fast_count > layout.prime_count;
        for (long i = 0; i < count; i++) {
            const bool small = freq[i] == slowest;
            const bool use = has_small ? small : freq[i] != fastest;
            if (use && freq[i] > 0) {
                CPU_SET(i, &layout.background);
                layout.background_count++;
            }
        }
        layout.valid = layout.prime_count > 0 && layout.background_count > 0;
        __android_log_print(ANDROID_LOG_INFO, log_tag,
            "CPU layout: game thread on %d prime core(s), background work on %d %s core(s)",
            layout.prime_count, layout.background_count, has_small ? "small" : "mid");
        return layout;
    }

    const Layout& layout() {
        static std::once_flag once;
        static Layout value;
        std::call_once(once, [] { value = compute(); });
        return value;
    }
}

// The calling thread: 0 = the game's own thread (prime cores), 2 = background work.
extern "C" void bt_android_set_thread_role(int role) {
    const Layout& l = layout();
    if (!l.valid) {
        return;
    }
    if (role == 0) {
        sched_setaffinity(0, sizeof(l.prime), &l.prime);
    } else if (role == 2) {
        sched_setaffinity(0, sizeof(l.background), &l.background);
    }
}
