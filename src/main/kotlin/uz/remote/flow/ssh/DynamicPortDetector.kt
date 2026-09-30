package uz.remote.flow.ssh

import com.intellij.openapi.project.Project
import java.io.File

/**
 * Intelligent detector for dynamic application HTTP/service ports.
 * Parses stdout logs in real-time (Spring Boot Tomcat, Netty, Undertow, Jetty, Quarkus, Micronaut, etc.),
 * inspects run/debug command line arguments, and reads local project configuration (application.properties/yml).
 */
object DynamicPortDetector {

    private val EXCLUDED_PORTS = setOf(22, 5005, 5432, 3306, 6379, 5672, 15672, 27017)

    private val LOG_PATTERNS = listOf(
        // Tomcat: "Tomcat started on port(s): 9789 (http)", "Tomcat initialized with port(s): 9789"
        Regex("""Tomcat (?:started|initialized with) (?:on )?port(?:\(s\)|s)?(?::)?\s*(\d+)""", RegexOption.IGNORE_CASE),
        // Netty: "Netty started on port 9789" or "Netty started on port(s) 9789"
        Regex("""Netty started on port(?:\(s\)|s)?(?::)?\s*(\d+)""", RegexOption.IGNORE_CASE),
        // Undertow: "Undertow started on port(s) 9789"
        Regex("""Undertow started on port(?:\(s\)|s)?(?::)?\s*(\d+)""", RegexOption.IGNORE_CASE),
        // Jetty: "Jetty started on port 9789"
        Regex("""Jetty started on port(?:\(s\)|s)?(?::)?\s*(\d+)""", RegexOption.IGNORE_CASE),
        // Micronaut / Quarkus / Generic HTTP URL: "http://localhost:9789", "http://0.0.0.0:9789", "http://127.0.0.1:9789"
        Regex("""https?://(?:localhost|0\.0\.0\.0|127\.0\.0\.1|\[::\]):(\d{2,5})""", RegexOption.IGNORE_CASE),
        // "Listening and serving HTTP on :9789" or "Listening on port 9789" or "Listening on :9789"
        Regex("""Listening (?:and serving HTTP )?on (?:all interfaces |port\s+|[^\s:]*:)?(\d{2,5})""", RegexOption.IGNORE_CASE),
        // "Server running at ...:9789" or "Server started on port 9789"
        Regex("""Server (?:running|started) (?:at\s+[^\s:]+:|on port\s+)(\d{2,5})""", RegexOption.IGNORE_CASE),
        // "Application is running on port 9789"
        Regex("""(?:running|started|listening)\s+on\s+(?:port\s+)?(\d{2,5})""", RegexOption.IGNORE_CASE),
        // Spring Actuator: "beneath base path '/actuator' on port 9789"
        Regex("""beneath base path .* on port\s*(\d{2,5})""", RegexOption.IGNORE_CASE)
    )

    private val READY_PATTERNS = listOf(
        Regex("""Tomcat (?:started|initialized with) (?:on )?port""", RegexOption.IGNORE_CASE),
        Regex("""Netty started on port""", RegexOption.IGNORE_CASE),
        Regex("""Undertow started on port""", RegexOption.IGNORE_CASE),
        Regex("""Jetty started on port""", RegexOption.IGNORE_CASE),
        Regex("""Started \w+ in \d+(?:\.\d+)? seconds""", RegexOption.IGNORE_CASE),
        Regex("""Listening on (?:port|\S+:\d+)""", RegexOption.IGNORE_CASE),
        Regex("""Server running at https?://""", RegexOption.IGNORE_CASE),
        Regex("""Application is ready""", RegexOption.IGNORE_CASE)
    )

    private val COMMAND_PATTERNS = listOf(
        Regex("""(?:--server\.port|-Dserver\.port|SERVER_PORT|PORT)[=\s]+(\d+)""", RegexOption.IGNORE_CASE),
        Regex("""--port[=\s]+(\d+)""", RegexOption.IGNORE_CASE),
        Regex("""-p\s+(\d+)""", RegexOption.IGNORE_CASE)
    )

