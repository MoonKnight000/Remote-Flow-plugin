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
            onLog("[ERROR] Local directory not found!\n")
            onComplete(false)
            return
        }
        onLog("[DRY-RUN PREVIEW] Comparing with remote server: " + basePath + " -> " + profile.remoteProjectPath + "\n")

        parallelPool.submit {
            var tempAskPass: java.io.File? = null
            try {
                val exe = resolveRsyncExecutable(profile)
                val rsyncCmd = mutableListOf(exe)
                val options = buildBaseRsyncOptions(profile)
                if (!options.contains("--dry-run") && !options.contains("-n")) {
                    options.add("-n")
                }
                if (!options.contains("--delete") && !options.contains("-delete")) {
                    options.add("--delete")
                }
                if (!options.contains("--itemize-changes")) {
                    options.add("--itemize-changes")
                }
                rsyncCmd.addAll(options)

                val rsyncSource = convertToRsyncPath(basePath.trimEnd('/') + "/", exe)
                val rsyncDest = "${profile.user}@${profile.host}:${profile.remoteProjectPath.trimEnd('/')}/"

                val sshCmdStr = buildSshCommand(profile, exe)
                rsyncCmd.addAll(listOf("-e", sshCmdStr, rsyncSource, rsyncDest))

                if (profile.authType == uz.remote.flow.ssh.AuthType.PASSWORD && profile.password.isNotBlank()) {
                    tempAskPass = java.io.File.createTempFile("rf_askpass_", ".bat")
                    tempAskPass.writeText("@echo off\r\necho " + profile.password + "\r\n", Charsets.US_ASCII)
                    tempAskPass.setExecutable(true)
                }

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
                onComplete(code == 0)
            } catch (e: Exception) {
                onLog("[DRY-RUN NOTE]: Rsync not found or failed (" + e.message + "). Checking via SSH.\n")
                onComplete(false)
            } finally {
                try { tempAskPass?.delete() } catch (_: Exception) {}
            }
        }
    }

    fun previewDryRunDiff(
        profile: ServerProfile = connectionManager.config.activeProfile,
        onLog: (String) -> Unit = {},
        onResult: (List<SyncDiffItem>) -> Unit
    ) {
        val basePath = profile.localProjectPath.ifBlank { project.basePath ?: "" }
        if (basePath.isBlank()) {
            onLog("[ERROR] Local directory not found!\n")
            onResult(emptyList())
            return
        }

        parallelPool.submit {
            val diffItems = mutableListOf<SyncDiffItem>()
            var tempAskPass: java.io.File? = null
            try {
                val exe = resolveRsyncExecutable(profile)
                val rsyncCmd = mutableListOf(exe)
                val options = buildBaseRsyncOptions(profile)
                if (!options.contains("--dry-run") && !options.contains("-n")) {
                    options.add("-n")
                }
                if (!options.contains("--delete") && !options.contains("-delete")) {
                    options.add("--delete")
                }
                if (!options.contains("--itemize-changes")) {
                    options.add("--itemize-changes")
                }
                rsyncCmd.addAll(options)

                val rsyncSource = convertToRsyncPath(basePath.trimEnd('/') + "/", exe)
                val rsyncDest = "${profile.user}@${profile.host}:${profile.remoteProjectPath.trimEnd('/')}/"

                val sshCmdStr = buildSshCommand(profile, exe)
                rsyncCmd.addAll(listOf("-e", sshCmdStr, rsyncSource, rsyncDest))

                if (profile.authType == uz.remote.flow.ssh.AuthType.PASSWORD && profile.password.isNotBlank()) {
                    tempAskPass = java.io.File.createTempFile("rf_askpass_", ".bat")
                    tempAskPass.writeText("@echo off\r\necho " + profile.password + "\r\n", Charsets.US_ASCII)
                    tempAskPass.setExecutable(true)
                }

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
                    lines.forEach { rawLine ->
                        val line = rawLine.trim()
                        onLog(line + "\n")
                        if (line.startsWith("*deleting")) {
                            val path = line.removePrefix("*deleting").trim()
                            if (path.isNotBlank()) {
                                diffItems.add(SyncDiffItem(path, SyncChangeType.DELETED))
                            }
                        } else if (line.matches(Regex("""^[>cf.shdLitpoguax+?]{9,11}\s+.*"""))) {
                            val parts = line.split("\\s+".toRegex(), limit = 2)
                            if (parts.size >= 2) {
                                val flags = parts[0]
                                val path = parts[1].trim()
                                if (path.isNotBlank()) {
                                    val isNew = flags.contains("+++++") || flags.startsWith(">f+") || flags.startsWith("cd+")
                                    val changeType = if (isNew) SyncChangeType.ADDED else SyncChangeType.MODIFIED
                                    diffItems.add(SyncDiffItem(path, changeType))
                                }
                            }
                        }
                    }
                }
                process.waitFor()
            } catch (e: Exception) {
                onLog("[DRY-RUN NOTE]: Rsync dry-run fallback: ${e.message}\n")
                try {
                    val excludes = uz.remote.flow.ssh.parseExcludeList(profile.excludePatterns)
                    val baseDir = java.io.File(basePath)
                    baseDir.walkTopDown().forEach { file ->
                        if (file.isFile) {
                            val rel = file.relativeTo(baseDir).path.replace('\\', '/')
                            if (!uz.remote.flow.ssh.isPathExcluded(rel, file.name, excludes)) {
                                diffItems.add(SyncDiffItem(rel, SyncChangeType.MODIFIED))
                            }
                        }
                    }
                } catch (_: Exception) {}
            } finally {
                try { tempAskPass?.delete() } catch (_: Exception) {}
            }
            onResult(diffItems)
        }
    }

    fun syncNow(
        profile: ServerProfile = connectionManager.config.activeProfile,
        onLog: (String) -> Unit = {},
        onComplete: (Boolean) -> Unit = {}
    ) = syncSingleServer(profile, onLog, onComplete)

    fun syncSingleServer(
        profile: ServerProfile = connectionManager.config.activeProfile,
        onLog: (String) -> Unit,
        onComplete: (Boolean) -> Unit
    ) {
        val basePath = profile.localProjectPath.ifBlank { project.basePath ?: "" }
        if (basePath.isBlank()) {
            onLog("[ERROR] Local directory not found!\n")
            onComplete(false)
            return
        }
        onLog("[SYNC -> " + profile.name + "] Synchronization started: " + basePath + " -> " + profile.remoteProjectPath + "\n")

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
                val methodNotice = if (!hasRsync) "Rsync not found on system" else "SFTP Archive Sync mode"
                onLog("[SFTP SYNC -> " + profile.name + "] " + methodNotice + ". Synchronizing via SSH/SFTP...\n")
                val baseDir = java.io.File(basePath)
                if (!baseDir.exists() || !baseDir.isDirectory) {
                    onLog("[ERROR] Local folder does not exist: " + basePath + "\n")
                    onComplete(false)
                    return@submit
                }

                val excludes = uz.remote.flow.ssh.parseExcludeList(profile.excludePatterns)

                onLog("[SFTP SYNC] Packaging project files (applying ${excludes.size} exclude rules)...\n")
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
                onLog("[SFTP SYNC] $fileCount files archived ($sizeMb MB). Uploading to server...\n")

                val remoteTempZip = "/tmp/rf_sync_" + java.util.UUID.randomUUID().toString().take(8) + ".zip"

                connectionManager.uploadFile(
                    profile = profile,
                    localFile = tempZip,
                    remotePath = remoteTempZip,
                    onProgress = onLog,
                    onComplete = { uploaded ->
                        if (!uploaded) {
                            onLog("[ERROR] Failed to upload archive to server!\n")
                            tempZip?.delete()
                            onComplete(false)
                            return@uploadFile
                        }

                        onLog("[SFTP SYNC] Extracting archive on server: " + profile.remoteProjectPath + "...\n")
                        val extractCmd = "mkdir -p \"" + profile.remoteProjectPath + "\" && " +
                                "(which unzip >/dev/null 2>&1 && unzip -q -o \"" + remoteTempZip + "\" -d \"" + profile.remoteProjectPath + "\" || " +
                                "python3 -m zipfile -e \"" + remoteTempZip + "\" \"" + profile.remoteProjectPath + "\" || " +
                                "python -m zipfile -e \"" + remoteTempZip + "\" \"" + profile.remoteProjectPath + "\") && " +
                                "rm -f \"" + remoteTempZip + "\" && " +
                                "sed -i 's/\\r$//' \"" + profile.remoteProjectPath + "/gradlew\" \"" + profile.remoteProjectPath + "/mvnw\" \"" + profile.remoteProjectPath + "\"/*.sh 2>/dev/null || true; " +
                                "chmod +x \"" + profile.remoteProjectPath + "/gradlew\" \"" + profile.remoteProjectPath + "/mvnw\" \"" + profile.remoteProjectPath + "\"/*.sh 2>/dev/null || true"

                        connectionManager.executeRemoteCommand(
                            cmd = extractCmd,
                            workingDir = "",
                            onOutput = onLog,
                            onComplete = { code ->
                                tempZip?.delete()
                                if (code == 0) {
                                    onLog("[SYNC SUCCESS] $fileCount files successfully synchronized to " + profile.name + "!\n")
                                    onComplete(true)
                                } else {
                                    onLog("[ERROR] Could not extract archive on server (unzip or python not found).\n")
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
        isAutoSync: Boolean = false,
        onLog: (String) -> Unit = {},
        onComplete: (Boolean) -> Unit = {}
    ) {
        val basePath = profile.localProjectPath.ifBlank { project.basePath ?: "" }
        val prefix = if (isAutoSync) "AUTO-SYNC" else "SYNC SPECIFIC"
        if (basePath.isBlank()) {
            onLog("[$prefix ERROR] Local directory not found!\n")
            onComplete(false)
            return
        }

        val cleanRelative = relativePath.trimStart('/', '\\').replace('\\', '/')
        val localFile = java.io.File(basePath, cleanRelative)
        if (!localFile.exists()) {
            onLog("[$prefix ERROR] File or directory not found: " + localFile.path + "\n")
            onComplete(false)
            return
        }

        val startTime = System.currentTimeMillis()
        onLog("[$prefix -> " + profile.name + "] Uploading " + cleanRelative + "...\n")
        parallelPool.submit {
            var tempAskPass: java.io.File? = null
            try {
                val remoteTarget = profile.remoteProjectPath.trimEnd('/') + "/" + cleanRelative
                val exe = resolveRsyncExecutable(profile)
                if (checkRsync(profile)) {
                    val rsyncSource = convertToRsyncPath(localFile.absolutePath, exe)
                    val sshCmdStr = buildSshCommand(profile, exe)
                    val rsyncCmd = listOf(
                        exe,
                        "-avz",
                        "--no-perms",
                        "--no-owner",
                        "--no-group",
                        "-e", sshCmdStr,
                        rsyncSource,
                        profile.user + "@" + profile.host + ":" + remoteTarget
                    )
                    if (profile.authType == uz.remote.flow.ssh.AuthType.PASSWORD && profile.password.isNotBlank()) {
                        tempAskPass = java.io.File.createTempFile("rf_askpass_", ".bat")
                        tempAskPass.writeText("@echo off\r\necho " + profile.password + "\r\n", Charsets.US_ASCII)
                        tempAskPass.setExecutable(true)
                    }
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
                            connectionManager.executeRemoteCommand("sed -i 's/\\r$//' \"$remoteTarget\" 2>/dev/null || true; chmod +x \"$remoteTarget\" 2>/dev/null || true", "", {}, {})
                        }
                        val duration = System.currentTimeMillis() - startTime
                        onLog("[$prefix SUCCESS] ✅ '$cleanRelative' successfully uploaded to server (${duration}ms)\n")
                        onComplete(true)
                    } else {
                        onLog("[RSYNC NOTICE] Rsync returned error (code: $code). Uploading via SFTP...\n")
                        connectionManager.uploadFile(
                            profile = profile,
                            localFile = localFile,
                            remotePath = remoteTarget,
                            onProgress = onLog,
                            onComplete = { ok ->
                                if (ok && (cleanRelative.endsWith("gradlew") || cleanRelative.endsWith(".sh") || cleanRelative.endsWith("mvnw"))) {
                                    connectionManager.executeRemoteCommand("sed -i 's/\\r$//' \"$remoteTarget\" 2>/dev/null || true; chmod +x \"$remoteTarget\" 2>/dev/null || true", "", {}, {})
                                }
                                val duration = System.currentTimeMillis() - startTime
                                if (ok) {
                                    onLog("[$prefix SUCCESS] ✅ '$cleanRelative' successfully uploaded to server (${duration}ms)\n")
                                } else {
                                    onLog("[$prefix ERROR] ❌ Failed to upload '$cleanRelative' to server!\n")
                                }
                                onComplete(ok)
                            }
                        )
                    }
                } else {
                    onLog("[SFTP SYNC] Transferring " + cleanRelative + " to server...\n")
                    connectionManager.uploadFile(
                        profile = profile,
                        localFile = localFile,
                        remotePath = remoteTarget,
                        onProgress = onLog,
                        onComplete = { success ->
                            if (success && (cleanRelative.endsWith("gradlew") || cleanRelative.endsWith(".sh") || cleanRelative.endsWith("mvnw"))) {
                                connectionManager.executeRemoteCommand("sed -i 's/\\r$//' \"$remoteTarget\" 2>/dev/null || true; chmod +x \"$remoteTarget\" 2>/dev/null || true", "", {}, {})
                            }
                            val duration = System.currentTimeMillis() - startTime
                            if (success) {
                                onLog("[$prefix SUCCESS] ✅ '$cleanRelative' successfully uploaded to server (${duration}ms)\n")
                            } else {
                                onLog("[$prefix ERROR] ❌ Failed to upload '$cleanRelative' to server!\n")
                            }
                            onComplete(success)
                        }
                    )
                }
            } catch (e: Exception) {
                onLog("[$prefix ERROR] ❌ " + (e.message ?: e.toString()) + "\n")
                onComplete(false)
            } finally {
                try { tempAskPass?.delete() } catch (_: Exception) {}
            }
        }
    }

    /**
     * Pulls / downloads changes from remote server directory to local project.
     * Uses reverse Rsync or SFTP to synchronize remote-generated files or migrations.
     */
    fun pullFromRemote(
        profile: ServerProfile = connectionManager.config.activeProfile,
        onLog: (String) -> Unit = {},
        onComplete: (Boolean) -> Unit = {}
    ) {
        val basePath = profile.localProjectPath.ifBlank { project.basePath ?: "" }
        if (basePath.isBlank()) {
            onLog("[PULL ERROR] Local project path not found!\n")
            onComplete(false)
            return
        }

        onLog("[PULL <- ${profile.name}] Pulling remote changes: ${profile.remoteProjectPath} -> $basePath\n")

        val rsyncAvailable = checkRsync(profile)
        if (rsyncAvailable) {
            parallelPool.submit {
                var tempAskPass: java.io.File? = null
                try {
                    val exe = resolveRsyncExecutable(profile)
                    val rsyncCmd = mutableListOf(exe)
                    rsyncCmd.addAll(buildBaseRsyncOptions(profile))

                    val rsyncSource = "${profile.user}@${profile.host}:${profile.remoteProjectPath.trimEnd('/')}/"
                    val rsyncDest = convertToRsyncPath(basePath.trimEnd('/') + "/", exe)

                    val sshCmdStr = buildSshCommand(profile, exe)
                    rsyncCmd.addAll(listOf("-e", sshCmdStr, rsyncSource, rsyncDest))

                    if (profile.authType == uz.remote.flow.ssh.AuthType.PASSWORD && profile.password.isNotBlank()) {
                        tempAskPass = java.io.File.createTempFile("rf_askpass_", ".bat")
                        tempAskPass.writeText("@echo off\r\necho " + profile.password + "\r\n", Charsets.US_ASCII)
                        tempAskPass.setExecutable(true)
                    }

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
                        onLog("[PULL] ✓ Successfully downloaded remote changes to local project.\n")
                        com.intellij.openapi.vfs.LocalFileSystem.getInstance().refresh(true)
                    } else {
                        onLog("[PULL ERROR] Rsync pull exited with code $code\n")
                    }
                    onComplete(code == 0)
                } catch (e: Exception) {
                    onLog("[PULL ERROR] " + (e.message ?: e.toString()) + "\n")
                    onComplete(false)
                } finally {
                    try { tempAskPass?.delete() } catch (_: Exception) {}
                }
            }
        } else {
            parallelPool.submit {
                try {
                    onLog("[PULL SFTP] Downloading remote files via SSH/SFTP...\n")
                    val client = connectionManager.getActiveSshClient()
                    if (client == null || !connectionManager.isConnected) {
                        onLog("[PULL ERROR] SSH connection is not active.\n")
                        onComplete(false)
                        return@submit
                    }
                    client.newSFTPClient().use { sftp ->
                        sftp.get(profile.remoteProjectPath, net.schmizz.sshj.xfer.FileSystemFile(basePath))
                    }
                    onLog("[PULL] ✓ SFTP pull completed successfully.\n")
                    com.intellij.openapi.vfs.LocalFileSystem.getInstance().refresh(true)
                    onComplete(true)
                } catch (e: Exception) {
                    onLog("[PULL ERROR] SFTP download failed: ${e.message}\n")
                    onComplete(false)
                }
            }
        }
    }

    /**
     * Git-Aware fast synchronization:
     * Inspects local `git status --porcelain` to identify changed/added files,
     * and synchronizes ONLY those files in sub-second time.
     */
    fun syncGitModified(
        profile: ServerProfile = connectionManager.config.activeProfile,
        onLog: (String) -> Unit = {},
        onComplete: (Boolean) -> Unit = {}
    ) {
        val basePath = profile.localProjectPath.ifBlank { project.basePath ?: "" }
        val gitDir = java.io.File(basePath, ".git")
        if (!gitDir.exists()) {
            syncSingleServer(profile, onLog, onComplete)
            return
        }

        parallelPool.submit {
            try {
                onLog("[GIT SYNC] Inspecting git status in $basePath...\n")
                val pb = ProcessBuilder("git", "status", "--porcelain")
                    .directory(java.io.File(basePath))
                    .redirectErrorStream(true)
                val p = pb.start()
                val lines = p.inputStream.bufferedReader().readLines()
                p.waitFor()

                val excludes = uz.remote.flow.ssh.parseExcludeList(profile.excludePatterns)
                val filesToSync = mutableListOf<String>()

                for (raw in lines) {
                    if (raw.length < 4) continue
                    val status = raw.substring(0, 2)
                    if (status.contains("D")) continue
                    var filePath = raw.substring(3).trim().replace("\"", "").replace('\\', '/')
                    if (filePath.contains(" -> ")) {
                        filePath = filePath.substringAfter(" -> ").trim()
                    }
                    val f = java.io.File(basePath, filePath)
                    if (f.exists() && !uz.remote.flow.ssh.isPathExcluded(filePath, f.name, excludes)) {
                        filesToSync.add(filePath)
                    }
                }

                if (filesToSync.isEmpty()) {
                    onLog("[GIT SYNC] ✓ No modified files found in working tree. Already synchronized.\n")
                    onComplete(true)
                    return@submit
                }

                onLog("[GIT SYNC] Found ${filesToSync.size} modified files. Fast-syncing...\n")
                val latch = java.util.concurrent.CountDownLatch(filesToSync.size)
                var allOk = true

                for (rel in filesToSync) {
                    syncSpecificPath(profile, rel, isAutoSync = false, onLog = onLog) { ok ->
                        if (!ok) allOk = false
                        latch.countDown()
                    }
                }

                latch.await(45, TimeUnit.SECONDS)
                onLog("[GIT SYNC] ✓ Completed git-aware sync (${filesToSync.size} files).\n")
                onComplete(allOk)
            } catch (e: Exception) {
                onLog("[GIT SYNC ERROR] ${e.message}. Falling back to full sync.\n")
                syncSingleServer(profile, onLog, onComplete)
            }
        }
    }

    fun syncAllServersParallel(
        profiles: List<ServerProfile>,
        onLog: (String) -> Unit,
        onComplete: (Boolean) -> Unit
    ) {
        onLog("[PARALLEL SYNC] Starting parallel synchronization for all " + profiles.size + " servers...\n")

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
                                onLog("[PARALLEL SYNC] Synchronization completed successfully for all servers!\n")
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
        val ideRsync = IntelliJRsyncConfigProvider.getRsyncConfig().rsyncPath
        if (ideRsync.isNotBlank()) {
            val f = java.io.File(ideRsync)
            if (f.exists() && f.canExecute()) return f.absolutePath
        }
        val detected = uz.remote.flow.ssh.detectRsyncPath()
        return detected.ifBlank { "rsync" }
    }

    fun resolveShellExecutable(rsyncExe: String): String {
        val ideShell = IntelliJRsyncConfigProvider.getRsyncConfig().shellPath
        if (ideShell.isNotBlank()) {
            val f = java.io.File(ideShell)
            if (f.exists() && f.canExecute()) return f.absolutePath
        }
        val rsyncFile = java.io.File(rsyncExe)
        if (rsyncFile.parentFile != null) {
            val isWin = com.intellij.openapi.util.SystemInfo.isWindows
            val candidate = java.io.File(rsyncFile.parentFile, if (isWin) "ssh.exe" else "ssh")
            if (candidate.exists() && candidate.canExecute()) return candidate.absolutePath
        }
        return "ssh"
    }

    fun buildBaseRsyncOptions(profile: ServerProfile): MutableList<String> {
        val optionsList = mutableListOf<String>()
        val ideConfig = IntelliJRsyncConfigProvider.getRsyncConfig()
        val ideOptsStr = ideConfig.options.trim()
        if (ideOptsStr.isNotBlank()) {
            try {
                optionsList.addAll(com.intellij.util.execution.ParametersListUtil.parse(ideOptsStr))
            } catch (_: Throwable) {
                optionsList.addAll(ideOptsStr.split("\\s+".toRegex()).filter { it.isNotBlank() })
            }
        }
        if (optionsList.none { it.startsWith("-a") || it.startsWith("-r") || it.startsWith("-z") }) {
            optionsList.add("-avz")
        }
        val excludes = uz.remote.flow.ssh.parseExcludeList(profile.excludePatterns)
        for (exc in excludes) {
            val opt = "--exclude=$exc"
            if (!optionsList.contains(opt)) {
                optionsList.add(opt)
            }
        }
        if (!optionsList.contains("--no-perms")) optionsList.add("--no-perms")
        if (!optionsList.contains("--no-owner")) optionsList.add("--no-owner")
        if (!optionsList.contains("--no-group")) optionsList.add("--no-group")
        return optionsList
    }

    fun buildSshCommand(profile: ServerProfile, rsyncExe: String): String {
        val shellExe = resolveShellExecutable(rsyncExe).replace('\\', '/')
        val args = mutableListOf(
            shellExe,
            "-p", profile.port.toString(),
            "-o", "StrictHostKeyChecking=no",
            "-o", "UserKnownHostsFile=/dev/null"
        )
        if (profile.authType == uz.remote.flow.ssh.AuthType.PRIVATE_KEY && profile.privateKeyPath.isNotBlank()) {
            val keyPath = convertToRsyncPath(profile.privateKeyPath, rsyncExe)
            args.addAll(listOf("-i", keyPath))
        }
        return args.joinToString(" ")
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
                val ideConfig = IntelliJRsyncConfigProvider.getRsyncConfig()
                val isIdeRsync = exe.equals(ideConfig.rsyncPath, ignoreCase = true)
                val origin = if (isIdeRsync) "IntelliJ IDEA Rsync configuration ($exe)" else exe
                onLog("[RSYNC SYNC -> " + profile.name + "] Rsync found: $origin. Starting fast sync...\n")

                val rsyncCmd = mutableListOf(exe)
                val options = buildBaseRsyncOptions(profile)
                if (!options.contains("--delete") && !options.contains("-delete")) {
                    options.add("--delete")
                }
                rsyncCmd.addAll(options)

                val rsyncSource = convertToRsyncPath(localDir.trimEnd('/') + "/", exe)
                val rsyncDest = "${profile.user}@${profile.host}:${profile.remoteProjectPath.trimEnd('/')}/"

                val sshCmdStr = buildSshCommand(profile, exe)
                rsyncCmd.addAll(listOf("-e", sshCmdStr, rsyncSource, rsyncDest))

                if (profile.authType == uz.remote.flow.ssh.AuthType.PASSWORD && profile.password.isNotBlank()) {
                    tempAskPass = java.io.File.createTempFile("rf_askpass_", ".bat")
                    tempAskPass.writeText("@echo off\r\necho " + profile.password + "\r\n", Charsets.US_ASCII)
                    tempAskPass.setExecutable(true)
                }

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
                    val postSyncCmd = "sed -i 's/\\r$//' \"" + profile.remoteProjectPath + "/gradlew\" \"" + profile.remoteProjectPath + "/mvnw\" \"" + profile.remoteProjectPath + "\"/*.sh 2>/dev/null || true; " +
                            "chmod +x \"" + profile.remoteProjectPath + "/gradlew\" \"" + profile.remoteProjectPath + "/mvnw\" \"" + profile.remoteProjectPath + "\"/*.sh 2>/dev/null || true"
                    connectionManager.executeRemoteCommand(
                        cmd = postSyncCmd,
                        workingDir = "",
                        onOutput = {},
                        onComplete = {
                            onLog("[SYNC SUCCESS] All files successfully synchronized to server via rsync!\n")
                            onComplete(true)
                        }
                    )
                } else {
                    onLog("[RSYNC NOTICE] Rsync returned error (code: $code). Falling back to SFTP Archive Sync mode...\n")
                    runSftpArchiveSync(profile, localDir, onLog, onComplete)
                }
            } catch (e: Exception) {
                onLog("[RSYNC NOTICE] Rsync failed to start (" + (e.message ?: e.toString()) + "). Falling back to SFTP Archive Sync mode...\n")
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
