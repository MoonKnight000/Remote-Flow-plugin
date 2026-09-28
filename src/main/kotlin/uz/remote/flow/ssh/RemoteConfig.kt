package uz.remote.flow.ssh

import java.util.UUID

enum class AuthType {
    PASSWORD,
    PRIVATE_KEY
}

enum class SyncMode {
    MANUAL,
    AUTO_INTERVAL
}

data class PortMapping(
    var localPort: Int = 8080,
    var remotePort: Int = 8080,
    var serviceName: String = "Service",
    var isForwarded: Boolean = false
)

data class ServerProfile(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "Server 1 (Dev)",
    var host: String = "192.168.1.100",
    var port: Int = 22,
    var authType: AuthType = AuthType.PASSWORD,
    var user: String = "root",
    var password: String = "",
    var privateKeyPath: String = "",
    var localProjectPath: String = "",
    var remoteProjectPath: String = "",
    var syncMode: SyncMode = SyncMode.MANUAL,
    var syncIntervalMinutes: Int = 5,
    var runCommand: String = "./gradlew bootRun",
    var debugCommand: String = "./gradlew bootRun --debug-jvm",
    var excludePatterns: String = defaultExcludes(),
    var rsyncPath: String = "",
    var monitorMode: String = "REALTIME",
    var autoSyncOnSave: Boolean = false,
    var forwardedPorts: MutableList<PortMapping> = defaultPorts()
) {
    override fun toString(): String = name + " (" + host + ")"

    fun copyProfile(newName: String = name, newId: String = id): ServerProfile {
        return ServerProfile(
            id = newId,
            name = newName,
            host = host,
            port = port,
            authType = authType,
            user = user,
            password = password,
            privateKeyPath = privateKeyPath,
            localProjectPath = localProjectPath,
            remoteProjectPath = remoteProjectPath,
            syncMode = syncMode,
            syncIntervalMinutes = syncIntervalMinutes,
            runCommand = runCommand,
            debugCommand = debugCommand,
            excludePatterns = excludePatterns,
            rsyncPath = rsyncPath,
            monitorMode = monitorMode,
            autoSyncOnSave = autoSyncOnSave,
            forwardedPorts = forwardedPorts.map { it.copy() }.toMutableList()
        )
    }

    fun duplicateProfile(): ServerProfile = copyProfile(
        newName = "$name (Copy)",
        newId = UUID.randomUUID().toString()
    )

    companion object {
        fun defaultPorts(): MutableList<PortMapping> = mutableListOf(
            PortMapping(5432, 5432, "PostgreSQL Database"),
            PortMapping(6379, 6379, "Redis"),
            PortMapping(5672, 5672, "RabbitMQ"),
            PortMapping(15672, 15672, "RabbitMQ Management"),
            PortMapping(5005, 5005, "JVM Remote Debug"),
            PortMapping(8080, 8080, "Backend App")
        )
    }
}

fun defaultExcludes(): String =
    ".git, .gradle, build, .idea, out, target, node_modules, *.log, *.tmp, *.class, __pycache__, .DS_Store, *.swp"

fun parseExcludeList(patterns: String): List<String> {
    return patterns.split(',', '\n', ';')
        .map { it.trim() }
        .filter { it.isNotBlank() }
}

fun isPathExcluded(relativePath: String, fileName: String, excludePatterns: List<String>): Boolean {
    val cleanRel = relativePath.replace('\\', '/').trim('/')
    for (pat in excludePatterns) {
        val cleanPat = pat.trim().replace('\\', '/').trim('/')
        if (cleanPat.isEmpty()) continue
        if (cleanPat.startsWith("*.")) {
            val ext = cleanPat.removePrefix("*")
            if (fileName.endsWith(ext, ignoreCase = true)) return true
        } else if (cleanPat.contains('*')) {
            val regex = Regex("^" + Regex.escape(cleanPat).replace("\\*", ".*") + "$", RegexOption.IGNORE_CASE)
            if (regex.matches(fileName) || regex.matches(cleanRel)) return true
        } else {
            if (fileName.equals(cleanPat, ignoreCase = true) ||
                cleanRel.equals(cleanPat, ignoreCase = true) ||
                cleanRel.startsWith("$cleanPat/") ||
                cleanRel.contains("/$cleanPat/") ||
                cleanRel.endsWith("/$cleanPat")
            ) {
                return true
            }
        }
    }
    return false
}

fun detectRsyncPath(): String {
    try {
        val p = ProcessBuilder("rsync", "--version").start()
        if (p.waitFor() == 0) return "rsync"
    } catch (_: Exception) {}

    val userProfile = System.getenv("USERPROFILE") ?: ""
    val localAppData = System.getenv("LOCALAPPDATA") ?: ""
    val programFiles = System.getenv("ProgramFiles") ?: "C:\\Program Files"
    val programFilesX86 = System.getenv("ProgramFiles(x86)") ?: "C:\\Program Files (x86)"

    val candidates = listOf(
        "C:\\cwrsync\\bin\\rsync.exe",
        "C:\\tools\\cwrsync\\bin\\rsync.exe",
        "$programFiles\\Git\\usr\\bin\\rsync.exe",
        "$programFilesX86\\Git\\usr\\bin\\rsync.exe",
        "$localAppData\\Programs\\Git\\usr\\bin\\rsync.exe",
        "C:\\msys64\\usr\\bin\\rsync.exe",
        "C:\\tools\\msys64\\usr\\bin\\rsync.exe",
        "C:\\cygwin64\\bin\\rsync.exe",
        "C:\\cygwin\\bin\\rsync.exe",
        "C:\\ProgramData\\chocolatey\\bin\\rsync.exe",
        "$userProfile\\scoop\\shims\\rsync.exe"
    )

    for (cand in candidates) {
        val f = java.io.File(cand)
        if (f.exists() && f.canExecute()) {
            return f.absolutePath
        }
    }
    return ""
}

fun cleanServerName(name: String): String {
    return name.replace(Regex("""(\s*\(\s*Copy\s*\))+""", RegexOption.IGNORE_CASE), "").trim()
}

data class RemoteConfig(
    val profiles: MutableList<ServerProfile> = mutableListOf(),
    var activeProfileIndex: Int = 0
) {
    val activeProfileOrNull: ServerProfile?
        get() {
            if (profiles.isEmpty()) return null
            val idx = activeProfileIndex.coerceIn(profiles.indices)
            return profiles[idx]
        }

    val activeProfile: ServerProfile
        get() = activeProfileOrNull ?: ServerProfile(name = "No Server", host = "")
}
