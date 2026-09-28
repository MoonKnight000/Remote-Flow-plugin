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

    // Filter & Actions
    private val searchField = JBTextField()
    private val btnOpenInIdea = JButton("📄 Open in IDE")
    private val btnViewModal = JButton("👁 View / Edit")
    private val btnDownload = JButton("📥 Download")
    private val btnDiff = JButton("🔍 Diff")
    private val btnConfigManager = JButton("⚙ Configs")
    private val btnUpload = JButton("📤 Upload File")
    private val btnNewFolder = JButton("📁+ Folder")
    private val btnNewFile = JButton("📄+ File")
    private val btnDelete = JButton("🗑 Delete")

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
        btnUp.toolTipText = "Bitta yuqoridagi papkaga o'tish"
        btnUp.addActionListener { navigateUp() }
        navLeft.add(btnUp)

        btnHome.toolTipText = "Loyiha asosiy papkasiga qaytish (remoteProjectPath)"
        btnHome.addActionListener {
            val rootPath = settings.activeProfile.remoteProjectPath.ifBlank { "/" }
            pathField.text = rootPath
            loadDirectory(rootPath)
        }
        navLeft.add(btnHome)

        pathRow.add(navLeft, BorderLayout.WEST)

        pathField.font = Font("Monospaced", Font.PLAIN, 12)
        pathField.toolTipText = "Masofaviy papka yo'li (Enter bosing yoki 'Go' tugmasi)"
        pathField.addActionListener { loadDirectory(pathField.text.trim()) }
        pathRow.add(pathField, BorderLayout.CENTER)

        val navRight = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
        btnGo.addActionListener { loadDirectory(pathField.text.trim()) }
        navRight.add(btnGo)

        btnRefresh.font = btnRefresh.font.deriveFont(Font.BOLD)
        btnRefresh.toolTipText = "Papkani qayta yuklash"
        btnRefresh.addActionListener { refreshCurrentDirectory() }
        navRight.add(btnRefresh)

        pathRow.add(navRight, BorderLayout.EAST)
        panel.add(pathRow)
        panel.add(Box.createVerticalStrut(4))

        // Row 2: Action Toolbar
        val actionRow = JPanel(BorderLayout(6, 0))
        actionRow.border = JBUI.Borders.empty(2, 4)

        searchField.emptyText.text = "🔍 Filter files..."
        searchField.preferredSize = Dimension(160, 26)
        searchField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                applyFilter(searchField.text.trim())
            }
        })
        actionRow.add(searchField, BorderLayout.WEST)

        val buttonsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))

        btnOpenInIdea.font = btnOpenInIdea.font.deriveFont(Font.BOLD)
        btnOpenInIdea.foreground = JBColor(Color(16, 185, 129), Color(16, 185, 129))
        btnOpenInIdea.toolTipText = "Tanlangan faylni IntelliJ IDEA muharririda ochish"
        btnOpenInIdea.addActionListener {
            val selected = getSelectedFileInfo() ?: return@addActionListener
            openInIntelliJEditor(selected)
        }
        buttonsPanel.add(btnOpenInIdea)

        btnViewModal.toolTipText = "Fayl ichini ko'rish va to'g'ridan-to'g'ri tahrirlab saqlash"
        btnViewModal.addActionListener {
            val selected = getSelectedFileInfo() ?: return@addActionListener
            openInViewerDialog(selected)
        }
        buttonsPanel.add(btnViewModal)

        btnDownload.toolTipText = "Serverdagi faylni kompyuterga yuklab olish"
        btnDownload.addActionListener { downloadSelectedFile() }
        buttonsPanel.add(btnDownload)

        btnDiff.toolTipText = "Tanlangan masofaviy faylni lokal fayl bilan IntelliJ Diff oynasida solishtirish"
        btnDiff.addActionListener {
            val selected = getSelectedFileInfo() ?: return@addActionListener
            compareWithLocalFile(selected)
        }
        buttonsPanel.add(btnDiff)

        btnConfigManager.toolTipText = "Masofaviy .env va application.yml sozlamalarini tahrirlash"
        btnConfigManager.addActionListener {
            val selected = getSelectedFileInfo()
            openInConfigManager(selected)
        }
        buttonsPanel.add(btnConfigManager)

        btnUpload.toolTipText = "Kompyuterdan ushbu masofaviy papkaga fayl yuklash"
        btnUpload.addActionListener { uploadFileToCurrentDir() }
        buttonsPanel.add(btnUpload)

        btnNewFolder.toolTipText = "Yangi papka yaratish (mkdir)"
        btnNewFolder.addActionListener { createNewFolderPrompt() }
        buttonsPanel.add(btnNewFolder)

        btnNewFile.toolTipText = "Yangi bo'sh fayl yaratish (touch)"
        btnNewFile.addActionListener { createNewFilePrompt() }
        buttonsPanel.add(btnNewFile)

        btnDelete.foreground = JBColor.RED
        btnDelete.toolTipText = "Tanlangan fayl yoki papkani o'chirish"
        btnDelete.addActionListener { deleteSelectedItem() }
        buttonsPanel.add(btnDelete)

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
            serverIndicatorLabel.text = "Server: Konfiguratsiya qilinmagan"
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
            statusLabel.text = "Server sozlanmagan. Avval server qo'shing."
            return
        }

        val cleanPath = targetPath.trim().ifBlank { profile.remoteProjectPath }.ifBlank { "/" }
        pathField.text = cleanPath
        statusLabel.text = "Yuklanmoqda: $cleanPath..."

        fileManager.listDirectory(profile, cleanPath) { items, err ->
            ApplicationManager.getApplication().invokeLater {
                if (err != null && items.isEmpty()) {
                    statusLabel.text = "Xatolik: $err"
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
                    statusLabel.text = "Jami: ${items.size} ta element ($folderCount ta papka, $fileCount ta fayl, ${uz.remote.flow.files.formatFileSize(totalBytes)})"

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
                val selPath = filesTree.getPathForLocation(e.x, e.y) ?: return
                val node = selPath.lastPathComponent as? DefaultMutableTreeNode ?: return
                val item = node.userObject as? RemoteFileInfo ?: return

                if (e.clickCount == 2 && !item.isDirectory) {
                    openInIntelliJEditor(item)
                } else if (SwingUtilities.isRightMouseButton(e)) {
                    filesTree.selectionPath = selPath
                    showContextMenu(e.component, e.x, e.y, item)
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
            val itemOpenIdea = JMenuItem("📄 Open in IntelliJ Editor")
            itemOpenIdea.font = itemOpenIdea.font.deriveFont(Font.BOLD)
            itemOpenIdea.addActionListener { openInIntelliJEditor(item) }
            menu.add(itemOpenIdea)

            val itemViewModal = JMenuItem("👁 View / Edit in Viewer")
            itemViewModal.addActionListener { openInViewerDialog(item) }
            menu.add(itemViewModal)

            val itemDownload = JMenuItem("📥 Download to Local...")
            itemDownload.addActionListener { downloadSelectedFile(item) }
            menu.add(itemDownload)

            val itemDiff = JMenuItem("🔍 Compare with Local File (Diff)")
            itemDiff.addActionListener { compareWithLocalFile(item) }
            menu.add(itemDiff)

            if (item.name.startsWith(".env") || item.name.endsWith(".yml") || item.name.endsWith(".yaml") || item.name.endsWith(".properties")) {
                val itemConfigManager = JMenuItem("⚙ Edit in Config Manager (.env / .yml)")
                itemConfigManager.addActionListener { openInConfigManager(item) }
                menu.add(itemConfigManager)
            }

            menu.addSeparator()
        } else if (!item.isParentDir) {
            val itemOpenFolder = JMenuItem("📂 Open Directory")
            itemOpenFolder.addActionListener { loadDirectory(item.path) }
            menu.add(itemOpenFolder)

            val itemUploadHere = JMenuItem("📤 Upload File Here...")
            itemUploadHere.addActionListener { uploadFileToDir(item.path) }
            menu.add(itemUploadHere)
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
                        FileEditorManager.getInstance(project).openFile(vFile, true)
                        statusLabel.text = "Fayl IntelliJ muharririda ochildi: ${item.name}"
                    } else {
                        openInViewerDialog(item)
                    }
                } else {
                    statusLabel.text = "Yuklab olishda xatolik: $err"
                    openInViewerDialog(item)
                }
            }
        }
    }

    private fun openInViewerDialog(item: RemoteFileInfo) {
        if (item.isDirectory || item.isParentDir) return
        val profile = settings.activeProfileOrNull ?: return

        statusLabel.text = "Fayl o'qilmoqda: ${item.name}..."
        fileManager.readFileContent(profile, item.path) { content, err ->
            ApplicationManager.getApplication().invokeLater {
                if (content != null) {
                    val dialog = RemoteFileViewerDialog(project, profile, item.path, content)
                    dialog.show()
                    statusLabel.text = "Fayl yopildi: ${item.name}"
                } else {
                    statusLabel.text = "Faylni ochib bo'lmadi: $err"
                    Messages.showErrorDialog(project, "Fayl mazmunini o'qib bo'lmadi:\n$err", "Xatolik")
                }
            }
        }
    }

    private fun downloadSelectedFile(item: RemoteFileInfo? = getSelectedFileInfo()) {
        val targetItem = item ?: return
        if (targetItem.isDirectory || targetItem.isParentDir) {
            Messages.showWarningDialog(project, "Iltimos, yuklab olish uchun faylni tanlang (papka emas).", "Fayl Tanlanmagan")
            return
        }

        val profile = settings.activeProfileOrNull ?: return
        val descriptor = FileSaverDescriptor("Download Remote File", "Faylni saqlash joyini tanlang", targetItem.extension)
        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val baseVirtualDir = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        val target = dialog.save(baseVirtualDir, targetItem.name) ?: return

        statusLabel.text = "Yuklab olinmoqda: ${targetItem.name}..."
        fileManager.downloadFile(profile, targetItem.path, target.file) { success, err ->
            ApplicationManager.getApplication().invokeLater {
                if (success) {
                    statusLabel.text = "Yuklab olindi: ${targetItem.name}"
                    Messages.showInfoMessage(project, "Fayl kompyuterga saqlandi:\n${target.file.absolutePath}", "Yuklab Olindi")
                } else {
                    statusLabel.text = "Xatolik: $err"
                    Messages.showErrorDialog(project, "Yuklab olishda xatolik: $err", "Xatolik")
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
        descriptor.description = "Serverga yuklamoqchi bo'lgan faylni tanlang:"

        val files = FileChooser.chooseFiles(descriptor, project, null)
        if (files.isEmpty()) return
        val localFile = File(files[0].path)

        statusLabel.text = "Serverga yuklanmoqda: ${localFile.name}..."
        fileManager.uploadFile(profile, localFile, remoteDir) { success, err ->
            ApplicationManager.getApplication().invokeLater {
                if (success) {
                    statusLabel.text = "Yuklandi: ${localFile.name}"
                    refreshCurrentDirectory()
                } else {
                    statusLabel.text = "Yuklashda xato: $err"
                    Messages.showErrorDialog(project, "Serverga yuklab bo'lmadi:\n$err", "Xatolik")
                }
            }
        }
    }

    private fun createNewFolderPrompt() {
        val folderName = Messages.showInputDialog(
            project,
            "Yangi papka nomini kiriting:",
            "New Remote Folder",
            Messages.getQuestionIcon()
        ) ?: return

        val clean = folderName.trim()
        if (clean.isEmpty() || clean.contains('/') || clean.contains(' ')) {
            Messages.showErrorDialog(project, "Papka nomida bo'sh joy yoki / belgisi bo'lmasin!", "Noto'g'ri Nom")
            return
        }

        val profile = settings.activeProfileOrNull ?: return
        val currentDir = pathField.text.trim().trimEnd('/')
        val newPath = "$currentDir/$clean"

        statusLabel.text = "Papka yaratilmoqda: $clean..."
        fileManager.createDirectory(profile, newPath) { success, err ->
            ApplicationManager.getApplication().invokeLater {
                if (success) {
                    statusLabel.text = "Papka yaratildi: $clean"
                    refreshCurrentDirectory()
                } else {
                    statusLabel.text = "Xatolik: $err"
                    Messages.showErrorDialog(project, "Papkani yaratib bo'lmadi: $err", "Xatolik")
                }
            }
        }
    }

    private fun createNewFilePrompt() {
        val fileName = Messages.showInputDialog(
            project,
            "Yangi fayl nomini kiriting (masalan: application-dev.yml, test.sh):",
            "New Remote File",
            Messages.getQuestionIcon()
        ) ?: return

        val clean = fileName.trim()
        if (clean.isEmpty() || clean.contains('/') || clean.contains(' ')) {
            Messages.showErrorDialog(project, "Fayl nomida bo'sh joy yoki / belgisi bo'lmasin!", "Noto'g'ri Nom")
            return
        }

        val profile = settings.activeProfileOrNull ?: return
        val currentDir = pathField.text.trim().trimEnd('/')
        val newPath = "$currentDir/$clean"

        statusLabel.text = "Fayl yaratilmoqda: $clean..."
        fileManager.createNewFile(profile, newPath) { success, err ->
            ApplicationManager.getApplication().invokeLater {
                if (success) {
                    statusLabel.text = "Fayl yaratildi: $clean"
                    refreshCurrentDirectory()
                } else {
                    statusLabel.text = "Xatolik: $err"
                    Messages.showErrorDialog(project, "Fayl yaratib bo'lmadi: $err", "Xatolik")
                }
            }
        }
    }

    private fun renameItemPrompt(item: RemoteFileInfo) {
        val newName = Messages.showInputDialog(
            project,
            "'${item.name}' uchun yangi nom kiriting:",
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

        statusLabel.text = "Nom o'zgartirilmoqda: $clean..."
        fileManager.renamePath(profile, item.path, newPath) { success, err ->
            ApplicationManager.getApplication().invokeLater {
                if (success) {
                    statusLabel.text = "Nom o'zgartirildi: $clean"
                    refreshCurrentDirectory()
                } else {
                    statusLabel.text = "Xatolik: $err"
                    Messages.showErrorDialog(project, "Nomni o'zgartirib bo'lmadi: $err", "Xatolik")
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

        val typeText = if (item.isDirectory) "papka va uning barcha ichidagi fayllari" else "fayl"
        val confirm = Messages.showYesNoDialog(
            project,
            "Serverdagi '${item.name}' $typeText butunlay o'chiriladi!\n\nDavom etasizmi?",
            "O'chirishni Tasdiqlang",
            Messages.getWarningIcon()
        )
        if (confirm != Messages.YES) return

        val profile = settings.activeProfileOrNull ?: return
        statusLabel.text = "O'chirilmoqda: ${item.name}..."
        fileManager.deletePath(profile, item.path, item.isDirectory) { success, err ->
            ApplicationManager.getApplication().invokeLater {
                if (success) {
                    statusLabel.text = "O'chirildi: ${item.name}"
                    refreshCurrentDirectory()
                } else {
                    statusLabel.text = "O'chirishda xatolik: $err"
                    Messages.showErrorDialog(project, "O'chirishda xatolik: $err", "Xatolik")
                }
            }
        }
    }

    private fun compareWithLocalFile(item: RemoteFileInfo) {
        val profile = settings.activeProfileOrNull ?: return
        if (item.isDirectory || item.isParentDir) return

        statusLabel.text = "Fayl solishtirish uchun yuklanmoqda: ${item.name}..."
        fileManager.readFileContent(profile, item.path) { remoteContent, err ->
            ApplicationManager.getApplication().invokeLater {
                if (err != null || remoteContent == null) {
                    Messages.showErrorDialog(project, "Masofadagi faylni o'qib bo'lmadi:\n" + (err ?: "Noma'lum xatolik"), "Diff Xatolik")
                    statusLabel.text = "Diff xatolik"
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
                        .withDescription("Solishtirish uchun lokal '${item.name}' faylini tanlang")
                    val chosen = FileChooser.chooseFile(descriptor, project, null)
                    if (chosen != null) {
                        localFile = File(chosen.path)
                    } else {
                        statusLabel.text = "Diff bekor qilindi"
                        return@invokeLater
                    }
                }

                val virtualLocal = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(localFile)
                if (virtualLocal == null) {
                    Messages.showErrorDialog(project, "Lokal fayl topilmadi: ${localFile.path}", "Diff Xatolik")
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
                    statusLabel.text = "Diff ochildi: ${item.name}"
                } catch (ex: Exception) {
                    Messages.showErrorDialog(project, "Diff viewer ochishda xatolik: " + (ex.message ?: ex.toString()), "Diff Xatolik")
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
