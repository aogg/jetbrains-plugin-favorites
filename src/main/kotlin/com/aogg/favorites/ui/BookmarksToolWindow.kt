package com.aogg.favorites.ui

import com.aogg.favorites.helper.BookmarkRefreshHelper
import com.aogg.favorites.model.Bookmark
import com.aogg.favorites.model.FavoriteScope
import com.aogg.favorites.model.FavoritesScopeResolver
import com.aogg.favorites.model.FavoritesTransferService
import com.aogg.favorites.model.SortType
import com.aogg.favorites.settings.FavoritesConfigurable
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.options.ShowSettingsUtil
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
 * 提供统一的收藏管理界面（1.2.0 四层树 + 双作用域）：
 * - 顶部：SearchTextField 实时过滤（作用域/描述/文件名/列表名）+ 排序方式下拉（按列表记忆持久化，两作用域独立）
 * - 中部：ToolbarDecorator + 树（作用域→列表→类型分组→收藏）
 * - 工具栏：添加收藏 / 编辑描述 / 删除 / 添加助记符 / 导入原生书签 / 全部展开 / 全部折叠 / 收藏设置（最后一个）
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

            override fun moveSelectedTo(scope: FavoriteScope, listName: String) {
                moveSelectedBookmarksTo(scope, listName)
            }

            override fun createList(scope: FavoriteScope) {
                createNewList(scope)
            }

            override fun renameList(scope: FavoriteScope, listName: String?) {
                renameSelectedList(scope, listName)
            }

            override fun deleteList(scope: FavoriteScope, listName: String?) {
                deleteSelectedList(scope, listName)
            }
        })

        // 搜索过滤（按作用域/描述/文件名/列表名实时过滤）
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

        // 排序方式下拉（按列表记忆持久化，两作用域各自独立）
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

        // 工具栏（ToolbarDecorator 原生观感；最后一个按钮为"收藏设置"）
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
            .addExtraAction(OpenFavoritesSettingsAction())
        add(decorator.createPanel(), BorderLayout.CENTER)

        // 选中变化时同步排序下拉显示
        bookmarksTree.addTreeSelectionListener {
            syncSortComboBox()
        }
        syncSortComboBox()
    }

    /**
     * 按作用域取对应管理器（列表/收藏操作路由）
     */
    private fun managerFor(scope: FavoriteScope) = FavoritesScopeResolver.managerFor(project, scope)

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

    // ==================== 收藏操作（按作用域路由） ====================

    private fun editSelectedDescription() {
        val selected = bookmarksTree.getSelectedBookmarks().singleOrNull()
        if (selected == null) {
            Messages.showInfoMessage(project, "请先在树中选中一个收藏项", "编辑收藏描述")
            return
        }
        val input = Messages.showInputDialog(
            project,
            "请输入收藏描述：",
            "编辑收藏描述",
            Messages.getInformationIcon(),
            selected.bookmark.description ?: "",
            null
        )
        // 取消输入
        if (input == null) return
        managerFor(selected.scope).updateBookmarkDescription(selected.bookmark.id, input.trim().ifEmpty { null })
        afterDataChanged()
    }

    private fun deleteSelectedNodes() {
        val selected = bookmarksTree.getSelectedBookmarks()
        if (selected.isEmpty()) {
            // 选中的是列表节点时，按删除列表处理（作用于列表所在作用域）
            bookmarksTree.getSelectedList()?.let { deleteSelectedList(it.scope, it.name) }
            return
        }

        val message = if (selected.size == 1) {
            "确定要删除这个收藏吗？"
        } else {
            "确定要删除这 ${selected.size} 个收藏吗？"
        }
        val result = Messages.showYesNoDialog(project, message, "删除收藏", Messages.getQuestionIcon())
        if (result != Messages.YES) return

        // 按各自作用域的管理器删除
        selected.forEach { managerFor(it.scope).removeBookmark(it.bookmark.id) }
        afterDataChanged()
    }

    private fun setMnemonicForSelectedBookmark() {
        val selected = bookmarksTree.getSelectedBookmarks().singleOrNull()
        if (selected == null) {
            Messages.showInfoMessage(project, "请先在树中选中一个收藏项", "设置助记符")
            return
        }
        // 助记符对话框带作用域：占用检测按该作用域进行
        val dialog = MnemonicSelectorDialog(
            project,
            scope = selected.scope,
            currentMnemonic = selected.bookmark.mnemonic,
            currentDescription = selected.bookmark.description
        )
        if (!dialog.showAndGet()) return
        val mnemonic = dialog.selectedMnemonic ?: return
        if (!managerFor(selected.scope).updateBookmarkDetails(selected.bookmark.id, mnemonic, dialog.description)) {
            Messages.showErrorDialog(
                project,
                "助记符 [$mnemonic] 已被「${selected.scope.label}」中的其它收藏占用",
                "设置助记符失败"
            )
            return
        }
        afterDataChanged()
    }

    /**
     * 移动选中收藏到目标列表：
     * - 同作用域 → 普通列表移动；
     * - 跨作用域 → 转移 API（路径转换 + 助记符降级），失败弹错误提示。
     */
    private fun moveSelectedBookmarksTo(scope: FavoriteScope, listName: String) {
        val selected = bookmarksTree.getSelectedBookmarks()
        if (selected.isEmpty()) return

        // ////////////////  分类：同作用域移动 / 跨作用域转移  start  ////////////////////
        val failures = mutableListOf<String>()
        for (item in selected) {
            if (item.scope == scope) {
                // 同作用域：普通列表移动
                managerFor(scope).moveToList(item.bookmark.id, listName)
            } else {
                // 跨作用域：转移（路径转换+助记符降级；失败收集错误信息统一提示）
                val result = FavoritesTransferService.getInstance(project)
                    .transferBookmark(item.bookmark.id, item.scope, scope, listName)
                if (!result.success) {
                    failures.add(result.errorMessage ?: "未知原因")
                }
            }
        }
        // ////////////////  分类  end  ////////////////////////////////////////////////

        // ////////////////  逻辑后：失败的转移统一弹错误提示  start  ////////////////////
        if (failures.isNotEmpty()) {
            Messages.showErrorDialog(
                project,
                "有 ${failures.size} 个收藏未能转移：\n${failures.distinct().joinToString("\n")}",
                "移动收藏失败"
            )
        }
        // ////////////////  逻辑后  end  //////////////////////////////////////////////

        afterDataChanged()
    }

    // ==================== 列表操作（按作用域路由） ====================

    private fun createNewList(scope: FavoriteScope) {
        val manager = managerFor(scope)
        val name = Messages.showInputDialog(
            project,
            "请输入新列表名称（加入「${scope.label}」）：",
            "新建列表",
            Messages.getInformationIcon(),
            "",
            object : InputValidator {
                override fun checkInput(inputString: String): Boolean =
                    inputString.isNotBlank() && !manager.hasList(inputString.trim())

                override fun canClose(inputString: String): Boolean = checkInput(inputString)
            }
        ) ?: return
        if (manager.createList(name)) {
            refreshTreeOnly()
        }
    }

    private fun renameSelectedList(scope: FavoriteScope, listName: String?) {
        if (listName == null) {
            Messages.showInfoMessage(project, "请先在树中选中一个列表或列表中的收藏", "重命名列表")
            return
        }
        if (listName == Bookmark.DEFAULT_LIST_NAME) {
            Messages.showInfoMessage(project, "默认列表「$listName」不可重命名", "重命名列表")
            return
        }
        val manager = managerFor(scope)
        val newName = Messages.showInputDialog(
            project,
            "请输入新的列表名称：",
            "重命名列表",
            Messages.getInformationIcon(),
            listName,
            object : InputValidator {
                override fun checkInput(inputString: String): Boolean =
                    inputString.isNotBlank() &&
                        (!manager.hasList(inputString.trim()) || inputString.trim() == listName)

                override fun canClose(inputString: String): Boolean = checkInput(inputString)
            }
        ) ?: return
        if (manager.renameList(listName, newName)) {
            refreshTreeOnly()
        } else {
            Messages.showErrorDialog(project, "重命名列表失败：名称不能为空且不能与现有列表重名", "重命名列表")
        }
    }

    private fun deleteSelectedList(scope: FavoriteScope, listName: String?) {
        if (listName == null) {
            Messages.showInfoMessage(project, "请先在树中选中一个列表", "删除列表")
            return
        }
        if (listName == Bookmark.DEFAULT_LIST_NAME) {
            Messages.showInfoMessage(project, "默认列表「$listName」不可删除", "删除列表")
            return
        }
        val manager = managerFor(scope)
        val count = manager.getBookmarksByList(listName).size
        val message = "确定要删除「${scope.label}」中的列表「$listName」吗？\n" +
            "其中的 $count 个收藏将并入默认列表「${Bookmark.DEFAULT_LIST_NAME}」。"
        val result = Messages.showYesNoDialog(project, message, "删除列表", Messages.getQuestionIcon())
        if (result != Messages.YES) return
        if (manager.deleteList(listName)) {
            afterDataChanged()
        }
    }

    // ==================== 排序（按选中列表所在作用域生效） ====================

    private fun applySortType(type: SortType) {
        val selectedList = bookmarksTree.getSelectedList()
        if (selectedList != null) {
            // 对选中列表生效（其所在作用域记忆 sortType）
            managerFor(selectedList.scope).setSortType(selectedList.name, type)
        } else {
            // 无选中上下文时应用到全部列表（两个作用域一起）
            FavoriteScope.entries.forEach { scope ->
                managerFor(scope).getLists().forEach { info ->
                    managerFor(scope).setSortType(info.name, type)
                }
            }
        }
        refreshTreeOnly()
    }

    private fun syncSortComboBox() {
        val selectedList = bookmarksTree.getSelectedList()
        // 无选中时回退显示项目作用域默认列表的排序（与旧行为一致）
        val scope = selectedList?.scope ?: FavoriteScope.PROJECT
        val listName = selectedList?.name ?: Bookmark.DEFAULT_LIST_NAME
        val sortType = managerFor(scope).getSortType(listName)
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

    /**
     * 收藏设置按钮（工具栏最后一个）：打开 Settings → Tools → Favorites
     */
    private inner class OpenFavoritesSettingsAction :
        AnAction("收藏设置", "打开收藏设置页（Settings → Tools → Favorites）", AllIcons.General.GearPlain) {
        override fun actionPerformed(e: AnActionEvent) {
            // 直接按 Configurable 类打开，避免按显示名匹配产生歧义
            ShowSettingsUtil.getInstance().showSettingsDialog(project, FavoritesConfigurable::class.java)
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
