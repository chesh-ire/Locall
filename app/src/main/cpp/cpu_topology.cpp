#include "cpu_topology.h"

#include <sched.h>
#include <unistd.h>
#include <cstdio>
#include <algorithm>
#include <android/log.h>

#define LOG_TAG "LlamaTopology"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static long read_max_freq(int cpu) {
    char path[128];
    snprintf(path, sizeof(path),
             "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", cpu);
    FILE *f = fopen(path, "r");
    if (!f) return -1;
    long khz = -1;
    if (fscanf(f, "%ld", &khz) != 1) khz = -1;
    fclose(f);
    return khz;
}

CpuTopology detect_cpu_topology() {
    CpuTopology topo;
    int n = (int) sysconf(_SC_NPROCESSORS_CONF);
    if (n <= 0) n = 1;
    topo.total_cores = n;

    std::vector<std::pair<int, long>> freqs;
    freqs.reserve(n);
    for (int i = 0; i < n; ++i) {
        long khz = read_max_freq(i);
        freqs.emplace_back(i, khz);
    }

    std::sort(freqs.begin(), freqs.end(),
              [](const auto &a, const auto &b) { return a.second > b.second; });

    long top = freqs.empty() ? -1 : freqs.front().second;
    for (auto &p : freqs) {
        topo.cores_by_speed.push_back(p.first);
        if (top > 0 && p.second >= top * 0.65) {
            topo.performance_cores.push_back(p.first);
        }
    }

    if (topo.performance_cores.empty()) {
        int half = std::max(1, n / 2);
        for (int i = 0; i < half; ++i) topo.performance_cores.push_back(i);
    }

    for (int c : topo.cores_by_speed) {
        bool is_perf = false;
        for (int p : topo.performance_cores) if (p == c) { is_perf = true; break; }
        if (!is_perf) topo.efficiency_cores.push_back(c);
    }
    if (topo.efficiency_cores.empty()) topo.efficiency_cores = topo.performance_cores;

    int perf = (int) topo.performance_cores.size();
    topo.suggested_decode_threads = std::min(perf, 3);
    topo.suggested_batch_threads = perf;

    LOGI("cores=%d perf_cluster=%d decode=%d batch=%d",
         topo.total_cores, perf,
         topo.suggested_decode_threads, topo.suggested_batch_threads);
    return topo;
}

bool pin_current_thread_to(const std::vector<int> &cores) {
    if (cores.empty()) return false;
    cpu_set_t set;
    CPU_ZERO(&set);
    for (int c : cores) CPU_SET(c, &set);
    int rc = sched_setaffinity(0, sizeof(set), &set);
    if (rc != 0) {
        LOGI("sched_setaffinity failed (rc=%d) - continuing unpinned", rc);
        return false;
    }
    return true;
}
