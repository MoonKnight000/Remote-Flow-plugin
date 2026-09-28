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
    private val logService get() = uz.remote.flow.logging.RemoteFlowLogService.getInstance(project)
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val parallelPool = Executors.newCachedThreadPool()

    private var periodicTask: ScheduledFuture<*>? = null
    private val isSyncing = AtomicBoolean(false)

    private fun log(profile: ServerProfile, msg: String, onLog: (String) -> Unit) {
        logService.log(msg, uz.remote.flow.logging.LogCategory.SYNC, profile.name)
        onLog(msg)
    }

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
                val exe = resolveRsyncExecutable(profile)
                val excludes = uz.remote.flow.ssh.parseExcludeList(profile.excludePatterns)
                val rsyncCmd = mutableListOf(
                    exe,
                    "-avzn",
                    "--delete",
                    "--itemize-changes"
                )
                excludes.forEach { rsyncCmd.add("--exclude=$it") }
                val rsyncSource = convertToRsyncPath(basePath.trimEnd('/') + "/", exe)
                val rsyncDest = "${profile.user}@${profile.host}:${profile.remoteProjectPath.trimEnd('/')}/"

                val sshCmd = mutableListOf("ssh", "-p", profile.port.toString(), "-o", "StrictHostKeyChecking=no", "-o", "UserKnownHostsFile=/dev/null")
                if (profile.authType == uz.remote.flow.ssh.AuthType.PRIVATE_KEY && profile.privateKeyPath.isNotBlank()) {
                    val keyPath = convertToRsyncPath(profile.privateKeyPath, exe)
                    sshCmd.addAll(listOf("-i", keyPath))
                }

                rsyncCmd.addAll(listOf(
                    "-e", sshCmd.joinToString(" "),
                    rsyncSource,
                    rsyncDest
                ))

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

        val rsyncAvailable = checkRsync(profile)
        connectionManager.createDirectory(profile, profile.remoteProjectPath) { _, _ ->
            if (rsyncAvailable) {
                runRsync(profile, basePath, onLog, onComplete)
            } else {
                runSftpArchiveSync(profile, basePath, onLog, onComplete)
            }
        }
    }

    private fun runSftpArchiveSync(
        profile: ServerProfile,
        basePath: String,
        onLog: (String) -> Unit,
        onComplete: (Boolean) -> Unit
    ) {
        parallelPool.submit {
            var tempZip: java.io.File? = null
            try {
                val hasRsync = checkRsync(profile)
                val methodNotice = if (!hasRsync) "Rsync tizimda topilmadi" else "SFTP Archive Sync rejimi"
                onLog("[SFTP SYNC -> " + profile.name + "] " + methodNotice + ". SSH/SFTP orqali sinxronlashtirilmoqda...\n")
                val baseDir = java.io.File(basePath)
                if (!baseDir.exists() || !baseDir.isDirectory) {
                    onLog("[ERROR] Local papka mavjud emas: " + basePath + "\n")
                    onComplete(false)
                    return@submit
                }

                val excludes = uz.remote.flow.ssh.parseExcludeList(profile.excludePatterns)

                onLog("[SFTP SYNC] Loyiha fayllari paketlanmoqda (${excludes.size} ta exclude qoidasi bo'yicha)...\n")
                tempZip = java.io.File.createTempFile("rf_sync_", ".zip")

                var fileCount = 0
                var totalBytes = 0L

                java.util.zip.ZipOutputStream(java.io.BufferedOutputStream(java.io.FileOutputStream(tempZip))).use { zos ->
                    baseDir.walkTopDown()
                        .onEnter { dir ->
                            val rel = dir.relativeTo(baseDir).path.replace('\\', '/')
                            !uz.remote.flow.ssh.isPathExcluded(rel, dir.name, excludes)
                        }
                        .forEach { file ->
                            if (file.isFile) {
                                val rel = file.relativeTo(baseDir).path.replace('\\', '/')
                                if (!uz.remote.flow.ssh.isPathExcluded(rel, file.name, excludes)) {
                                    val entry = java.util.zip.ZipEntry(rel).apply {
                                        time = file.lastModified()
                                    }
                                    zos.putNextEntry(entry)
                                    file.inputStream().use { it.copyTo(zos) }
                                    zos.closeEntry()
                                    fileCount++
                                    totalBytes += file.length()
                                }
                            }
                        }
                }

                val sizeMb = String.format(java.util.Locale.US, "%.2f", tempZip.length() / (1024.0 * 1024.0))
                onLog("[SFTP SYNC] $fileCount ta fayl arxivlandi ($sizeMb MB). Serverga yuklanmoqda...\n")

                val remoteTempZip = "/tmp/rf_sync_" + java.util.UUID.randomUUID().toString().take(8) + ".zip"

                connectionManager.uploadFile(
                    profile = profile,
                    localFile = tempZip,
                    remotePath = remoteTempZip,
                    onProgress = onLog,
                    onComplete = { uploaded ->
                        if (!uploaded) {
                            onLog("[ERROR] Arxiv serverga yuklanmadi!\n")
                            tempZip?.delete()
                            onComplete(false)
                            return@uploadFile
                        }

                        onLog("[SFTP SYNC] Arxiv serverda ochilmoqda: " + profile.remoteProjectPath + "...\n")
                        val extractCmd = "mkdir -p \"" + profile.remoteProjectPath + "\" && " +
                                "(which unzip >/dev/null 2>&1 && unzip -q -o \"" + remoteTempZip + "\" -d \"" + profile.remoteProjectPath + "\" || " +
                                "python3 -m zipfile -e \"" + remoteTempZip + "\" \"" + profile.remoteProjectPath + "\" || " +
                                "python -m zipfile -e \"" + remoteTempZip + "\" \"" + profile.remoteProjectPath + "\") && " +
                                "rm -f \"" + remoteTempZip + "\" && " +
                                "chmod +x \"" + profile.remoteProjectPath + "/gradlew\" 2>/dev/null || true; " +
                                "chmod +x \"" + profile.remoteProjectPath + "/mvnw\" 2>/dev/null || true; " +
                                "chmod +x \"" + profile.remoteProjectPath + "\"/*.sh 2>/dev/null || true"

                        connectionManager.executeRemoteCommand(
                            cmd = extractCmd,
                            workingDir = "",
                            onOutput = onLog,
                            onComplete = { code ->
                                tempZip?.delete()
                                if (code == 0) {
                                    onLog("[SYNC SUCCESS] $fileCount ta fayl " + profile.name + " serveriga muvaffaqiyatli sinxronlashtirildi!\n")
                                    onComplete(true)
                                } else {
                                    onLog("[ERROR] Serverda arxivni ochib bo'lmadi (unzip yoki python topilmadi).\n")
                                    onComplete(false)
                                }
                            }
                        )
                    }
                )
            } catch (e: Exception) {
                onLog("[SYNC ERROR]: " + (e.message ?: e.toString()) + "\n")
                tempZip?.delete()
                onComplete(false)
            }
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
            var tempAskPass: java.io.File? = null
            try {
                val remoteTarget = profile.remoteProjectPath.trimEnd('/') + "/" + cleanRelative
                val exe = resolveRsyncExecutable(profile)
                if (checkRsync(profile)) {
                    val rsyncSource = convertToRsyncPath(localFile.absolutePath, exe)
                    val sshCmd = mutableListOf("ssh", "-p", profile.port.toString(), "-o", "StrictHostKeyChecking=no", "-o", "UserKnownHostsFile=/dev/null")
                    if (profile.authType == uz.remote.flow.ssh.AuthType.PRIVATE_KEY && profile.privateKeyPath.isNotBlank()) {
                        val keyPath = convertToRsyncPath(profile.privateKeyPath, exe)
                        sshCmd.addAll(listOf("-i", keyPath))
                    } else if (profile.authType == uz.remote.flow.ssh.AuthType.PASSWORD && profile.password.isNotBlank()) {
                        tempAskPass = java.io.File.createTempFile("rf_askpass_", ".bat")
                        tempAskPass.writeText("@echo off\r\necho " + profile.password + "\r\n")
                        tempAskPass.setExecutable(true)
                    }
                    val rsyncCmd = listOf(
                        exe,
                        "-avz",
                        "-e", sshCmd.joinToString(" "),
                        rsyncSource,
                        profile.user + "@" + profile.host + ":" + remoteTarget
                    )
                    val pb = ProcessBuilder(rsyncCmd).redirectErrorStream(true)
                    val exeFile = java.io.File(exe)
                    if (exeFile.parentFile != null) {
                        val rsyncDir = exeFile.parentFile.absolutePath
                        val currentPath = pb.environment()["PATH"] ?: ""
                        pb.environment()["PATH"] = rsyncDir + java.io.File.pathSeparator + currentPath
                    }
                    if (tempAskPass != null) {
                        pb.environment()["SSH_ASKPASS"] = tempAskPass.absolutePath
                        pb.environment()["SSH_ASKPASS_REQUIRE"] = "force"
                        pb.environment()["DISPLAY"] = "dummy:0"
                    }
                    val process = pb.start()
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { onLog(it + "\n") }
                    }
                    val code = process.waitFor()
                    if (code == 0) {
                        if (cleanRelative.endsWith("gradlew") || cleanRelative.endsWith(".sh") || cleanRelative.endsWith("mvnw")) {
                            connectionManager.executeRemoteCommand("chmod +x \"$remoteTarget\" 2>/dev/null || true", "", {}, {})
                        }
                        onComplete(true)
                    } else {
                        onLog("[RSYNC NOTICE] Rsync xato berdi (code: $code). SFTP orqali yuklanmoqda...\n")
                        connectionManager.uploadFile(
                            profile = profile,
                            localFile = localFile,
                            remotePath = remoteTarget,
                            onProgress = onLog,
                            onComplete = { ok ->
                                if (ok && (cleanRelative.endsWith("gradlew") || cleanRelative.endsWith(".sh") || cleanRelative.endsWith("mvnw"))) {
                                    connectionManager.executeRemoteCommand("chmod +x \"$remoteTarget\" 2>/dev/null || true", "", {}, {})
                                }
                                onComplete(ok)
                            }
                        )
                    }
                } else {
                    onLog("[SFTP SYNC] " + cleanRelative + " serverga uzatilmoqda...\n")
                    connectionManager.uploadFile(
                        profile = profile,
                        localFile = localFile,
                        remotePath = remoteTarget,
                        onProgress = onLog,
                        onComplete = { success ->
                            if (success) {
                                if (cleanRelative.endsWith("gradlew") || cleanRelative.endsWith(".sh") || cleanRelative.endsWith("mvnw")) {
                                    connectionManager.executeRemoteCommand("chmod +x \"$remoteTarget\" 2>/dev/null || true", "", {}, {})
                                }
                                onLog("[SYNC SUCCESS] Fayl serverda yangilandi: " + cleanRelative + "\n")
                            } else {
                                onLog("[SYNC ERROR] Faylni uzatishda xatolik!\n")
                            }
                            onComplete(success)
                        }
                    )
                }
            } catch (e: Exception) {
                onLog("[SYNC ERROR]: " + e.message + "\n")
                onComplete(false)
            } finally {
                try { tempAskPass?.delete() } catch (_: Exception) {}
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

    fun resolveRsyncExecutable(profile: ServerProfile = connectionManager.config.activeProfile): String {
        if (profile.rsyncPath.isNotBlank()) {
            val f = java.io.File(profile.rsyncPath)
            if (f.exists() && f.canExecute()) return f.absolutePath
        }
        val detected = uz.remote.flow.ssh.detectRsyncPath()
        return detected.ifBlank { "rsync" }
    }

    fun checkRsync(profile: ServerProfile = connectionManager.config.activeProfile): Boolean {
        val exe = resolveRsyncExecutable(profile)
        return try {
            val process = ProcessBuilder(exe, "--version").start()
            process.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

    private fun runRsync(profile: ServerProfile, localDir: String, onLog: (String) -> Unit, onComplete: (Boolean) -> Unit) {
        parallelPool.submit {
            var tempAskPass: java.io.File? = null
            try {
                val exe = resolveRsyncExecutable(profile)
                onLog("[RSYNC SYNC -> " + profile.name + "] Rsync topildi ($exe). Tezkor sinxronizatsiya boshlandi...\n")
                val excludes = uz.remote.flow.ssh.parseExcludeList(profile.excludePatterns)
                val rsyncCmd = mutableListOf(exe, "-avz", "--delete")
                excludes.forEach { rsyncCmd.add("--exclude=$it") }

                val rsyncSource = convertToRsyncPath(localDir.trimEnd('/') + "/", exe)
                val rsyncDest = "${profile.user}@${profile.host}:${profile.remoteProjectPath.trimEnd('/')}/"

                val sshCmd = mutableListOf("ssh", "-p", profile.port.toString(), "-o", "StrictHostKeyChecking=no", "-o", "UserKnownHostsFile=/dev/null")
                if (profile.authType == uz.remote.flow.ssh.AuthType.PRIVATE_KEY && profile.privateKeyPath.isNotBlank()) {
                    val keyPath = convertToRsyncPath(profile.privateKeyPath, exe)
                    sshCmd.addAll(listOf("-i", keyPath))
                } else if (profile.authType == uz.remote.flow.ssh.AuthType.PASSWORD && profile.password.isNotBlank()) {
                    tempAskPass = java.io.File.createTempFile("rf_askpass_", ".bat")
                    tempAskPass.writeText("@echo off\r\necho " + profile.password + "\r\n")
                    tempAskPass.setExecutable(true)
                }

                rsyncCmd.addAll(listOf(
                    "-e", sshCmd.joinToString(" "),
                    rsyncSource,
                    rsyncDest
                ))

                onLog("[RSYNC EXEC] $rsyncSource -> $rsyncDest\n")
                val pb = ProcessBuilder(rsyncCmd).redirectErrorStream(true)

                val exeFile = java.io.File(exe)
                if (exeFile.parentFile != null) {
                    val rsyncDir = exeFile.parentFile.absolutePath
                    val currentPath = pb.environment()["PATH"] ?: ""
                    pb.environment()["PATH"] = rsyncDir + java.io.File.pathSeparator + currentPath
                }

                if (tempAskPass != null) {
                    pb.environment()["SSH_ASKPASS"] = tempAskPass.absolutePath
                    pb.environment()["SSH_ASKPASS_REQUIRE"] = "force"
                    pb.environment()["DISPLAY"] = "dummy:0"
                }

                val process = pb.start()
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { onLog(it + "\n") }
                }
                val code = process.waitFor()
                if (code == 0) {
                    connectionManager.executeRemoteCommand(
                        cmd = "chmod +x \"" + profile.remoteProjectPath + "/gradlew\" \"" + profile.remoteProjectPath + "/mvnw\" \"" + profile.remoteProjectPath + "\"/*.sh 2>/dev/null || true",
                        workingDir = "",
                        onOutput = {},
                        onComplete = {
                            onLog("[SYNC SUCCESS] Rsync orqali barcha fayllar serverga muvaffaqiyatli sinxronlandi!\n")
                            onComplete(true)
                        }
                    )
                } else {
                    onLog("[RSYNC NOTICE] Rsync xatolik qaytardi (code: $code). Avtomatik ravishda SFTP Archive Sync rejimiga o'tilmoqda...\n")
                    runSftpArchiveSync(profile, localDir, onLog, onComplete)
                }
            } catch (e: Exception) {
                onLog("[RSYNC NOTICE] Rsync ishga tushmadi (" + (e.message ?: e.toString()) + "). SFTP Archive Sync rejimiga o'tilmoqda...\n")
                runSftpArchiveSync(profile, localDir, onLog, onComplete)
            } finally {
                try { tempAskPass?.delete() } catch (_: Exception) {}
            }
        }
    }
}

fun convertToRsyncPath(path: String, rsyncExe: String): String {
    val clean = path.trim().replace('\\', '/')
    val match = Regex("^([a-zA-Z]):/(.*)$").find(clean) ?: return clean
    val drive = match.groupValues[1].lowercase()
    val subPath = match.groupValues[2]

    val exeLower = rsyncExe.lowercase().replace('\\', '/')
    val isCygwin = exeLower.contains("cygwin") ||
            exeLower.contains("cwrsync") ||
            try {
                val exeFile = java.io.File(rsyncExe)
                java.io.File(exeFile.parentFile, "cygwin1.dll").exists()
            } catch (_: Exception) { false }

    val prefix = if (isCygwin) "/cygdrive/$drive" else "/$drive"
    return "$prefix/$subPath"
}
