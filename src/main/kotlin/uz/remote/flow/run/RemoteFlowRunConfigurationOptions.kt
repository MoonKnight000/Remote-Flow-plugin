package uz.remote.flow.run

import com.intellij.execution.configurations.LocatableRunConfigurationOptions

class RemoteFlowRunConfigurationOptions : LocatableRunConfigurationOptions() {
    private val myServerProfileName = string("").provideDelegate(this, "serverProfileName")
    private val myRunCommand = string("").provideDelegate(this, "runCommand")
    private val myDebugCommand = string("").provideDelegate(this, "debugCommand")
    private val myRemoteWorkingDir = string("").provideDelegate(this, "remoteWorkingDir")
    private val myAutoSync = property(true).provideDelegate(this, "autoSync")
    private val myForwardPorts = property(true).provideDelegate(this, "forwardPorts")

    var serverProfileName: String
        get() = myServerProfileName.getValue(this) ?: ""
        set(value) = myServerProfileName.setValue(this, value)

    var runCommand: String
        get() = myRunCommand.getValue(this) ?: ""
        set(value) = myRunCommand.setValue(this, value)

    var debugCommand: String
        get() = myDebugCommand.getValue(this) ?: ""
        set(value) = myDebugCommand.setValue(this, value)

    var remoteWorkingDir: String
        get() = myRemoteWorkingDir.getValue(this) ?: ""
        set(value) = myRemoteWorkingDir.setValue(this, value)

    var autoSync: Boolean
        get() = myAutoSync.getValue(this)
        set(value) = myAutoSync.setValue(this, value)

    var forwardPorts: Boolean
        get() = myForwardPorts.getValue(this)
        set(value) = myForwardPorts.setValue(this, value)
}
