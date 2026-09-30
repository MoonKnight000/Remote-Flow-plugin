package uz.remote.flow.system

import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import uz.remote.flow.ssh.RemoteConnectionManager
import uz.remote.flow.ssh.ServerProfile
import java.util.concurrent.ConcurrentHashMap

object ServerHealthAlertManager {

    private const val ALERT_COOLDOWN_MS = 180_000L // 3 minutes

    private val lastAlertTimestamps = ConcurrentHashMap<String, Long>()

    fun checkAndAlert(project: Project, profile: ServerProfile, metrics: MachineMetrics) {
        val now = System.currentTimeMillis()
        val connMgr = RemoteConnectionManager.getInstance(project)

        // 1. RAM Alert (Threshold >= 90%)
        if (metrics.ramPercent >= 90) {
            val key = "${profile.id}:RAM"
            val last = lastAlertTimestamps[key] ?: 0L
            if (now - last > ALERT_COOLDOWN_MS) {
                lastAlertTimestamps[key] = now
                connMgr.notifyUser(
                    title = "⚠️ High RAM Alert: ${profile.name}",
                    message = "Memory usage has reached ${metrics.ramPercent}% (${metrics.ramText}). Risk of application termination by Linux OOM killer!",
                    type = NotificationType.WARNING
                )
            }
        }

        // 2. CPU Alert (Threshold >= 90%)
        if (metrics.cpuPercent >= 90) {
            val key = "${profile.id}:CPU"
            val last = lastAlertTimestamps[key] ?: 0L
            if (now - last > ALERT_COOLDOWN_MS) {
                lastAlertTimestamps[key] = now
                connMgr.notifyUser(
                    title = "⚠️ High CPU Alert: ${profile.name}",
                    message = "CPU load is at ${metrics.cpuText}. Remote processes may become slow or unresponsive.",
                    type = NotificationType.WARNING
                )
            }
        }

        // 3. Disk Alert (Threshold >= 95%)
        if (metrics.diskPercent >= 95) {
            val key = "${profile.id}:DISK"
            val last = lastAlertTimestamps[key] ?: 0L
            if (now - last > ALERT_COOLDOWN_MS) {
                lastAlertTimestamps[key] = now
                connMgr.notifyUser(
                    title = "🚨 Critical Disk Alert: ${profile.name}",
                    message = "Disk usage has reached ${metrics.diskPercent}% (${metrics.diskText}). Root partition is almost out of space!",
                    type = NotificationType.ERROR
                )
            }
        }
    }

    fun isCritical(metrics: MachineMetrics?): Boolean {
        if (metrics == null) return false
        return metrics.ramPercent >= 90 || metrics.cpuPercent >= 90 || metrics.diskPercent >= 95
    }

    fun getAlertSummary(metrics: MachineMetrics?): String? {
        if (metrics == null) return null
        val alerts = mutableListOf<String>()
        if (metrics.ramPercent >= 90) alerts.add("RAM ${metrics.ramPercent}%")
        if (metrics.cpuPercent >= 90) alerts.add("CPU ${metrics.cpuPercent}%")
        if (metrics.diskPercent >= 95) alerts.add("DISK ${metrics.diskPercent}%")
        return if (alerts.isNotEmpty()) "⚠️ Warning: " + alerts.joinToString(", ") else null
    }
}
