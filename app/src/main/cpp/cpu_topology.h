#ifndef LOCALL_CPU_TOPOLOGY_H
#define LOCALL_CPU_TOPOLOGY_H

#include <vector>
#include <cstdint>

struct CpuTopology {
    int total_cores = 0;
    std::vector<int> cores_by_speed;
    std::vector<int> performance_cores;
    std::vector<int> efficiency_cores;
    int suggested_decode_threads = 4;
    int suggested_batch_threads = 4;
};

CpuTopology detect_cpu_topology();

bool pin_current_thread_to(const std::vector<int> &cores);

#endif
