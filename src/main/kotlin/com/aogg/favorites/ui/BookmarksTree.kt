package com.aogg.favorites.ui

import com.aogg.favorites.helper.BookmarkIconHelper
import com.aogg.favorites.model.Bookmark
import com.aogg.favorites.model.FavoriteScope
import com.aogg.favorites.model.FavoritesScopeResolver
import com.aogg.favorites.model.ListInfo
import com.aogg.favorites.model.MatchRule
import com.aogg.favorites.model.RuleMatchManager
import com.aogg.favorites.rules.RuleItem
import com.aogg.favorites.rules.RuleStatus
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.TextRange
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
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeWillExpandListener
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
 * 目录节点用户对象（1.3.0 PhpStorm 风格树，列表节点之下的文件系统层级）：
 * - 虚拟目录：仅承载通向收藏项的路径，不懒加载真实内容
 * - 文件夹收藏目录：可展开，首次展开懒加载真实子文件/子文件夹（含未收藏项，深层逐级懒加载）
 * - 真实目录：从文件夹收藏展开出的未收藏真实子目录（继续逐级懒加载）
 * toString 同时作为 TreeSpeedSearch 的匹配文本
 *
 * @param scope 所属作用域
 * @param listName 所属列表名（懒加载时查找列表骨架的键组成部分）
 * @param path 目录完整路径（规范化：项目作用域=项目根相对路径；全局作用域=绝对路径；""=作用域基准目录）
 * @param name 目录显示名（路径最后一段）
 * @param bookmark 挂在该目录上的文件夹收藏（纯虚拟目录/真实目录为 null）
 * @param fromFs 是否来自真实文件系统展开（真实目录=true，展开时继续读文件系统）
 * @param missing 文件夹收藏目标不存在/不可读（缺失态：叶子节点+红叉图标）
 */
class DirNode(
    val scope: FavoriteScope,
    val listName: String,
    val path: String,
    val name: String,
    val bookmark: Bookmark? = null,
    val fromFs: Boolean = false,
    val missing: Boolean = false
) {
    /** 子节点是否已懒加载（骨架子项 + 文件夹收藏的真实子项） */
    var childrenLoaded: Boolean = false

    /** 展开时是否读真实文件系统（文件夹收藏目录/真实目录=true；纯虚拟目录仅骨架子项） */
    val fsBacked: Boolean
        get() = bookmark != null || fromFs

    override fun toString(): String = name
}

/**
 * 文件节点用户对象：文件收藏本体（FILE 收藏即节点本身），或仅承载行收藏的文件容器
 * 同一路径同时存在 FILE 与 LINE 收藏时合并为一个文件节点（LINE 收藏为其子节点）
 * toString 同时作为 TreeSpeedSearch 的匹配文本
 *
 * @param scope 所属作用域
 * @param listName 所属列表名
 * @param path 文件完整路径（规范化同 [DirNode.path]）
 * @param name 文件名（显示主文本）
 * @param fileBookmark 文件收藏（仅承载行收藏时为 null）
 * @param lineBookmarks 挂在该文件下的行收藏（保持列表排序出现顺序）
 */
class FileNode(
    val scope: FavoriteScope,
    val listName: String,
    val path: String,
    val name: String,
    val fileBookmark: Bookmark?,
    val lineBookmarks: List<Bookmark>
) {
    override fun toString(): String = buildString {
        val mnemonic = fileBookmark?.mnemonic
        if (mnemonic != null) {
            append(BookmarkIconHelper.getMnemonicText(mnemonic)).append(' ')
        }
        append(name)
    }
}

/**
 * 行收藏节点用户对象（文件节点的子节点）
 * toString 同时作为 TreeSpeedSearch 的匹配文本
 *
 * @param scope 所属作用域
 * @param listName 所属列表名
 * @param bookmark 行收藏
 * @param contentText 所在行文本（超长已截断；读取失败为 null，显示时回退收藏描述）
 */
class LineEntryNode(
    val scope: FavoriteScope,
    val listName: String,
    val bookmark: Bookmark,
    val contentText: String?
) {
    /**
     * 灰色说明文本：行内容优先（超长已在构建时截断），读取失败回退收藏描述
     */
    fun grayText(): String = contentText ?: bookmark.description?.takeIf { it.isNotBlank() } ?: ""

    override fun toString(): String = buildString {
        val mnemonic = bookmark.mnemonic
        if (mnemonic != null) {
            append(BookmarkIconHelper.getMnemonicText(mnemonic)).append(' ')
        }
        append("${bookmark.lineNumber + 1}: ${grayText()}")
    }
}

/**
 * 未收藏真实文件节点用户对象（文件夹收藏展开出的真实子文件，收藏类操作对其不可用）
 * toString 同时作为 TreeSpeedSearch 的匹配文本
 *
 * @param virtualFile 真实虚拟文件
 */
class RealFileNode(val virtualFile: VirtualFile) {
    override fun toString(): String = virtualFile.name
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

/** 规则条目单行显示最大字符数（超长截断加省略号） */
private const val RULE_ITEM_MAX_TEXT_LENGTH = 120

/**
 * 规则条目文本单行截断（超长加省略号）
 */
private fun truncateRuleItemText(text: String): String =
    if (text.length > RULE_ITEM_MAX_TEXT_LENGTH) text.take(RULE_ITEM_MAX_TEXT_LENGTH) + "…" else text

/**
 * 规则匹配根节点用户对象（树第一层第三个根节点，排在全局收藏之后）
 *
 * @param enabledRuleCount 启用中的规则数（徽标展示）
 * @param totalItemCount 全部规则 OK 结果条目总数（徽标展示）
 */
data class RulesRootNode(
    val enabledRuleCount: Int,
    val totalItemCount: Int
) {
    override fun toString(): String = "规则匹配 ($enabledRuleCount 规则/$totalItemCount 条)"
}

/**
 * 规则节点用户对象（规则匹配根节点的子节点）
 * toString 同时作为 TreeSpeedSearch 的匹配文本
 *
 * @param rule 规则
 * @param status 最近一次执行状态（未执行过为 null，主文本仅显示规则名）
 * @param count OK 状态的结果条目数（无结果时照常显示 0）
 * @param error ERROR 状态的失败原因（渲染器取其设置节点 tooltip）
 */
data class RuleNode(
    val rule: MatchRule,
    val status: RuleStatus?,
    val count: Int,
    val error: String? = null
) {
    override fun toString(): String = buildString {
        append(rule.name)
        when (status) {
            RuleStatus.LOADING -> append(" ⏳ 执行中")
            RuleStatus.OK -> append(" ✅ $count")
            RuleStatus.ERROR -> append(" ❌")
            null -> {}
        }
        if (!rule.enabled) append("（已禁用）")
    }
}

/**
 * 规则结果条目节点用户对象（规则节点的子节点）
 * toString 同时作为 TreeSpeedSearch 的匹配文本
 *
 * @param ruleId 所属规则 id（预留：后续按规则维度操作条目时使用）
 * @param item 结果条目（FileItem 可跳转；RowItem 带 path 可跳转；LineItem 仅展示）
 */
class RuleItemNode(val ruleId: String, val item: RuleItem) {

