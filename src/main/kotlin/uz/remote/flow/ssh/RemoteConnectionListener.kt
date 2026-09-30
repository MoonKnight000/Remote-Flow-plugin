package uz.remote.flow.ssh

import com.intellij.util.messages.Topic

interface RemoteConnectionListener {
    fun connectionStateChanged(connected: Boolean, profile: ServerProfile)
    fun profileChanged(profile: ServerProfile)
    fun processStateChanged(running: Boolean, command: String?) {}
    fun portForwardingChanged() {}

    companion object {
        val TOPIC: Topic<RemoteConnectionListener> = Topic.create("Remote Flow Connection Topic", RemoteConnectionListener::class.java)
    }
}