    fun extractPortFromLine(line: String): Int? {
        for (pattern in LOG_PATTERNS) {
            val match = pattern.find(line)
            if (match != null) {
                val portStr = match.groupValues[1]
                val port = portStr.toIntOrNull()
                if (port != null && port in 1..65535 && port !in EXCLUDED_PORTS) {
                    return port
                }
            }
        }
        return null
    }

    fun isAppReadyLine(line: String): Boolean {
        for (pattern in READY_PATTERNS) {
            if (pattern.containsMatchIn(line)) {
                return true
            }
        }
        return false
    }

    fun detectPortFromCommand(command: String): Int? {
        if (command.isBlank()) return null
        for (pattern in COMMAND_PATTERNS) {
            val match = pattern.find(command)
            if (match != null) {
                val port = match.groupValues[1].toIntOrNull()
                if (port != null && port in 1..65535 && port !in EXCLUDED_PORTS) {
                    return port
                }
            }
        }
        return null
    }

    fun detectPortFromLocalConfig(project: Project): Int? {
        val basePath = project.basePath ?: return null
        val baseDir = File(basePath)
        if (!baseDir.exists() || !baseDir.isDirectory) return null

        val candidateFiles = listOf(
            File(baseDir, "src/main/resources/application.properties"),
            File(baseDir, "src/main/resources/application.yml"),
            File(baseDir, "src/main/resources/application.yaml"),
            File(baseDir, "application.properties"),
            File(baseDir, "application.yml"),
            File(baseDir, "application.yaml")
        )

        for (file in candidateFiles) {
            if (file.exists() && file.isFile) {
                val port = parsePortFromFile(file)
                if (port != null && port in 1..65535 && port !in EXCLUDED_PORTS) {
                    return port
                }
            }
        }
        return null
    }

    private fun parsePortFromFile(file: File): Int? {
        try {
            val lines = file.readLines()
            if (file.name.endsWith(".properties")) {
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("#") || trimmed.startsWith("!")) continue
                    if (trimmed.startsWith("server.port")) {
                        val parts = trimmed.split("=", ":", limit = 2)
                        if (parts.size == 2) {
                            val p = parts[1].trim().toIntOrNull()
                            if (p != null) return p
                        }
                    }
                }
            } else {
                var inServerBlock = false
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("#")) continue
                    if (trimmed == "server:" || trimmed.startsWith("server:")) {
                        inServerBlock = true
                        continue
                    }
                    if (inServerBlock) {
                        if (!line.startsWith(" ") && !line.startsWith("\t") && trimmed.contains(":")) {
                            inServerBlock = false
                        } else if (trimmed.startsWith("port:")) {
                            val parts = trimmed.split(":", limit = 2)
                            if (parts.size == 2) {
                                val p = parts[1].trim().toIntOrNull()
                                if (p != null) return p
                            }
                        }
                    }
                    if (trimmed.startsWith("server.port:")) {
                        val parts = trimmed.split(":", limit = 2)
                        if (parts.size == 2) {
                            val p = parts[1].trim().toIntOrNull()
                            if (p != null) return p
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    fun resolveInitialAppPort(project: Project, command: String, defaultFallback: Int = 8080): Int {
        return detectPortFromCommand(command)
            ?: detectPortFromLocalConfig(project)
            ?: defaultFallback
    }

    fun buildTargetUrl(configuredUrl: String, detectedPort: Int): String {
        if (configuredUrl.isBlank()) {
            return "http://localhost:$detectedPort"
        }
        val localhostRegex = Regex("""(https?://(?:localhost|127\.0\.0\.1))(?::\d+)?(.*)""", RegexOption.IGNORE_CASE)
        val match = localhostRegex.find(configuredUrl.trim())
        if (match != null) {
            val schemeAndHost = match.groupValues[1]
            val path = match.groupValues[2]
            return "$schemeAndHost:$detectedPort$path"
        }
        return configuredUrl.trim()
    }
}
