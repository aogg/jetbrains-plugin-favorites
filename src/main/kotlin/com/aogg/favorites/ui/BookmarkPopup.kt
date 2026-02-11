package com.aogg.favorites.ui

import com.aogg.favorites.helper.BookmarkIconHelper
import com.aogg.favorites.model.Bookmark
import com.aogg.favorites.model.BookmarkManager
import com.aogg.favorites.model.BookmarkType
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ListSpeedSearch
import com.intellij.ui.components.JBList
import com.intellij.ui.popup.PopupFactoryImpl
import com.intellij.util.ui.JBUI
import java.awt.Component
import javax.swing.DefaultListCellRenderer
import javax.swing.JList
import javax.swing.ListCellRenderer

/**
 * 收藏快速访问弹出窗口
 * 支持键盘导航和搜索
 */
class BookmarkPopup(
    private val project: Project,
    private val editor: Editor? = null
) {
    
    private val bookmarkManager = BookmarkManager.getInstance(project)
    
    /**
     * 显示弹出窗口
     */
    fun show() {
        val bookmarks = bookmarkManager.getAllBookmarks()
        
        if (bookmarks.isEmpty()) {
            return
        }
        
        // 创建列表模型
        val listModel = javax.swing.DefaultListModel<Bookmark>()
        bookmarks.forEach { listModel.addElement(it) }
        
        // 创建列表
        val list = JBList(listModel).apply {
            cellRenderer = BookmarkListCellRenderer()
            selectionMode = javax.swing.ListSelectionModel.SINGLE_SELECTION
            // 启用速度搜索
            ListSpeedSearch(this) { bookmark ->
                formatBookmarkText(bookmark)
            }
        }
        
        // 创建弹出窗口
        val popup = PopupFactoryImpl.getInstance().createListPopupBuilder(list)
            .setTitle("收藏列表")
            .setItemChoosenCallback {
                val selectedBookmark = list.selectedValue as? Bookmark ?: return@createListPopupBuilder
                navigateToBookmark(selectedBookmark)
            }
            .setFilteringEnabled(true)
            .createPopup()
        
        // 显示弹出窗口
        val component = editor?.contentComponent ?: FileEditorManager.getInstance(project).selectedTextEditor?.contentComponent
        if (component != null) {
            popup.showInBestPositionFor(component)
        } else {
            popup.showCenteredInCurrentWindow(project)
        }
    }
    
    /**
     * 导航到收藏位置
     */
    private fun navigateToBookmark(bookmark: Bookmark) {
        // 获取项目根目录
        val projectBaseDir = project.baseDir ?: return
        
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
        val fileEditorManager = FileEditorManager.getInstance(project)
        fileEditorManager.openFile(virtualFile, true)
        
        // 如果是代码行收藏，跳转到指定行
        if (bookmark.type == BookmarkType.LINE && bookmark.lineNumber >= 0) {
            // 等待文件打开
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                val editor = fileEditorManager.selectedTextEditor ?: return@invokeLater
                val document = editor.document
                val lineIndex = bookmark.lineNumber
                if (lineIndex < document.lineCount) {
                    val lineStartOffset = document.getLineStartOffset(lineIndex)
                    editor.caretModel.moveToOffset(lineStartOffset)
                    editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)
                }
            }
        }
    }
    
    /**
     * 格式化收藏显示文本
     */
    private fun formatBookmarkText(bookmark: Bookmark): String {
        val displayName = bookmark.getDisplayName()
        val location = when (bookmark.type) {
            BookmarkType.LINE -> "${bookmark.filePath}:${bookmark.lineNumber + 1}"
            BookmarkType.FILE -> bookmark.filePath
            BookmarkType.FOLDER -> bookmark.filePath
        }
        
        return if (bookmark.isMnemonic()) {
            "[${bookmark.mnemonic}] $displayName - $location"
        } else {
            "$displayName - $location"
        }
    }
    
    /**
     * 收藏列表单元格渲染器
     */
    private class BookmarkListCellRenderer : ListCellRenderer<Bookmark> {
        
        private val defaultRenderer = DefaultListCellRenderer()
        
        override fun getListCellRendererComponent(
            list: JList<out Bookmark>?,
            value: Bookmark?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            val component = defaultRenderer.getListCellRendererComponent(
                list, value, index, isSelected, cellHasFocus
            ) as javax.swing.JLabel
            
            if (value != null) {
                // 设置图标
                component.icon = if (value.isMnemonic()) {
                    BookmarkIconHelper.getMnemonicIcon(value.mnemonic!!)
                } else {
                    BookmarkIconHelper.ANONYMOUS_ICON
                }
                
                // 设置文本
                val displayName = value.getDisplayName()
                val location = when (value.type) {
                    BookmarkType.LINE -> "${value.filePath}:${value.lineNumber + 1}"
                    BookmarkType.FILE -> value.filePath
                    BookmarkType.FOLDER -> value.filePath
                }
                
                val text = if (value.isMnemonic()) {
                    "[${value.mnemonic}] $displayName - $location"
                } else {
                    "$displayName - $location"
                }
                
                component.text = text
                
                // 设置图标文本间距
                component.iconTextGap = JBUI.scale(8)
            }
            
            return component
        }
    }
}