    /**
     * 是否可跳转：FileItem 恒可；RowItem 带 path 可；LineItem 不可
     */
    val jumpable: Boolean
        get() = when (item) {
            is RuleItem.FileItem -> item.path.isNotBlank()
            is RuleItem.RowItem -> !item.path.isNullOrBlank()
            is RuleItem.LineItem -> false
        }

    override fun toString(): String = when (item) {
        is RuleItem.FileItem -> item.path.substringAfterLast('/')
        is RuleItem.RowItem -> truncateRuleItemText(item.cells.joinToString("  "))
        is RuleItem.LineItem -> truncateRuleItemText(item.text)
    }

    /**
     * 详情文本（双击/右键「查看详情」信息框展示完整内容）
     */
    fun detailText(): String = when (item) {
        is RuleItem.FileItem -> item.path
        is RuleItem.RowItem -> buildString {
            append(item.cells.joinToString("\t"))
            if (!item.path.isNullOrBlank()) {
                append("\n路径: ").append(item.path)
            }
        }
        is RuleItem.LineItem -> item.text
    }
}

/**
 * 收藏树组件（1.3.0 PhpStorm 风格树状视图）：
 * 第一层=作用域（项目收藏/全局收藏，始终显示+总数徽标），第二层=列表，
 * 第二层之下按文件系统层级组织：
 * - 每个列表按收藏路径计算公共祖先目录，自上而下生成虚拟目录链（仅承载通向收藏项的路径）
 * - 行收藏(LINE)挂在其所在文件的文件节点下（同文件共用一个文件节点，行节点="行号: 行内容"）
 * - 文件收藏(FILE)即文件节点本体；同路径 FILE+LINE 收藏合并为一个文件节点
 * - 文件夹收藏(FOLDER)为虚拟目录链上带收藏标记的目录节点，首次展开懒加载真实子文件/子文件夹
 *   （含未收藏项，深层逐级懒加载）；目录不存在/不可读显示缺失态（红叉）
 *
 * 规则匹配（1.4.0）：第一层追加第三个根节点「规则匹配」（排在全局收藏之后，始终显示）：
 * - 规则匹配根 → 规则节点（名称+状态后缀 ✅ M / ⏳ 执行中 / ❌，ERROR tooltip=失败原因）→ 结果条目节点
 * - 条目：FileItem 双击跳转（文件打开/目录 Project 视图选中）；RowItem/LineItem 双击弹信息框显示完整内容
 * - 右键菜单：规则根=新建规则/全部刷新；规则=刷新此规则/编辑规则/启用禁用/删除规则；条目=跳转（仅可跳转的）
 * - 搜索过滤：规则名与条目文本参与匹配（规则名命中放行全部条目，否则仅保留命中条目）
 *
 * 交互：
 * - Enter / 双击 = 跳转（行→编辑器定位；文件→打开；文件夹→Project 视图选中；未收藏真实节点同样可跳转）
 * - F2 = 编辑描述、Del = 删除（通过 [Interactions] 回调到面板实现；未收藏真实节点收藏类操作不可用）
 * - 右键菜单：跳转/编辑描述/设置助记符/移动到列表(按作用域分组)/列表CRUD/删除收藏 + 规则匹配菜单
 * - 选中行收藏时预览打开文件但不抢焦点（openFile(file, false)）
 * - 装 TreeSpeedSearch（TreeUIHelper.installTreeSpeedSearch），各节点 toString 均参与匹配
 * - 搜索过滤时作用域名/列表名/文件名/路径/描述/规则名/条目文本均参与匹配，过滤后虚拟目录链按命中项重建
 * - 刷新（含过滤）保留已展开目录的展开状态；作用域/列表/规则根/规则层级始终展开
 */
class BookmarksTree(
    private val project: Project,
    private val interactions: Interactions
) : Tree() {

    /**
     * 面板侧交互回调（带作用域：列表操作作用于所在作用域；规则操作回调到面板弹对话框/驱动管理器）
     */
    interface Interactions {
        fun editSelectedBookmark()
        fun deleteSelected()
        fun setMnemonicForSelected()
        fun moveSelectedTo(scope: FavoriteScope, listName: String)
        fun createList(scope: FavoriteScope)
        fun renameList(scope: FavoriteScope, listName: String?)
        fun deleteList(scope: FavoriteScope, listName: String?)

        // 规则匹配（1.4.0 第三个根节点）
        fun createRule()
        fun refreshAllRules()
        fun refreshRule(rule: MatchRule)
        fun editRule(rule: MatchRule)
        fun toggleRuleEnabled(rule: MatchRule)
        fun deleteRule(rule: MatchRule)
    }

    companion object {
        private val log = Logger.getInstance(BookmarksTree::class.java)

        private const val ACTION_KEY_JUMP = "favorites.jump"
        private const val ACTION_KEY_EDIT = "favorites.edit"
        private const val ACTION_KEY_DELETE = "favorites.delete"

        /** 行内容显示最大字符数（超长截断加省略号） */
        private const val MAX_LINE_TEXT_LENGTH = 120

        /** 读取行内容时的原始取样长度（先取样再压缩空白并按显示上限截断） */
        private const val LINE_READ_SAMPLE_LENGTH = 500

        /** 全部展开时的行数安全上限（防止在超大真实目录树上无限懒加载） */
        private const val MAX_EXPAND_ROWS = 10000
    }

    private var filterText: String = ""

    /** 当前各列表的文件系统骨架（key = spineKey(scope, listName)，refresh 时重建） */
    private var listSpines: Map<String, FavoritesListSpine> = emptyMap()

    init {
        isRootVisible = false
        showsRootHandles = true
        model = DefaultTreeModel(DefaultMutableTreeNode("root"))
        cellRenderer = BookmarksTreeCellRenderer(project)

        // 速度搜索（按节点文本匹配，各层节点 toString 均参与）
        TreeUIHelper.getInstance().installTreeSpeedSearch(this)

        // 双击跳转 + 右键菜单
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    navigateToSelection()
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

        // 目录懒加载：首次展开时物化骨架子项，文件夹收藏/真实目录再合并真实文件系统子项
        addTreeWillExpandListener(object : TreeWillExpandListener {
            override fun treeWillExpand(event: TreeExpansionEvent) {
                // ////////////////  核心逻辑：懒加载目录子项  start  //////////////////////
                val treeNode = event.path.lastPathComponent as? DefaultMutableTreeNode ?: return
                val dir = treeNode.userObject as? DirNode ?: return
                if (!dir.childrenLoaded) {
                    loadDirChildren(treeNode, dir)
                }
                // ////////////////  核心逻辑  end  ///////////////////////////////////////
            }

            override fun treeWillCollapse(event: TreeExpansionEvent) {
                // 折叠无需处理（已加载子项保留，避免重复懒加载）
            }
        })

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
     * 重建树模型（作用域 → 列表 → 文件系统风格树：公共祖先虚拟目录链/文件/行收藏/文件夹懒加载）
     *
     * @param filter 过滤关键字（匹配作用域名/列表名/描述/文件名/路径，空串表示不过滤；过滤后虚拟目录链按命中项重建）
     */
    fun refresh(filter: String = filterText) {
        filterText = filter
        val query = filterText.trim().lowercase(Locale.ROOT)

        // ////////////////  准备数据：记录刷新前已展开的目录键（刷新后恢复展开）  start  //
        val expandedDirKeys = captureExpandedDirKeys()
        // ////////////////  准备数据  end  ////////////////////////////////////////////

        val root = DefaultMutableTreeNode("root")
        val spines = HashMap<String, FavoritesListSpine>()

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

                // ////////////////  第三层起：文件系统风格树（按命中收藏重建骨架）  start  //
                val spine = FavoritesListSpine.build(scope, listInfo.name, visibleBookmarks)
                spines[spineKey(scope, listInfo.name)] = spine
                if (spine.commonAncestorPath.isEmpty()) {
                    // ////////////////  基准目录自身带文件夹收藏（如收藏了项目根）时物化为顶层节点  //
                    val baseBookmark = spine.dirBookmarkAt("")
                    if (baseBookmark != null) {
                        val baseName = if (scope == FavoriteScope.GLOBAL) {
                            "/"
                        } else {
                            project.basePath?.substringAfterLast('/') ?: "."
                        }
                        val baseDir = DirNode(
                            scope = scope,
                            listName = listInfo.name,
                            path = "",
                            name = baseName,
                            bookmark = baseBookmark,
                            fromFs = false,
                            missing = !isReadableDirectory(resolveNodePath(scope, ""))
                        )
                        val baseTreeNode = DefaultMutableTreeNode(baseDir, !baseDir.missing)
                        appendSpineChildren(baseTreeNode, scope, listInfo.name, spine, "")
                        listNode.add(baseTreeNode)
                    } else {
                        // 公共祖先即作用域基准目录（项目根/文件系统根）：骨架子项直接挂列表节点
                        appendSpineChildren(listNode, scope, listInfo.name, spine, "")
                    }
                    // ////////////////  基准目录  end  /////////////////////////////////////
                } else {
                    // 公共祖先目录自身作为顶层目录节点（保证文件始终有上级文件夹层级）
                    val commonBookmark = spine.dirBookmarkAt(spine.commonAncestorPath)
                    val commonDir = DirNode(
                        scope = scope,
                        listName = listInfo.name,
                        path = spine.commonAncestorPath,
                        name = spine.commonAncestorPath.substringAfterLast('/'),
                        bookmark = commonBookmark,
                        fromFs = false,
                        missing = commonBookmark != null &&
                            !isReadableDirectory(resolveNodePath(scope, spine.commonAncestorPath))
                    )
                    // 缺失态目录不挂子节点（叶子+红叉）
                    val commonTreeNode = DefaultMutableTreeNode(commonDir, !commonDir.missing)
                    appendSpineChildren(commonTreeNode, scope, listInfo.name, spine, spine.commonAncestorPath)
                    listNode.add(commonTreeNode)
                }
                // ////////////////  第三层起  end  ///////////////////////////////////////

                scopeNode.add(listNode)
            }
            // ////////////////  第二层  end  ///////////////////////////////////////////

            root.add(scopeNode)
        }
        // ////////////////  第一层  end  ////////////////////////////////////////////////

        // ////////////////  第一层附加：规则匹配根节点（第三个根，排在全局收藏之后）  start //
        root.add(buildRulesSubtree(query))
        // ////////////////  第一层附加  end  ///////////////////////////////////////////

        // ////////////////  逻辑后：替换模型并恢复展开态  start  /////////////////////////
        listSpines = spines
        model = DefaultTreeModel(root)
        for (scopeChild in root.children()) {
            restoreExpansion(scopeChild as DefaultMutableTreeNode, expandedDirKeys)
        }
        // ////////////////  逻辑后  end  ///////////////////////////////////////////////
    }

