package com.aogg.favorites.ui

import com.aogg.favorites.helper.BookmarkIconHelper
import com.aogg.favorites.model.Bookmark
import com.aogg.favorites.model.BookmarkManager
import com.aogg.favorites.model.BookmarkType
import com.aogg.favorites.model.ListInfo
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.TreeUIHelper
import com.intellij.ui.treeStructure.Tree
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.util.Locale
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.KeyStroke
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * 列表节点用户对象（树第一层）
 */
data class BookmarkListNode(
    val info: ListInfo,
    val totalCount: Int
) {
    override fun toString(): String = "${info.name} ($totalCount)"
}

/**
 * 收藏节点用户对象（树第二层）
 * toString 同时作为 TreeSpeedSearch 的匹配文本
 */
class BookmarkNode(val bookmark: Bookmark) {
    override fun toString(): String = formatBookmarkNodeText(bookmark)
}

internal fun formatBookmarkNodeText(bookmark: Bookmark): String {
    val displayName = bookmark.getDisplayName()
    val location = when (bookmark.type) {
        BookmarkType.LINE -> "${bookmark.filePath}:${bookmark.lineNumber + 1}"
        BookmarkType.FILE -> bookmark.filePath
        BookmarkType.FOLDER -> bookmark.filePath
    }
    val prefix = if (bookmark.isMnemonic()) "[${bookmark.mnemonic}] " else ""
    return "$prefix$displayName - $location"
}

/**
 * 收藏树组件：第一层=列表（含收藏数徽标文案），第二层=收藏项
 *
 * 交互：
 * - Enter / 双击 = 跳转（行→编辑器定位；文件→打开；文件夹→Project 视图选中）
 * - F2 = 编辑描述、Del = 删除（通过 [Interactions] 回调到面板实现）
 * - 右键菜单：跳转/编辑描述/设置助记符/移动到列表/新建列表/重命名列表/删除列表/删除收藏
 * - 选中行收藏时预览打开文件但不抢焦点（openFile(file, false)）
 * - 装 TreeSpeedSearch（TreeUIHelper.installTreeSpeedSearch）
 */
