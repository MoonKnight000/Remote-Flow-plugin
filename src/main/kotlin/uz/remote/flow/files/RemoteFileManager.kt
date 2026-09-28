package uz.remote.flow.files

import com.intellij.openapi.project.Project
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.xfer.FileSystemFile
import uz.remote.flow.ssh.RemoteConnectionManager
import uz.remote.flow.ssh.ServerProfile
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class RemoteFileManager(private val project: Project) {

    private val connectionManager get() = RemoteConnectionManager.getInstance(project)
    private val executor = Executors.newCachedThreadPool()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun listDirectory(
        profile: ServerProfile = connectionManager.config.activeProfile,
        remotePath: String,
        onResult: (List<RemoteFileInfo>, error: String?) -> Unit
    ) {
        executor.submit {
            val cleanPath = remotePath.trim().ifBlank { profile.remoteProjectPath }.ifBlank { "/" }
            try {
                val results = mutableListOf<RemoteFileInfo>()

                connectionManager.withSshClient(profile) { client ->
                    val sftp = client.newSFTPClient()
                    try {
                        val resources = sftp.ls(cleanPath)
                        for (r in resources) {
                            val name = r.name
                            if (name == "." || name == "..") continue

                            val isDir = r.isDirectory || r.attributes.type == FileMode.Type.DIRECTORY
                            val size = r.attributes.size
                            val mtime = r.attributes.mtime * 1000L
                            val modifiedStr = if (mtime > 0) dateFormat.format(Date(mtime)) else "-"
                            val perms = r.attributes.mode?.toString() ?: ""
                            val itemPath = if (cleanPath == "/") "/$name" else "${cleanPath.trimEnd('/')}/$name"

                            results.add(
                                RemoteFileInfo(
                                    name = name,
                                    path = itemPath,
                                    isDirectory = isDir,
                                    size = size,
                                    formattedSize = if (isDir) "<DIR>" else formatFileSize(size),
                                    lastModified = modifiedStr,
                                    permissions = perms
                                )
                            )
                        }
                    } finally {
                        try { sftp.close() } catch (_: Exception) {}
                    }
                }

                results.sortWith(compareBy({ !it.isDirectory }, { it.name.lowercase(Locale.US) }))
                onResult(results, null)
            } catch (e: Exception) {
                fallbackListDirectory(profile, cleanPath, onResult, e.message ?: e.toString())
            }
        }
    }

    private fun fallbackListDirectory(
        profile: ServerProfile,
        cleanPath: String,
        onResult: (List<RemoteFileInfo>, error: String?) -> Unit,
        originalError: String
    ) {
        val cmd = "ls -la --time-style=+\"%Y-%m-%d %H:%M:%S\" \"$cleanPath\" 2>/dev/null || ls -la \"$cleanPath\""
        val lines = mutableListOf<String>()

        connectionManager.executeRemoteCommand(
            cmd = cmd,
            workingDir = "",
            onOutput = { lines.add(it) },
            onComplete = { code ->
                if (code == 0 && lines.isNotEmpty()) {
                    val parsed = parseLsOutput(cleanPath, lines)
                    onResult(parsed, null)
                } else {
                    onResult(emptyList(), "Masofaviy papkani o'qib bo'lmadi: $originalError")
                }
            }
        )
    }

    private fun parseLsOutput(basePath: String, lines: List<String>): List<RemoteFileInfo> {
        val results = mutableListOf<RemoteFileInfo>()
        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isBlank() || line.startsWith("total")) continue

            val parts = line.split("\\s+".toRegex())
            if (parts.size < 8) continue

            val perms = parts[0]
            val isDir = perms.startsWith("d")
            val size = parts[4].toLongOrNull() ?: 0L

            // Time and name resolution
            val name = parts.subList(7, parts.size).joinToString(" ").substringAfterLast(" ")
            if (name == "." || name == "..") continue

            val dateStr = if (parts.size >= 8) "${parts[5]} ${parts[6]}" else "-"
            val itemPath = if (basePath == "/") "/$name" else "${basePath.trimEnd('/')}/$name"

            results.add(
                RemoteFileInfo(
                    name = name,
                    path = itemPath,
                    isDirectory = isDir,
                    size = size,
                    formattedSize = if (isDir) "<DIR>" else formatFileSize(size),
                    lastModified = dateStr,
                    permissions = perms
                )
            )
        }
        results.sortWith(compareBy({ !it.isDirectory }, { it.name.lowercase(Locale.US) }))
        return results
    }

    fun readFileContent(
        profile: ServerProfile = connectionManager.config.activeProfile,
        remotePath: String,
        onResult: (content: String?, error: String?) -> Unit
    ) {
        executor.submit {
            try {
                connectionManager.withSshClient(profile) { client ->
                    val sftp = client.newSFTPClient()
                    try {
                        val temp = File.createTempFile("rf_view_", ".tmp")
                        temp.deleteOnExit()
                        sftp.get(remotePath, FileSystemFile(temp))
                        val text = temp.readText(Charsets.UTF_8)
                        temp.delete()
                        onResult(text, null)
                    } finally {
                        try { sftp.close() } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                // Fallback to cat
                val sb = StringBuilder()
                connectionManager.executeRemoteCommand(
                    cmd = "cat \"$remotePath\"",
                    workingDir = "",
                    onOutput = { sb.append(it) },
                    onComplete = { code ->
                        if (code == 0) onResult(sb.toString(), null)
                        else onResult(null, e.message ?: "Faylni o'qib bo'lmadi")
                    }
                )
            }
        }
    }

    fun saveFileContent(
        profile: ServerProfile = connectionManager.config.activeProfile,
        remotePath: String,
        newContent: String,
        onComplete: (Boolean, String?) -> Unit
    ) {
        executor.submit {
            var temp: File? = null
            try {
                temp = File.createTempFile("rf_save_", ".tmp")
                temp.writeText(newContent, Charsets.UTF_8)
                connectionManager.withSshClient(profile) { client ->
                    val sftp = client.newSFTPClient()
                    try {
                        sftp.put(FileSystemFile(temp), remotePath)
                    } finally {
                        try { sftp.close() } catch (_: Exception) {}
                    }
                }
                onComplete(true, null)
            } catch (e: Exception) {
                onComplete(false, e.message ?: e.toString())
            } finally {
                temp?.delete()
            }
        }
    }

    fun downloadFile(
        profile: ServerProfile = connectionManager.config.activeProfile,
        remotePath: String,
        localDest: File,
        onComplete: (Boolean, String?) -> Unit
    ) {
        executor.submit {
            try {
                connectionManager.withSshClient(profile) { client ->
                    val sftp = client.newSFTPClient()
                    try {
                        localDest.parentFile?.mkdirs()
                        sftp.get(remotePath, FileSystemFile(localDest))
                    } finally {
                        try { sftp.close() } catch (_: Exception) {}
                    }
                }
                onComplete(true, null)
            } catch (e: Exception) {
                onComplete(false, e.message ?: e.toString())
            }
        }
    }

    fun uploadFile(
        profile: ServerProfile = connectionManager.config.activeProfile,
        localFile: File,
        remoteTargetDir: String,
        onComplete: (Boolean, String?) -> Unit
    ) {
        executor.submit {
            try {
                val targetPath = remoteTargetDir.trimEnd('/') + "/" + localFile.name
                connectionManager.withSshClient(profile) { client ->
                    val sftp = client.newSFTPClient()
                    try {
                        sftp.put(FileSystemFile(localFile), targetPath)
                    } finally {
                        try { sftp.close() } catch (_: Exception) {}
                    }
                }
                onComplete(true, null)
            } catch (e: Exception) {
                onComplete(false, e.message ?: e.toString())
            }
        }
    }

    fun createDirectory(
        profile: ServerProfile = connectionManager.config.activeProfile,
        remotePath: String,
        onComplete: (Boolean, String?) -> Unit
    ) {
        executor.submit {
            try {
                connectionManager.withSshClient(profile) { client ->
                    val sftp = client.newSFTPClient()
                    try {
                        sftp.mkdirs(remotePath)
                    } finally {
                        try { sftp.close() } catch (_: Exception) {}
                    }
                }
                onComplete(true, null)
            } catch (e: Exception) {
                connectionManager.executeRemoteCommand(
                    cmd = "mkdir -p \"$remotePath\"",
                    workingDir = "",
                    onOutput = {},
                    onComplete = { code ->
                        onComplete(code == 0, if (code == 0) null else e.message)
                    }
                )
            }
        }
    }

    fun createNewFile(
        profile: ServerProfile = connectionManager.config.activeProfile,
        remotePath: String,
        onComplete: (Boolean, String?) -> Unit
    ) {
        saveFileContent(profile, remotePath, "", onComplete)
    }

    fun deletePath(
        profile: ServerProfile = connectionManager.config.activeProfile,
        remotePath: String,
        isDirectory: Boolean,
        onComplete: (Boolean, String?) -> Unit
    ) {
        val cmd = if (isDirectory) "rm -rf \"$remotePath\"" else "rm -f \"$remotePath\""
        connectionManager.executeRemoteCommand(
            cmd = cmd,
            workingDir = "",
            onOutput = {},
            onComplete = { code ->
                onComplete(code == 0, if (code == 0) null else "Xatolik (Exit status: $code)")
            }
        )
    }

    fun renamePath(
        profile: ServerProfile = connectionManager.config.activeProfile,
        oldPath: String,
        newPath: String,
        onComplete: (Boolean, String?) -> Unit
    ) {
        executor.submit {
            try {
                connectionManager.withSshClient(profile) { client ->
                    val sftp = client.newSFTPClient()
                    try {
                        sftp.rename(oldPath, newPath)
                    } finally {
                        try { sftp.close() } catch (_: Exception) {}
                    }
                }
                onComplete(true, null)
            } catch (e: Exception) {
                connectionManager.executeRemoteCommand(
                    cmd = "mv \"$oldPath\" \"$newPath\"",
                    workingDir = "",
                    onOutput = {},
                    onComplete = { code ->
                        onComplete(code == 0, if (code == 0) null else e.message)
                    }
                )
            }
        }
    }
}
