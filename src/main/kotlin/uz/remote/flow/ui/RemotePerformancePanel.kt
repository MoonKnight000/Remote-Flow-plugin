package uz.remote.flow.ui

import com.intellij.execution.process.ProcessHandler
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import uz.remote.flow.profiler.ProfilerListener
import uz.remote.flow.profiler.ProfilerMetric
import uz.remote.flow.profiler.ProfilerMode
import uz.remote.flow.profiler.ProfilerState
import uz.remote.flow.profiler.RemoteProfilerManager
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.GeneralPath
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Embedded Performance and Profiling Panel modeled after IntelliJ's native Run Performance tab.
 * Displays real-time live CPU and Heap Memory area charts and provides 1-click recording controls.
 */
class RemotePerformancePanel(
    val project: Project,
    val profilerManager: RemoteProfilerManager,
    val processHandler: ProcessHandler? = null
) : JPanel(BorderLayout()), Disposable, ProfilerListener {

    private val cardLayout = CardLayout()
    private val mainContainer = JPanel(cardLayout)

    private val expandedPanel = JPanel(BorderLayout())
    private val collapsedPanel = JPanel(BorderLayout())

    // Header controls
    private val titleLabel = JBLabel("Performance").apply {
        font = font.deriveFont(Font.BOLD, 12f)
        foreground = UIUtil.getLabelForeground()
    }
    private val collapseButton = JButton("›").apply {
        toolTipText = "Collapse Performance panel"
        isBorderPainted = false
        isContentAreaFilled = false
        isFocusPainted = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        font = font.deriveFont(Font.BOLD, 14f)
        margin = JBUI.emptyInsets()
        addActionListener { toggleCollapse(true) }
    }

    // Controls bar
    private val recordButton = JButton("Start Recording").apply {
        icon = AllIcons.Actions.Execute
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        font = font.deriveFont(Font.PLAIN, 12f)
    }
    private val modeDropdownButton = JButton("▾").apply {
        toolTipText = "Select Profiling Mode"
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        isFocusPainted = false
        margin = JBUI.insets(2, 4)
        addActionListener { showModePopup() }
    }
    private val timerLabel = JBLabel("⏱ 00:00").apply {
        foreground = JBColor.GRAY
        font = font.deriveFont(Font.PLAIN, 11f)
    }

    // Live Charts
    private val cpuChart = MetricLiveChart(
        chartTitle = "CPU",
        unit = "%",
        lineColor = JBColor(Color(76, 175, 80), Color(90, 195, 95)),
        fillColorStart = JBColor(Color(76, 175, 80, 110), Color(76, 175, 80, 120)),
        fillColorEnd = JBColor(Color(76, 175, 80, 15), Color(76, 175, 80, 20)),
        fixedMax = 100.0
    )

    private val heapChart = MetricLiveChart(
        chartTitle = "Heap Memory",
        unit = "MB",
        lineColor = JBColor(Color(33, 150, 243), Color(45, 160, 255)),
        fillColorStart = JBColor(Color(33, 150, 243, 110), Color(33, 150, 243, 120)),
        fillColorEnd = JBColor(Color(33, 150, 243, 15), Color(33, 150, 243, 20)),
        fixedMax = null
    )

    // Last Results Action
    private val viewLastButton = JButton("📊 View Last Results").apply {
        isVisible = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        font = font.deriveFont(Font.PLAIN, 11f)
        addActionListener {
            profilerManager.lastSnapshot?.let { snapshot ->
                RemoteProfilerResultsDialog(project, snapshot).show()
            }
        }
    }

    private var isCollapsed = false

    init {
        border = JBUI.Borders.customLine(JBColor.border(), 0, 1, 0, 0)
        preferredSize = Dimension(280, 300)
        minimumSize = Dimension(28, 100)

        buildExpandedView()
        buildCollapsedView()

        mainContainer.add(expandedPanel, "EXPANDED")
        mainContainer.add(collapsedPanel, "COLLAPSED")
        add(mainContainer, BorderLayout.CENTER)

        setupListeners()
        profilerManager.addListener(this)
    }

    private fun buildExpandedView() {
        expandedPanel.layout = BorderLayout()
        expandedPanel.background = UIUtil.getPanelBackground()

        // 1. Top Header
        val header = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(6, 10, 4, 6)
            background = UIUtil.getPanelBackground()
            add(titleLabel, BorderLayout.WEST)
            add(collapseButton, BorderLayout.EAST)
        }

        // 2. Control Toolbar
        val controls = JPanel(BorderLayout(6, 0)).apply {
            border = JBUI.Borders.empty(4, 10, 8, 10)
            val btnGroup = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                add(recordButton)
                add(Box.createHorizontalStrut(2))
                add(modeDropdownButton)
            }
            add(btnGroup, BorderLayout.WEST)
            add(timerLabel, BorderLayout.EAST)
        }

        val topPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(header)
            add(controls)
        }
        expandedPanel.add(topPanel, BorderLayout.NORTH)

        // 3. Charts Area (CPU + Heap)
        val chartsPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(0, 10, 8, 10)
            add(cpuChart)
            add(Box.createVerticalStrut(10))
            add(heapChart)
            add(Box.createVerticalStrut(8))
            add(viewLastButton)
            add(Box.createVerticalGlue())
        }
        expandedPanel.add(chartsPanel, BorderLayout.CENTER)
    }

    private fun buildCollapsedView() {
        collapsedPanel.layout = BorderLayout()
        collapsedPanel.background = UIUtil.getPanelBackground()
        collapsedPanel.preferredSize = Dimension(28, 300)

        val expandBtn = JButton("‹").apply {
            toolTipText = "Expand Performance panel"
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusPainted = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            font = font.deriveFont(Font.BOLD, 14f)
            margin = JBUI.emptyInsets()
            addActionListener { toggleCollapse(false) }
        }

        val top = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4)
            add(expandBtn, BorderLayout.CENTER)
        }

        val verticalLabel = object : JComponent() {
            init {
                preferredSize = Dimension(28, 120)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent?) {
                        toggleCollapse(false)
                    }
                })
            }
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                g2.color = UIUtil.getLabelForeground()
                g2.font = font.deriveFont(Font.PLAIN, 11f)
                g2.translate(18, 110)
                g2.rotate(-Math.PI / 2)
                g2.drawString("⚡ Performance", 0, 0)
                g2.dispose()
            }
        }

        collapsedPanel.add(top, BorderLayout.NORTH)
        collapsedPanel.add(verticalLabel, BorderLayout.CENTER)
    }

    private fun setupListeners() {
        recordButton.addActionListener {
            when (profilerManager.currentState) {
                ProfilerState.IDLE -> {
                    profilerManager.startRecording(profilerManager.selectedMode)
                }
                ProfilerState.RECORDING -> {
                    recordButton.isEnabled = false
                    recordButton.text = "Stopping..."
                    profilerManager.stopRecording {
                        recordButton.isEnabled = true
                    }
                }
                ProfilerState.ANALYZING -> {}
            }
        }
    }

    private fun toggleCollapse(collapse: Boolean) {
        isCollapsed = collapse
        if (collapse) {
            preferredSize = Dimension(28, height)
            cardLayout.show(mainContainer, "COLLAPSED")
        } else {
            preferredSize = Dimension(280, height)
            cardLayout.show(mainContainer, "EXPANDED")
        }
        revalidate()
        repaint()
    }

    private fun showModePopup() {
        val group = DefaultActionGroup()
        for (mode in ProfilerMode.values()) {
            group.add(object : AnAction(mode.displayName, mode.description, null) {
                override fun actionPerformed(e: AnActionEvent) {
                    profilerManager.selectedMode = mode
                    recordButton.toolTipText = mode.description
                }
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
            })
        }
        val popup = JBPopupFactory.getInstance().createActionGroupPopup(
            "Profiling Mode",
            group,
            com.intellij.openapi.actionSystem.DataContext.EMPTY_CONTEXT,
            JBPopupFactory.ActionSelectionAid.MNEMONICS,
            true
        )
        popup.showUnderneathOf(modeDropdownButton)
    }

    override fun onMetricUpdated(metric: ProfilerMetric) {
        SwingUtilities.invokeLater {
            cpuChart.addSample(metric.cpuPercent, "${String.format("%.1f", metric.cpuPercent)}%")
            val heapUsed = metric.heapUsedMb
            val heapMax = metric.heapMaxMb
            heapChart.setMax(heapMax.toDouble())
            heapChart.addSample(heapUsed.toDouble(), "$heapUsed / $heapMax MB")
        }
    }

    override fun onStateChanged(state: ProfilerState) {
        SwingUtilities.invokeLater {
            when (state) {
                ProfilerState.IDLE -> {
                    recordButton.text = "Start Recording"
                    recordButton.icon = AllIcons.Actions.Execute
                    recordButton.isEnabled = true
                    timerLabel.foreground = JBColor.GRAY
                    timerLabel.text = "⏱ 00:00"
                    viewLastButton.isVisible = profilerManager.lastSnapshot != null
                }
                ProfilerState.RECORDING -> {
                    recordButton.text = "Stop Recording"
                    recordButton.icon = AllIcons.Actions.Suspend
                    recordButton.isEnabled = true
                    timerLabel.foreground = JBColor.RED
                }
                ProfilerState.ANALYZING -> {
                    recordButton.text = "Analyzing..."
                    recordButton.icon = AllIcons.Process.ProgressPause
                    recordButton.isEnabled = false
                }
            }
        }
    }

    override fun onRecordingTimeTick(elapsedSeconds: Long) {
        SwingUtilities.invokeLater {
            val mins = elapsedSeconds / 60
            val secs = elapsedSeconds % 60
            timerLabel.text = "🔴 %02d:%02d".format(mins, secs)
        }
    }

    override fun dispose() {
        profilerManager.removeListener(this)
    }
}

