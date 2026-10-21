package com.aogg.favorites.ui

import com.aogg.favorites.helper.BookmarkRefreshHelper
import com.aogg.favorites.model.Bookmark
import com.aogg.favorites.model.BookmarkManager
import com.aogg.favorites.model.SortType
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DataManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.SearchTextField
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * 收藏工具窗口
 * 提供统一的收藏管理界面：
 * - 顶部：SearchTextField 实时过滤（描述/文件名/列表名）+ 排序方式下拉（按列表记忆持久化）
 * - 中部：ToolbarDecorator + 树（第一层=列表，第二层=收藏项）
 * - 工具栏：添加收藏 / 编辑描述 / 删除 / 添加助记符 / 全部展开 / 全部折叠
 */
class BookmarksToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val toolWindowPanel = BookmarksToolWindowPanel(project)
        val content = ContentFactory.SERVICE.getInstance().createContent(toolWindowPanel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}

/**
 * 收藏工具窗口面板
 */
class BookmarksToolWindowPanel(private val project: Project) : JPanel(BorderLayout()) {

    /**
     * 排序下拉项
     */
    private inner class SortItem(val type: SortType) {
        override fun toString(): String = when (type) {
            SortType.ADDED -> "按添加顺序"
            SortType.DESCRIPTION -> "按描述"
            SortType.LOCATION -> "按位置"
        }
    }

    private val bookmarkManager = BookmarkManager.getInstance(project)
    private val bookmarksTree: BookmarksTree
    private val searchField = SearchTextField(false)
    private val sortComboBox = JComboBox<SortItem>()
    private var updatingSortComboBox = false

    init {
        bookmarksTree = BookmarksTree(project, object : BookmarksTree.Interactions {
            override fun editSelectedBookmark() {
                editSelectedDescription()
            }

            override fun deleteSelected() {
                deleteSelectedNodes()
            }

            override fun setMnemonicForSelected() {
                setMnemonicForSelectedBookmark()
            }

            override fun moveSelectedTo(listName: String) {
                moveSelectedBookmarksTo(listName)
            }

            override fun createList() {
                createNewList()
            }

            override fun renameList(listName: String?) {
                renameSelectedList(listName)
            }

            override fun deleteList(listName: String?) {
                deleteSelectedList(listName)
            }
        })

        // 搜索过滤（按描述/文件名/列表名实时过滤）
        searchField.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) {
                applyFilter()
            }

            override fun removeUpdate(e: DocumentEvent?) {
                applyFilter()
            }

