package uz.remote.flow.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import uz.remote.flow.ssh.RemoteConfig
import uz.remote.flow.ssh.ServerProfile

@Service(Service.Level.PROJECT)
@State(
    name = "RemoteFlowSettings",
    storages = [Storage("remoteFlow.xml")]
)
class RemoteFlowSettings(private val project: Project) : PersistentStateComponent<RemoteFlowSettings.State> {

    class State {
        var profiles: MutableList<ServerProfile> = mutableListOf(ServerProfile())
        var activeProfileIndex: Int = 0
        var autoReconnect: Boolean = true
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, myState)
        if (myState.profiles.isEmpty()) {
            myState.profiles.add(ServerProfile())
        }
        if (myState.activeProfileIndex !in myState.profiles.indices) {
            myState.activeProfileIndex = 0
        }
    }

    var config: RemoteConfig
        get() = RemoteConfig(myState.profiles, myState.activeProfileIndex)
        set(value) {
            myState.profiles = value.profiles
            myState.activeProfileIndex = value.activeProfileIndex
        }

    val activeProfile: ServerProfile
        get() {
            if (myState.activeProfileIndex !in myState.profiles.indices) {
                myState.activeProfileIndex = 0
            }
            if (myState.profiles.isEmpty()) {
                myState.profiles.add(ServerProfile())
            }
            return myState.profiles[myState.activeProfileIndex]
        }

    var activeProfileIndex: Int
        get() = myState.activeProfileIndex
        set(value) {
            if (value in myState.profiles.indices) {
                myState.activeProfileIndex = value
            }
        }

    var profiles: MutableList<ServerProfile>
        get() = myState.profiles
        set(value) {
            myState.profiles = value
        }

    var autoReconnect: Boolean
        get() = myState.autoReconnect
        set(value) {
            myState.autoReconnect = value
        }

    companion object {
        fun getInstance(project: Project): RemoteFlowSettings =
            project.getService(RemoteFlowSettings::class.java)
    }
}
