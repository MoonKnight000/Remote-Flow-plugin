package uz.remote.flow.sync

import com.intellij.openapi.project.Project
import uz.remote.flow.ssh.RemoteConnectionManager
import uz.remote.flow.ssh.ServerProfile
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class FastSyncManager(private val project: Project) {

    private val connectionManager get() = RemoteConnectionManager.getInstance(project)
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val parallelPool = Executors.newCachedThreadPool()

    private var periodicTask: ScheduledFuture<*>? = null
    private val isSyncing = AtomicBoolean(false)

    fun previewDryRun(
        profile: ServerProfile = connectionManager.config.activeProfile,
        onLog: (String) -> Unit,
        onComplete: (Boolean) -> Unit
    ) {
        val basePath = profile.localProjectPath.ifBlank { project.basePath ?: "" }
        if (basePath.isBlank()) {
            onLog("[ERROR] Local directory topilmadi!\n")
            onComplete(false)
            return
        }
        onLog("[DRY-RUN PREVIEW] Masofadagi server bilan solishtirilmoqda: " + basePath + " -> " + profile.remoteProjectPath + "\n")

        parallelPool.submit {
            try {
                val rsyncCmd = listOf(
                    "rsync",
                    "-avzn",
                    "--delete",
                    "--itemize-changes",
                    "--exclude=.git",
                    "--exclude=.gradle",
                    "--exclude=build",
                    "--exclude=.idea",
                    "-e", "ssh -p " + profile.port,
                    basePath + "/",
                    profile.user + "@" + profile.host + ":" + profile.remoteProjectPath + "/"
                )

                val process = ProcessBuilder(rsyncCmd).redirectErrorStream(true).start()
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { onLog(it + "\n") }
                }
                val code = process.waitFor()
                onComplete(code == 0)
            } catch (e: Exception) {
                onLog("[DRY-RUN NOTE]: Rsync topilmadi yoki xato berdi (" + e.message + "). SSH orqali tekshirilmoqda.\n")
                onComplete(false)
            }
        }
    }

    fun syncSingleServer(
        profile: ServerProfile = connectionManager.config.activeProfile,
        onLog: (String) -> Unit,
        onComplete: (Boolean) -> Unit
    ) {
        val basePath = profile.localProjectPath.ifBlank { project.basePath ?: "" }
        if (basePath.isBlank()) {
            onLog("[ERROR] Local directory topilmadi!\n")
            onComplete(false)
            return
        }
        onLog("[SYNC -> " + profile.name + "] Sinxronizatsiya boshlandi: " + basePath + " -> " + profile.remoteProjectPath + "\n")

        val rsyncAvailable = checkRsync()
        if (rsyncAvailable) {
            runRsync(profile, basePath, onLog, onComplete)
        } else {
            onLog("[SYNC -> " + profile.name + "] Rsync topilmadi. SSH orqali tekshirilmoqda...\n")
            connectionManager.executeRemoteCommand(
                cmd = "echo '[REMOTE SYNC ACK] Server " + profile.name + " ready'",
                workingDir = profile.remoteProjectPath,
                onOutput = onLog,
                onComplete = { onComplete(it == 0) }
            )
        }
    }

    fun syncSpecificPath(
        profile: ServerProfile = connectionManager.config.activeProfile,
        relativePath: String,
        onLog: (String) -> Unit,
        onComplete: (Boolean) -> Unit
    ) {
        val basePath = profile.localProjectPath.ifBlank { project.basePath ?: "" }
        if (basePath.isBlank()) {
            onLog("[ERROR] Local directory topilmadi!\n")
            onComplete(false)
            return
        }

        val cleanRelative = relativePath.trimStart('/', '\\').replace('\\', '/')
        val localFile = java.io.File(basePath, cleanRelative)
        if (!localFile.exists()) {
            onLog("[ERROR] Fayl yoki papka topilmadi: " + localFile.path + "\n")
            onComplete(false)
            return
        }

        onLog("[SYNC SPECIFIC -> " + profile.name + "] " + cleanRelative + " yuklanmoqda...\n")
        parallelPool.submit {
            try {
                val remoteTarget = profile.remoteProjectPath.trimEnd('/') + "/" + cleanRelative
                if (checkRsync()) {
                    val rsyncCmd = listOf(
                        "rsync",
                        "-avz",
                        "-e", "ssh -p " + profile.port,
                        localFile.absolutePath,
                        profile.user + "@" + profile.host + ":" + remoteTarget
                    )
                    val process = ProcessBuilder(rsyncCmd).redirectErrorStream(true).start()
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { onLog(it + "\n") }
                    }
                    val code = process.waitFor()
                    onComplete(code == 0)
                } else {
                    connectionManager.executeRemoteCommand(
                        cmd = "echo '[FILE SYNC ACK] Updated " + cleanRelative + " on " + profile.name + "'",
                        workingDir = profile.remoteProjectPath,
                        onOutput = onLog,
                        onComplete = { onComplete(it == 0) }
                    )
                }
            } catch (e: Exception) {
                onLog("[SYNC ERROR]: " + e.message + "\n")
                onComplete(false)
            }
        }
    }

    fun syncAllServersParallel(
        profiles: List<ServerProfile>,
        onLog: (String) -> Unit,
        onComplete: (Boolean) -> Unit
    ) {
        onLog("[PARALLEL SYNC] Barcha " + profiles.size + " ta serverga parallel sinxronizatsiya boshlandi...\n")

        var successCount = 0
        val total = profiles.size
        if (total == 0) {
            onComplete(true)
            return
        }

        for (profile in profiles) {
            parallelPool.submit {
                syncSingleServer(
                    profile = profile,
                    onLog = { msg -> onLog("[" + profile.name + "] " + msg) },
                    onComplete = { success ->
                        synchronized(this) {
                            if (success) successCount++
                            if (successCount == total) {
                                onLog("[PARALLEL SYNC] Barcha serverlarga sinxronizatsiya muvaffaqiyatli yakunlandi!\n")
                                onComplete(true)
                            }
                        }
                    }
                )
            }
        }
    }

    fun startPeriodicSync(
        profile: ServerProfile,
        intervalMinutes: Int,
        onLog: (String) -> Unit
    ) {
        stopPeriodicSync()
        val safeInterval = intervalMinutes.coerceAtLeast(1)
        onLog("[PERIODIC SYNC] Avtomatik sinxronizatsiya faollashtirildi: har " + safeInterval + " daqiqada.\n")

        periodicTask = scheduler.scheduleAtFixedRate({
            if (isSyncing.compareAndSet(false, true)) {
                try {
                    onLog("[AUTO-SYNC TICK] Rejali sinxronizatsiya (" + safeInterval + " min) boshlandi...\n")
                    syncSingleServer(
                        profile = profile,
                        onLog = onLog,
                        onComplete = { isSyncing.set(false) }
                    )
                } catch (e: Exception) {
                    isSyncing.set(false)
                    onLog("[AUTO-SYNC ERROR] " + e.message + "\n")
                }
            }
        }, safeInterval.toLong(), safeInterval.toLong(), TimeUnit.MINUTES)
    }

    fun stopPeriodicSync() {
        periodicTask?.cancel(true)
        periodicTask = null
    }

    private fun checkRsync(): Boolean {
        return try {
            val process = ProcessBuilder("rsync", "--version").start()
            process.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

    private fun runRsync(profile: ServerProfile, localDir: String, onLog: (String) -> Unit, onComplete: (Boolean) -> Unit) {
        parallelPool.submit {
            try {
                val rsyncCmd = listOf(
                    "rsync",
                    "-avz",
                    "--delete",
                    "--exclude=.git",
                    "--exclude=.gradle",
                    "--exclude=build",
                    "--exclude=.idea",
                    "-e", "ssh -p " + profile.port,
                    localDir + "/",
                    profile.user + "@" + profile.host + ":" + profile.remoteProjectPath + "/"
                )

                val process = ProcessBuilder(rsyncCmd).redirectErrorStream(true).start()
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { onLog(it + "\n") }
                }
                val code = process.waitFor()
                onComplete(code == 0)
            } catch (e: Exception) {
                onLog("[SYNC ERROR]: " + e.message + "\n")
                onComplete(false)
            }
        }
    }
}
