package uz.remote.flow.ssh

import java.io.File

data class SshConfigEntry(
    val hostAlias: String,
    val hostName: String,
    val port: Int = 22,
    val user: String = "root",
    val identityFile: String = "",
    val proxyJump: String = ""
)

object SshConfigParser {

    fun getDefaultConfigFile(): File {
        val userHome = System.getProperty("user.home") ?: ""
        return File(File(userHome, ".ssh"), "config")
    }

    fun parseConfigFile(file: File = getDefaultConfigFile()): List<SshConfigEntry> {
        if (!file.exists() || !file.isFile) return emptyList()

        val entries = mutableListOf<SshConfigEntry>()
        val lines = file.readLines()

        var currentHost: String? = null
        var hostName: String? = null
        var user: String? = null
        var port: Int = 22
        var identityFile: String? = null
        var proxyJump: String? = null

        fun saveCurrent() {
            val alias = currentHost ?: return
            if (alias != "*" && !alias.contains("*") && !alias.contains("?")) {
                entries.add(
                    SshConfigEntry(
                        hostAlias = alias,
                        hostName = hostName ?: alias,
                        port = port,
                        user = user ?: "root",
                        identityFile = identityFile ?: "",
                        proxyJump = proxyJump ?: ""
                    )
                )
            }
        }

        val userHome = System.getProperty("user.home") ?: ""

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue

            val parts = line.split(Regex("""\s+"""), 2)
            if (parts.size < 2) continue

            val key = parts[0].trim().lowercase()
            val value = parts[1].trim()

            when (key) {
                "host" -> {
                    saveCurrent()
                    currentHost = value
                    hostName = null
                    user = null
                    port = 22
                    identityFile = null
                    proxyJump = null
                }
                "hostname" -> {
                    hostName = value
                }
                "user" -> {
                    user = value
                }
                "port" -> {
                    port = value.toIntOrNull() ?: 22
                }
                "identityfile" -> {
                    var idPath = value.replace("\"", "")
                    if (idPath.startsWith("~")) {
                        idPath = userHome + idPath.removePrefix("~")
                    }
                    identityFile = idPath
                }
                "proxyjump" -> {
                    proxyJump = value
                }
            }
        }
        saveCurrent()

        return entries
    }

    fun toServerProfile(entry: SshConfigEntry, localProjectPath: String = ""): ServerProfile {
        val authType = if (entry.identityFile.isNotBlank()) AuthType.PRIVATE_KEY else AuthType.PASSWORD
        val profileName = entry.hostAlias.ifBlank { entry.hostName }

        return ServerProfile(
            name = profileName,
            host = entry.hostName,
            port = entry.port,
            user = entry.user,
            authType = authType,
            privateKeyPath = entry.identityFile,
            localProjectPath = localProjectPath,
            remoteProjectPath = "/home/${entry.user}/remote-flow/${File(localProjectPath).name.ifBlank { "app" }}",
            jumpHost = entry.proxyJump
        )
    }
}
