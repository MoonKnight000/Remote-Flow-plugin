package uz.remote.flow.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import uz.remote.flow.files.RemoteFileInfo
import uz.remote.flow.files.RemoteFileManager
import uz.remote.flow.settings.RemoteFlowSettings
import uz.remote.flow.ssh.RemoteConnectionListener
import uz.remote.flow.ssh.RemoteConnectionManager
import uz.remote.flow.ssh.ServerProfile
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.util.Locale
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeWillExpandListener
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

class RemoteFileExplorerPanel(private val project: Project) : JPanel(BorderLayout(0, 4)), Disposable {

    private val settings = RemoteFlowSettings.getInstance(project)
    private val connectionManager = RemoteConnectionManager.getInstance(project)
    private val fileManager = RemoteFileManager(project)

    // Path & Controls
    private val pathField = JBTextField()
    private val btnUp = JButton("⬆ Up")
    private val btnHome = JButton("🏠 Project Root")
    private val btnGo = JButton("Go")
    private val btnRefresh = JButton("⟳ Refresh")

    // Filter & Primary Actions
    private val searchField = JBTextField()
    private val btnUpload = JButton("📤 Upload")
    private val btnNewFolder = JButton("📁+ Folder")
    private val btnNewFile = JButton("📄+ File")

    // Table View
    private val tableColumns = arrayOf("Name", "Size", "Type", "Last Modified", "Permissions")
    private val tableModel = object : DefaultTableModel(tableColumns, 0) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }
    private val filesTable = JBTable(tableModel)
    private val allCurrentItems = mutableListOf<RemoteFileInfo>()

    // Tree View
    private var treeRootNode = DefaultMutableTreeNode("Remote Project Root")
    private var treeModel = DefaultTreeModel(treeRootNode)
    private val filesTree = Tree(treeModel)

    // Status
    private val statusLabel = JBLabel("Ready")
    private val serverIndicatorLabel = JBLabel("Server: -")

    init {
        border = JBUI.Borders.empty(4)

        // Initialize UI
        add(createTopControlPanel(), BorderLayout.NORTH)
        add(createCenterContent(), BorderLayout.CENTER)
        add(createBottomStatusBar(), BorderLayout.SOUTH)

        setupTableListeners()
        setupTreeListeners()

        // Subscribe to connection/profile events
        project.messageBus.connect(this).subscribe(RemoteConnectionListener.TOPIC, object : RemoteConnectionListener {
            override fun connectionStateChanged(connected: Boolean, profile: ServerProfile) {
                ApplicationManager.getApplication().invokeLater {
                    updateServerStatus()
                    if (connected) {
                        refreshCurrentDirectory()
                    }
                }
            }

            override fun profileChanged(profile: ServerProfile) {
                ApplicationManager.getApplication().invokeLater {
                    updateServerStatus()
                    val targetPath = profile.remoteProjectPath.ifBlank { "/root" }
                    pathField.text = targetPath
                    loadDirectory(targetPath)
                }
            }
        })

        updateServerStatus()
        val initialPath = settings.activeProfile.remoteProjectPath.ifBlank { "/root" }
        pathField.text = initialPath
        loadDirectory(initialPath)
    }

    override fun dispose() {
        allCurrentItems.clear()
    }

    private fun createTopControlPanel(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)

        // Row 1: Path navigation
        val pathRow = JPanel(BorderLayout(4, 0))
        pathRow.border = IdeBorderFactory.createTitledBorder("Remote Directory Location", false)

        val navLeft = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        btnUp.toolTipText = "Go to parent directory"
        btnUp.addActionListener { navigateUp() }
        navLeft.add(btnUp)

        btnHome.toolTipText = "Return to project root directory (remoteProjectPath)"
        btnHome.addActionListener {
            val rootPath = settings.activeProfile.remoteProjectPath.ifBlank { "/" }
            pathField.text = rootPath
            loadDirectory(rootPath)
        }
        navLeft.add(btnHome)

        pathRow.add(navLeft, BorderLayout.WEST)

        pathField.font = Font("Monospaced", Font.PLAIN, 12)
        pathField.toolTipText = "Remote directory path (press Enter or click 'Go')"
        pathField.addActionListener { loadDirectory(pathField.text.trim()) }
        pathRow.add(pathField, BorderLayout.CENTER)

        val navRight = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
        btnGo.addActionListener { loadDirectory(pathField.text.trim()) }
        navRight.add(btnGo)

        btnRefresh.font = btnRefresh.font.deriveFont(Font.BOLD)
        btnRefresh.toolTipText = "Reload directory"
        btnRefresh.addActionListener { refreshCurrentDirectory() }
        navRight.add(btnRefresh)

        pathRow.add(navRight, BorderLayout.EAST)
        panel.add(pathRow)
        panel.add(Box.createVerticalStrut(4))

        // Row 2: Action Toolbar
        val actionRow = JPanel(BorderLayout(6, 0))
        actionRow.border = JBUI.Borders.empty(2, 4)

        searchField.emptyText.text = "🔍 Filter files..."
        searchField.preferredSize = Dimension(180, 26)
        searchField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                applyFilter(searchField.text.trim())
            }
        })
        actionRow.add(searchField, BorderLayout.WEST)

        val buttonsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))

        val hintLabel = JBLabel("💡 Right-click for full context menu")
        hintLabel.font = hintLabel.font.deriveFont(Font.ITALIC, 11f)
        hintLabel.foreground = JBColor.GRAY
        buttonsPanel.add(hintLabel)

        btnUpload.toolTipText = "Upload file from local computer to this remote directory"
        btnUpload.addActionListener { uploadFileToCurrentDir() }
        buttonsPanel.add(btnUpload)

        btnNewFolder.toolTipText = "Create new remote directory (mkdir)"
        btnNewFolder.addActionListener { createNewFolderPrompt() }
        buttonsPanel.add(btnNewFolder)

        btnNewFile.toolTipText = "Create new remote file (touch)"
        btnNewFile.addActionListener { createNewFilePrompt() }
        buttonsPanel.add(btnNewFile)

        actionRow.add(buttonsPanel, BorderLayout.CENTER)
        panel.add(actionRow)

        return panel
    }

    private fun createCenterContent(): JComponent {
        val tabbedPane = JBTabbedPane()

        // Tab 1: Table Explorer
        filesTable.rowHeight = 26
        filesTable.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        filesTable.setDefaultRenderer(Any::class.java, object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                table: JTable?,
                value: Any?,
                isSelected: Boolean,
                hasFocus: Boolean,
                row: Int,
                column: Int
            ): Component {
                val comp = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                if (column == 0 && row < allCurrentItems.size) {
                    val item = getDisplayedItemAt(row)
                    if (item != null) {
                        text = "${item.iconEmoji}  ${item.name}"
                        if (item.isDirectory) {
                            font = font.deriveFont(Font.BOLD)
                            foreground = if (isSelected) table?.selectionForeground else JBColor(Color(37, 99, 235), Color(96, 165, 250))
                        } else {
                            font = font.deriveFont(Font.PLAIN)
                            foreground = if (isSelected) table?.selectionForeground else table?.foreground
                        }
                    }
                }
                return comp
            }
        })
        val tableScrollPane = JBScrollPane(filesTable)
        tabbedPane.addTab("📁 Folder Explorer", tableScrollPane)

        // Tab 2: Tree Explorer
        filesTree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        filesTree.setCellRenderer(object : DefaultTreeCellRenderer() {
            override fun getTreeCellRendererComponent(
                tree: JTree?,
                value: Any?,
                sel: Boolean,
                expanded: Boolean,
                leaf: Boolean,
                row: Int,
                hasFocus: Boolean
            ): Component {
                super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)
                val node = value as? DefaultMutableTreeNode
                val userObj = node?.userObject
                if (userObj is RemoteFileInfo) {
                    text = "${userObj.iconEmoji} ${userObj.name}" + if (!userObj.isDirectory) " (${userObj.formattedSize})" else ""
                    if (userObj.isDirectory) {
                        font = font.deriveFont(Font.BOLD)
                    }
                }
                return this
            }
        })
        val treeScrollPane = JBScrollPane(filesTree)
        tabbedPane.addTab("🌲 Project Tree", treeScrollPane)

        return tabbedPane
    }

    private fun createBottomStatusBar(): JPanel {
        val panel = JPanel(BorderLayout(6, 0))
        panel.border = JBUI.Borders.empty(4, 6)

        statusLabel.font = statusLabel.font.deriveFont(Font.PLAIN, 11f)
        serverIndicatorLabel.font = serverIndicatorLabel.font.deriveFont(Font.BOLD, 11f)

        panel.add(statusLabel, BorderLayout.WEST)
        panel.add(serverIndicatorLabel, BorderLayout.EAST)
        return panel
    }

    private fun updateServerStatus() {
        val p = settings.activeProfileOrNull
        if (p == null || p.host.isBlank()) {
            serverIndicatorLabel.text = "Server: Not configured"
            serverIndicatorLabel.foreground = JBColor.RED
        } else if (connectionManager.isConnected) {
            serverIndicatorLabel.text = "Server: 🟢 ${p.name} (${p.host})"
            serverIndicatorLabel.foreground = JBColor(Color(16, 185, 129), Color(16, 185, 129))
        } else {
            serverIndicatorLabel.text = "Server: ⚪ ${p.name} (Offline)"
            serverIndicatorLabel.foreground = JBColor.GRAY
        }
    }

    fun refreshCurrentDirectory() {
        loadDirectory(pathField.text.trim())
    }

    fun loadDirectory(targetPath: String) {
        val profile = settings.activeProfileOrNull
        if (profile == null || profile.host.isBlank()) {
            statusLabel.text = "Server not configured. Please add a server first."
            return
        }

        val cleanPath = targetPath.trim().ifBlank { profile.remoteProjectPath }.ifBlank { "/" }
        pathField.text = cleanPath
        statusLabel.text = "Loading: $cleanPath..."

        fileManager.listDirectory(profile, cleanPath) { items, err ->
            ApplicationManager.getApplication().invokeLater {
                if (err != null && items.isEmpty()) {
                    statusLabel.text = "Error: $err"
                    tableModel.rowCount = 0
                    allCurrentItems.clear()
                } else {
                    allCurrentItems.clear()
                    // Add parent item if not at root
                    if (cleanPath != "/" && cleanPath.isNotBlank()) {
                        val parentPath = getParentPath(cleanPath)
                        allCurrentItems.add(
                            RemoteFileInfo(
                                name = "..",
                                path = parentPath,
                                isDirectory = true,
                                formattedSize = "<UP>",
                                lastModified = "-",
                                permissions = ""
                            )
                        )
                    }

                    allCurrentItems.addAll(items)
                    applyFilter(searchField.text.trim())

                    val folderCount = items.count { it.isDirectory }
                    val fileCount = items.size - folderCount
                    val totalBytes = items.filter { !it.isDirectory }.sumOf { it.size }
                    statusLabel.text = "Total: ${items.size} items ($folderCount folders, $fileCount files, ${uz.remote.flow.files.formatFileSize(totalBytes)})"

                    rebuildTree(cleanPath, items)
                }
            }
        }
    }

    private fun rebuildTree(rootPath: String, items: List<RemoteFileInfo>) {
        val rootInfo = RemoteFileInfo(
            name = rootPath.substringAfterLast('/').ifBlank { rootPath },
            path = rootPath,
            isDirectory = true
        )
        treeRootNode = DefaultMutableTreeNode(rootInfo)
        for (item in items) {
            val node = DefaultMutableTreeNode(item)
            if (item.isDirectory) {
                // Add dummy child for expandable icon
                node.add(DefaultMutableTreeNode("Loading..."))
            }
            treeRootNode.add(node)
        }
        treeModel = DefaultTreeModel(treeRootNode)
        filesTree.model = treeModel
    }

    private fun applyFilter(filter: String) {
        tableModel.rowCount = 0
        val query = filter.lowercase(Locale.US)
        for (item in allCurrentItems) {
            if (item.isParentDir || query.isEmpty() || item.name.lowercase(Locale.US).contains(query)) {
                tableModel.addRow(
                    arrayOf(
                        item.name,
                        item.formattedSize,
                        item.typeDescription,
                        item.lastModified,
                        item.permissions
                    )
                )
            }
        }
    }

    private fun getDisplayedItemAt(rowIndex: Int): RemoteFileInfo? {
        val query = searchField.text.trim().lowercase(Locale.US)
        val filtered = if (query.isEmpty()) allCurrentItems else allCurrentItems.filter { it.isParentDir || it.name.lowercase(Locale.US).contains(query) }
        return filtered.getOrNull(rowIndex)
    }

    private fun getSelectedFileInfo(): RemoteFileInfo? {
        val row = filesTable.selectedRow
        if (row >= 0) {
            return getDisplayedItemAt(row)
        }
        val treeNode = filesTree.lastSelectedPathComponent as? DefaultMutableTreeNode
        return treeNode?.userObject as? RemoteFileInfo
    }

    private fun setupTableListeners() {
        filesTable.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val row = filesTable.rowAtPoint(e.point)
                if (row >= 0) {
                    val item = getDisplayedItemAt(row) ?: return
                    if (e.clickCount == 2) {
                        if (item.isDirectory) {
                            loadDirectory(item.path)
                        } else {
                            openInIntelliJEditor(item)
                        }
                    } else if (SwingUtilities.isRightMouseButton(e)) {
                        filesTable.setRowSelectionInterval(row, row)
                        showContextMenu(e.component, e.x, e.y, item)
                    }
                } else if (SwingUtilities.isRightMouseButton(e)) {
                    filesTable.clearSelection()
                    showBackgroundContextMenu(e.component, e.x, e.y)
                }
            }
        })
    }

    private fun setupTreeListeners() {
        filesTree.addTreeWillExpandListener(object : TreeWillExpandListener {
            override fun treeWillExpand(event: TreeExpansionEvent) {
                val node = event.path.lastPathComponent as? DefaultMutableTreeNode ?: return
                val info = node.userObject as? RemoteFileInfo ?: return
                if (info.isDirectory && node.childCount == 1 && (node.getChildAt(0) as? DefaultMutableTreeNode)?.userObject == "Loading...") {
                    loadTreeSubdirectory(node, info.path)
                }
            }

            override fun treeWillCollapse(event: TreeExpansionEvent) {}
        })

        filesTree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val selPath = filesTree.getPathForLocation(e.x, e.y)
                if (selPath != null) {
                    val node = selPath.lastPathComponent as? DefaultMutableTreeNode ?: return
                    val item = node.userObject as? RemoteFileInfo ?: return

                    if (e.clickCount == 2 && !item.isDirectory) {
                        openInIntelliJEditor(item)
                    } else if (SwingUtilities.isRightMouseButton(e)) {
                        filesTree.selectionPath = selPath
                        showContextMenu(e.component, e.x, e.y, item)
                    }
                } else if (SwingUtilities.isRightMouseButton(e)) {
                    showBackgroundContextMenu(e.component, e.x, e.y)
                }
            }
        })
    }

    private fun loadTreeSubdirectory(parentNode: DefaultMutableTreeNode, path: String) {
        val profile = settings.activeProfileOrNull ?: return
        fileManager.listDirectory(profile, path) { children, _ ->
            ApplicationManager.getApplication().invokeLater {
                parentNode.removeAllChildren()
                for (child in children) {
                    val childNode = DefaultMutableTreeNode(child)
                    if (child.isDirectory) {
                        childNode.add(DefaultMutableTreeNode("Loading..."))
                    }
                    parentNode.add(childNode)
                }
                treeModel.nodeStructureChanged(parentNode)
            }
        }
    }

    private fun showContextMenu(component: Component, x: Int, y: Int, item: RemoteFileInfo) {
        val menu = JPopupMenu()

        if (!item.isDirectory) {
            // 1. Open in IDE
            val itemOpenIdea = JMenuItem("📄 Open in IDE")
            itemOpenIdea.font = itemOpenIdea.font.deriveFont(Font.BOLD, 12f)
            itemOpenIdea.foreground = JBColor(Color(16, 185, 129), Color(16, 185, 129))
            itemOpenIdea.toolTipText = "Open file in IntelliJ editor (saving with Ctrl+S automatically syncs to remote server)"
            itemOpenIdea.addActionListener { openInIntelliJEditor(item) }
            menu.add(itemOpenIdea)

            // 2. View / Edit in Viewer Modal
            val itemViewModal = JMenuItem("👁 View / Edit (Viewer Modal)")
            itemViewModal.addActionListener { openInViewerDialog(item) }
            menu.add(itemViewModal)

            // 3. Diff with Local File
            val itemDiff = JMenuItem("🔍 Compare with Local File (Diff)")
            itemDiff.addActionListener { compareWithLocalFile(item) }
            menu.add(itemDiff)

            // 4. Remote Config Manager
            if (item.name.startsWith(".env") || item.name.endsWith(".yml") || item.name.endsWith(".yaml") || item.name.endsWith(".properties") || item.name.endsWith(".json")) {
                val itemConfigManager = JMenuItem("⚙ Edit in Remote Config Manager (.env / .yml)")
                itemConfigManager.addActionListener { openInConfigManager(item) }
                menu.add(itemConfigManager)
            }

            menu.addSeparator()

            // 5. Download
            val itemDownload = JMenuItem("📥 Download to Local...")
            itemDownload.addActionListener { downloadSelectedFile(item) }
            menu.add(itemDownload)

            // 6. Upload & Replace
            val itemUploadReplace = JMenuItem("📤 Upload & Replace with Local File...")
            itemUploadReplace.addActionListener { uploadAndReplaceFile(item) }
            menu.add(itemUploadReplace)

            menu.addSeparator()
        } else if (!item.isParentDir) {
            val itemOpenFolder = JMenuItem("📂 Open Directory")
            itemOpenFolder.font = itemOpenFolder.font.deriveFont(Font.BOLD)
            itemOpenFolder.addActionListener { loadDirectory(item.path) }
            menu.add(itemOpenFolder)

            menu.addSeparator()

            val itemUploadHere = JMenuItem("📤 Upload File Here...")
            itemUploadHere.addActionListener { uploadFileToDir(item.path) }
            menu.add(itemUploadHere)

            val itemNewSubFolder = JMenuItem("📁+ New Subfolder...")
            itemNewSubFolder.addActionListener { createNewFolderPrompt(item.path) }
            menu.add(itemNewSubFolder)

            val itemNewSubFile = JMenuItem("📄+ New File Inside...")
            itemNewSubFile.addActionListener { createNewFilePrompt(item.path) }
            menu.add(itemNewSubFile)

            menu.addSeparator()

            val itemDownloadFolder = JMenuItem("📥 Download Folder (as Zip)...")
            itemDownloadFolder.addActionListener { downloadFolder(item) }
            menu.add(itemDownloadFolder)

            menu.addSeparator()
        }

        if (!item.isParentDir) {
            val itemRename = JMenuItem("✏ Rename...")
            itemRename.addActionListener { renameItemPrompt(item) }
            menu.add(itemRename)

            val itemDelete = JMenuItem("🗑 Delete")
            itemDelete.foreground = JBColor.RED
            itemDelete.addActionListener { deleteItem(item) }
            menu.add(itemDelete)
            menu.addSeparator()
        }

        val itemCopyPath = JMenuItem("📋 Copy Remote Path")
        itemCopyPath.addActionListener {
            val sel = StringSelection(item.path)
            Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
            statusLabel.text = "Yo'l buferga nusxalandi: ${item.path}"
        }
        menu.add(itemCopyPath)

        val itemRefresh = JMenuItem("⟳ Refresh Directory")
        itemRefresh.addActionListener { refreshCurrentDirectory() }
        menu.add(itemRefresh)

        menu.show(component, x, y)
    }

    private fun showBackgroundContextMenu(component: Component, x: Int, y: Int) {
        val menu = JPopupMenu()
        val curPath = pathField.text.trim().ifBlank { settings.activeProfile.remoteProjectPath }

        val itemUpload = JMenuItem("📤 Upload File to Current Directory...")
        itemUpload.addActionListener { uploadFileToCurrentDir() }
        menu.add(itemUpload)

        val itemNewFolder = JMenuItem("📁+ New Folder...")
        itemNewFolder.addActionListener { createNewFolderPrompt(curPath) }
        menu.add(itemNewFolder)

        val itemNewFile = JMenuItem("📄+ New File...")
        itemNewFile.addActionListener { createNewFilePrompt(curPath) }
        menu.add(itemNewFile)

        menu.addSeparator()

        val itemCopyCur = JMenuItem("📋 Copy Current Path ($curPath)")
        itemCopyCur.addActionListener {
            val sel = StringSelection(curPath)
            Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
            statusLabel.text = "Yo'l buferga nusxalandi: $curPath"
        }
        menu.add(itemCopyCur)

        val itemRefresh = JMenuItem("⟳ Refresh")
        itemRefresh.addActionListener { refreshCurrentDirectory() }
        menu.add(itemRefresh)

        menu.show(component, x, y)
    }

    private fun navigateUp() {
        val current = pathField.text.trim()
        val parent = getParentPath(current)
        pathField.text = parent
        loadDirectory(parent)
    }

    private fun getParentPath(path: String): String {
        val trimmed = path.trimEnd('/')
        val lastSlash = trimmed.lastIndexOf('/')
        return if (lastSlash <= 0) "/" else trimmed.substring(0, lastSlash)
    }

    private fun openInIntelliJEditor(item: RemoteFileInfo) {
        if (item.isDirectory || item.isParentDir) return
        val profile = settings.activeProfileOrNull ?: return

        statusLabel.text = "Fayl ochilmoqda: ${item.name}..."
        val safeHost = profile.host.replace(":", "_").replace("/", "_")
        val localCacheDir = File(System.getProperty("java.io.tmpdir"), "remote-flow-cache/$safeHost")
        val targetLocalFile = File(localCacheDir, item.path.trimStart('/'))

        fileManager.downloadFile(profile, item.path, targetLocalFile) { success, err ->
            ApplicationManager.getApplication().invokeLater {
                if (success && targetLocalFile.exists()) {
                    val vFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(targetLocalFile)
                    if (vFile != null) {
                        vFile.refresh(false, false)
                        FileEditorManager.getInstance(project).openFile(vFile, true)
                        statusLabel.text = "File opened in IntelliJ editor: ${item.name} (Ctrl+S automatically saves to server)"
                    } else {
                        openInViewerDialog(item)
                    }
                } else {
                    statusLabel.text = "Download error: $err"
                    openInViewerDialog(item)
                }
            }
        }
    }

    private fun openInViewerDialog(item: RemoteFileInfo) {
        if (item.isDirectory || item.isParentDir) return
        val profile = settings.activeProfileOrNull ?: return

        statusLabel.text = "Reading file: ${item.name}..."
        fileManager.readFileContent(profile, item.path) { content, err ->
            ApplicationManager.getApplication().invokeLater {
                if (content != null) {
                    val dialog = RemoteFileViewerDialog(project, profile, item.path, content)
                    dialog.show()
                    statusLabel.text = "File closed: ${item.name}"
                } else {
                    statusLabel.text = "Failed to open file: $err"
                    Messages.showErrorDialog(project, "Could not read file content:\n$err", "Error")
                }
            }
        }
    }

    private fun downloadSelectedFile(item: RemoteFileInfo? = getSelectedFileInfo()) {
        val targetItem = item ?: return
        if (targetItem.isDirectory || targetItem.isParentDir) {
            Messages.showWarningDialog(project, "Please select a file to download (not a directory).", "No File Selected")
            return
        }

        val profile = settings.activeProfileOrNull ?: return
        val descriptor = FileSaverDescriptor("Download Remote File", "Choose destination to save file", targetItem.extension)
        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val baseVirtualDir = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        val target = dialog.save(baseVirtualDir, targetItem.name) ?: return

        statusLabel.text = "Downloading: ${targetItem.name}..."
        fileManager.downloadFile(profile, targetItem.path, target.file) { success, err ->
            ApplicationManager.getApplication().invokeLater {
                if (success) {
                    statusLabel.text = "Downloaded: ${targetItem.name}"
                    Messages.showInfoMessage(project, "File saved locally:\n${target.file.absolutePath}", "Download Complete")
                } else {
                    statusLabel.text = "Error: $err"
                    Messages.showErrorDialog(project, "Error downloading file: $err", "Error")
                }
            }
        }
    }

    private fun downloadFolder(folderItem: RemoteFileInfo) {
        if (!folderItem.isDirectory || folderItem.isParentDir) return
        val profile = settings.activeProfileOrNull ?: return

        val descriptor = FileSaverDescriptor("Download Remote Folder", "Choose destination to save archive (.tar.gz)", "tar.gz")
        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val baseVirtualDir = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        val target = dialog.save(baseVirtualDir, "${folderItem.name}.tar.gz") ?: return

        statusLabel.text = "Archiving and downloading folder: ${folderItem.name}..."
        fileManager.downloadDirectoryAsArchive(profile, folderItem.path, target.file) { success, err ->
            ApplicationManager.getApplication().invokeLater {
                if (success) {
                    statusLabel.text = "Folder saved successfully: ${target.file.name}"
                    Messages.showInfoMessage(project, "Folder archived and saved locally:\n${target.file.absolutePath}", "Download Complete")
                } else {
                    statusLabel.text = "Error: $err"
                    Messages.showErrorDialog(project, "Error downloading folder:\n$err", "Error")
                }
            }
        }
    }

    private fun uploadAndReplaceFile(item: RemoteFileInfo) {
        val profile = settings.activeProfileOrNull ?: return
        if (item.isDirectory || item.isParentDir) return

        val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
        descriptor.title = "Upload & Replace: ${item.name}"
        descriptor.description = "Select local file to replace remote file '${item.path}':"

        val files = FileChooser.chooseFiles(descriptor, project, null)
        if (files.isEmpty()) return
        val localFile = File(files[0].path)

        val confirm = Messages.showYesNoDialog(
            project,
            "Remote file '${item.name}' will be replaced with local file '${localFile.name}'.\n\nDo you want to proceed?",
            "Confirm File Replacement",
            Messages.getQuestionIcon()
        )
        if (confirm != Messages.YES) return

        statusLabel.text = "Replacing file: ${item.name}..."
        fileManager.uploadFileToRemotePath(profile, localFile, item.path) { success, err ->
            ApplicationManager.getApplication().invokeLater {
                if (success) {
                    statusLabel.text = "File successfully replaced: ${item.name}"
                    refreshCurrentDirectory()
                } else {
                    statusLabel.text = "Error: $err"
                    Messages.showErrorDialog(project, "Failed to upload to server:\n$err", "Error")
                }
            }
        }
    }

    private fun uploadFileToCurrentDir() {
        uploadFileToDir(pathField.text.trim())
    }

    private fun uploadFileToDir(remoteDir: String) {
        val profile = settings.activeProfileOrNull ?: return
        val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
        descriptor.title = "Upload File to Remote Directory"
        descriptor.description = "Select file to upload to remote server:"

        val files = FileChooser.chooseFiles(descriptor, project, null)
        if (files.isEmpty()) return
        val localFile = File(files[0].path)

        statusLabel.text = "Uploading to server: ${localFile.name}..."
        fileManager.uploadFile(profile, localFile, remoteDir) { success, err ->
            ApplicationManager.getApplication().invokeLater {
                if (success) {
                    statusLabel.text = "Uploaded: ${localFile.name}"
                    refreshCurrentDirectory()
                } else {
                    statusLabel.text = "Upload error: $err"
                    Messages.showErrorDialog(project, "Failed to upload to server:\n$err", "Error")
                }
            }
        }
    }

    private fun createNewFolderPrompt(parentDir: String = pathField.text.trim().trimEnd('/')) {
        val folderName = Messages.showInputDialog(
            project,
            "Enter new directory name:",
            "New Remote Folder",
            Messages.getQuestionIcon()
        ) ?: return

        val clean = folderName.trim()
        if (clean.isEmpty() || clean.contains('/') || clean.contains(' ')) {
            Messages.showErrorDialog(project, "Directory name cannot contain spaces or '/' characters!", "Invalid Name")
            return
        }

        val profile = settings.activeProfileOrNull ?: return
        val cleanParent = parentDir.ifBlank { "/" }
        val newPath = if (cleanParent == "/") "/$clean" else "$cleanParent/$clean"

        statusLabel.text = "Creating directory: $clean..."
        fileManager.createDirectory(profile, newPath) { success, err ->
            ApplicationManager.getApplication().invokeLater {
                if (success) {
                    statusLabel.text = "Directory created: $clean"
                    refreshCurrentDirectory()
                } else {
                    statusLabel.text = "Error: $err"
                    Messages.showErrorDialog(project, "Failed to create directory: $err", "Error")
                }
            }
        }
    }

    private fun createNewFilePrompt(parentDir: String = pathField.text.trim().trimEnd('/')) {
        val fileName = Messages.showInputDialog(
            project,
            "Enter new file name (e.g., application-dev.yml, test.sh):",
            "New Remote File",
            Messages.getQuestionIcon()
        ) ?: return

        val clean = fileName.trim()
        if (clean.isEmpty() || clean.contains('/') || clean.contains(' ')) {
            Messages.showErrorDialog(project, "File name cannot contain spaces or '/' characters!", "Invalid Name")
            return
        }

        val profile = settings.activeProfileOrNull ?: return
        val cleanParent = parentDir.ifBlank { "/" }
        val newPath = if (cleanParent == "/") "/$clean" else "$cleanParent/$clean"

        statusLabel.text = "Creating file: $clean..."
        fileManager.createNewFile(profile, newPath) { success, err ->
            ApplicationManager.getApplication().invokeLater {
                if (success) {
                    statusLabel.text = "File created: $clean"
                    refreshCurrentDirectory()
                } else {
                    statusLabel.text = "Error: $err"
                    Messages.showErrorDialog(project, "Failed to create file: $err", "Error")
                }
            }
        }
    }

    private fun renameItemPrompt(item: RemoteFileInfo) {
        val newName = Messages.showInputDialog(
            project,
            "Enter new name for '${item.name}':",
            "Rename Remote Item",
            Messages.getQuestionIcon(),
            item.name,
            null
        ) ?: return

        val clean = newName.trim()
        if (clean.isEmpty() || clean == item.name) return

        val profile = settings.activeProfileOrNull ?: return
        val parent = getParentPath(item.path)
        val newPath = if (parent == "/") "/$clean" else "$parent/$clean"

        statusLabel.text = "Renaming: $clean..."
        fileManager.renamePath(profile, item.path, newPath) { success, err ->
            ApplicationManager.getApplication().invokeLater {
                if (success) {
                    statusLabel.text = "Renamed: $clean"
                    refreshCurrentDirectory()
                } else {
                    statusLabel.text = "Error: $err"
                    Messages.showErrorDialog(project, "Failed to rename: $err", "Error")
                }
            }
        }
    }

    private fun deleteSelectedItem() {
        val item = getSelectedFileInfo() ?: return
        deleteItem(item)
    }

    private fun deleteItem(item: RemoteFileInfo) {
        if (item.isParentDir) return

        val typeText = if (item.isDirectory) "directory and all of its contents" else "file"
        val confirm = Messages.showYesNoDialog(
            project,
            "Remote '${item.name}' ($typeText) will be permanently deleted!\n\nDo you want to proceed?",
            "Confirm Deletion",
            Messages.getWarningIcon()
        )
        if (confirm != Messages.YES) return

        val profile = settings.activeProfileOrNull ?: return
        statusLabel.text = "Deleting: ${item.name}..."
        fileManager.deletePath(profile, item.path, item.isDirectory) { success, err ->
            ApplicationManager.getApplication().invokeLater {
                if (success) {
                    statusLabel.text = "Deleted: ${item.name}"
                    refreshCurrentDirectory()
                } else {
                    statusLabel.text = "Error deleting: $err"
                    Messages.showErrorDialog(project, "Failed to delete: $err", "Error")
                }
            }
        }
    }

    private fun compareWithLocalFile(item: RemoteFileInfo) {
        val profile = settings.activeProfileOrNull ?: return
        if (item.isDirectory || item.isParentDir) return

        statusLabel.text = "Loading file for diff: ${item.name}..."
        fileManager.readFileContent(profile, item.path) { remoteContent, err ->
            ApplicationManager.getApplication().invokeLater {
                if (err != null || remoteContent == null) {
                    Messages.showErrorDialog(project, "Could not read remote file:\n" + (err ?: "Unknown error"), "Diff Error")
                    statusLabel.text = "Diff error"
                    return@invokeLater
                }

                val localBase = profile.localProjectPath.ifBlank { project.basePath ?: "" }
                val remoteBase = profile.remoteProjectPath.trimEnd('/')
                var localFile: File? = null

                if (localBase.isNotBlank() && item.path.startsWith(remoteBase)) {
                    val rel = item.path.removePrefix(remoteBase).trimStart('/')
                    val cand = File(localBase, rel)
                    if (cand.exists() && cand.isFile) {
                        localFile = cand
                    }
                }

                if (localFile == null) {
                    val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
                        .withTitle("Compare with Local File")
                        .withDescription("Select local '${item.name}' file to compare with")
                    val chosen = FileChooser.chooseFile(descriptor, project, null)
                    if (chosen != null) {
                        localFile = File(chosen.path)
                    } else {
                        statusLabel.text = "Diff cancelled"
                        return@invokeLater
                    }
                }

                val virtualLocal = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(localFile)
                if (virtualLocal == null) {
                    Messages.showErrorDialog(project, "Local file not found: ${localFile.path}", "Diff Error")
                    return@invokeLater
                }

                try {
                    val diffContentFactory = DiffContentFactory.getInstance()
                    val localDiffContent = diffContentFactory.create(project, virtualLocal)
                    val remoteDiffContent = diffContentFactory.create(remoteContent, virtualLocal.fileType)

                    val request = SimpleDiffRequest(
                        "Remote Flow Diff: Local vs Remote [${item.name}]",
                        localDiffContent,
                        remoteDiffContent,
                        "Local: ${virtualLocal.name}",
                        "Remote: ${profile.name} (${item.path})"
                    )

                    DiffManager.getInstance().showDiff(project, request)
                    statusLabel.text = "Diff opened: ${item.name}"
                } catch (ex: Exception) {
                    Messages.showErrorDialog(project, "Error opening diff viewer: " + (ex.message ?: ex.toString()), "Diff Error")
                }
            }
        }
    }

    private fun openInConfigManager(item: RemoteFileInfo? = null) {
        val profile = settings.activeProfileOrNull ?: return
        val relPath = if (item != null && !item.isDirectory) {
            val base = profile.remoteProjectPath.trimEnd('/')
            if (item.path.startsWith(base)) item.path.removePrefix(base).trimStart('/') else item.name
        } else null

        val dialog = uz.remote.flow.config.RemoteConfigManagerDialog(project, profile, relPath)
        dialog.show()
    }
}
