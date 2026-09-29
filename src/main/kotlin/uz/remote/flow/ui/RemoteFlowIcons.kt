package uz.remote.flow.ui

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object RemoteFlowIcons {
    @JvmField
    val REMOTE_FLOW: Icon = IconLoader.getIcon("/icons/remoteFlow.svg", RemoteFlowIcons::class.java)

    @JvmField
    val CLOUD_CONNECTED: Icon = IconLoader.getIcon("/icons/cloudConnected.svg", RemoteFlowIcons::class.java)

    @JvmField
    val CLOUD_DISCONNECTED: Icon = IconLoader.getIcon("/icons/cloudDisconnected.svg", RemoteFlowIcons::class.java)

    @JvmField
    val CLOUD_CONNECTING: Icon = IconLoader.getIcon("/icons/cloudConnecting.svg", RemoteFlowIcons::class.java)

    @JvmField
    val TERMINAL: Icon = IconLoader.getIcon("/icons/terminal.svg", RemoteFlowIcons::class.java)
}
