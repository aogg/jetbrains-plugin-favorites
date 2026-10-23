package com.aogg.favorites.ui

import com.aogg.favorites.helper.BookmarkRefreshHelper
import com.aogg.favorites.model.Bookmark
import com.aogg.favorites.model.FavoriteScope
import com.aogg.favorites.model.FavoritesScopeResolver
import com.aogg.favorites.model.FavoritesTransferService
import com.aogg.favorites.model.MatchRule
import com.aogg.favorites.model.RuleMatchManager
import com.aogg.favorites.model.SortType
import com.aogg.favorites.settings.FavoritesConfigurable
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
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
 * 提供统一的收藏管理界面（1.3.0 PhpStorm 风格树 + 双作用域 + 1.4.0 规则匹配）：
 * - 顶部：SearchTextField 实时过滤（作用域/描述/文件名/路径/列表名/规则名/条目文本）+ 排序方式下拉（按列表记忆持久化，两作用域独立）
 * - 中部：ToolbarDecorator + 树（作用域→列表→文件系统风格树：虚拟目录链/文件/行收藏/文件夹懒加载 + 规则匹配第三根节点）
 * - 工具栏：添加收藏 / 编辑描述 / 删除 / 添加助记符 / 导入原生书签 / 全部展开 / 全部折叠 / 收藏设置 / 新建规则 / 刷新规则（最后两个为 1.4.0 新增）
 * - 打开工具窗口时后台自动全量刷新一次规则（不阻塞 UI）
 */
class BookmarksToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val toolWindowPanel = BookmarksToolWindowPanel(project)
        val content = ContentFactory.SERVICE.getInstance().createContent(toolWindowPanel, "", false)
        toolWindow.contentManager.addContent(content)

        // 打开工具窗口时后台自动全量刷新一次规则（不阻塞 UI；失败仅记日志不影响其它功能）
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            runCatching { RuleMatchManager.getInstance(project).refreshAll() }
                .onFailure { log.warn("打开工具窗口自动全刷规则失败", it) }
        }
    }

    companion object {
        private val log = Logger.getInstance(BookmarksToolWindowFactory::class.java)
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

            // 规则匹配（1.4.0 第三个根节点）
            override fun createRule() {
                createNewRule()
            }

            override fun refreshAllRules() {
                RuleMatchManager.getInstance(project).refreshAll()
            }

            override fun refreshRule(rule: MatchRule) {
                RuleMatchManager.getInstance(project).refreshRule(rule.id)
            }

            override fun editRule(rule: MatchRule) {
                editExistingRule(rule)
            }

            override fun toggleRuleEnabled(rule: MatchRule) {
                toggleRuleEnabledState(rule)
            }

            override fun deleteRule(rule: MatchRule) {
                deleteRuleWithConfirm(rule)
            }
        })

        // 搜索过滤（按作用域/描述/文件名/路径/列表名实时过滤，命中项重建文件系统骨架）
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

        // 工具栏（ToolbarDecorator 原生观感；1.4.0 新增「新建规则」「刷新规则」置于现有 extraAction 之后）
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
            .addExtraAction(CreateRuleAction())
            .addExtraAction(RefreshAllRulesAction())
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

    // ==================== 规则匹配操作（1.4.0 第三个根节点） ====================

    /**
     * 新建规则：编辑对话框（新建模式），OK 后持久化并立即后台执行一次
     */
    private fun createNewRule() {
        val dialog = RuleEditDialog(project, null)
        if (!dialog.showAndGet()) return
        val rule = dialog.buildRule() ?: return
        val manager = RuleMatchManager.getInstance(project)
        manager.addRule(rule)
        refreshTreeOnly()
        // 新建后立即后台执行一次，完成后自动刷新树
        manager.refreshRule(rule.id)
    }

    /**
     * 编辑规则：编辑对话框（编辑模式，回填既有值），OK 后更新并重新执行一次
     */
    private fun editExistingRule(rule: MatchRule) {
        val dialog = RuleEditDialog(project, rule)
        if (!dialog.showAndGet()) return
        val edited = dialog.buildRule() ?: return
        val manager = RuleMatchManager.getInstance(project)
        manager.updateRule(edited)
        refreshTreeOnly()
        manager.refreshRule(edited.id)
    }

    /**
     * 切换规则启用/禁用：禁用不参与全部刷新；重新启用时自动执行一次
     */
    private fun toggleRuleEnabledState(rule: MatchRule) {
        val manager = RuleMatchManager.getInstance(project)
        manager.updateRule(rule.copy(enabled = !rule.enabled))
        refreshTreeOnly()
        if (rule.enabled) {
            // 原来是禁用态 → 本次为启用，自动执行一次出结果
            manager.refreshRule(rule.id)
        }
    }

    /**
     * 删除规则（带确认框）：删除后结果缓存一并清除
     */
    private fun deleteRuleWithConfirm(rule: MatchRule) {
        val result = Messages.showYesNoDialog(
            project,
            "确定要删除规则「${rule.name}」吗？删除后其匹配结果一并清除。",
            "删除规则",
            Messages.getQuestionIcon()
        )
        if (result != Messages.YES) return
        RuleMatchManager.getInstance(project).removeRule(rule.id)
        refreshTreeOnly()
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

    /**
     * 新建规则按钮（1.4.0）：打开规则编辑对话框（新建模式）
     */
    private inner class CreateRuleAction :
        AnAction("新建规则", "新建一条匹配规则（正则/SQL/SSH，当前支持正则）", AllIcons.General.Add) {
        override fun actionPerformed(e: AnActionEvent) {
            createNewRule()
        }
    }

    /**
     * 刷新规则按钮（1.4.0）：并行刷新全部启用规则（后台执行，完成后自动刷新树）
     */
    private inner class RefreshAllRulesAction :
        AnAction("刷新规则", "并行刷新全部启用规则", AllIcons.Actions.Refresh) {
        override fun actionPerformed(e: AnActionEvent) {
            RuleMatchManager.getInstance(project).refreshAll()
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
