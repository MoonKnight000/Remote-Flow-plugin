package uz.remote.flow.execution

import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ExecutionConsole
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBSplitter
import uz.remote.flow.ui.RemotePerformancePanel
import javax.swing.JComponent

/**
 * Composite Execution Console hosting the standard IDE console log on the left
 * and the Remote Flow Performance & Profiler panel on the right.
 */
class RemoteFlowExecutionConsole(
    val consoleView: ConsoleView,
    val performancePanel: RemotePerformancePanel
) : ConsoleView by consoleView, ExecutionConsole, Disposable {

    private val splitter = JBSplitter(false, 0.76f).apply {
        firstComponent = consoleView.component
        secondComponent = performancePanel
        dividerWidth = 2
    }

    override fun getComponent(): JComponent = splitter

    override fun getPreferredFocusableComponent(): JComponent = consoleView.preferredFocusableComponent

    override fun dispose() {
        Disposer.dispose(performancePanel)
        Disposer.dispose(consoleView)
    }
}
