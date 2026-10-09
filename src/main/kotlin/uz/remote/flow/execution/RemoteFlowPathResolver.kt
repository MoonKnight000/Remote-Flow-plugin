package uz.remote.flow.execution

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import uz.remote.flow.settings.RemoteFlowSettings
import uz.remote.flow.ssh.ServerProfile
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Intelligent path resolver that maps remote server file paths (e.g., Linux remote build paths)
 * back to local workspace VirtualFiles so they can be opened directly in the IntelliJ editor.
 */
object RemoteFlowPathResolver {

    private val resolvedCache = ConcurrentHashMap<String, VirtualFile>()

    fun clearCache() {
        resolvedCache.clear()
    }

    /**
     * Resolves a remote or local path string to a local project [VirtualFile].
     */
    fun resolve(project: Project, rawPath: String, hintServerName: String? = null): VirtualFile? {
        val normalized = normalizePath(rawPath)
        if (normalized.isBlank()) return null

        val cacheKey = "${project.locationHash}:$normalized"
        resolvedCache[cacheKey]?.let { cached ->
            if (cached.isValid) return cached
            resolvedCache.remove(cacheKey)
        }

        val result = doResolve(project, normalized, hintServerName)
        if (result != null && result.isValid) {
            resolvedCache[cacheKey] = result
        }
        return result
    }

    private fun doResolve(project: Project, normalizedPath: String, hintServerName: String?): VirtualFile? {
        val lfs = LocalFileSystem.getInstance()

        // 1. Direct local file check (in case path is already a valid local path)
        val directIo = File(normalizedPath)
        if (directIo.isAbsolute && directIo.exists()) {
            val vf = lfs.findFileByIoFile(directIo) ?: lfs.refreshAndFindFileByIoFile(directIo)
            if (vf != null && vf.isValid) return vf
        }

        val settings = RemoteFlowSettings.getInstance(project)
        val projectBase = project.basePath?.replace('\\', '/') ?: ""

        // 2. Resolve via Server Profiles (prioritize hint server if matched in log line)
        val profilesToCheck = mutableListOf<ServerProfile>()
        if (!hintServerName.isNullOrBlank()) {
            settings.profiles.find { it.name.equals(hintServerName, ignoreCase = true) }?.let {
                profilesToCheck.add(it)
            }
        }
        settings.activeProfileOrNull?.let {
            if (!profilesToCheck.contains(it)) profilesToCheck.add(it)
        }
        settings.profiles.forEach {
            if (!profilesToCheck.contains(it)) profilesToCheck.add(it)
        }

        for (profile in profilesToCheck) {
            val remoteBase = profile.remoteProjectPath.replace('\\', '/').trimEnd('/')
            val localBase = profile.localProjectPath.ifBlank { projectBase }.replace('\\', '/').trimEnd('/')
            if (remoteBase.isNotBlank() && localBase.isNotBlank() && normalizedPath.startsWith(remoteBase, ignoreCase = true)) {
                val rel = normalizedPath.substring(remoteBase.length).trimStart('/')

                // Strategy A: Direct concatenation (localBase/rel)
                val f1 = File(localBase, rel)
                if (f1.exists()) {
                    val vf = lfs.findFileByIoFile(f1) ?: lfs.refreshAndFindFileByIoFile(f1)
                    if (vf != null && vf.isValid) return vf
                }

                // Strategy B: If localBase already ends with the first directory of rel
                // e.g. localBase is ".../uysot-app" and rel is "uysot-app/src/..."
                val firstSegment = rel.substringBefore('/', "")
                if (firstSegment.isNotBlank() && localBase.endsWith(firstSegment, ignoreCase = true)) {
                    val subRel = rel.substringAfter('/', "")
                    val f2 = File(localBase, subRel)
                    if (f2.exists()) {
                        val vf = lfs.findFileByIoFile(f2) ?: lfs.refreshAndFindFileByIoFile(f2)
                        if (vf != null && vf.isValid) return vf
                    }
                }
            }
        }

        // 3. Search relative to /src/ directory in project or content roots
        val srcIndex = normalizedPath.indexOf("/src/")
        if (srcIndex >= 0) {
            val relFromSrc = normalizedPath.substring(srcIndex + 1) // e.g. "src/main/kotlin/..."

            if (projectBase.isNotBlank()) {
                val directSrc = File(projectBase, relFromSrc)
                if (directSrc.exists()) {
                    val vf = lfs.findFileByIoFile(directSrc) ?: lfs.refreshAndFindFileByIoFile(directSrc)
                    if (vf != null && vf.isValid) return vf
                }
            }

            // Check across all project content roots / modules
            for (root in ProjectRootManager.getInstance(project).contentRoots) {
                val vf = root.findFileByRelativePath(relFromSrc)
                if (vf != null && vf.isValid) return vf
            }

            // Also check with module segment immediately preceding /src/ (e.g. ".../uysot-app/src/...")
            val beforeSrc = normalizedPath.substring(0, srcIndex)
            val moduleDir = beforeSrc.substringAfterLast('/')
            if (moduleDir.isNotBlank() && projectBase.isNotBlank()) {
                val modSrc = File(projectBase, "$moduleDir/$relFromSrc")
                if (modSrc.exists()) {
                    val vf = lfs.findFileByIoFile(modSrc) ?: lfs.refreshAndFindFileByIoFile(modSrc)
                    if (vf != null && vf.isValid) return vf
                }
            }
        }

        // 4. Filename Index Search fallback (when project is not in dumb mode)
        val fileName = normalizedPath.substringAfterLast('/')
        if (fileName.isNotBlank() && !DumbService.isDumb(project)) {
            try {
                val matches = FilenameIndex.getVirtualFilesByName(fileName, GlobalSearchScope.projectScope(project))
                if (matches.isNotEmpty()) {
                    if (matches.size == 1) {
                        return matches.first()
                    }
                    var best: VirtualFile? = null
                    var maxLen = -1
                    for (candidate in matches) {
                        val cPath = candidate.path.replace('\\', '/')
                        val common = commonSuffixLength(normalizedPath, cPath)
                        if (common > maxLen) {
                            maxLen = common
                            best = candidate
                        }
                    }
                    if (best != null) return best
                }
            } catch (_: Throwable) {}
        }

        return null
    }

    private fun normalizePath(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("file://", ignoreCase = true)) {
            s = s.substring(7)
            if (s.startsWith("localhost/", ignoreCase = true)) {
                s = s.substring(9)
            }
        }
        s = s.replace('\\', '/')
        while (s.startsWith("//")) {
            s = s.substring(1)
        }
        return s
    }

    private fun commonSuffixLength(a: String, b: String): Int {
        var count = 0
        val minLen = minOf(a.length, b.length)
        while (count < minLen && a[a.length - 1 - count].equals(b[b.length - 1 - count], ignoreCase = true)) {
            count++
        }
        return count
    }
}
