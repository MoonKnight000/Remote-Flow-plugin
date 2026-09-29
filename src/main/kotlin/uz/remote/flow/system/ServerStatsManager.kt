package uz.remote.flow.system

import com.intellij.openapi.project.Project
import uz.remote.flow.ssh.RemoteConnectionManager

data class MachineMetrics(
    var cpuPercent: Int = 0,
    var cpuText: String = "N/A",
    var cpuCores: List<Int> = emptyList(),
    var coreCount: Int = 0,
    var ramUsedGb: Double = 0.0,
    var ramTotalGb: Double = 0.0,
    var ramPercent: Int = 0,
    var ramText: String = "N/A",
    var diskUsedGb: Double = 0.0,
    var diskTotalGb: Double = 0.0,
    var diskPercent: Int = 0,
    var diskText: String = "N/A"
)

data class ProcessInfo(
    val pid: String,
    val user: String,
    val cpuPercent: Double,
    val memPercent: Double,
    val rssMb: Double,
    val command: String
)

class ServerStatsManager(private val project: Project) {

    private val connectionManager get() = RemoteConnectionManager.getInstance(project)

    fun fetchMetrics(
        onParsed: (MachineMetrics) -> Unit,
        onLog: (String) -> Unit,
        onComplete: (Boolean) -> Unit
    ) {
        val script = "echo '---METRICS_START---'; " +
            "echo -n 'RAM: '; free -m 2>/dev/null | awk 'NR==2{printf \"%s %s\\n\", \$3, \$2}' || echo '0 0'; " +
            "echo -n 'DISK: '; df -m / 2>/dev/null | awk 'NR==2{printf \"%s %s\\n\", \$3, \$2}' || echo '0 0'; " +
            "echo '---CPU_SAMPLE1---'; " +
            "grep '^cpu' /proc/stat 2>/dev/null || (top -bn1 | grep -i 'Cpu(s)' || uptime); " +
            "sleep 0.25; " +
            "echo '---CPU_SAMPLE2---'; " +
            "grep '^cpu' /proc/stat 2>/dev/null || true; " +
            "echo '---METRICS_END---'"

        val fullOutput = StringBuilder()

        connectionManager.executeRemoteCommand(
            cmd = script,
            workingDir = "",
            onOutput = { line ->
                fullOutput.append(line)
                onLog(line)
            },
            onComplete = { code ->
                if (code == 0) {
                    val metrics = parseMetrics(fullOutput.toString())
                    onParsed(metrics)
                    onComplete(true)
                } else {
                    onComplete(false)
                }
            }
        )
    }

    fun fetchTopProcesses(
        sortBy: String = "CPU",
        limit: Int = 40,
        onSuccess: (List<ProcessInfo>) -> Unit,
        onError: (String) -> Unit
    ) {
        val sortFlag = when (sortBy) {
            "MEM" -> "-%mem"
            "NAME" -> "args"
            else -> "-%cpu"
        }
        val cmd = "ps -eo pid:10,user:14,%cpu:8,%mem:8,rss:12,args --sort=$sortFlag 2>/dev/null | head -n ${limit + 1}"
        val outputSb = StringBuilder()

        connectionManager.executeRemoteCommand(
            cmd = cmd,
            workingDir = "",
            onOutput = { line -> outputSb.append(line) },
            onComplete = { code ->
                if (code == 0) {
                    val procs = parseProcessOutput(outputSb.toString())
                    onSuccess(procs)
                } else {
                    onError("Failed to retrieve processes (Exit code: $code)")
                }
            }
        )
    }

    fun terminateProcess(pid: String, force: Boolean, onResult: (Boolean, String) -> Unit) {
        val signal = if (force) "-9" else "-15"
        val cmd = "kill $signal $pid 2>&1"
        val outputSb = StringBuilder()

        connectionManager.executeRemoteCommand(
            cmd = cmd,
            workingDir = "",
            onOutput = { outputSb.append(it) },
            onComplete = { code ->
                val msg = outputSb.toString().trim()
                if (code == 0) {
                    onResult(true, "Process $pid successfully signaled ($signal)")
                } else {
                    onResult(false, if (msg.isNotBlank()) msg else "Failed to kill process $pid (Exit code: $code)")
                }
            }
        )
    }

    private fun parseProcessOutput(output: String): List<ProcessInfo> {
        val list = mutableListOf<ProcessInfo>()
        val lines = output.lines()
        for (i in 1 until lines.size) {
            val line = lines[i].trim()
            if (line.isBlank()) continue
            val parts = line.split("\\s+".toRegex(), limit = 6)
            if (parts.size >= 6) {
                val pid = parts[0]
                val user = parts[1]
                val cpu = parts[2].toDoubleOrNull() ?: 0.0
                val mem = parts[3].toDoubleOrNull() ?: 0.0
                val rssKb = parts[4].toDoubleOrNull() ?: 0.0
                val rssMb = rssKb / 1024.0
                val command = parts[5]
                list.add(ProcessInfo(pid, user, cpu, mem, rssMb, command))
            }
        }
        return list
    }

    private data class CpuRaw(val active: Long, val total: Long)

