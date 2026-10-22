package com.aogg.favorites.ui

import com.aogg.favorites.helper.BookmarkIconHelper
import com.aogg.favorites.model.Bookmark
import com.aogg.favorites.model.BookmarkType
import com.aogg.favorites.model.FavoriteScope
import com.aogg.favorites.model.FavoritesScopeResolver
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
 * 作用域节点用户对象（树第一层，固定两个：项目收藏/全局收藏）
 *
 * @param scope 作用域
 * @param totalCount 该作用域收藏总数（徽标展示）
 */
data class ScopeNode(
    val scope: FavoriteScope,
    val totalCount: Int
) {
    override fun toString(): String = "${scope.label} ($totalCount)"
}

/**
 * 列表节点用户对象（树第二层）
 *
 * @param scope 列表所属作用域（列表操作按该作用域执行）
 * @param info 列表元信息
 * @param totalCount 该列表收藏总数（徽标展示）
 */
data class BookmarkListNode(
    val scope: FavoriteScope,
    val info: ListInfo,
    val totalCount: Int
) {
    override fun toString(): String = "${info.name} ($totalCount)"
}

/**
 * 类型分组节点用户对象（树第三层，固定顺序：行收藏→文件收藏→文件夹收藏，只显示非空组）
 *
 * @param scope 所属作用域
 * @param type 收藏类型
 * @param count 该类型收藏数量（徽标展示）
 */
data class TypeGroupNode(
    val scope: FavoriteScope,
    val type: BookmarkType,
    val count: Int
) {
    /**
     * 类型分组显示名（固定文案）
     */
    val label: String = when (type) {
        BookmarkType.LINE -> "行收藏"
        BookmarkType.FILE -> "文件收藏"
        BookmarkType.FOLDER -> "文件夹收藏"
    }

    override fun toString(): String = "$label ($count)"
}

/**
 * 带作用域的收藏（选中结果）：UI 操作需要知道收藏归属哪个作用域以路由到对应管理器
 *
 * @param scope 收藏所属作用域
 * @param bookmark 收藏项
 */
data class ScopedBookmark(
    val scope: FavoriteScope,
    val bookmark: Bookmark
)

/**
 * 带作用域的列表名（选中结果）
 *
 * @param scope 列表所属作用域
 * @param name 列表名
 */
data class ScopedList(
    val scope: FavoriteScope,
    val name: String
)

/**
 * 收藏节点用户对象（树第四层）
 * toString 同时作为 TreeSpeedSearch 的匹配文本
 *
 * @param scope 收藏所属作用域
 * @param bookmark 收藏项
 */
