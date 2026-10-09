package uz.remote.flow.execution

import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import java.net.URLDecoder

/**
 * Console filter for Remote Flow that intercepts remote Linux compiler errors, file URLs,
 * and stack traces, mapping them to local project files and opening them in the IDE editor
 * instead of letting UrlFilter redirect to an external web browser.
 */
class RemoteFlowConsoleFilter(private val project: Project) : Filter, DumbAware {

    companion object {
        // Pattern 1: file:///root/...:line:col or file://...
        private val FILE_URL_PATTERN = Regex(
            """(?i)file://(?:localhost)?(/+[^\s:()\[\]]+\.[a-zA-Z0-9_\-]+)(?:[:\(\[]+(\d+)(?:[:,\s]+(\d+))?[\)\]]*)?"""
        )

        // Pattern 2: Linux absolute paths: /root/.../File.kt:line:col
        private val LINUX_PATH_PATTERN = Regex(
            """(?<![a-zA-Z0-9_/-])(/+[a-zA-Z0-9_.][^\s:()\[\]]*\.(?:kt|java|groovy|scala|xml|yml|yaml|properties|json|ts|js|py|go|rs|c|cpp|h|sql|html|gradle|kts|md|txt|cfg|conf|toml|env))(?:[:\(\[]+(\d+)(?:[:,\s]+(\d+))?[\)\]]*)?""",
            RegexOption.IGNORE_CASE
        )

        // Pattern 3: Windows paths: C:/.../File.kt:line:col
        private val WINDOWS_PATH_PATTERN = Regex(
            """(?<![a-zA-Z0-9_])([a-zA-Z]:[\\/][^\s:()\[\]]+\.(?:kt|java|groovy|scala|xml|yml|yaml|properties|json|ts|js|py|go|rs|c|cpp|h|sql|html|gradle|kts|md|txt|cfg|conf|toml|env))(?:[:\(\[]+(\d+)(?:[:,\s]+(\d+))?[\)\]]*)?""",
            RegexOption.IGNORE_CASE
        )

        // Pattern 4: Stack trace references: (FileName.kt:157)
        private val STACK_TRACE_PATTERN = Regex(
            """\(([a-zA-Z0-9_$\-]+\.(?:kt|java|groovy|scala|ts|js|py|go|rs)):(\d+)\)"""
        )

        private val SERVER_BADGE_PATTERN = Regex("""\[([a-zA-Z0-9_\- ]+)\]""")
    }

    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        if (line.isEmpty()) return null

        val lineStartOffset = entireLength - line.length
        val resultItems = mutableListOf<Filter.ResultItem>()
        val coveredRanges = mutableListOf<IntRange>()

        val hintServer = extractHintServerName(line)

        // 1. Check file:// URLs (Priority 1: overrides UrlFilter opening external browser)
        for (m in FILE_URL_PATTERN.findAll(line)) {
            val range = m.range
            if (isOverlapping(range, coveredRanges)) continue

            val rawPath = decodeUrlPath(m.groupValues[1])
            val lineNum = m.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
            val colNum = m.groupValues.getOrNull(3)?.toIntOrNull() ?: 0

            val resolvedVf = RemoteFlowPathResolver.resolve(project, rawPath, hintServer)
            val info = if (resolvedVf != null && resolvedVf.isValid) {
                OpenFileHyperlinkInfo(project, resolvedVf, maxOf(0, lineNum - 1), maxOf(0, colNum - 1))
            } else {
                RemoteFlowFileHyperlinkInfo(project, rawPath, resolvedVf, lineNum, colNum, hintServer)
            }

            val start = lineStartOffset + range.first
            val end = lineStartOffset + range.last + 1
            resultItems.add(Filter.ResultItem(start, end, info))
            coveredRanges.add(range)
        }

        // 2. Check Linux absolute paths
        for (m in LINUX_PATH_PATTERN.findAll(line)) {
            val range = m.range
            if (isOverlapping(range, coveredRanges)) continue

            val rawPath = decodeUrlPath(m.groupValues[1])
            val lineNum = m.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
            val colNum = m.groupValues.getOrNull(3)?.toIntOrNull() ?: 0

            val resolvedVf = RemoteFlowPathResolver.resolve(project, rawPath, hintServer)
            if (resolvedVf != null && resolvedVf.isValid) {
                val info = OpenFileHyperlinkInfo(project, resolvedVf, maxOf(0, lineNum - 1), maxOf(0, colNum - 1))
                val start = lineStartOffset + range.first
                val end = lineStartOffset + range.last + 1
                resultItems.add(Filter.ResultItem(start, end, info))
                coveredRanges.add(range)
            }
        }

        // 3. Check Windows paths
        for (m in WINDOWS_PATH_PATTERN.findAll(line)) {
            val range = m.range
            if (isOverlapping(range, coveredRanges)) continue

            val rawPath = decodeUrlPath(m.groupValues[1])
            val lineNum = m.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
            val colNum = m.groupValues.getOrNull(3)?.toIntOrNull() ?: 0

            val resolvedVf = RemoteFlowPathResolver.resolve(project, rawPath, hintServer)
            if (resolvedVf != null && resolvedVf.isValid) {
                val info = OpenFileHyperlinkInfo(project, resolvedVf, maxOf(0, lineNum - 1), maxOf(0, colNum - 1))
                val start = lineStartOffset + range.first
                val end = lineStartOffset + range.last + 1
                resultItems.add(Filter.ResultItem(start, end, info))
                coveredRanges.add(range)
            }
        }

        // 4. Check Stack trace references (e.g. at ... (FileName.kt:157))
        for (m in STACK_TRACE_PATTERN.findAll(line)) {
            val range = m.range
            if (isOverlapping(range, coveredRanges)) continue

            val fileName = m.groupValues[1]
            val lineNum = m.groupValues.getOrNull(2)?.toIntOrNull() ?: 0

            val resolvedVf = RemoteFlowPathResolver.resolve(project, fileName, hintServer)
            if (resolvedVf != null && resolvedVf.isValid) {
                val info = OpenFileHyperlinkInfo(project, resolvedVf, maxOf(0, lineNum - 1), 0)
                val start = lineStartOffset + range.first
                val end = lineStartOffset + range.last + 1
                resultItems.add(Filter.ResultItem(start, end, info))
                coveredRanges.add(range)
            }
        }

        return when {
            resultItems.isEmpty() -> null
            resultItems.size == 1 -> Filter.Result(
                resultItems[0].highlightStartOffset,
                resultItems[0].highlightEndOffset,
                resultItems[0].hyperlinkInfo
            )
            else -> Filter.Result(resultItems)
        }
    }

    private fun isOverlapping(candidate: IntRange, existing: List<IntRange>): Boolean {
        return existing.any { it.first <= candidate.last && candidate.first <= it.last }
    }

    private fun extractHintServerName(line: String): String? {
        val badges = SERVER_BADGE_PATTERN.findAll(line).map { it.groupValues[1] }.toList()
        return badges.firstOrNull { b ->
            !b.matches(Regex("""\d{2}:\d{2}:\d{2}""")) &&
            b !in listOf("ALL", "RUN", "SYNC", "PORT", "SSH", "FILES", "AI", "ERROR", "WARN", "INFO", "SUCCESS")
        }
    }

    private fun decodeUrlPath(path: String): String {
        return if (path.contains('%')) {
            try {
                URLDecoder.decode(path, "UTF-8")
            } catch (_: Throwable) {
                path
            }
        } else {
            path
        }
    }
}