    /**
     * 构建「规则匹配」子树（第三个根节点）：规则匹配根 → 规则节点（名称+状态后缀）→ 结果条目节点
     * 管理器/存储异常时兜底渲染空根节点，不影响收藏树其它部分。
     *
     * @param query 过滤关键字（小写；空串表示不过滤）
     * @return 规则匹配根树节点（始终非 null）
     */
    private fun buildRulesSubtree(query: String): DefaultMutableTreeNode {
        // ////////////////  准备数据：规则与结果缓存（平台调用兜底）  start  ///////////////
        val manager = runCatching { RuleMatchManager.getInstance(project) }.getOrNull()
        val rules = manager?.getRules().orEmpty()
        val enabledRuleCount = rules.count { it.enabled }
        val totalItemCount = rules.sumOf { rule ->
            val result = manager?.getResult(rule.id)
            if (result?.status == RuleStatus.OK) result.items.size else 0
        }
        // ////////////////  准备数据  end  ////////////////////////////////////////////

        // ////////////////  核心逻辑：规则节点 + 条目节点（应用过滤）  start  //////////////
        val rootNode = DefaultMutableTreeNode(RulesRootNode(enabledRuleCount, totalItemCount))
        if (manager == null) {
            return rootNode
        }

        for (rule in rules) {
            val result = manager.getResult(rule.id)
            val items = result?.items ?: emptyList()
            // 规则名命中过滤词时放行全部条目；否则仅保留文本命中的条目（不过滤时全部显示）
            val ruleMatches = query.isEmpty() || rule.name.lowercase(Locale.ROOT).contains(query)
            val visibleItems = if (query.isEmpty() || ruleMatches) {
                items
            } else {
                items.filter { ruleItemMatches(it, query) }
            }
            if (query.isNotEmpty() && !ruleMatches && visibleItems.isEmpty()) continue

            val ruleTreeNode = DefaultMutableTreeNode(
                RuleNode(
                    rule = rule,
                    status = result?.status,
                    count = items.size,
                    error = result?.error
                )
            )
            for (item in visibleItems) {
                ruleTreeNode.add(DefaultMutableTreeNode(RuleItemNode(rule.id, item)))
            }
            rootNode.add(ruleTreeNode)
        }
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////

        // ////////////////  返回  start  //////////////////////////////////////////////
        return rootNode
        // ////////////////  返回  end  ///////////////////////////////////////////////
    }

