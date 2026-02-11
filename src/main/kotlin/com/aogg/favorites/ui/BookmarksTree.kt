package com.aogg.favorites.ui

import com.aogg.favorites.helper.BookmarkIconHelper
import com.aogg.favorites.model.Bookmark
import com.aogg.favorites.model.BookmarkManager
import com.aogg.favorites.model.BookmarkType
import com.intellij.icons.AllIcons
import com.intellij.ide.util.treeView.AbstractTreeBuilder
import com.intellij.ide.util.treeView.AbstractTreeStructure
import com.intellij.ide.util.treeView.NodeDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPopupMenu
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.ui.treeStructure.Tree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * 收藏树组件
 * 支持双击跳转和右键菜单
 */
class BookmarksTree(project: Project) : Tree() {
    
    private val bookmarkManager = BookmarkManager.getInstance(project)
    private val projectInstance = project
    
    init {
        // 设置树模型
        model = DefaultTreeModel(createTreeModel())
        
        // 启用双击跳转
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2) {
                    val path = getPathForLocation(e.x, e.y) ?: return
                    val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                    val bookmark = node.userObject as? Bookmark ?: return
                    navigateToBookmark(bookmark)
                }
            }
        })
        
        // 设置单元格渲染器
        cellRenderer = BookmarksTreeCellRenderer()
        
        // 添加右键菜单
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mousePressed(e: java.awt.event.MouseEvent) {
                if (e.isPopupTrigger) {
                    showPopupMenu(e)
                }
            }
            
            override fun mouseReleased(e: java.awt.event.MouseEvent) {
                if (e.isPopupTrigger) {
                    showPopupMenu(e)
                }
            }
        })
    }
    
    /**
     * 显示右键菜单
     */
    private fun showPopupMenu(e: java.awt.event.MouseEvent) {
        val path = getPathForLocation(e.x, e.y) ?: return
        val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
        val bookmark = node.userObject as? Bookmark ?: return
        
        // 选中节点
        selectionPath = path
        
        // 创建动作组
        val actionGroup = DefaultActionGroup().apply {
            add(com.aogg.favorites.action.EditBookmarkAction())
            add(com.aogg.favorites.action.DeleteBookmarkAction())
        }
        
        // 创建弹出菜单
        val popupMenu = ActionManager.getInstance().createActionPopupMenu("FavoritesToolWindowPopupMenu", actionGroup)
        popupMenu.component.show(this, e.x, e.y)
    }
    
    /**
     * 创建树模型
     */
    private fun createTreeModel(): DefaultMutableTreeNode {
        val root = DefaultMutableTreeNode("收藏")
        
        // 获取所有收藏
        val allBookmarks = bookmarkManager.getAllBookmarks()
        
        // 按类型分组
        val lineBookmarks = allBookmarks.filter { it.type == BookmarkType.LINE }
        val fileBookmarks = allBookmarks.filter { it.type == BookmarkType.FILE }
        val folderBookmarks = allBookmarks.filter { it.type == BookmarkType.FOLDER }
        
        // 添加代码行收藏节点
        if (lineBookmarks.isNotEmpty()) {
            val lineNode = DefaultMutableTreeNode("代码行收藏 (${lineBookmarks.size})")
            lineBookmarks.forEach { bookmark ->
                lineNode.add(DefaultMutableTreeNode(bookmark))
            }
            root.add(lineNode)
        }
        
        // 添加文件收藏节点
        if (fileBookmarks.isNotEmpty()) {
            val fileNode = DefaultMutableTreeNode("文件收藏 (${fileBookmarks.size})")
            fileBookmarks.forEach { bookmark ->
                fileNode.add(DefaultMutableTreeNode(bookmark))
            }
            root.add(fileNode)
        }
        
        // 添加文件夹收藏节点
        if (folderBookmarks.isNotEmpty()) {
            val folderNode = DefaultMutableTreeNode("文件夹收藏 (${folderBookmarks.size})")
            folderBookmarks.forEach { bookmark ->
                folderNode.add(DefaultMutableTreeNode(bookmark))
            }
            root.add(folderNode)
        }
        
        return root
    }
    
    /**
     * 刷新树
     */
    fun refresh() {
        model = DefaultTreeModel(createTreeModel())
        expandRow(0)
    }
    
    /**
     * 导航到收藏位置
     */
    private fun navigateToBookmark(bookmark: Bookmark) {
        // 获取项目根目录
        val projectBaseDir = projectInstance.baseDir ?: return
        
        // 构建完整文件路径
        val filePath = if (java.io.File(bookmark.filePath).isAbsolute) {
            bookmark.filePath
        } else {
            java.io.File(projectBaseDir.path, bookmark.filePath).absolutePath
        }
        
        // 查找虚拟文件
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return
        
        if (!virtualFile.exists()) {
            return
        }
        
        // 打开文件
        val fileEditorManager = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(projectInstance)
        fileEditorManager.openFile(virtualFile, true)
        
        // 如果是代码行收藏，跳转到指定行
        if (bookmark.type == BookmarkType.LINE && bookmark.lineNumber > 0) {
            // 等待文件打开
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                val editor = fileEditorManager.selectedTextEditor ?: return@invokeLater
                val document = editor.document
                if (bookmark.lineNumber <= document.lineCount) {
                    val lineStartOffset = document.getLineStartOffset(bookmark.lineNumber - 1)
                    editor.caretModel.moveToOffset(lineStartOffset)
                    editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)
                }
            }
        }
    }
    
    /**
     * 获取选中的收藏
     */
    fun getSelectedBookmark(): Bookmark? {
        val selectedNode = selectionPath?.lastPathComponent as? DefaultMutableTreeNode ?: return null
        return selectedNode.userObject as? Bookmark
    }
    
    /**
     * 获取所有选中的收藏
     */
    fun getSelectedBookmarks(): List<Bookmark> {
        return selectionPaths?.mapNotNull { path ->
            val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return@mapNotNull null
            node.userObject as? Bookmark
        } ?: emptyList()
    }
}