            override fun changedUpdate(e: DocumentEvent?) {
                applyFilter()
            }
        })

        // 排序方式下拉（按列表记忆持久化）
        listOf(SortType.ADDED, SortType.DESCRIPTION, SortType.LOCATION).forEach {
            sortComboBox.addItem(SortItem(it))
        }
        sortComboBox.addActionListener {
            if (updatingSortComboBox) return@addActionListener
            val item = sortComboBox.selectedItem as? SortItem ?: return@addActionListener
            applySortType(item.type)
        }

        val northPanel = JPanel(BorderLayout())
        northPanel.add(searchField, BorderLayout.CENTER)
        val sortPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
        sortPanel.add(JBLabel("排序:"))
        sortPanel.add(sortComboBox)
        northPanel.add(sortPanel, BorderLayout.EAST)
        add(northPanel, BorderLayout.NORTH)

        // 工具栏（ToolbarDecorator 原生观感）
        val decorator = ToolbarDecorator.createDecorator(bookmarksTree)
            .disableUpDownActions()
            .setAddActionName("添加收藏")
            .setAddAction { addAnonymousBookmarkFromToolWindow() }
            .setEditActionName("编辑描述")
            .setEditAction { editSelectedDescription() }
            .setRemoveActionName("删除收藏")
            .setRemoveAction { deleteSelectedNodes() }
            .addExtraAction(AddMnemonicFromToolWindowAction())
            .addExtraAction(ImportNativeBookmarksFromToolWindowAction())
            .addExtraAction(ExpandAllAction())
            .addExtraAction(CollapseAllAction())
        add(decorator.createPanel(), BorderLayout.CENTER)

        // 选中变化时同步排序下拉显示
        bookmarksTree.addTreeSelectionListener {
            syncSortComboBox()
        }
        syncSortComboBox()
    }

    /**
     * 刷新面板内容
     */
    fun refresh() {
        bookmarksTree.refresh(searchField.text)
        syncSortComboBox()
    }

    /**
     * 获取收藏树
     */
    fun getBookmarksTree(): BookmarksTree {
        return bookmarksTree
    }

    private fun applyFilter() {
        bookmarksTree.refresh(searchField.text)
    }

    private fun refreshTreeOnly() {
        bookmarksTree.refresh(searchField.text)
        syncSortComboBox()
    }

    /**
     * 收藏数据发生变化：刷新树与 gutter
     */
    private fun afterDataChanged() {
        BookmarkRefreshHelper.refreshAfterChange(project)
        syncSortComboBox()
    }

    // ==================== 工具栏动作 ====================

    private fun addAnonymousBookmarkFromToolWindow() {
        invokeGlobalAction("Favorites.AddAnonymousBookmark")
    }

    /**
     * 以工具窗口为上下文调用已注册的全局动作，复用其添加/移除逻辑：
     * - 当前有编辑器时携带 EDITOR 上下文（→行收藏）
     * - 否则由动作内部回退到当前打开文件（→文件收藏）
     */
    private fun invokeGlobalAction(actionId: String) {
        val action = ActionManager.getInstance().getAction(actionId) ?: return
        val baseContext = DataManager.getInstance().getDataContext(this)
        val selectedEditor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedTextEditor
        val dataContext = DataContext { dataId ->
            if (dataId == CommonDataKeys.EDITOR.name) {
                selectedEditor
            } else {
                baseContext.getData(dataId)
            }
        }
        val event = AnActionEvent.createFromDataContext("FavoritesToolWindow", null, dataContext)
        action.actionPerformed(event)
    }

    // ==================== 收藏操作 ====================

    private fun editSelectedDescription() {
        val bookmark = bookmarksTree.getSelectedBookmarks().singleOrNull()
        if (bookmark == null) {
            Messages.showInfoMessage(project, "请先在树中选中一个收藏项", "编辑收藏描述")
            return
        }
        val input = Messages.showInputDialog(
            project,
            "请输入收藏描述：",
            "编辑收藏描述",
            Messages.getInformationIcon(),
            bookmark.description ?: "",
            null
        )
        // 取消输入
        if (input == null) return
        bookmarkManager.updateBookmarkDescription(bookmark.id, input.trim().ifEmpty { null })
        afterDataChanged()
    }

    private fun deleteSelectedNodes() {
        val selected = bookmarksTree.getSelectedBookmarks()
        if (selected.isEmpty()) {
            // 选中的是列表节点时，按删除列表处理
            bookmarksTree.getSelectedListName()?.let { deleteSelectedList(it) }
            return
        }

        val message = if (selected.size == 1) {
            "确定要删除这个收藏吗？"
        } else {
            "确定要删除这 ${selected.size} 个收藏吗？"
        }
        val result = Messages.showYesNoDialog(project, message, "删除收藏", Messages.getQuestionIcon())
        if (result != Messages.YES) return

        selected.forEach { bookmarkManager.removeBookmark(it.id) }
        afterDataChanged()
    }

    private fun setMnemonicForSelectedBookmark() {
        val bookmark = bookmarksTree.getSelectedBookmarks().singleOrNull()
        if (bookmark == null) {
            Messages.showInfoMessage(project, "请先在树中选中一个收藏项", "设置助记符")
            return
        }
        val dialog = MnemonicSelectorDialog(project, bookmark.mnemonic, bookmark.description)
        if (!dialog.showAndGet()) return
        val mnemonic = dialog.selectedMnemonic ?: return
        if (!bookmarkManager.updateBookmarkDetails(bookmark.id, mnemonic, dialog.description)) {
            Messages.showErrorDialog(project, "助记符 [$mnemonic] 已被其它收藏占用", "设置助记符失败")
            return
        }
        afterDataChanged()
    }

    private fun moveSelectedBookmarksTo(listName: String) {
        val selected = bookmarksTree.getSelectedBookmarks()
        if (selected.isEmpty()) return
        selected.forEach { bookmarkManager.moveToList(it.id, listName) }
        afterDataChanged()
    }

    // ==================== 列表操作 ====================

    private fun createNewList() {
        val name = Messages.showInputDialog(
            project,
            "请输入新列表名称：",
            "新建列表",
            Messages.getInformationIcon(),
            "",
            object : InputValidator {
                override fun checkInput(inputString: String): Boolean =
                    inputString.isNotBlank() && !bookmarkManager.hasList(inputString.trim())

                override fun canClose(inputString: String): Boolean = checkInput(inputString)
            }
        ) ?: return
        if (bookmarkManager.createList(name)) {
            refreshTreeOnly()
        }
    }

    private fun renameSelectedList(listName: String?) {
        if (listName == null) {
            Messages.showInfoMessage(project, "请先在树中选中一个列表或列表中的收藏", "重命名列表")
            return
        }
        if (listName == Bookmark.DEFAULT_LIST_NAME) {
            Messages.showInfoMessage(project, "默认列表「$listName」不可重命名", "重命名列表")
            return
        }
        val newName = Messages.showInputDialog(
            project,
            "请输入新的列表名称：",
            "重命名列表",
            Messages.getInformationIcon(),
            listName,
            object : InputValidator {
                override fun checkInput(inputString: String): Boolean =
                    inputString.isNotBlank() &&
                        (!bookmarkManager.hasList(inputString.trim()) || inputString.trim() == listName)

                override fun canClose(inputString: String): Boolean = checkInput(inputString)
            }
        ) ?: return
        if (bookmarkManager.renameList(listName, newName)) {
            refreshTreeOnly()
        } else {
            Messages.showErrorDialog(project, "重命名列表失败：名称不能为空且不能与现有列表重名", "重命名列表")
        }
    }

    private fun deleteSelectedList(listName: String?) {
        if (listName == null) {
            Messages.showInfoMessage(project, "请先在树中选中一个列表", "删除列表")
            return
        }
        if (listName == Bookmark.DEFAULT_LIST_NAME) {
            Messages.showInfoMessage(project, "默认列表「$listName」不可删除", "删除列表")
            return
        }
        val count = bookmarkManager.getBookmarksByList(listName).size
        val message = "确定要删除列表「$listName」吗？\n" +
            "其中的 $count 个收藏将并入默认列表「${Bookmark.DEFAULT_LIST_NAME}」。"
        val result = Messages.showYesNoDialog(project, message, "删除列表", Messages.getQuestionIcon())
        if (result != Messages.YES) return
        if (bookmarkManager.deleteList(listName)) {
            afterDataChanged()
        }
    }

    // ==================== 排序 ====================

    private fun applySortType(type: SortType) {
        val listName = bookmarksTree.getSelectedListName()
        if (listName != null) {
            bookmarkManager.setSortType(listName, type)
        } else {
            // 无选中上下文时应用到全部列表
            bookmarkManager.getLists().forEach { bookmarkManager.setSortType(it.name, type) }
        }
        refreshTreeOnly()
    }

    private fun syncSortComboBox() {
        val listName = bookmarksTree.getSelectedListName() ?: Bookmark.DEFAULT_LIST_NAME
        val sortType = bookmarkManager.getSortType(listName)
        updatingSortComboBox = true
        try {
            for (i in 0 until sortComboBox.itemCount) {
                val item = sortComboBox.getItemAt(i)
                if (item.type == sortType) {
                    sortComboBox.selectedIndex = i
                    break
                }
            }
        } finally {
            updatingSortComboBox = false
        }
    }

    // ==================== 工具栏额外按钮 ====================

    private inner class AddMnemonicFromToolWindowAction :
        AnAction("添加助记符收藏", "在当前位置添加/移除助记符收藏", AllIcons.Gutter.Bookmark) {
        override fun actionPerformed(e: AnActionEvent) {
            invokeGlobalAction("Favorites.AddMnemonicBookmark")
        }
    }

    /**
     * 导入原生书签（转发到 Favorites.ImportNativeBookmarks 动作）
     */
    private inner class ImportNativeBookmarksFromToolWindowAction :
        AnAction("导入原生书签", "导入 PhpStorm 原生书签到收藏列表", AllIcons.ToolbarDecorator.Import) {
        override fun actionPerformed(e: AnActionEvent) {
            invokeGlobalAction("Favorites.ImportNativeBookmarks")
        }
    }

    private inner class ExpandAllAction : AnAction("全部展开", "展开所有列表", AllIcons.Actions.Expandall) {
        override fun actionPerformed(e: AnActionEvent) {
            bookmarksTree.expandAllRows()
        }
    }

    private inner class CollapseAllAction : AnAction("全部折叠", "折叠所有列表", AllIcons.Actions.Collapseall) {
        override fun actionPerformed(e: AnActionEvent) {
            bookmarksTree.collapseAllRows()
        }
    }

    companion object {
        /**
         * 刷新指定项目的工具窗口
         */
        @JvmStatic
        fun refreshToolWindow(project: Project) {
            val toolWindow = ToolWindowManager.getInstance(project)
                .getToolWindow("Favorites") ?: return

            val content = toolWindow.contentManager.selectedContent
                ?: toolWindow.contentManager.contents.firstOrNull()
                ?: return
            val panel = content.component as? BookmarksToolWindowPanel ?: return
            panel.refresh()
        }
    }
}
