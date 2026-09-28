package uz.remote.flow.ssh

import com.intellij.util.messages.Topic

interface RemoteConnectionListener {
    fun connectionStateChanged(connected: Boolean, profile: ServerProfile)
    fun profileChanged(profile: ServerProfile)

    companion object {
        val TOPIC: Topic<RemoteConnectionListener> = Topic.create("Remote Flow Connection Topic", RemoteConnectionListener::class.java)
    }
}
