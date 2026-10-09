package uz.remote.flow.execution

import com.intellij.execution.filters.ConsoleFilterProvider
import com.intellij.execution.filters.Filter
import com.intellij.openapi.project.Project

/**
 * Extension provider contributing RemoteFlowConsoleFilter to all IntelliJ IDEA console views.
 */
class RemoteFlowConsoleFilterProvider : ConsoleFilterProvider {
    override fun getDefaultFilters(project: Project): Array<Filter> {
        return arrayOf(RemoteFlowConsoleFilter(project))
    }
}
