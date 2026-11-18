// Performance hints (Android 13+, "ADPF"): tells Android how long each rendered frame is
// allowed to take and how long it really took, so the phone raises its clocks *before* frames
// run late instead of after (the "clocks drop, frames slow, clocks drop further" loop seen in
// Angry Aztec in DK64). Covers RT64's workload and present threads and the game's own thread.
//
// RT64 (rt64-android.patch) calls bt_android_perf_hint_frame() from the workload thread
// once per rendered frame; threads register with bt_android_perf_hint_add_thread().
// On Android 15+ the report also splits CPU and GPU time.
//
// The API is looked up at run time (the app supports Android 9), so older phones just skip it.

#include <android/log.h>
#include <dlfcn.h>
#include <sys/system_properties.h>
#include <time.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstdlib>
#include <mutex>
#include <vector>

namespace {
    constexpr const char* log_tag = "BTRecomp";

    struct APerformanceHintManager;
    struct APerformanceHintSession;
    struct AWorkDuration;

    using GetManagerFn = APerformanceHintManager* (*)();
    using CreateSessionFn = APerformanceHintSession* (*)(APerformanceHintManager*, const int32_t*, size_t, int64_t);
    using UpdateTargetFn = int (*)(APerformanceHintSession*, int64_t);
    using ReportActualFn = int (*)(APerformanceHintSession*, int64_t);
    using CloseSessionFn = void (*)(APerformanceHintSession*);
    using SetThreadsFn = int (*)(APerformanceHintSession*, const int32_t*, size_t);           // API 34
    using WorkDurationCreateFn = AWorkDuration* (*)();                                          // API 35
    using WorkDurationReleaseFn = void (*)(AWorkDuration*);
    using WorkDurationSetFn = void (*)(AWorkDuration*, int64_t);
    using ReportActual2Fn = int (*)(APerformanceHintSession*, AWorkDuration*);

    struct Api {
        bool loaded = false;
        GetManagerFn getManager = nullptr;
        CreateSessionFn createSession = nullptr;
        UpdateTargetFn updateTarget = nullptr;
        ReportActualFn reportActual = nullptr;
        CloseSessionFn closeSession = nullptr;
        SetThreadsFn setThreads = nullptr;
        WorkDurationCreateFn wdCreate = nullptr;
        WorkDurationReleaseFn wdRelease = nullptr;
        WorkDurationSetFn wdSetStart = nullptr, wdSetTotal = nullptr, wdSetCpu = nullptr, wdSetGpu = nullptr;
        ReportActual2Fn reportActual2 = nullptr;
    };

    std::mutex mutex;
    Api api;
    bool tried = false;
    APerformanceHintSession* session = nullptr;
    std::vector<int32_t> threads;
    bool threads_changed = false;
    int64_t current_target = 0;
    AWorkDuration* work_duration = nullptr;
    int64_t last_cpu_ns = 0;
    uint32_t reports = 0;

    template <typename T>
    void load(void* lib, T& fn, const char* name) {
        fn = reinterpret_cast<T>(dlsym(lib, name));
    }

    bool load_api() {
        if (tried) {
            return api.loaded;
        }
        tried = true;
        char sdk[PROP_VALUE_MAX] = {};
        if (__system_property_get("ro.build.version.sdk", sdk) <= 0 || atoi(sdk) < 33) {
            return false;
        }
        void* lib = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
        if (lib == nullptr) {
            return false;
        }
        load(lib, api.getManager, "APerformanceHint_getManager");
        load(lib, api.createSession, "APerformanceHint_createSession");
        load(lib, api.updateTarget, "APerformanceHint_updateTargetWorkDuration");
        load(lib, api.reportActual, "APerformanceHint_reportActualWorkDuration");
        load(lib, api.closeSession, "APerformanceHint_closeSession");
        load(lib, api.setThreads, "APerformanceHint_setThreads");
        load(lib, api.wdCreate, "AWorkDuration_create");
        load(lib, api.wdRelease, "AWorkDuration_release");
        load(lib, api.wdSetStart, "AWorkDuration_setWorkPeriodStartTimestampNanos");
        load(lib, api.wdSetTotal, "AWorkDuration_setActualTotalDurationNanos");
        load(lib, api.wdSetCpu, "AWorkDuration_setActualCpuDurationNanos");
        load(lib, api.wdSetGpu, "AWorkDuration_setActualGpuDurationNanos");
        load(lib, api.reportActual2, "APerformanceHint_reportActualWorkDuration2");
        api.loaded = api.getManager && api.createSession && api.updateTarget && api.reportActual && api.closeSession;
        if (!(api.wdCreate && api.wdRelease && api.wdSetStart && api.wdSetTotal && api.wdSetCpu && api.wdSetGpu && api.reportActual2)) {
            api.reportActual2 = nullptr;
        }
        __android_log_print(ANDROID_LOG_INFO, log_tag, "Performance hints: %s%s",
            api.loaded ? "available" : "not available",
            api.loaded && api.reportActual2 != nullptr ? " (with GPU time)" : "");
        return api.loaded;
    }