    /**
     * 规则结果条目是否命中过滤词（条目完整文本，含路径/各列/行文本）
     */
    private fun ruleItemMatches(item: RuleItem, query: String): Boolean =
        item.searchText().lowercase(Locale.ROOT).contains(query)

    /**
     * 全部展开（懒加载目录展开后行数会增加，循环展开直到行数稳定或达安全上限）
     */
    fun expandAllRows() {
        // ////////////////  核心逻辑：逐行展开直至行数稳定/达上限  start  ////////////////
        var previousRowCount = -1
        while (rowCount != previousRowCount && rowCount < MAX_EXPAND_ROWS) {
            previousRowCount = rowCount
            for (i in 0 until rowCount) {
                expandRow(i)
            }
        }
        if (rowCount >= MAX_EXPAND_ROWS) {
            log.info("收藏树全部展开达到行数上限 $MAX_EXPAND_ROWS，停止继续展开")
        }
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////
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
     * 获取选中的收藏（多选，带作用域）：
     * 仅行收藏/文件收藏/文件夹收藏节点返回对应 Bookmark；
     * 纯虚拟目录、真实未收藏文件节点返回空（收藏类操作对其不可用）
     */
    fun getSelectedBookmarks(): List<ScopedBookmark> {
        return selectionPaths
            ?.mapNotNull { path -> bookmarkOfNode(path.lastPathComponent) }
            ?: emptyList()
    }

    /**
     * 树节点 → 带作用域收藏（非收藏节点返回 null）：
     * 行收藏节点/文件收藏节点/文件夹收藏节点分别返回对应 Bookmark
     *
     * @param component 树节点（lastPathComponent）
     * @return 带作用域收藏或 null
     */
    private fun bookmarkOfNode(component: Any?): ScopedBookmark? {
        val userObject = (component as? DefaultMutableTreeNode)?.userObject ?: return null
        return when (userObject) {
            is LineEntryNode -> ScopedBookmark(userObject.scope, userObject.bookmark)
            is FileNode -> userObject.fileBookmark?.let { ScopedBookmark(userObject.scope, it) }
            is DirNode -> userObject.bookmark?.let { ScopedBookmark(userObject.scope, it) }
            else -> null
        }
    }

    /**
     * 获取当前选中上下文对应的列表（带作用域）：
     * 选中列表节点→该列表；选中带收藏的节点→其所属列表；
     * 纯虚拟目录/真实未收藏节点→null（避免 Del 等列表操作被误触发）
     */
    fun getSelectedList(): ScopedList? {
        // ////////////////  校验参数：仅列表节点或带收藏节点向上归属列表  start  ///////////
        val node = selectionPath?.lastPathComponent as? DefaultMutableTreeNode ?: return null
        if (node.userObject !is BookmarkListNode && bookmarkOfNode(node) == null) return null
        // ////////////////  校验参数  end  ////////////////////////////////////////////

        // ////////////////  核心逻辑：沿父链找最近的列表节点  start  /////////////////////
        var cursor: DefaultMutableTreeNode? = node
        while (cursor != null) {
            val userObject = cursor.userObject
            if (userObject is BookmarkListNode) {
                return ScopedList(userObject.scope, userObject.info.name)
            }
            cursor = cursor.parent as? DefaultMutableTreeNode
        }
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////
        return null
    }

    /**
     * 获取当前选中上下文的作用域（任意层级节点均可沿父链推断，供列表CRUD等按作用域路由）
     */
    fun getSelectedScope(): FavoriteScope? {
        val node = selectionPath?.lastPathComponent as? DefaultMutableTreeNode ?: return null
        var cursor: DefaultMutableTreeNode? = node
        while (cursor != null) {
            val userObject = cursor.userObject
            if (userObject is ScopeNode) return userObject.scope
            if (userObject is BookmarkListNode) return userObject.scope
            cursor = cursor.parent as? DefaultMutableTreeNode
        }
        return null
    }

    /**
     * 跳转当前选中：收藏节点→[navigateToBookmark]；
     * 未收藏真实/容器节点→打开文件（RealFileNode/仅行收藏的文件容器）或 Project 视图选中目录
     */
    fun navigateToSelection() {
        // ////////////////  分支一：收藏节点按收藏跳转  start  ///////////////////////////
        getSelectedBookmarks().firstOrNull()?.let {
            navigateToBookmark(it.bookmark)
            return
        }
        // ////////////////  分支一  end  //////////////////////////////////////////////

        // ////////////////  分支二：未收藏真实/容器节点尽力跳转  start  //////////////////
        val node = selectionPath?.lastPathComponent as? DefaultMutableTreeNode ?: return
        when (val userObject = node.userObject) {
            is RealFileNode -> openFileNode(userObject.virtualFile)
            is FileNode -> {
                // 仅承载行收藏的文件容器：优先定位到第一条行收藏，否则打开文件
                val firstLine = userObject.lineBookmarks.firstOrNull()
                if (firstLine != null) {
                    navigateToBookmark(firstLine)
                } else {
                    resolveNodePath(userObject.scope, userObject.path)?.let { openFileNode(it) }
                }
            }
            is DirNode -> {
                val dirFile = resolveNodePath(userObject.scope, userObject.path)
                if (isReadableDirectory(dirFile)) {
                    selectInProjectView(dirFile!!)
                }
            }
            is RuleItemNode -> activateRuleItem(userObject)
            else -> {}
        }
        // ////////////////  分支二  end  //////////////////////////////////////////////
    }

    /**
     * 激活规则结果条目（双击/Enter）：
     * FileItem → 跳转（文件打开/目录 Project 视图选中）；RowItem/LineItem → 弹信息框显示完整内容
     *
     * @param node 规则条目节点用户对象
     */
    private fun activateRuleItem(node: RuleItemNode) {
        // ////////////////  核心逻辑：按条目类型分流  start  /////////////////////////////
        when (val item = node.item) {
            is RuleItem.FileItem -> jumpToRuleItemPath(item.path, item.isFolder)
            is RuleItem.RowItem -> showRuleItemInfoDialog(node)
            is RuleItem.LineItem -> showRuleItemInfoDialog(node)
        }
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////
    }

    /**
     * 跳转到规则结果条目路径（相对项目根为主，兼容绝对路径；目录→Project 视图选中，文件→打开）
     *
     * @param path 条目路径
     * @param isFolder 条目是否为目录（路径不存在时按该标记决定展示方式）
     */
    private fun jumpToRuleItemPath(path: String, isFolder: Boolean) {
        // ////////////////  校验参数  start  ///////////////////////////////////////////
        if (path.isBlank()) return
        // ////////////////  校验参数  end  /////////////////////////////////////////////

        // ////////////////  核心逻辑：解析 VirtualFile 后按类型跳转  start  ///////////////
        val file = resolveByAbsolutePath(path, FavoritesTreePaths.isAbsolutePath(path)) ?: return
        if (!file.isValid) return
        if (isFolder || file.isDirectory) {
            selectInProjectView(file)
        } else {
            openFileNode(file)
        }
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////
    }

    /**
     * 弹信息框显示规则条目完整内容（RowItem 多列 tab 拼接 + 可选路径行；LineItem 完整行文本）
     */
    private fun showRuleItemInfoDialog(node: RuleItemNode) {
        runCatching {
            Messages.showInfoMessage(project, node.detailText(), "规则结果详情")
        }.onFailure { log.warn("显示规则条目详情失败", it) }
    }

    /**
     * 跳转到收藏位置（行→编辑器定位；文件→打开；文件夹→Project 视图选中）
     * 兼容两种作用域：项目收藏相对路径拼项目根；全局收藏绝对路径直用
     */
    fun navigateToBookmark(bookmark: Bookmark) {
        val file = resolveVirtualFile(bookmark) ?: return
        if (!file.exists()) {
            return
        }

        when {
            bookmark.isFolderBookmark() -> {
                // 选中到 Project 视图（不抢焦点）
                selectInProjectView(file)
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
     * 右键菜单（列表操作按选中上下文的作用域执行；未收藏真实节点仅提供跳转，收藏类操作不可用；
     * 规则匹配子树提供独立菜单：规则根=新建/全刷、规则=刷新/编辑/启禁/删除、条目=跳转/查看详情）
     */
    private fun showPopupMenu(e: MouseEvent) {
        val path = getPathForLocation(e.x, e.y) ?: return
        selectionPath = path

        // ////////////////  分支零：规则匹配子树独立右键菜单  start  /////////////////////
        val clickedUserObject = (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject
        if (clickedUserObject is RulesRootNode || clickedUserObject is RuleNode || clickedUserObject is RuleItemNode) {
            val ruleGroup = DefaultActionGroup()
            buildRulePopupMenu(ruleGroup, clickedUserObject)
            val rulePopup = ActionManager.getInstance()
                .createActionPopupMenu("FavoritesToolWindowPopupMenu", ruleGroup)
            rulePopup.component.show(this, e.x, e.y)
            return
        }
        // ////////////////  分支零  end  //////////////////////////////////////////////

        val group = DefaultActionGroup()
        val selectedBookmarks = getSelectedBookmarks()
        val selectedList = getSelectedList()
        // 列表操作作用域：优先列表节点自身 → 收藏所属列表 → 选中上下文作用域，兜底项目收藏
        val listScope = selectedList?.scope
            ?: selectedBookmarks.firstOrNull()?.scope
            ?: getSelectedScope()
            ?: FavoriteScope.PROJECT

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
        } else {
            // ////////////////  未收藏真实/容器节点：仅提供跳转  start  ///////////////////
            val userObject = (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject
            if (userObject is RealFileNode || userObject is FileNode || userObject is DirNode) {
                group.add(object : AnAction("跳转", "打开文件或在 Project 视图中选中目录", null) {
                    override fun actionPerformed(e: AnActionEvent) {
                        navigateToSelection()
                    }
                })
            }
            // ////////////////  未收藏真实/容器节点  end  /////////////////////////////////
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
     * 构建"移动到列表▸"子菜单（按作用域分组展示所有列表，当前所在列表置灰）
     *
     * - 同作用域列表：普通列表移动；
     * - 跨作用域列表：调用转移 API（路径转换+助记符降级规则由 FavoritesTransferService 处理）。
     */
    private fun buildMoveToListGroup(selectedBookmarks: List<ScopedBookmark>): DefaultActionGroup {
        val group = DefaultActionGroup("移动到列表", true)
        val current = selectedBookmarks.firstOrNull()

        // ////////////////  按作用域分组：每组一个子菜单  start  /////////////////////////
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
        // ////////////////  按作用域分组  end  /////////////////////////////////////////
        return group
    }

    /**
     * 构建「规则匹配」子树右键菜单：
     * - 规则根节点：新建规则 / 全部刷新
     * - 规则节点：刷新此规则 / 编辑规则 / 启用|禁用（切换）/ 删除规则
     * - 条目节点：跳转（仅可跳转的）+ 查看详情（RowItem/LineItem）
     *
     * @param group 菜单挂载的动作组
     * @param userObject 点击节点的用户对象（RulesRootNode/RuleNode/RuleItemNode）
     */
    private fun buildRulePopupMenu(group: DefaultActionGroup, userObject: Any) {
        // ////////////////  分支一：规则根节点  start  //////////////////////////////////
        if (userObject is RulesRootNode) {
            group.add(object : AnAction("新建规则", "新建一条匹配规则", null) {
                override fun actionPerformed(e: AnActionEvent) {
                    interactions.createRule()
                }
            })
            group.add(object : AnAction("全部刷新", "并行刷新全部启用规则", null) {
                override fun actionPerformed(e: AnActionEvent) {
                    interactions.refreshAllRules()
                }
            })
            return
        }
        // ////////////////  分支一  end  //////////////////////////////////////////////

        // ////////////////  分支二：规则节点  start  ////////////////////////////////////
        if (userObject is RuleNode) {
            val rule = userObject.rule
            group.add(object : AnAction("刷新此规则", "重新执行该规则并刷新结果", null) {
                override fun actionPerformed(e: AnActionEvent) {
                    interactions.refreshRule(rule)
                }
            })
            group.add(object : AnAction("编辑规则", "编辑该规则的名称与参数", null) {
                override fun actionPerformed(e: AnActionEvent) {
                    interactions.editRule(rule)
                }
            })
            group.add(object : AnAction(
                if (rule.enabled) "禁用" else "启用",
                if (rule.enabled) "禁用后不参与全部刷新" else "启用后参与全部刷新",
                null
            ) {
                override fun actionPerformed(e: AnActionEvent) {
                    interactions.toggleRuleEnabled(rule)
                }
            })
            group.add(Separator.getInstance())
            group.add(object : AnAction("删除规则", "删除该规则（结果一并清除）", null) {
                override fun actionPerformed(e: AnActionEvent) {
                    interactions.deleteRule(rule)
                }
            })
            return
        }
        // ////////////////  分支二  end  //////////////////////////////////////////////

        // ////////////////  分支三：条目节点（跳转仅可跳转的）  start  /////////////////////
        if (userObject is RuleItemNode) {
            if (userObject.jumpable) {
                group.add(object : AnAction("跳转", "打开文件或在 Project 视图中选中目录", null) {
                    override fun actionPerformed(e: AnActionEvent) {
                        navigateToSelection()
                    }
                })
            }
            if (userObject.item !is RuleItem.FileItem) {
                group.add(object : AnAction("查看详情", "显示条目完整内容", null) {
                    override fun actionPerformed(e: AnActionEvent) {
                        showRuleItemInfoDialog(userObject)
                    }
                })
            }
        }
        // ////////////////  分支三  end  //////////////////////////////////////////////
    }

    private fun registerKeyStrokes() {
        val inputMap = getInputMap(JComponent.WHEN_FOCUSED)
        val actionMap = getActionMap()

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), ACTION_KEY_JUMP)
        actionMap.put(ACTION_KEY_JUMP, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                navigateToSelection()
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
     * 选中行收藏时预览打开文件但不抢焦点（仅单选行收藏节点生效）
     */
    private fun previewSelectedLineBookmark() {
        // ////////////////  校验参数：仅单选行收藏节点  start  ///////////////////////////
        val node = selectionPath?.lastPathComponent as? DefaultMutableTreeNode ?: return
        val line = node.userObject as? LineEntryNode ?: return
        // ////////////////  校验参数  end  ////////////////////////////////////////////

        // ////////////////  核心逻辑：预览打开（不抢焦点）  start  ///////////////////////
        val file = resolveVirtualFile(line.bookmark) ?: return
        try {
            FileEditorManager.getInstance(project).openFile(file, false)
        } catch (e: Exception) {
            log.warn("预览打开文件失败：${line.bookmark.filePath}", e)
        }
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////
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
    private fun resolveVirtualFile(bookmark: Bookmark): VirtualFile? =
        resolveByAbsolutePath(bookmark.filePath, FavoritesTreePaths.isAbsolutePath(bookmark.filePath))

    /**
     * 解析节点路径对应的虚拟文件（节点路径已规范化：项目作用域=项目根相对；全局作用域=绝对）
     *
     * @param scope 节点所属作用域
     * @param path 规范化路径（""=作用域基准目录）
     * @return 虚拟文件或 null（不存在/离线）
     */
    private fun resolveNodePath(scope: FavoriteScope, path: String): VirtualFile? =
        resolveByAbsolutePath(path, absolute = scope == FavoriteScope.GLOBAL)

    /**
     * 按路径与绝对性解析虚拟文件（统一入口，供收藏/节点两类解析复用）
     *
     * @param path 路径字符串
     * @param absolute 是否绝对路径语义
     * @return 虚拟文件或 null
     */
    private fun resolveByAbsolutePath(path: String, absolute: Boolean): VirtualFile? {
        // ////////////////  准备数据：换算绝对路径  start  //////////////////////////////
        val absolutePath = when {
            absolute -> if (path.isEmpty()) "/" else path
            else -> project.basePath?.let { basePath ->
                if (path.isEmpty()) basePath else File(basePath, path).absolutePath
            } ?: return null
        }
        // ////////////////  准备数据  end  ////////////////////////////////////////////

        // ////////////////  返回：查 VFS 缓存（findFileByPath 需正斜杠风格路径）  start  //
        return LocalFileSystem.getInstance().findFileByPath(absolutePath.replace('\\', '/'))
        // ////////////////  返回  end  ////////////////////////////////////////////////
    }

    /**
     * 路径是否解析为可读目录（文件夹收藏展开能力判定）
     */
    private fun isReadableDirectory(file: VirtualFile?): Boolean =
        file != null && file.isValid && file.isDirectory

    /**
     * 将骨架目录下的子项物化为树节点（目录条目在前、文件条目在后，保持收藏排序出现顺序）
     *
     * @param parentTreeNode 挂载的树节点
     * @param scope 作用域
     * @param listName 列表名
     * @param spine 列表文件系统骨架
     * @param dirPath 目录路径键（""=基准目录）
     */
    private fun appendSpineChildren(
        parentTreeNode: DefaultMutableTreeNode,
        scope: FavoriteScope,
        listName: String,
        spine: FavoritesListSpine,
        dirPath: String
    ) {
        // ////////////////  核心逻辑：目录条目→目录节点、文件条目→文件节点(+行子节点)  start
        for (entry in spine.childrenOf(dirPath)) {
            when (entry) {
                is SpineDirEntry -> {
                    val bookmark = entry.bookmark
                    // 文件夹收藏目标缺失/不可读 → 缺失态（叶子节点，渲染器呈现红叉）
                    val missing = bookmark != null &&
                        !isReadableDirectory(resolveNodePath(scope, entry.path))
                    parentTreeNode.add(
                        DefaultMutableTreeNode(
                            DirNode(
                                scope = scope,
                                listName = listName,
                                path = entry.path,
                                name = entry.name,
                                bookmark = bookmark,
                                fromFs = false,
                                missing = missing
                            ),
                            !missing
                        )
                    )
                }

                is SpineFileEntry -> parentTreeNode.add(buildFileTreeNode(scope, listName, entry))
            }
        }
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////
    }

    /**
     * 文件条目 → 文件节点树节点（FILE 收藏即节点本身；行收藏物化为其子节点）
     *
     * @param scope 作用域
     * @param listName 列表名
     * @param entry 骨架文件条目（FILE/LINE 收藏已合并）
     * @return 文件节点树节点
     */
    private fun buildFileTreeNode(scope: FavoriteScope, listName: String, entry: SpineFileEntry): DefaultMutableTreeNode {
        val fileNode = FileNode(
            scope = scope,
            listName = listName,
            path = entry.path,
            name = entry.name,
            fileBookmark = entry.fileBookmark,
            lineBookmarks = entry.lineBookmarks.toList()
        )
        val treeNode = DefaultMutableTreeNode(fileNode)

        // ////////////////  核心逻辑：行收藏子节点（"行号: 行内容"）  start  //////////////
        for (lineBookmark in entry.lineBookmarks) {
            treeNode.add(
                DefaultMutableTreeNode(
                    LineEntryNode(scope, listName, lineBookmark, readLineContent(lineBookmark))
                )
            )
        }
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////
        return treeNode
    }

    /**
     * 读取行收藏所在行的文本（压缩空白；超长按显示上限截断；任何异常兜底返回 null，显示时回退收藏描述）
     *
     * @param bookmark 行收藏
     * @return 行文本（空白行为 null）或 null（读取失败）
     */
    private fun readLineContent(bookmark: Bookmark): String? {
        // ////////////////  校验参数  start  ///////////////////////////////////////////
        if (!bookmark.isLineBookmark() || bookmark.lineNumber < 0) return null
        // ////////////////  校验参数  end  /////////////////////////////////////////////

        // ////////////////  核心逻辑：VirtualFile→Document→目标行文本  start  ////////////
        return try {
            val file = resolveVirtualFile(bookmark) ?: return null
            if (!file.isValid) return null
            val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
            if (bookmark.lineNumber >= document.lineCount) return null
            val lineStart = document.getLineStartOffset(bookmark.lineNumber)
            val lineEnd = minOf(document.getLineEndOffset(bookmark.lineNumber), lineStart + LINE_READ_SAMPLE_LENGTH)
            // 压缩空白保证树节点单行展示；超长截断加省略号
            val raw = document.getText(TextRange(lineStart, lineEnd)).replace(Regex("\\s+"), " ").trim()
            when {
                raw.isEmpty() -> null
                raw.length > MAX_LINE_TEXT_LENGTH -> raw.take(MAX_LINE_TEXT_LENGTH) + "…"
                else -> raw
            }
            // ////////////////  核心逻辑  end  ///////////////////////////////////////
        } catch (e: Exception) {
            // 异常兜底：返回 null，节点灰色文本回退用收藏描述
            log.warn("读取行收藏内容失败：${bookmark.filePath}:${bookmark.lineNumber + 1}", e)
            null
        }
    }

    /**
     * 懒加载目录节点子项：骨架子项（虚拟目录链/文件/行收藏）必建；
     * 文件夹收藏目录/真实目录再合并真实文件系统子项（收藏物化节点优先，同名真实子项跳过去重）
     *
     * @param treeNode 目录节点对应的树节点
     * @param dir 目录节点用户对象
     */
    private fun loadDirChildren(treeNode: DefaultMutableTreeNode, dir: DirNode) {
        // ////////////////  校验参数  start  ///////////////////////////////////////////
        if (dir.childrenLoaded) return
        val spine = listSpines[spineKey(dir.scope, dir.listName)]
        // ////////////////  校验参数  end  /////////////////////////////////////////////

        // ////////////////  核心逻辑一：骨架子项（虚拟目录链+文件/行收藏）  start  /////////
        if (spine != null) {
            appendSpineChildren(treeNode, dir.scope, dir.listName, spine, dir.path)
        }
        // ////////////////  核心逻辑一  end  ///////////////////////////////////////////

        // ////////////////  核心逻辑二：文件夹收藏/真实目录合并真实子项  start  ////////////
        if (dir.fsBacked) {
            val dirFile = resolveNodePath(dir.scope, dir.path)
            if (isReadableDirectory(dirFile)) {
                // 收藏骨架已有同名子项时跳过真实子项，避免重复展示
                val spineNames = spine?.childrenOf(dir.path)
                    ?.mapTo(HashSet()) { it.name }
                    ?: emptySet()
                val realChildren = dirFile!!.children ?: emptyArray<VirtualFile>()
                // 真实子项排序：目录在前、名称不区分大小写
                val sortedChildren = realChildren.sortedWith(
                    compareBy({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) })
                )
                for (child in sortedChildren) {
                    if (child.name in spineNames) continue
                    val childTreeNode = if (child.isDirectory) {
                        DefaultMutableTreeNode(
                            DirNode(
                                scope = dir.scope,
                                listName = dir.listName,
                                path = FavoritesTreePaths.childPath(dir.path, child.name, dir.scope == FavoriteScope.GLOBAL),
                                name = child.name,
                                bookmark = null,
                                fromFs = true,
                                missing = false
                            )
                        )
                    } else {
                        DefaultMutableTreeNode(RealFileNode(child))
                    }
                    treeNode.add(childTreeNode)
                }
            }
        }
        // ////////////////  核心逻辑二  end  ///////////////////////////////////////////

        // ////////////////  逻辑后：标记已加载并通知模型  start  /////////////////////////
        dir.childrenLoaded = true
        (model as? DefaultTreeModel)?.nodeStructureChanged(treeNode)
        // ////////////////  逻辑后  end  ///////////////////////////////////////////////
    }

    /**
     * 记录刷新前处于展开态的目录节点键（刷新后按记忆恢复展开；未加载过的目录不记录，保持折叠）
     */
    private fun captureExpandedDirKeys(): Set<String> {
        // ////////////////  校验参数：旧模型存在才可遍历  start  /////////////////////////
        val currentRoot = (model as? DefaultTreeModel)?.root as? DefaultMutableTreeNode
            ?: return emptySet()
        // ////////////////  校验参数  end  ////////////////////////////////////////////

        // ////////////////  核心逻辑：整树遍历收集展开目录的键  start  ///////////////////
        val keys = HashSet<String>()
        walkForExpandedDirKeys(currentRoot, keys)
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////

        // ////////////////  返回  start  //////////////////////////////////////////////
        return keys
        // ////////////////  返回  end  ////////////////////////////////////////////////
    }

    /**
     * 遍历树节点收集处于展开态的目录节点键（递归辅助）
     *
     * @param node 当前树节点
     * @param accumulator 收集结果
     */
    private fun walkForExpandedDirKeys(node: DefaultMutableTreeNode, accumulator: MutableSet<String>) {
        val userObject = node.userObject
        if (userObject is DirNode && isExpanded(TreePath(node.path))) {
            accumulator.add(dirKey(userObject))
        }
        for (child in node.children()) {
            walkForExpandedDirKeys(child as DefaultMutableTreeNode, accumulator)
        }
    }

    /**
     * 恢复展开态：作用域/列表/规则根/规则层级始终展开；目录节点按刷新前记忆恢复（展开动作经
     * TreeWillExpandListener 触发懒加载，加载完成后继续向子级递归）
     *
     * @param node 当前处理的树节点
     * @param expandedDirKeys 刷新前已展开的目录键集合
     */
    private fun restoreExpansion(node: DefaultMutableTreeNode, expandedDirKeys: Set<String>) {
        when (val userObject = node.userObject) {
            // ////////////////  作用域/列表/规则根/规则层级：始终展开并继续向下  start  ///////
            is ScopeNode, is BookmarkListNode, is RulesRootNode, is RuleNode -> {
                expandPath(TreePath(node.path))
                for (child in node.children()) {
                    restoreExpansion(child as DefaultMutableTreeNode, expandedDirKeys)
                }
            }
            // ////////////////  作用域/列表/规则根/规则层级  end  //////////////////////////

            // ////////////////  目录层级：按记忆恢复展开（触发懒加载）  start  //////////////
            is DirNode -> {
                if (dirKey(userObject) in expandedDirKeys) {
                    expandPath(TreePath(node.path))
                    if (userObject.childrenLoaded) {
                        for (child in node.children()) {
                            restoreExpansion(child as DefaultMutableTreeNode, expandedDirKeys)
                        }
                    }
                }
            }
            // ////////////////  目录层级  end  ///////////////////////////////////////////

            else -> {}
        }
    }

    /**
     * 打开文件节点（编辑器打开，抢焦点；失败仅记日志）
     */
    private fun openFileNode(file: VirtualFile) {
        try {
            FileEditorManager.getInstance(project).openFile(file, true)
        } catch (e: Exception) {
            log.warn("打开文件失败：${file.path}", e)
        }
    }

    /**
     * 在 Project 视图中选中目录/文件（不抢焦点，跳转文件夹的统一出口）
     */
    private fun selectInProjectView(file: VirtualFile) {
        try {
            com.intellij.ide.projectView.ProjectView.getInstance(project).select(null, file, false)
        } catch (e: Exception) {
            log.warn("在 Project 视图中选中失败：${file.path}", e)
        }
    }

    /**
     * 列表骨架查找键（作用域+列表名）
     */
    private fun spineKey(scope: FavoriteScope, listName: String): String = "${scope.name}|$listName"

    /**
     * 目录节点展开记忆键（作用域+列表名+目录路径）
     */
    private fun dirKey(dir: DirNode): String = "${dir.scope.name}|${dir.listName}|${dir.path}"
}
