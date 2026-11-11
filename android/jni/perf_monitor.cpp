// Performance log for testers: every 5 seconds, which threads used the CPU and how much (as a
// percentage of one core), plus the CPU and GPU clock speeds when the system lets apps read
// them. Together with RT64's "Perf:" line this shows whether a slow spot is the game's own
// code, the renderer's CPU work, the GPU, or the device clocking down.
//
// Reads only /proc/self and /sys; it wakes once every 5 seconds and costs nothing in between.

#include <android/log.h>
#include <dirent.h>
#include <pthread.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <map>
#include <string>
#include <thread>
#include <vector>

namespace {
    constexpr const char* log_tag = "BTRecomp";
    std::atomic<bool> started{false};

    struct ThreadTimes {
        std::string name;
        long long ticks = 0;
    };

    // utime + stime of every thread of this process, by thread id.
    std::map<int, ThreadTimes> read_threads() {
        std::map<int, ThreadTimes> out;
        DIR* dir = opendir("/proc/self/task");
        if (dir == nullptr) {
            return out;
        }
        while (dirent* entry = readdir(dir)) {
            if (entry->d_name[0] < '0' || entry->d_name[0] > '9') {
                continue;
            }
            const int tid = atoi(entry->d_name);
            char path[64];
            snprintf(path, sizeof(path), "/proc/self/task/%d/stat", tid);
            FILE* f = fopen(path, "r");
            if (f == nullptr) {
                continue;
            }
            char buf[1024];
            const size_t n = fread(buf, 1, sizeof(buf) - 1, f);
            fclose(f);
            buf[n] = 0;
            // "tid (name) state ppid ... utime(14) stime(15) ..."; the name may contain spaces.
            char* open = strchr(buf, '(');
            char* close = strrchr(buf, ')');
            if (open == nullptr || close == nullptr || close < open) {
                continue;
            }
            ThreadTimes t;
            t.name.assign(open + 1, close - open - 1);
            // Fields after ")": state(3) ppid pgrp session tty tpgid flags minflt cminflt majflt cmajflt utime(14) stime(15)
            char* p = close + 2;
            long long utime = 0, stime = 0;
            int field = 3;
            char* save = nullptr;
            for (char* tok = strtok_r(p, " ", &save); tok != nullptr; tok = strtok_r(nullptr, " ", &save), field++) {
                if (field == 14) {
                    utime = atoll(tok);
                } else if (field == 15) {
                    stime = atoll(tok);
                    break;
                }
            }
            t.ticks = utime + stime;
            out[tid] = t;
        }
        closedir(dir);
        return out;
    }

    long read_long(const std::string& path) {
        std::ifstream f(path);
        long v = -1;
        if (!(f >> v)) {
            return -1;
        }
        return v;
    }

    void run() {
        const long ticks_per_second = sysconf(_SC_CLK_TCK);
        const long cpus = sysconf(_SC_NPROCESSORS_CONF);
        auto before = read_threads();
        auto last = std::chrono::steady_clock::now();
        while (true) {
            std::this_thread::sleep_for(std::chrono::seconds(5));
            const auto now = std::chrono::steady_clock::now();
            const double seconds = std::chrono::duration<double>(now - last).count();
            last = now;
            auto after = read_threads();

            struct Use {
                std::string name;
                double percent;
            };
            std::vector<Use> uses;
            for (const auto& [tid, t] : after) {
                auto it = before.find(tid);
                const long long delta = t.ticks - (it != before.end() ? it->second.ticks : 0);
                if (delta > 0) {
                    uses.push_back({ t.name, 100.0 * delta / ticks_per_second / seconds });
                }
            }
            before = std::move(after);
            std::sort(uses.begin(), uses.end(), [](const Use& a, const Use& b) { return a.percent > b.percent; });

            std::string line = "Perf threads (% of one core):";
            double total = 0;
            for (const Use& u : uses) {
                total += u.percent;
            }
            for (size_t i = 0; i < uses.size() && i < 10; i++) {
                char part[80];
                snprintf(part, sizeof(part), " %s %.0f%%", uses[i].name.c_str(), uses[i].percent);
                line += part;
                if (i + 1 < uses.size() && i < 9) {
                    line += ",";
                }
            }
            char tail[64];
            snprintf(tail, sizeof(tail), " | total %.0f%%", total);
            line += tail;
            __android_log_print(ANDROID_LOG_INFO, log_tag, "%s", line.c_str());

            // Clock speeds, if readable (MHz).
            std::string clocks = "Perf clocks: CPU";
            for (long i = 0; i < cpus; i++) {
                const long khz = read_long("/sys/devices/system/cpu/cpu" + std::to_string(i) + "/cpufreq/scaling_cur_freq");
                char part[24];
                snprintf(part, sizeof(part), " %ld", khz > 0 ? khz / 1000 : -1);
                clocks += part;
            }
            long gpu = read_long("/sys/class/kgsl/kgsl-3d0/gpuclk");
            if (gpu > 0) {
                char part[48];
                snprintf(part, sizeof(part), " | GPU %ld", gpu / 1000000);
                clocks += part;
            }
            const long busy = read_long("/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load");
            if (busy >= 0) {
                char part[32];
                snprintf(part, sizeof(part), " (%ld%% busy)", busy);
                clocks += part;
            }
            __android_log_print(ANDROID_LOG_INFO, log_tag, "%s", clocks.c_str());
        }
    }
}

// Called once after SDL_Init (src/main/main.cpp).
extern "C" void bt_android_start_perf_monitor() {
    if (started.exchange(true)) {
        return;
    }
    std::thread([] {
        pthread_setname_np(pthread_self(), "Perf monitor");
        run();
    }).detach();
}