class BookmarksTree(
    private val project: Project,
    private val interactions: Interactions
) : Tree() {

    /**
     * 面板侧交互回调
     */
    interface Interactions {
        fun editSelectedBookmark()
        fun deleteSelected()
        fun setMnemonicForSelected()
        fun moveSelectedTo(listName: String)
        fun createList()
        fun renameList(listName: String?)
        fun deleteList(listName: String?)
    }

    companion object {
        private val log = Logger.getInstance(BookmarksTree::class.java)

        private const val ACTION_KEY_JUMP = "favorites.jump"
        private const val ACTION_KEY_EDIT = "favorites.edit"
        private const val ACTION_KEY_DELETE = "favorites.delete"
    }

    private val bookmarkManager = BookmarkManager.getInstance(project)

    private var filterText: String = ""

    init {
        isRootVisible = false
        showsRootHandles = true
        model = DefaultTreeModel(DefaultMutableTreeNode("root"))
        cellRenderer = BookmarksTreeCellRenderer()

        // 速度搜索（按节点文本匹配）
        TreeUIHelper.getInstance().installTreeSpeedSearch(this)

        // 双击跳转 + 右键菜单
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val bookmark = getSelectedBookmarks().firstOrNull() ?: return
                    navigateToBookmark(bookmark)
                }
            }

            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) showPopupMenu(e)
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) showPopupMenu(e)
            }
        })

        // Enter=跳转 / F2=编辑描述 / Del=删除
        registerKeyStrokes()

        // 选中行收藏时预览打开文件（不抢焦点）
        addTreeSelectionListener {
            previewSelectedLineBookmark()
        }

        refresh()
    }

    /**
     * 重建树模型
     *
     * @param filter 过滤关键字（匹配描述/文件名/列表名，空串表示不过滤）
     */
    fun refresh(filter: String = filterText) {
        filterText = filter
        val query = filterText.trim().lowercase(Locale.ROOT)

        val root = DefaultMutableTreeNode("root")
        for (listInfo in bookmarkManager.getLists()) {
            val allBookmarks = bookmarkManager.getBookmarksByList(listInfo.name)
            val listMatches = query.isEmpty() || listInfo.name.lowercase(Locale.ROOT).contains(query)
            val visibleBookmarks = if (query.isEmpty()) {
                allBookmarks
            } else if (listMatches) {
                allBookmarks
            } else {
                allBookmarks.filter { bookmarkMatches(it, query) }
            }
            if (query.isNotEmpty() && !listMatches && visibleBookmarks.isEmpty()) continue

            val listNode = DefaultMutableTreeNode(BookmarkListNode(listInfo, allBookmarks.size))
            visibleBookmarks.forEach { listNode.add(DefaultMutableTreeNode(BookmarkNode(it))) }
            root.add(listNode)
        }

        model = DefaultTreeModel(root)
        // 列表数量少，默认全部展开
        expandAllRows()
    }

    /**
     * 全部展开
     */
    fun expandAllRows() {
        for (i in 0 until rowCount) {
            expandRow(i)
        }
    }

    /**
     * 全部折叠
     */
    fun collapseAllRows() {
        for (i in rowCount - 1 downTo 0) {
            collapseRow(i)
        }
    }

    /**
     * 获取选中的收藏（多选）
     */
    fun getSelectedBookmarks(): List<Bookmark> {
        return selectionPaths
            ?.mapNotNull { path ->
                (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? BookmarkNode
            }
            ?.map { it.bookmark }
            ?: emptyList()
    }

    /**
     * 获取当前选中上下文对应的列表名：
     * 选中列表节点→该列表名；选中收藏→其所属列表名
     */
    fun getSelectedListName(): String? {
        val node = selectionPath?.lastPathComponent as? DefaultMutableTreeNode ?: return null
        return when (val userObject = node.userObject) {
            is BookmarkListNode -> userObject.info.name
            is BookmarkNode -> userObject.bookmark.listName
            else -> null
        }
    }

    /**
     * 跳转到收藏位置（行→编辑器定位；文件→打开；文件夹→Project 视图选中）
     */
    fun navigateToBookmark(bookmark: Bookmark) {
        val file = resolveVirtualFile(bookmark)
        if (file == null || !file.exists()) {
            return
        }

        when {
            bookmark.isFolderBookmark() -> {
                try {
                    // 选中到 Project 视图（不抢焦点）
                    com.intellij.ide.projectView.ProjectView.getInstance(project).select(null, file, false)
                } catch (e: Exception) {
                    log.warn("在 Project 视图中选中文件夹失败：${bookmark.filePath}", e)
                }
            }

            else -> {
                val editorManager = FileEditorManager.getInstance(project)
                editorManager.openFile(file, true)

                // 行收藏：定位到指定行（lineNumber 为 0 基）
                if (bookmark.isLineBookmark() && bookmark.lineNumber >= 0) {
                    ApplicationManager.getApplication().invokeLater {
                        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@invokeLater
                        val document = editor.document
                        if (bookmark.lineNumber < document.lineCount) {
                            val offset = document.getLineStartOffset(bookmark.lineNumber)
                            editor.caretModel.moveToOffset(offset)
                            editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
                        }
                    }
                }
            }
        }
    }

    /**
     * 右键菜单
     */
    private fun showPopupMenu(e: MouseEvent) {
        val path = getPathForLocation(e.x, e.y) ?: return
        selectionPath = path

        val group = DefaultActionGroup()
        val selectedBookmarks = getSelectedBookmarks()
        val listName = getSelectedListName()

        if (selectedBookmarks.isNotEmpty()) {
            group.add(object : AnAction("跳转", "跳转到收藏位置", null) {
                override fun actionPerformed(e: AnActionEvent) {
                    selectedBookmarks.firstOrNull()?.let { navigateToBookmark(it) }
                }
            })
            group.add(object : AnAction("编辑描述", "编辑收藏的描述", null) {
                override fun actionPerformed(e: AnActionEvent) {
                    interactions.editSelectedBookmark()
                }
            })
            group.add(object : AnAction("设置助记符", "设置或修改收藏的助记符", null) {
                override fun actionPerformed(e: AnActionEvent) {
                    interactions.setMnemonicForSelected()
                }
            })
            group.add(buildMoveToListGroup(selectedBookmarks))
            group.add(Separator.getInstance())
        }

        group.add(object : AnAction("新建列表", "新建一个收藏列表", null) {
            override fun actionPerformed(e: AnActionEvent) {
                interactions.createList()
            }
        })

        if (listName != null) {
            group.add(object : AnAction("重命名列表", "重命名选中的列表（默认列表不可重命名）", null) {
                override fun actionPerformed(e: AnActionEvent) {
                    interactions.renameList(listName)
                }
            })
            if (listName != Bookmark.DEFAULT_LIST_NAME) {
                group.add(object : AnAction("删除列表", "删除选中的列表，其收藏并入默认列表", null) {
                    override fun actionPerformed(e: AnActionEvent) {
                        interactions.deleteList(listName)
                    }
                })
            }
        }

        if (selectedBookmarks.isNotEmpty()) {
            group.add(Separator.getInstance())
            group.add(object : AnAction("删除收藏", "删除选中的收藏", null) {
                override fun actionPerformed(e: AnActionEvent) {
                    interactions.deleteSelected()
                }
            })
        }

        val popup = ActionManager.getInstance()
            .createActionPopupMenu("FavoritesToolWindowPopupMenu", group)
        popup.component.show(this, e.x, e.y)
    }

    /**
     * 构建"移动到列表▸"子菜单（列出全部列表，当前所属列表置灰）
     */
    private fun buildMoveToListGroup(selectedBookmarks: List<Bookmark>): DefaultActionGroup {
        val group = DefaultActionGroup("移动到列表", true)
        val currentListName = selectedBookmarks.firstOrNull()?.listName
        for (info in bookmarkManager.getLists()) {
            val targetName = info.name
            group.add(object : AnAction(targetName, "移动到列表「$targetName」", null) {
                override fun actionPerformed(e: AnActionEvent) {
                    interactions.moveSelectedTo(targetName)
                }

                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = targetName != currentListName
                }
            })
        }
        return group
    }

    private fun registerKeyStrokes() {
        val inputMap = getInputMap(JComponent.WHEN_FOCUSED)
        val actionMap = getActionMap()

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), ACTION_KEY_JUMP)
        actionMap.put(ACTION_KEY_JUMP, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                getSelectedBookmarks().firstOrNull()?.let { navigateToBookmark(it) }
            }
        })

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), ACTION_KEY_EDIT)
        actionMap.put(ACTION_KEY_EDIT, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                interactions.editSelectedBookmark()
            }
        })

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), ACTION_KEY_DELETE)
        actionMap.put(ACTION_KEY_DELETE, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                interactions.deleteSelected()
            }
        })
    }

    /**
     * 选中行收藏时预览打开文件但不抢焦点
     */
    private fun previewSelectedLineBookmark() {
        val bookmark = getSelectedBookmarks().singleOrNull() ?: return
        if (!bookmark.isLineBookmark()) return
        val file = resolveVirtualFile(bookmark) ?: return
        try {
            FileEditorManager.getInstance(project).openFile(file, false)
        } catch (e: Exception) {
            log.warn("预览打开文件失败：${bookmark.filePath}", e)
        }
    }

    private fun bookmarkMatches(bookmark: Bookmark, query: String): Boolean {
        val fileName = bookmark.filePath.substringAfterLast('/')
        return bookmark.description?.lowercase(Locale.ROOT)?.contains(query) == true ||
            fileName.lowercase(Locale.ROOT).contains(query) ||
            bookmark.filePath.lowercase(Locale.ROOT).contains(query) ||
            bookmark.listName.lowercase(Locale.ROOT).contains(query)
    }

    private fun resolveVirtualFile(bookmark: Bookmark): VirtualFile? {
        val basePath = project.basePath ?: return null
        val absolutePath = if (File(bookmark.filePath).isAbsolute) {
            bookmark.filePath
        } else {
            File(basePath, bookmark.filePath).absolutePath
        }
        // findFileByPath 需要正斜杠风格路径
        val normalized = absolutePath.replace('\\', '/')
        return LocalFileSystem.getInstance().findFileByPath(normalized)
    }
}