class BookmarkNode(val scope: FavoriteScope, val bookmark: Bookmark) {
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
 * 收藏树组件（1.2.0 四层结构）：
 * 第一层=作用域（项目收藏/全局收藏，始终显示+总数徽标），第二层=列表，第三层=类型分组，第四层=收藏项
 *
 * 交互：
 * - Enter / 双击 = 跳转（行→编辑器定位；文件→打开；文件夹→Project 视图选中）
 * - F2 = 编辑描述、Del = 删除（通过 [Interactions] 回调到面板实现）
 * - 右键菜单：跳转/编辑描述/设置助记符/移动到列表(按作用域分组)/列表CRUD/删除收藏
 * - 选中行收藏时预览打开文件但不抢焦点（openFile(file, false)）
 * - 装 TreeSpeedSearch（TreeUIHelper.installTreeSpeedSearch）
 * - 搜索过滤时作用域名/列表名/类型组名均参与匹配，空类型组隐藏
 */
class BookmarksTree(
    private val project: Project,
    private val interactions: Interactions
) : Tree() {

    /**
     * 面板侧交互回调（1.2.0 起带作用域：列表操作作用于所在作用域）
     */
    interface Interactions {
        fun editSelectedBookmark()
        fun deleteSelected()
        fun setMnemonicForSelected()
        fun moveSelectedTo(scope: FavoriteScope, listName: String)
        fun createList(scope: FavoriteScope)
        fun renameList(scope: FavoriteScope, listName: String?)
        fun deleteList(scope: FavoriteScope, listName: String?)
    }

    companion object {
        private val log = Logger.getInstance(BookmarksTree::class.java)

        private const val ACTION_KEY_JUMP = "favorites.jump"
        private const val ACTION_KEY_EDIT = "favorites.edit"
        private const val ACTION_KEY_DELETE = "favorites.delete"

        /**
         * 类型分组固定展示顺序：行收藏 → 文件收藏 → 文件夹收藏
         */
        private val TYPE_GROUP_ORDER = listOf(BookmarkType.LINE, BookmarkType.FILE, BookmarkType.FOLDER)
    }

    private var filterText: String = ""

    init {
        isRootVisible = false
        showsRootHandles = true
        model = DefaultTreeModel(DefaultMutableTreeNode("root"))
        cellRenderer = BookmarksTreeCellRenderer()

        // 速度搜索（按节点文本匹配，四层节点 toString 均参与）
        TreeUIHelper.getInstance().installTreeSpeedSearch(this)

        // 双击跳转 + 右键菜单
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val bookmark = getSelectedBookmarks().firstOrNull() ?: return
                    navigateToBookmark(bookmark.bookmark)
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
     * 按作用域取对应管理器
     */
    private fun managerFor(scope: FavoriteScope) = FavoritesScopeResolver.managerFor(project, scope)

    /**
     * 重建树模型（四层：作用域→列表→类型分组→收藏）
     *
     * @param filter 过滤关键字（匹配作用域名/描述/文件名/列表名/类型组名，空串表示不过滤）
     */
    fun refresh(filter: String = filterText) {
        filterText = filter
        val query = filterText.trim().lowercase(Locale.ROOT)

        val root = DefaultMutableTreeNode("root")

        // ////////////////  第一层：固定两个作用域节点（始终显示）  start  ////////////////
        for (scope in FavoriteScope.entries) {
            val manager = managerFor(scope)
            val scopeNode = DefaultMutableTreeNode(ScopeNode(scope, manager.getTotalCount()))

            // 作用域名命中过滤词时，该作用域整体放行（列表与收藏不再逐项过滤）
            val scopeMatches = query.isEmpty() || scope.label.lowercase(Locale.ROOT).contains(query)

            // ////////////////  第二层：作用域下的列表  start  //////////////////////////
            for (listInfo in manager.getLists()) {
                val allBookmarks = manager.getBookmarksByList(listInfo.name)
                val listMatches = scopeMatches || listInfo.name.lowercase(Locale.ROOT).contains(query)
                val visibleBookmarks = if (query.isEmpty() || listMatches) {
                    allBookmarks
                } else {
                    allBookmarks.filter { bookmarkMatches(it, query) }
                }
                // 过滤中且本列表无命中时隐藏该列表（不过滤时列表始终显示）
                if (query.isNotEmpty() && !listMatches && visibleBookmarks.isEmpty()) continue

                val listNode = DefaultMutableTreeNode(BookmarkListNode(scope, listInfo, allBookmarks.size))

                // ////////////////  第三层：类型分组（固定顺序，只显示非空组）  start  ////
                for (type in TYPE_GROUP_ORDER) {
                    val groupItems = visibleBookmarks.filter { it.type == type }
                    if (groupItems.isEmpty()) continue

                    val groupNode = DefaultMutableTreeNode(TypeGroupNode(scope, type, groupItems.size))
                    // ////////////////  第四层：收藏节点  start  ////////////////////////
                    groupItems.forEach {
                        groupNode.add(DefaultMutableTreeNode(BookmarkNode(scope, it)))
                    }
                    // ////////////////  第四层  end  ///////////////////////////////////
                    listNode.add(groupNode)
                }
                // ////////////////  第三层  end  ///////////////////////////////////////

                scopeNode.add(listNode)
            }
            // ////////////////  第二层  end  ///////////////////////////////////////////

            root.add(scopeNode)
        }
        // ////////////////  第一层  end  ////////////////////////////////////////////////

        model = DefaultTreeModel(root)
        // 层级数量少，默认全部展开
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
     * 获取选中的收藏（多选，带作用域）
     */
    fun getSelectedBookmarks(): List<ScopedBookmark> {
        return selectionPaths
            ?.mapNotNull { path ->
                (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? BookmarkNode
            }
            ?.map { ScopedBookmark(it.scope, it.bookmark) }
            ?: emptyList()
    }

    /**
     * 获取当前选中上下文对应的列表（带作用域）：
     * 选中列表节点→该列表；选中收藏→其所属列表；选中类型组→其所属列表不可知，返回null
     */
    fun getSelectedList(): ScopedList? {
        val node = selectionPath?.lastPathComponent as? DefaultMutableTreeNode ?: return null
        return when (val userObject = node.userObject) {
            is BookmarkListNode -> ScopedList(userObject.scope, userObject.info.name)
            is BookmarkNode -> ScopedList(userObject.scope, userObject.bookmark.listName)
            else -> null
        }
    }

    /**
     * 获取当前选中上下文的作用域（作用域/列表/类型组/收藏节点均可推断）
     */
    fun getSelectedScope(): FavoriteScope? {
        val node = selectionPath?.lastPathComponent as? DefaultMutableTreeNode ?: return null
        return when (val userObject = node.userObject) {
            is ScopeNode -> userObject.scope
            is BookmarkListNode -> userObject.scope
            is TypeGroupNode -> userObject.scope
            is BookmarkNode -> userObject.scope
            else -> null
        }
    }

    /**
     * 跳转到收藏位置（行→编辑器定位；文件→打开；文件夹→Project 视图选中）
     * 兼容两种作用域：项目收藏相对路径拼项目根；全局收藏绝对路径直用
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
     * 右键菜单（列表操作按选中上下文的作用域执行）
     */
    private fun showPopupMenu(e: MouseEvent) {
        val path = getPathForLocation(e.x, e.y) ?: return
        selectionPath = path

        val group = DefaultActionGroup()
        val selectedBookmarks = getSelectedBookmarks()
        val selectedList = getSelectedList()
        // 列表操作作用域：优先列表节点自身 → 收藏所属列表 → 选中上下文作用域，兜底项目收藏
        val listScope = selectedList?.scope ?: selectedBookmarks.firstOrNull()?.scope ?: getSelectedScope() ?: FavoriteScope.PROJECT

        if (selectedBookmarks.isNotEmpty()) {
            group.add(object : AnAction("跳转", "跳转到收藏位置", null) {
                override fun actionPerformed(e: AnActionEvent) {
                    selectedBookmarks.firstOrNull()?.let { navigateToBookmark(it.bookmark) }
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
                interactions.createList(listScope)
            }
        })

        if (selectedList != null) {
            group.add(object : AnAction("重命名列表", "重命名选中的列表（默认列表不可重命名）", null) {
                override fun actionPerformed(e: AnActionEvent) {
                    interactions.renameList(selectedList.scope, selectedList.name)
                }
            })
            if (selectedList.name != Bookmark.DEFAULT_LIST_NAME) {
                group.add(object : AnAction("删除列表", "删除选中的列表，其收藏并入默认列表", null) {
                    override fun actionPerformed(e: AnActionEvent) {
                        interactions.deleteList(selectedList.scope, selectedList.name)
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
     * 构建"移动到列表▸"子菜单（1.2.0 起按作用域分组展示所有列表，当前所在列表置灰）
     *
     * - 同作用域列表：普通列表移动；
     * - 跨作用域列表：调用转移 API（路径转换+助记符降级规则由 FavoritesTransferService 处理）。
     */
    private fun buildMoveToListGroup(selectedBookmarks: List<ScopedBookmark>): DefaultActionGroup {
        val group = DefaultActionGroup("移动到列表", true)
        val current = selectedBookmarks.firstOrNull()

        // ////////////////  按作用域分组：每组一个子菜单  start  ////////////////////////
        for (scope in FavoriteScope.entries) {
            val scopeGroup = DefaultActionGroup(scope.label, true)
            for (info in managerFor(scope).getLists()) {
                val targetName = info.name
                scopeGroup.add(object : AnAction(targetName, "移动到「${scope.label}」列表「$targetName」", null) {
                    override fun actionPerformed(e: AnActionEvent) {
                        interactions.moveSelectedTo(scope, targetName)
                    }

                    override fun update(e: AnActionEvent) {
                        // 当前所在（作用域+列表）置灰
                        e.presentation.isEnabled =
                            !(current != null && current.scope == scope && current.bookmark.listName == targetName)
                    }
                })
            }
            group.add(scopeGroup)
        }
        // ////////////////  按作用域分组  end  ////////////////////////////////////////
        return group
    }

    private fun registerKeyStrokes() {
        val inputMap = getInputMap(JComponent.WHEN_FOCUSED)
        val actionMap = getActionMap()

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), ACTION_KEY_JUMP)
        actionMap.put(ACTION_KEY_JUMP, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                getSelectedBookmarks().firstOrNull()?.let { navigateToBookmark(it.bookmark) }
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
        if (!bookmark.bookmark.isLineBookmark()) return
        val file = resolveVirtualFile(bookmark.bookmark) ?: return
        try {
            FileEditorManager.getInstance(project).openFile(file, false)
        } catch (e: Exception) {
            log.warn("预览打开文件失败：${bookmark.bookmark.filePath}", e)
        }
    }

    /**
     * 收藏是否命中过滤词（描述/文件名/路径/列表名）
     */
    private fun bookmarkMatches(bookmark: Bookmark, query: String): Boolean {
        val fileName = bookmark.filePath.substringAfterLast('/')
        return bookmark.description?.lowercase(Locale.ROOT)?.contains(query) == true ||
            fileName.lowercase(Locale.ROOT).contains(query) ||
            bookmark.filePath.lowercase(Locale.ROOT).contains(query) ||
            bookmark.listName.lowercase(Locale.ROOT).contains(query)
    }

    /**
     * 解析收藏对应的虚拟文件（项目收藏相对路径拼项目根；全局收藏绝对路径直用）
     */
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
 * 收藏树单元格渲染器（1.2.0 四层节点）：
 * - 作用域节点：作用域图标 + "作用域名 (总数)"
 * - 列表节点：文件夹图标 + "名称 (收藏数)"
 * - 类型分组节点：类型图标 + "行收藏/文件收藏/文件夹收藏 (N)"
 * - 收藏节点：按类型区分（LINE=助记符/匿名书签图标，FILE=文件图标，FOLDER=文件夹图标）
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
            // 作用域节点（含总数徽标文案）
            is ScopeNode -> {
                text = userObject.toString()
                icon = BookmarkIconHelper.getScopeIcon(userObject.scope)
            }
            // 列表节点（含收藏数徽标文案）
            is BookmarkListNode -> {
                text = userObject.toString()
                icon = AllIcons.Nodes.Folder
            }
            // 类型分组节点（含数量徽标文案）
            is TypeGroupNode -> {
                text = userObject.toString()
                icon = BookmarkIconHelper.getTypeIcon(userObject.type)
            }
            // 收藏节点（按类型区分图标：LINE 沿用助记符/匿名书签图标）
            is BookmarkNode -> {
                val bookmark = userObject.bookmark
                text = formatBookmarkNodeText(bookmark)
                icon = when {
                    bookmark.isLineBookmark() -> if (bookmark.isMnemonic() && bookmark.mnemonic != null) {
                        BookmarkIconHelper.getMnemonicIcon(bookmark.mnemonic!!)
                    } else {
                        BookmarkIconHelper.ANONYMOUS_ICON
                    }
                    bookmark.isFileBookmark() -> BookmarkIconHelper.getTypeIcon(BookmarkType.FILE)
                    else -> BookmarkIconHelper.getTypeIcon(BookmarkType.FOLDER)
                }
            }
        }

        return this
    }
}
