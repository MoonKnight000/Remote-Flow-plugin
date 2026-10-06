package uz.remote.flow.execution

import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.project.Project
import uz.remote.flow.logging.LogCategory
import uz.remote.flow.logging.RemoteFlowLogService
import uz.remote.flow.ssh.RemoteConnectionManager
import uz.remote.flow.ssh.ServerProfile
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

class RemoteFlowProcessHandler(
    private val project: Project,
    val profile: ServerProfile,
    val command: String
) : ProcessHandler() {

    private val isStopping = AtomicBoolean(false)

    override fun destroyProcessImpl() {
        if (!isStopping.compareAndSet(false, true)) return

        val connMgr = RemoteConnectionManager.getInstance(project)
        val logService = RemoteFlowLogService.getInstance(project)
        notifyTextAvailable("[REMOTE FLOW] Stopping application on ${profile.name} via IDE controls...\n", ProcessOutputType.SYSTEM)
        logService.log("[REMOTE FLOW] Stopping application via IDE controls on ${profile.name}...\n", LogCategory.RUN, profile.name)

        connMgr.stopAppPortForward()
        connMgr.stopRemoteProcess(
            profile = profile,
            onOutput = { text -> notifyTextAvailable(text, ProcessOutputType.STDOUT) },
            onComplete = {
                notifyTextAvailable("[REMOTE FLOW] Application stopped.\n", ProcessOutputType.SYSTEM)
                finishProcess(130)
            }
        )
    }

    override fun detachProcessImpl() {
        if (!isStopping.compareAndSet(false, true)) return

        val connMgr = RemoteConnectionManager.getInstance(project)
        val logService = RemoteFlowLogService.getInstance(project)
        notifyTextAvailable("[REMOTE FLOW] Detaching from remote process (leaving it running on ${profile.name})...\n", ProcessOutputType.SYSTEM)
        logService.log("[REMOTE FLOW] Detaching from remote process on ${profile.name}...\n", LogCategory.RUN, profile.name)

        connMgr.detachRemoteProcess()
        notifyProcessDetached()
    }

    override fun detachIsDefault(): Boolean = false

    override fun getProcessInput(): OutputStream {
        return object : OutputStream() {
            override fun write(b: Int) {
                write(byteArrayOf(b.toByte()), 0, 1)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                try {
                    val sub = b.copyOfRange(off, off + len)
                    RemoteConnectionManager.getInstance(project).sendProcessInput(sub)
                } catch (_: Exception) {}
            }
        }
    }

    fun printOutput(text: String, isError: Boolean = false) {
        val type = if (isError) ProcessOutputType.STDERR else ProcessOutputType.STDOUT
        notifyTextAvailable(text, type)
    }

    fun printSystem(text: String) {
        notifyTextAvailable(text, ProcessOutputType.SYSTEM)
    }

    fun finishProcess(exitCode: Int) {
        if (!isProcessTerminated && !isProcessTerminating) {
            notifyProcessTerminated(exitCode)
        }
    }

    fun markDetached() {
        if (!isProcessTerminated && !isProcessTerminating) {
            notifyProcessDetached()
        }
    }
}
