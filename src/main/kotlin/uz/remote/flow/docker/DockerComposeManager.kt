package uz.remote.flow.docker

import com.intellij.openapi.project.Project
import uz.remote.flow.ssh.RemoteConnectionManager

data class DockerContainerInfo(
    val name: String,
    val image: String,
    val status: String,
    val ports: String
)

class DockerComposeManager(private val project: Project) {

    private val connectionManager get() = RemoteConnectionManager.getInstance(project)

    fun composeUp(build: Boolean = true, onOutput: (String) -> Unit, onComplete: (Int) -> Unit) {
        val flag = if (build) "up -d --build" else "up -d"
        connectionManager.executeRemoteCommand(
            cmd = "docker compose $flag || docker-compose $flag",
            onOutput = onOutput,
            onComplete = onComplete
        )
    }

    fun composeDown(onOutput: (String) -> Unit, onComplete: (Int) -> Unit) {
        connectionManager.executeRemoteCommand(
            cmd = "docker compose down || docker-compose down",
            onOutput = onOutput,
            onComplete = onComplete
        )
    }

    fun composePs(onOutput: (String) -> Unit, onComplete: (Int) -> Unit) {
        connectionManager.executeRemoteCommand(
            cmd = "docker compose ps -a || docker ps -a",
            onOutput = onOutput,
            onComplete = onComplete
        )
    }

    fun streamLogs(serviceName: String = "", onOutput: (String) -> Unit, onComplete: (Int) -> Unit) {
        val cmd = if (serviceName.isNotBlank()) "docker compose logs -f --tail=100 $serviceName" else "docker compose logs -f --tail=100"
        connectionManager.executeRemoteCommand(
            cmd = cmd,
            onOutput = onOutput,
            onComplete = onComplete
        )
    }

    fun restartContainer(containerName: String, onOutput: (String) -> Unit, onComplete: (Int) -> Unit) {
        connectionManager.executeRemoteCommand(
            cmd = "docker restart $containerName",
            onOutput = onOutput,
            onComplete = onComplete
        )
    }

    fun stopContainer(containerName: String, onOutput: (String) -> Unit, onComplete: (Int) -> Unit) {
        connectionManager.executeRemoteCommand(
            cmd = "docker stop $containerName",
            onOutput = onOutput,
            onComplete = onComplete
        )
    }

    fun startContainer(containerName: String, onOutput: (String) -> Unit, onComplete: (Int) -> Unit) {
        connectionManager.executeRemoteCommand(
            cmd = "docker start $containerName",
            onOutput = onOutput,
            onComplete = onComplete
        )
    }

    fun pruneDockerSystem(onOutput: (String) -> Unit, onComplete: (Int) -> Unit) {
        connectionManager.executeRemoteCommand(
            cmd = "docker system prune -af --volumes",
            onOutput = onOutput,
            onComplete = onComplete
        )
    }

    fun fetchContainerList(onResult: (List<DockerContainerInfo>) -> Unit) {
        val cmd = "docker ps -a --format '{{.Names}}|{{.Image}}|{{.Status}}|{{.Ports}}'"
        val lines = mutableListOf<String>()
        connectionManager.executeRemoteCommand(
            cmd = cmd,
            workingDir = "",
            onOutput = { lines.add(it) },
            onComplete = { code ->
                val list = mutableListOf<DockerContainerInfo>()
                if (code == 0) {
                    val fullText = lines.joinToString("")
                    for (line in fullText.lines()) {
                        val trimmed = line.trim()
                        if (trimmed.isNotBlank() && trimmed.contains("|")) {
                            val parts = trimmed.split("|")
                            if (parts.size >= 3) {
                                val name = parts[0]
                                val img = parts[1]
                                val st = parts[2]
                                val prts = if (parts.size >= 4) parts[3] else ""
                                list.add(DockerContainerInfo(name, img, st, prts))
                            }
                        }
                    }
                }
                onResult(list)
            }
        )
    }
}