/**
 * 收藏树单元格渲染器
 */
class BookmarksTreeCellRenderer : javax.swing.tree.DefaultTreeCellRenderer() {
    
    init {
        // 设置默认图标
        leafIcon = AllIcons.Gutter.Bookmark
        closedIcon = AllIcons.Nodes.Folder
        openIcon = AllIcons.Nodes.FolderOpen
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
        
        val node = value as? DefaultMutableTreeNode ?: return this
        val userObject = node.userObject
        
        when {
            // 根节点或分组节点
            userObject is String -> {
                text = userObject
                icon = if (expanded) AllIcons.Nodes.FolderOpen else AllIcons.Nodes.Folder
            }
            // 收藏节点
            userObject is Bookmark -> {
                val bookmark = userObject
                text = formatBookmarkText(bookmark)
                icon = if (bookmark.isMnemonic()) {
                    BookmarkIconHelper.getMnemonicIcon(bookmark.mnemonic!!)
                } else {
                    BookmarkIconHelper.ANONYMOUS_ICON
                }
            }
        }
        
        return this
    }
    
    /**
     * 格式化收藏显示文本
     */
    private fun formatBookmarkText(bookmark: Bookmark): String {
        val displayName = bookmark.getDisplayName()
        val location = when (bookmark.type) {
            BookmarkType.LINE -> "${bookmark.filePath}:${bookmark.lineNumber}"
            BookmarkType.FILE -> bookmark.filePath
            BookmarkType.FOLDER -> bookmark.filePath
        }
        
        return if (bookmark.isMnemonic()) {
            "[${bookmark.mnemonic}] $displayName - $location"
        } else {
            "$displayName - $location"
        }
    }
}
