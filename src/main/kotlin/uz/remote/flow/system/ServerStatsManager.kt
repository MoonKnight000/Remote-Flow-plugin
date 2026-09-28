package uz.remote.flow.system

import com.intellij.openapi.project.Project
import uz.remote.flow.ssh.RemoteConnectionManager

data class MachineMetrics(
    var cpuPercent: Int = 0,
    var cpuText: String = "N/A",
    var ramUsedGb: Double = 0.0,
    var ramTotalGb: Double = 0.0,
    var ramPercent: Int = 0,
    var ramText: String = "N/A",
    var diskUsedGb: Double = 0.0,
    var diskTotalGb: Double = 0.0,
    var diskPercent: Int = 0,
    var diskText: String = "N/A",
    var rawDockerStats: String = ""
)

class ServerStatsManager(private val project: Project) {

    private val connectionManager get() = RemoteConnectionManager.getInstance(project)

    fun fetchMetrics(
        onParsed: (MachineMetrics) -> Unit,
        onLog: (String) -> Unit,
        onComplete: (Boolean) -> Unit
    ) {
        val script = "echo '---METRICS_START---'; " +
            "echo -n 'RAM: '; free -m | awk 'NR==2{printf \"%s %s\\n\", \$3, \$2}'; " +
            "echo -n 'DISK: '; df -m / | awk 'NR==2{printf \"%s %s\\n\", \$3, \$2}'; " +
            "echo -n 'CPU: '; top -bn1 | grep -i 'Cpu(s)' | awk '{print \$2 + \$4}' 2>/dev/null || uptime; " +
            "echo '---DOCKER_START---'; " +
            "docker stats --no-stream --format 'table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.NetIO}}' 2>/dev/null || echo 'No containers'; " +
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

    private fun parseMetrics(output: String): MachineMetrics {
        val m = MachineMetrics()
        try {
            val lines = output.lines()
            for (line in lines) {
                val trimmed = line.trim()
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
                } else if (trimmed.startsWith("CPU:")) {
                    val valStr = trimmed.removePrefix("CPU:").trim()
                    val cpuVal = valStr.toDoubleOrNull() ?: 0.0
                    m.cpuPercent = cpuVal.toInt().coerceIn(0, 100)
                    m.cpuText = String.format("%d%%", m.cpuPercent)
                }
            }

            if (output.contains("---DOCKER_START---") && output.contains("---METRICS_END---")) {
                m.rawDockerStats = output.substringAfter("---DOCKER_START---").substringBefore("---METRICS_END---").trim()
            }
        } catch (_: Exception) {}
        return m
    }
}