/**
 * Lightweight real-time area chart component for CPU and Heap Memory metrics.
 */
class MetricLiveChart(
    val chartTitle: String,
    val unit: String,
    val lineColor: Color,
    val fillColorStart: Color,
    val fillColorEnd: Color,
    private var fixedMax: Double? = null
) : JPanel() {

    private val samples = mutableListOf<Double>()
    private val maxSamples = 60
    private var currentText = "0$unit"
    private var currentMax = fixedMax ?: 100.0

    init {
        preferredSize = Dimension(240, 85)
        minimumSize = Dimension(120, 60)
        maximumSize = Dimension(Short.MAX_VALUE.toInt(), 100)
        background = UIUtil.getPanelBackground()
        border = BorderFactory.createCompoundBorder(
            JBUI.Borders.customLine(JBColor.border(), 1),
            JBUI.Borders.empty(4, 6)
        )
    }

    fun setMax(max: Double) {
        if (fixedMax == null && max > 0) {
            currentMax = max
        }
    }

    fun addSample(value: Double, text: String) {
        samples.add(value)
        if (samples.size > maxSamples) {
            samples.removeAt(0)
        }
        currentText = text
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        val w = width - 12
        val h = height - 26
        val startX = 6
        val startY = 22

        // Draw Title & Value
        g2.color = UIUtil.getLabelForeground()
        g2.font = font.deriveFont(Font.BOLD, 11f)
        g2.drawString(chartTitle, startX, 14)

        g2.font = font.deriveFont(Font.PLAIN, 11f)
        val valueWidth = g2.fontMetrics.stringWidth(currentText)
        g2.drawString(currentText, width - valueWidth - 8, 14)

        // Draw horizontal grid lines
        g2.color = JBColor(Color(200, 200, 200, 60), Color(80, 80, 80, 60))
        g2.stroke = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(2f, 2f), 0f)
        g2.drawLine(startX, startY, startX + w, startY)
        g2.drawLine(startX, startY + h / 2, startX + w, startY + h / 2)
        g2.drawLine(startX, startY + h, startX + w, startY + h)

        if (samples.isEmpty()) {
            g2.dispose()
            return
        }

        // Draw Chart Path
        val path = GeneralPath()
        val fillPath = GeneralPath()

        val stepX = w.toDouble() / (maxSamples - 1).coerceAtLeast(1)
        val effectiveMax = if (fixedMax != null) fixedMax!! else (samples.maxOrNull() ?: currentMax).coerceAtLeast(currentMax)

        val points = mutableListOf<Pair<Double, Double>>()
        for (i in samples.indices) {
            val sampleVal = samples[i]
            val px = startX + (maxSamples - samples.size + i) * stepX
            val py = startY + h - ((sampleVal / effectiveMax.coerceAtLeast(1.0)).coerceIn(0.0, 1.0) * h)
            points.add(px to py)
        }

        if (points.isNotEmpty()) {
            val first = points.first()
            path.moveTo(first.first, first.second)
            fillPath.moveTo(first.first, (startY + h).toDouble())
            fillPath.lineTo(first.first, first.second)

            for (i in 1 until points.size) {
                val pt = points[i]
                path.lineTo(pt.first, pt.second)
                fillPath.lineTo(pt.first, pt.second)
            }

            val last = points.last()
            fillPath.lineTo(last.first, (startY + h).toDouble())
            fillPath.closePath()

            // Fill gradient beneath curve
            val gp = GradientPaint(0f, startY.toFloat(), fillColorStart, 0f, (startY + h).toFloat(), fillColorEnd)
            g2.paint = gp
            g2.fill(fillPath)

            // Draw line on top
            g2.color = lineColor
            g2.stroke = BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g2.draw(path)
        }

        g2.dispose()
    }
}
