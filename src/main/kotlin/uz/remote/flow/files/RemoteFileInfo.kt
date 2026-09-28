package uz.remote.flow.files

import java.util.Locale

data class RemoteFileInfo(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0L,
    val formattedSize: String = if (isDirectory) "<DIR>" else formatFileSize(size),
    val lastModified: String = "-",
    val permissions: String = "",
    val extension: String = if (isDirectory) "" else name.substringAfterLast('.', "").lowercase(Locale.US)
) {
    val isParentDir: Boolean get() = name == ".."

    val typeDescription: String get() {
        if (isDirectory) return "Folder"
        return when (extension) {
            "kt", "kts" -> "Kotlin Source"
            "java" -> "Java Source"
            "xml" -> "XML Configuration"
            "yml", "yaml" -> "YAML Configuration"
            "json" -> "JSON Document"
            "properties", "env", "conf" -> "Config File"
            "gradle" -> "Gradle Script"
            "sh", "bash" -> "Shell Script"
            "sql" -> "SQL Script"
            "md" -> "Markdown Document"
            "txt" -> "Text File"
            "log" -> "Log File"
            "jar", "war" -> "Java Archive"
            "zip", "tar", "gz" -> "Archive"
            "dockerfile" -> "Docker Specification"
            "" -> if (name.equals("Dockerfile", ignoreCase = true)) "Docker Specification" else "Binary / Plain"
            else -> extension.uppercase(Locale.US) + " File"
        }
    }

    val iconEmoji: String get() {
        if (isParentDir) return "📁 ⬆"
        if (isDirectory) return "📁"
        return when (extension) {
            "kt", "kts", "java" -> "☕"
            "xml", "yml", "yaml", "json", "properties", "env", "conf" -> "⚙"
            "gradle" -> "🐘"
            "sh", "bash" -> "⚡"
            "sql" -> "🗄"
            "md", "txt" -> "📄"
            "log" -> "📜"
            "jar", "war", "zip", "tar", "gz" -> "📦"
            "dockerfile" -> "🐳"
            else -> if (name.equals("Dockerfile", ignoreCase = true)) "🐳" else "📄"
        }
    }
}

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.2f GB", gb)
}