/**
 * 收藏树单元格渲染器：列表节点显示"名称 (收藏数)"，收藏节点显示描述/助记符与位置
 */
class BookmarksTreeCellRenderer : javax.swing.tree.DefaultTreeCellRenderer() {

    init {
        leafIcon = AllIcons.Gutter.Bookmark
        closedIcon = AllIcons.Nodes.Folder
        openIcon = AllIcons.Nodes.Folder
    }

    override fun getTreeCellRendererComponent(
        tree: javax.swing.JTree?,
        value: Any?,
        sel: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean
    ): java.awt.Component {
        super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)

        val node = value as? DefaultMutableTreeNode
        when (val userObject = node?.userObject) {
            // 列表节点（含收藏数徽标文案）
            is BookmarkListNode -> {
                text = userObject.toString()
                icon = AllIcons.Nodes.Folder
            }
            // 收藏节点
            is BookmarkNode -> {
                val bookmark = userObject.bookmark
                text = formatBookmarkNodeText(bookmark)
                icon = if (bookmark.isMnemonic() && bookmark.mnemonic != null) {
                    BookmarkIconHelper.getMnemonicIcon(bookmark.mnemonic!!)
                } else {
                    BookmarkIconHelper.ANONYMOUS_ICON
                }
            }
        }

        return this
    }
}
