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
    var forwardedPorts: MutableList<PortMapping> = defaultPorts()
) {
    override fun toString(): String = name + " (" + host + ")"

    fun copyProfile(): ServerProfile {
        return ServerProfile(
            id = UUID.randomUUID().toString(),
            name = "$name (Copy)",
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
            forwardedPorts = forwardedPorts.map { it.copy() }.toMutableList()
        )
    }

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

data class RemoteConfig(
    val profiles: MutableList<ServerProfile> = mutableListOf(ServerProfile()),
    var activeProfileIndex: Int = 0
) {
    val activeProfile: ServerProfile
        get() {
            if (activeProfileIndex !in profiles.indices) {
                activeProfileIndex = 0
            }
            if (profiles.isEmpty()) {
                profiles.add(ServerProfile())
            }
            return profiles[activeProfileIndex]
        }
}
