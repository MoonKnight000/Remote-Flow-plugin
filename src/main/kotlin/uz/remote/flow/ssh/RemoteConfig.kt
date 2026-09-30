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

enum class ForwardDirection {
    LOCAL_TO_REMOTE, // Local -> Host (ssh -L)
    REMOTE_TO_LOCAL  // Host -> Local (ssh -R)
}

enum class ServerEnvironment(val displayName: String, val tagColorRgb: Int) {
    DEV("DEV", 0x22C55E),
    STAGING("STAGING", 0xF59E0B),
    PRODUCTION("PROD", 0xEF4444)
}

data class PortMapping(
    var localPort: Int = 8080,
    var remotePort: Int = 8080,
    var serviceName: String = "Service",
    var isForwarded: Boolean = false,
    var direction: ForwardDirection = ForwardDirection.LOCAL_TO_REMOTE
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
    var javaHome: String = "",
    var runCommand: String = "./gradlew bootRun",
    var debugCommand: String = "./gradlew bootRun --debug-jvm",
    var excludePatterns: String = defaultExcludes(),
    var rsyncPath: String = "",
    var monitorMode: String = "REALTIME",
    var autoSyncOnSave: Boolean = false,
    var environment: ServerEnvironment = ServerEnvironment.DEV,
    var confirmOnProduction: Boolean = true,
    var openBrowserOnReady: Boolean = true,
    var browserUrl: String = "http://localhost:8080",
    var preRunCommand: String = "",
    var postRunCommand: String = "",
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
            javaHome = javaHome,
            runCommand = runCommand,
            debugCommand = debugCommand,
            excludePatterns = excludePatterns,
            rsyncPath = rsyncPath,
            monitorMode = monitorMode,
            autoSyncOnSave = autoSyncOnSave,
            environment = environment,
            confirmOnProduction = confirmOnProduction,
            openBrowserOnReady = openBrowserOnReady,
            browserUrl = browserUrl,
            preRunCommand = preRunCommand,
            postRunCommand = postRunCommand,
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
            PortMapping(5005, 5005, "JVM Remote Debug")
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
    // 1. IntelliJ IDEA native Rsync configuration
    try {
        val ideRsync = uz.remote.flow.sync.IntelliJRsyncConfigProvider.getRsyncConfig().rsyncPath
        if (ideRsync.isNotBlank()) {
            val f = java.io.File(ideRsync)
            if (f.exists() && f.canExecute()) {
                return f.absolutePath
            }
        }
    } catch (_: Throwable) {}

    // 2. Rsync on system PATH
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

fun resolveJavaEnvPrefix(javaHome: String): String {
    val trimmed = javaHome.trim()
    if (trimmed.isBlank()) return ""
    val home = when {
        trimmed.endsWith("/bin/java") -> trimmed.removeSuffix("/bin/java")
        trimmed.endsWith("/bin") -> trimmed.removeSuffix("/bin")
        else -> trimmed
    }.trimEnd('/', '\\')
    return "export JAVA_HOME=\"$home\"; export PATH=\"$home/bin:\$PATH\"; "
}

fun cleanAnsiText(text: String): String {
    if (text.isEmpty()) return text
    var s = text.replace(Regex("""\u001B\[[0-9;?]*[ -/]*[@-~]"""), "")
    s = s.replace(Regex("""\u001B\][^\u0007\u001B]*(?:\u0007|\u001B\\)"""), "")
    s = s.replace(Regex("""\u001B[@-Z\\-_]"""), "")
    s = s.replace(Regex("""\[\d+[a-zA-Z]"""), "")
    s = s.replace(Regex("""\[\?[0-9]+[a-zA-Z]"""), "")
    s = s.replace("\r", "")
    return s
}

fun buildRemoteExecutionCommand(rawCmd: String, javaHome: String): String {
    val javaPrefix = resolveJavaEnvPrefix(javaHome)
    var cmd = rawCmd.trim()
    // If executing Gradle, ensure colored console without rich progress animations
    if ((cmd.contains("gradlew") || cmd.contains("gradle")) && !cmd.contains("--console")) {
        cmd = "$cmd --console=colored"
    }
    return "${javaPrefix}export GRADLE_OPTS=\"-Dorg.gradle.console=colored \${GRADLE_OPTS:-}\"; export TERM=xterm-256color; export FORCE_COLOR=1; export SPRING_OUTPUT_ANSI_ENABLED=ALWAYS; sed -i 's/\\r$//' ./gradlew ./mvnw *.sh 2>/dev/null || true; chmod +x ./gradlew ./mvnw *.sh 2>/dev/null || true; $cmd"
}

fun cleanProgressRemnants(text: String): String {
    if (text.isEmpty()) return text
    // Strip Gradle/Maven interactive progress bars, e.g. │█████████████▎·│ 88% EXECUTING [14s]> :bootRun
    var s = text.replace(Regex("""[│|]?[█\s▎·#=\-/\\<]*\d+%\s*(EXECUTING|WAITING|CONFIGURING|BUILDING|RUNNING)(\s*\[[^\]\n]*\])?(>\s*:[a-zA-Z0-9_\-:]+)?"""), "")
    s = s.replace(Regex("""[│|][█\s▎·#=\-/\\<]+[│|]"""), "")
    return s.trim()
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