    private fun parseCpuRawLine(line: String): Pair<String, CpuRaw>? {
        val parts = line.trim().split("\\s+".toRegex())
        if (parts.size < 5) return null
        val key = parts[0]
        val user = parts.getOrNull(1)?.toLongOrNull() ?: 0L
        val nice = parts.getOrNull(2)?.toLongOrNull() ?: 0L
        val system = parts.getOrNull(3)?.toLongOrNull() ?: 0L
        val idle = parts.getOrNull(4)?.toLongOrNull() ?: 0L
        val iowait = parts.getOrNull(5)?.toLongOrNull() ?: 0L
        val irq = parts.getOrNull(6)?.toLongOrNull() ?: 0L
        val softirq = parts.getOrNull(7)?.toLongOrNull() ?: 0L
        val steal = parts.getOrNull(8)?.toLongOrNull() ?: 0L

        val idleTime = idle + iowait
        val activeTime = user + nice + system + irq + softirq + steal
        val totalTime = idleTime + activeTime
        return Pair(key, CpuRaw(activeTime, totalTime))
    }

    private fun calcPercent(raw1: CpuRaw?, raw2: CpuRaw?): Int {
        if (raw1 == null || raw2 == null) return 0
        val deltaActive = raw2.active - raw1.active
        val deltaTotal = raw2.total - raw1.total
        if (deltaTotal <= 0) return 0
        val pct = (deltaActive.toDouble() / deltaTotal.toDouble()) * 100.0
        return pct.toInt().coerceIn(0, 100)
    }

    private fun parseMetrics(output: String): MachineMetrics {
        val m = MachineMetrics()
        try {
            val lines = output.lines()
            var inSample1 = false
            var inSample2 = false
            val sample1Map = mutableMapOf<String, CpuRaw>()
            val sample2Map = mutableMapOf<String, CpuRaw>()
            var fallbackCpuFromTop: Int? = null

            for (rawLine in lines) {
                val trimmed = rawLine.trim()
                if (trimmed == "---CPU_SAMPLE1---") {
                    inSample1 = true
                    inSample2 = false
                    continue
                } else if (trimmed == "---CPU_SAMPLE2---") {
                    inSample1 = false
                    inSample2 = true
                    continue
                } else if (trimmed == "---METRICS_END---") {
                    inSample1 = false
                    inSample2 = false
                    continue
                }

                if (inSample1) {
                    val parsed = parseCpuRawLine(trimmed)
                    if (parsed != null) {
                        sample1Map[parsed.first] = parsed.second
                    } else if (trimmed.contains("Cpu(s)", ignoreCase = true)) {
                        // Fallback top -bn1
                        val parts = trimmed.split("\\s+".toRegex())
                        val userVal = parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                        val sysVal = parts.getOrNull(3)?.toDoubleOrNull() ?: 0.0
                        fallbackCpuFromTop = (userVal + sysVal).toInt().coerceIn(0, 100)
                    }
                } else if (inSample2) {
                    val parsed = parseCpuRawLine(trimmed)
                    if (parsed != null) {
                        sample2Map[parsed.first] = parsed.second
                    }
                } else {
                    if (trimmed.startsWith("RAM:")) {
                        val parts = trimmed.removePrefix("RAM:").trim().split("\\s+".toRegex())
                        if (parts.size >= 2) {
                            val usedMb = parts[0].toDoubleOrNull() ?: 0.0
                            val totalMb = parts[1].toDoubleOrNull() ?: 1.0
                            m.ramUsedGb = (usedMb / 1024.0)
                            m.ramTotalGb = (totalMb / 1024.0)
                            m.ramPercent = ((usedMb / totalMb) * 100.0).toInt().coerceIn(0, 100)
                            m.ramText = String.format("%.2f GB / %.2f GB (%d%%)", m.ramUsedGb, m.ramTotalGb, m.ramPercent)
                        }
                    } else if (trimmed.startsWith("DISK:")) {
                        val parts = trimmed.removePrefix("DISK:").trim().split("\\s+".toRegex())
                        if (parts.size >= 2) {
                            val usedMb = parts[0].toDoubleOrNull() ?: 0.0
                            val totalMb = parts[1].toDoubleOrNull() ?: 1.0
                            m.diskUsedGb = (usedMb / 1024.0)
                            m.diskTotalGb = (totalMb / 1024.0)
                            m.diskPercent = ((usedMb / totalMb) * 100.0).toInt().coerceIn(0, 100)
                            m.diskText = String.format("%.1f GB / %.1f GB (%d%%)", m.diskUsedGb, m.diskTotalGb, m.diskPercent)
                        }
                    }
                }
            }

            // Calculate CPU from /proc/stat samples
            if (sample1Map.containsKey("cpu") && sample2Map.containsKey("cpu")) {
                m.cpuPercent = calcPercent(sample1Map["cpu"], sample2Map["cpu"])
            } else if (fallbackCpuFromTop != null) {
                m.cpuPercent = fallbackCpuFromTop
            }

            // Calculate per-core CPU
            val coreKeys = sample1Map.keys.filter { it.matches(Regex("cpu[0-9]+")) }
                .sortedBy { it.removePrefix("cpu").toIntOrNull() ?: 0 }

            val corePercentages = mutableListOf<Int>()
            for (key in coreKeys) {
                if (sample2Map.containsKey(key)) {
                    val corePct = calcPercent(sample1Map[key], sample2Map[key])
                    corePercentages.add(corePct)
                }
            }

            m.cpuCores = corePercentages
            m.coreCount = corePercentages.size

            if (m.coreCount > 0) {
                m.cpuText = String.format("%d%% (%d Cores)", m.cpuPercent, m.coreCount)
            } else {
                m.cpuText = String.format("%d%%", m.cpuPercent)
            }

        } catch (_: Exception) {}
        return m
    }
}
