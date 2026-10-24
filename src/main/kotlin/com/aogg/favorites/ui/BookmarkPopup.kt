package com.aogg.favorites.ui

import com.aogg.favorites.helper.BookmarkIconHelper
import com.aogg.favorites.model.Bookmark
import com.aogg.favorites.model.BookmarkType
import com.aogg.favorites.model.FavoriteScope
import com.aogg.favorites.model.FavoritesScopeResolver
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ListSpeedSearch
import com.intellij.ui.components.JBList
import com.intellij.ui.popup.PopupFactoryImpl
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Font
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel

/**
 * 收藏快速访问弹出窗口
 * 支持键盘导航和搜索
 *
 * 1.2.0 分组展示：按「项目收藏」「全局收藏」两组（分组标题），组内沿用列表名子标题。
 * 1.5.x 分组路径展示：子标题升级为分组完整路径（如「父组/子组 (3)」）并带收藏数，
 * 多层级不展开为嵌套弹层、平铺即可；键盘导航/速度搜索沿用。
 */
class BookmarkPopup(
    private val project: Project,
    private val editor: Editor? = null
) {
    /**
     * 弹窗列表条目：分组标题（作用域/列表）或收藏项
     */
    private sealed class PopupItem {

        /**
         * 作用域分组标题
         */
        class ScopeHeader(val scope: FavoriteScope) : PopupItem() {
            override fun toString(): String = scope.label
        }

        /**
         * 列表子标题（1.5.x 起按分组完整路径平铺展示，如「父组/子组 (3)」）：
         * 分组层级不展开为嵌套弹层，沿用现有键盘导航/过滤
         *
         * @param path 分组完整层级路径（默认列表即「收藏」）
         * @param count 该分组下的收藏条数
         */
        class ListHeader(val path: String, val count: Int) : PopupItem() {
            override fun toString(): String = "$path ($count)"
        }

        /**
         * 收藏条目
         */
        class Entry(val bookmark: Bookmark) : PopupItem() {
            override fun toString(): String = formatPopupBookmarkText(bookmark)
        }
    }

    /**
     * 显示弹出窗口
     */
    fun show() {
        // ////////////////  准备数据：按作用域→分组构建平铺条目  start  //////////////////
        val model = DefaultListModel<PopupItem>()
        for (scope in FavoriteScope.entries) {
            val manager = FavoritesScopeResolver.managerFor(project, scope)
            val listNames = manager.getLists().map { it.name }
                .ifEmpty { listOf(Bookmark.DEFAULT_LIST_NAME) }
            // 该作用域下所有收藏按分组归拢（沿用各分组记忆的排序；标题取分组完整路径）
            val grouped = listNames.mapNotNull { name ->
                val bookmarks = manager.getBookmarksByList(name)
                if (bookmarks.isEmpty()) null else manager.fullPath(name) to bookmarks
            }
            // 空作用域不显示分组标题，减少噪音
            if (grouped.isEmpty()) continue

            model.addElement(PopupItem.ScopeHeader(scope))
            for ((groupPath, bookmarks) in grouped) {
                model.addElement(PopupItem.ListHeader(groupPath, bookmarks.size))
                bookmarks.forEach { model.addElement(PopupItem.Entry(it)) }
            }
        }
        // ////////////////  准备数据  end  ////////////////////////////////////////////

        if (model.isEmpty) {
            return
        }

        // ////////////////  核心逻辑：构建分组列表与弹窗  start  ////////////////////////
        val list = JBList(model).apply {
            cellRenderer = BookmarkListCellRenderer()
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            // 启用速度搜索（标题条目按其文本参与搜索）
            ListSpeedSearch(this) { item ->
                when (item) {
                    is PopupItem.Entry -> formatPopupBookmarkText(item.bookmark)
                    else -> item.toString()
                }
            }
        }

        val popup = PopupFactoryImpl.getInstance().createListPopupBuilder(list)
            .setTitle("收藏列表")
            .setItemChoosenCallback {
                // 分组标题不可选中跳转，仅收藏条目响应
                val selected = list.selectedValue as? PopupItem.Entry ?: return@setItemChoosenCallback
                navigateToBookmark(selected.bookmark)
            }
            .createPopup()
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////

        // 显示弹出窗口
        if (editor != null) {
            popup.showInBestPositionFor(editor)
        } else {
            val selectedEditor = FileEditorManager.getInstance(project).selectedTextEditor
            if (selectedEditor != null) {
                popup.showInBestPositionFor(selectedEditor)
            } else {
                popup.showCenteredInCurrentWindow(project)
            }
        }
    }

    /**
     * 导航到收藏位置（项目收藏相对路径拼项目根；全局收藏绝对路径直用）
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
     * 格式化收藏显示文本（委托文件级共享函数，供嵌套类复用）
     */
    private fun formatBookmarkText(bookmark: Bookmark): String = formatPopupBookmarkText(bookmark)

    /**
     * 收藏列表单元格渲染器：作用域标题加粗+作用域图标；列表标题加粗+文件夹图标；
     * 收藏条目按类型区分图标（LINE=助记符/匿名书签，FILE=文件，FOLDER=文件夹）
     */
    private class BookmarkListCellRenderer : ListCellRenderer<PopupItem> {

        private val defaultRenderer = DefaultListCellRenderer()

        override fun getListCellRendererComponent(
            list: JList<out PopupItem>?,
            value: PopupItem?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            val component = defaultRenderer.getListCellRendererComponent(
                list, value, index, isSelected, cellHasFocus
            ) as javax.swing.JLabel

            if (value != null) {
                when (value) {
                    // 作用域分组标题：加粗 + 作用域图标
                    is PopupItem.ScopeHeader -> {
                        component.icon = BookmarkIconHelper.getScopeIcon(value.scope)
                        component.text = value.scope.label
                        component.font = component.font.deriveFont(Font.BOLD)
                        component.iconTextGap = JBUI.scale(8)
                    }
                    // 列表子标题：加粗 + 文件夹图标（按分组完整路径 + 收藏数展示）
                    is PopupItem.ListHeader -> {
                        component.icon = com.intellij.icons.AllIcons.Nodes.Folder
                        component.text = value.toString()
                        component.font = component.font.deriveFont(Font.BOLD)
                        component.iconTextGap = JBUI.scale(8)
                    }
                    // 收藏条目：按类型区分图标
                    is PopupItem.Entry -> {
                        val bookmark = value.bookmark
                        component.icon = when {
                            bookmark.isLineBookmark() -> if (bookmark.isMnemonic() && bookmark.mnemonic != null) {
                                BookmarkIconHelper.getMnemonicIcon(bookmark.mnemonic!!)
                            } else {
                                BookmarkIconHelper.ANONYMOUS_ICON
                            }
                            bookmark.isFileBookmark() -> BookmarkIconHelper.getTypeIcon(BookmarkType.FILE)
                            else -> BookmarkIconHelper.getTypeIcon(BookmarkType.FOLDER)
                        }
                        component.text = formatPopupBookmarkText(bookmark)
                        component.iconTextGap = JBUI.scale(8)
                    }
                }
            }

            return component
        }
    }
}

/**
 * 格式化收藏条目显示文本（文件级私有：嵌套类 Entry/渲染器均可访问）
 *
 * 格式：[助记符] 显示名 - 位置（行收藏带 :行号）
 */
private fun formatPopupBookmarkText(bookmark: Bookmark): String {
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