    int64_t thread_cpu_ns() {
        timespec ts{};
        clock_gettime(CLOCK_THREAD_CPUTIME_ID, &ts);
        return int64_t(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
    }

    int64_t now_ns() {
        timespec ts{};
        clock_gettime(CLOCK_MONOTONIC, &ts);
        return int64_t(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
    }

    // With the mutex held: (re)creates the session for the current threads.
    void ensure_session_locked(int64_t target_ns) {
        if (session != nullptr && threads_changed) {
            if (api.setThreads != nullptr && api.setThreads(session, threads.data(), threads.size()) == 0) {
                threads_changed = false;
            } else {
                api.closeSession(session);
                session = nullptr;
            }
        }
        if (session == nullptr) {
            APerformanceHintManager* manager = api.getManager();
            if (manager == nullptr) {
                return;
            }
            session = api.createSession(manager, threads.data(), threads.size(), target_ns);
            threads_changed = false;
            current_target = target_ns;
            __android_log_print(ANDROID_LOG_INFO, log_tag, "Performance hints: session for %zu threads, %.1f ms per frame%s",
                threads.size(), target_ns / 1e6, session == nullptr ? " (refused)" : "");
            if (session != nullptr && api.reportActual2 != nullptr && work_duration == nullptr) {
                work_duration = api.wdCreate();
            }
        }
    }
}

// A thread whose speed matters for frame times (the calling thread).
extern "C" void bt_android_perf_hint_add_thread() {
    const int32_t tid = gettid();
    std::lock_guard lock{mutex};
    if (std::find(threads.begin(), threads.end(), tid) == threads.end()) {
        threads.push_back(tid);
        threads_changed = true;
    }
}

// From RT64's workload thread, after each rendered frame: how long it took (wall clock, from
// starting to record it until the GPU finished) and how long it was allowed.
extern "C" void bt_android_perf_hint_frame(int64_t start_ns, int64_t wall_ns, int64_t target_ns) {
    if (target_ns <= 0 || wall_ns <= 0) {
        return;
    }
    const int64_t cpu_now = thread_cpu_ns();
    std::lock_guard lock{mutex};
    if (!load_api()) {
        return;
    }
    const int32_t tid = gettid();
    if (std::find(threads.begin(), threads.end(), tid) == threads.end()) {
        threads.push_back(tid);
        threads_changed = true;
    }
    ensure_session_locked(target_ns);
    if (session == nullptr) {
        return;
    }
    // The target changes when the frame rate setting does (30, 60...).
    if (std::abs(target_ns - current_target) > 100000) {
        api.updateTarget(session, target_ns);
        current_target = target_ns;
    }
    const int64_t cpu = last_cpu_ns == 0 ? wall_ns : std::clamp<int64_t>(cpu_now - last_cpu_ns, 0, wall_ns);
    last_cpu_ns = cpu_now;
    int result;
    if (api.reportActual2 != nullptr && work_duration != nullptr) {
        api.wdSetStart(work_duration, start_ns > 0 ? start_ns : now_ns() - wall_ns);
        api.wdSetTotal(work_duration, wall_ns);
        api.wdSetCpu(work_duration, std::max<int64_t>(cpu, 1));
        api.wdSetGpu(work_duration, std::max<int64_t>(wall_ns - cpu, 0));
        result = api.reportActual2(session, work_duration);
    } else {
        result = api.reportActual(session, wall_ns);
    }
    if (result != 0 && (reports++ % 600) == 0) {
        __android_log_print(ANDROID_LOG_WARN, log_tag, "Performance hints: report failed (%d)", result);
    }
}

// Keep GPU Awake (Graphics): whether RT64 keeps sending its idle GPU work between frames.
static std::atomic<bool> g_gpu_keep_awake{false};

extern "C" void bt_android_set_gpu_keep_awake(bool on) {
    g_gpu_keep_awake.store(on);
}

extern "C" bool bt_android_gpu_keep_awake() {
    return g_gpu_keep_awake.load();
}

extern "C" int64_t bt_android_perf_hint_now_ns() {
    return now_ns();
}
