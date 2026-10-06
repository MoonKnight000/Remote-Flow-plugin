package uz.remote.flow.profiler

import java.io.File

/**
 * Data structures for remote application performance metrics and profiling results.
 */
data class ProfilerMetric(
    val cpuPercent: Double,
    val heapUsedMb: Long,
    val heapMaxMb: Long,
    val pid: Long,
    val timestamp: Long = System.currentTimeMillis()
)

data class CpuHotspot(
    val rank: Int,
    val className: String,
    val methodName: String,
    val lineNumber: Int,
    val sampleCount: Int,
    val cpuPercent: Double
) {
    val fullMethodName: String get() = "$className.$methodName()"
}

data class MemoryAllocation(
    val rank: Int,
    val className: String,
    val allocationCount: Long,
    val totalBytes: Long
) {
    val totalMb: Double get() = totalBytes / (1024.0 * 1024.0)
}

data class ProfilingSnapshot(
    val id: String,
    val jfrFile: File?,
    val durationSeconds: Long,
    val timestamp: Long = System.currentTimeMillis(),
    val appPid: Long,
    val peakCpuPercent: Double,
    val averageCpuPercent: Double,
    val peakHeapUsedMb: Long,
    val heapMaxMb: Long,
    val totalSamples: Int,
    val hotspots: List<CpuHotspot>,
    val memoryAllocations: List<MemoryAllocation>,
    val gcCount: Long = 0,
    val gcTotalPauseMs: Long = 0
)

enum class ProfilerState {
    IDLE,
    RECORDING,
    ANALYZING
}

enum class ProfilerMode(val displayName: String, val description: String) {
    FULL("CPU & Memory Allocation", "Records both CPU execution samples and Heap allocations"),
    CPU_ONLY("CPU Sampling", "Records CPU execution samples and thread activity"),
    MEMORY_ONLY("Memory Allocation", "Records object allocation in new and outside TLAB")
}
