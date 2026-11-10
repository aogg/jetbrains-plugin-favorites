package com.aogg.favorites.ui

import com.aogg.favorites.helper.BookmarkFileResolver
import com.aogg.favorites.helper.BookmarkIconHelper
import com.aogg.favorites.helper.BookmarkOpenNotifier
import com.aogg.favorites.helper.BookmarkPathMacros
import com.aogg.favorites.model.Bookmark
import com.aogg.favorites.model.FavoriteScope
import com.aogg.favorites.model.FavoritesScopeResolver
import com.aogg.favorites.model.ListInfo
import com.aogg.favorites.model.MatchRule
import com.aogg.favorites.model.RuleMatchManager
import com.aogg.favorites.model.ScopedBookmarkManager
import com.aogg.favorites.model.SharedBookmarkManager
import com.aogg.favorites.model.SharedBookmarkStore
import com.aogg.favorites.model.SharedCollectionManagerView
import com.aogg.favorites.model.SourceProjectStore
import com.aogg.favorites.rules.RuleItem
import com.aogg.favorites.rules.RuleStatus
import com.intellij.ide.DataManager
import com.intellij.ide.DeleteProvider
import com.intellij.ide.IdeView
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileChooser.actions.VirtualFileDeleteProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileEvent
import com.intellij.openapi.vfs.VirtualFileListener
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.RefreshQueue
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
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
import javax.swing.Timer
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeWillExpandListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * 作用域节点用户对象（树第一层，固定三个：项目收藏/共享收藏/全局收藏）
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
 * 列表节点用户对象（树第二层起，1.5.0 起可多层级嵌套的分组节点）
 * 子节点 = [子分组节点（递归）..., 自身收藏的文件系统骨架树]
 *
 * @param scope 列表所属作用域（列表操作按该作用域执行）
 * @param info 列表元信息（父子关系按 info.id/info.parentId 关联）
 * @param totalCount 该分组自身收藏总数（徽标展示，不含子分组内收藏）
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
 * @param path 目录完整路径（规范化：项目作用域=项目根相对路径；共享/全局作用域=绝对路径；""=作用域基准目录）
 * @param name 目录显示名（路径最后一段）
 * @param bookmark 挂在该目录上的文件夹收藏（纯虚拟目录/真实目录为 null）
 * @param fromFs 是否来自真实文件系统展开（真实目录=true，展开时继续读文件系统）
 * @param missing 文件夹收藏目标不存在/不可读（缺失态：叶子节点+红叉图标）
 * @param collectionId 共享作用域下的集合 id（跨集合同名列表的骨架/展开记忆键隔离；其它作用域为 ""）
 *
 * 1.7.2 起别名（收藏描述）在树中立即可见：文件夹收藏节点主文本 = 别名（非空白时）否则目录名；
 * 未收藏的真实目录（无 bookmark）不显示别名。
 */
class DirNode(
    val scope: FavoriteScope,
    val listName: String,
    val path: String,
    val name: String,
    val bookmark: Bookmark? = null,
    val fromFs: Boolean = false,
    val missing: Boolean = false,
    val collectionId: String = ""
) {
    /** 子节点是否已懒加载（骨架子项 + 文件夹收藏的真实子项） */
    var childrenLoaded: Boolean = false

    /** 骨架子项是否已物化（刷新时公共祖先/基准目录节点会立即物化一次，置位后展开懒加载不再重复追加，防同路径文件节点成对出现） */
    var spineChildrenLoaded: Boolean = false

    /** 展开时是否读真实文件系统（文件夹收藏目录/真实目录=true；纯虚拟目录仅骨架子项） */
    val fsBacked: Boolean
        get() = bookmark != null || fromFs

    /**
     * 显示名（渲染器主文本）：有别名（收藏描述非空白）时用别名，否则用目录名；
     * 未收藏真实目录（无 bookmark）恒为目录名
     */
    val displayName: String
        get() = bookmark?.description?.takeIf { it.isNotBlank() } ?: name

    /**
     * 速度搜索匹配文本：别名与真实目录名都参与匹配（设置别名后仍可按目录名搜到）
     */
    override fun toString(): String = if (displayName == name) displayName else "$displayName $name"
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
 *
 * 1.7.2 起别名（收藏描述）在树中立即可见：文件收藏节点主文本 = 别名（非空白时）否则文件名；
 * 仅承载行收藏的文件容器（无文件收藏）不显示别名。
 */
class FileNode(
    val scope: FavoriteScope,
    val listName: String,
    val path: String,
    val name: String,
    val fileBookmark: Bookmark?,
    val lineBookmarks: List<Bookmark>
) {
    /**
     * 显示名（渲染器主文本）：有别名（文件收藏的描述非空白）时用别名，否则用文件名
     */
    val displayName: String
        get() = fileBookmark?.description?.takeIf { it.isNotBlank() } ?: name

    /**
     * 速度搜索匹配文本：助记符前缀 + 别名/文件名；设置别名后同时保留真实文件名参与匹配
     */
    override fun toString(): String = buildString {
        val mnemonic = fileBookmark?.mnemonic
        if (mnemonic != null) {
            append(BookmarkIconHelper.getMnemonicText(mnemonic)).append(' ')
        }
        append(displayName)
        if (displayName != name) {
            append(' ').append(name)
        }
    }
}

/**
 * 行收藏节点用户对象（文件节点的子节点）
 * toString 同时作为 TreeSpeedSearch 的匹配文本
 *
 * @param scope 所属作用域
 * @param listName 所属列表名
 * @param bookmark 行收藏
 * @param contentText 所在行文本（超长已截断；「…」=后台异步加载中；null=空白行/读取失败，显示时回退收藏描述）。
 *                    异步加载/文件变动刷新完成后原地更新该字段并 nodeChanged（不重建树，保持展开态）
 */
class LineEntryNode(
    val scope: FavoriteScope,
    val listName: String,
    val bookmark: Bookmark,
    var contentText: String?
) {
    /**
     * 灰色说明文本（1.7.2 起体现别名）：
     * - 行内容优先；有别名时格式为「别名 · 行内容」（不能把行内容挤没）；
     * - 行内容读取失败/空白行时回退收藏别名（无别名则为空串）
     */
    fun grayText(): String {
        val alias = bookmark.description?.takeIf { it.isNotBlank() }
        val content = contentText
        return when {
            content.isNullOrEmpty() -> alias ?: ""
            alias == null -> content
            else -> "$alias · $content"
        }
    }

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
 * 共享收藏集合节点用户对象（共享收藏根节点的子节点，1.7.0 新增，1.7.1 扩展来源项目节点）：
 * 集合节点之下才是其 分组 → 文件系统风格树。
 * toString 同时作为 TreeSpeedSearch 的匹配文本
 *
 * @param collectionId 集合 id（自己的集合=来源项目路径哈希；来源项目切片="source-" 前缀+哈希）
 * @param ownerProjectName 来源项目显示名（自己的集合显示为「我的共享（项目名）」）
 * @param ownerProjectPath 来源项目根路径（只读节点 tooltip 展示用）
 * @param mine 是否自己的集合（true=自己的，可写；false=只读：旧订阅遗留集合或来源项目切片）
 * @param totalCount 该集合收藏总数（徽标展示）
 * @param sourceProject 是否配置的来源项目切片节点（1.7.1 新增：右键=移除共享项目；
 *        false=自己的集合或旧订阅遗留集合）
 */
data class SharedCollectionNode(
    val collectionId: String,
    val ownerProjectName: String,
    val ownerProjectPath: String,
    val mine: Boolean,
    val totalCount: Int,
    val sourceProject: Boolean = false
) {
    override fun toString(): String = buildString {
        if (mine) {
            append("我的共享（$ownerProjectName）")
        } else {
            append("共享自：$ownerProjectName")
        }
        append(" ($totalCount)")
    }
}

/**
 * 共享收藏空态提示节点用户对象（1.7.0 新增，1.7.1 文案改为配置共享项目）：
 * 共享收藏根下无自己数据且未配置来源项目时显示的提示叶子节点
 */
class SharedEmptyHintNode {
    override fun toString(): String = "尚未配置共享项目（右键配置共享项目）"
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
 * 第一层=作用域（项目收藏/全局收藏，始终显示+总数徽标），第二层起=分组树（1.5.0 起
 * 列表升级为可多层级嵌套的分组：分组节点子节点 = 子分组节点（递归）+ 自身收藏的文件系统骨架树），
 * 分组节点之下按文件系统层级组织：
 * - 每个分组按自身收藏路径计算公共祖先目录，自上而下生成虚拟目录链（仅承载通向收藏项的路径）
 * - 行收藏(LINE)挂在其所在文件的文件节点下（同文件共用一个文件节点，行节点="行号: 行内容"）
 *   行内容加载策略：EDT 只读已加载 Document（getCachedDocument），读不到先显示占位「…」再后台异步加载；
 *   监听文件内容变更（保存/外部改动）防抖 ~500ms 后仅刷新受影响文件的行内容并原位更新节点（不整棵重建）
 * - 文件收藏(FILE)即文件节点本体；同路径 FILE+LINE 收藏合并为一个文件节点
 * - 文件夹收藏(FOLDER)为虚拟目录链上带收藏标记的目录节点，首次展开懒加载真实子文件/子文件夹
 *   （含未收藏项，深层逐级懒加载）；目录不存在/不可读显示缺失态（红叉）
 * - 展开能力的模型前提（1.6.0 修复）：DefaultTreeModel 显式 asksAllowsChildren=true，isLeaf 以节点
 *   allowsChildren 为准——无子节点的目录/文件夹收藏节点仍可展开（懒加载触发的前提）；
 *   叶子类节点（纯文件收藏/行收藏/未收藏真实文件/规则条目）显式 allowsChildren=false 不出空展开把手
 *
 * 规则匹配（1.4.0）：第一层追加「规则匹配」根节点（排在全局收藏之后，始终显示）：
 * - 规则匹配根 → 规则节点（名称+状态后缀 ✅ M / ⏳ 执行中 / ❌，ERROR tooltip=失败原因）→ 结果条目节点
 * - 条目：FileItem 双击跳转（文件打开/目录 Project 视图选中）；RowItem/LineItem 双击弹信息框显示完整内容
 * - 右键菜单：规则根=新建规则/全部刷新；规则=刷新此规则/编辑规则/启用禁用/删除规则；条目=跳转（仅可跳转的）
 * - 搜索过滤：规则名与条目文本参与匹配（规则名命中放行全部条目，否则仅保留命中条目）
 *
 * 共享收藏（1.7.0 引入，1.7.1 改版为配置来源项目模型）：第一层第二根节点「共享收藏」，根下按集合分组：
 * - 集合节点=自己的集合「我的共享（项目名）」（可写）与每个已配置来源项目的
 *   「共享自：来源项目」（只读展示该项目收藏），集合节点之下才是其 分组 → 文件树；
 *   空态（无自己数据且未配置来源项目）显示提示叶子节点
 * - 右键：共享根/集合节点=配置共享项目…；来源项目节点=移除共享项目；自己集合=取消共享…（确认后删共享数据）
 * - 外来集合上下文只读：分组 CRUD 隐藏、删除/编辑/助记符给只读提示；移动到列表目标分组只取自己集合
 *
 * 交互：
 * - Enter / 双击 = 跳转（行→编辑器定位；文件→打开；文件夹→Project 视图选中；未收藏真实节点同样可跳转）
 * - F2 = 重命名别名、Del = 删除（通过 [Interactions] 回调到面板实现；未收藏真实节点收藏类操作不可用）
 * - 右键菜单：作用域根/列表分组节点（项目/全局）首项=添加目录…（弹平台目录选择器写入收藏）；
 *   跳转/重命名别名…/设置助记符/移动到列表(按作用域分组、按分组层级嵌套)/分组CRUD
 *   （分组节点=新建子分组；选中默认列表或作用域节点=新建顶级列表；删除分组级联连删子分组）/删除收藏 + 规则匹配菜单
 *   + 文件夹收藏/真实目录「刷新此目录」+ 收藏文件/文件夹「项目视图操作」子菜单（1.7.2）
 * - 「项目视图操作」子菜单（1.7.2）：直接取平台 ProjectViewPopupMenu 动作组子项（平台动作实例，不复制实现），
 *   通过 ActionPopupMenu.setDataContext 注入与右键节点一致的 DataContext（项目/虚拟文件/PSI 元素/PSI 文件/
 *   NAVIGATABLE/IDE_VIEW/上下文组件/目标删除器），未知键回退基础上下文；排除原生书签组与插件自己的「收藏」组；
 *   平台动作组取不到时降级为不显示该子菜单（仅记日志，不影响其它菜单项）
 * - 选中行收藏时预览打开文件但不抢焦点（openFile(file, false)）
 * - 装 TreeSpeedSearch（TreeUIHelper.installTreeSpeedSearch），各节点 toString 均参与匹配
 *   （1.7.2 起别名包含在 FileNode/DirNode/LineEntryNode 的 toString 中，可按别名搜索）
 * - 搜索过滤时作用域名/分组名/文件名/路径/描述/规则名/条目文本均参与匹配，
 *   分组名命中放行该分组整棵子树，过滤后虚拟目录链按命中项重建
 * - 刷新（含过滤）保留已展开目录的展开状态；作用域/分组层级/规则根/规则层级始终展开
 * - 从磁盘刷新（1.7.2）：[refreshFromDisk] 清空 VFS 兜底缓存后重建树（工具栏「刷新」），
 *   目录节点右键「刷新此目录」异步刷新该目录 VFS 后原位重置并重新懒加载子项（缺失态退化为整树重建）
 */
class BookmarksTree(
    private val project: Project,
    private val interactions: Interactions
) : Tree() {

    /**
     * 面板侧交互回调（带作用域：列表操作作用于所在作用域；规则操作回调到面板弹对话框/驱动管理器）
     */
    interface Interactions {

        /**
         * 重命名别名（无参入口）：工具栏按钮与 F2 快捷键走这里，按 JTree 当前选中项执行
         */
        fun editSelectedBookmark()

        /**
         * 重命名别名（带参入口，1.7.2 新增）：右键菜单「重命名别名…」走这里，
         * 直接作用于右键点中的那个收藏节点，不依赖 JTree 当前选中项（避免选中态丢失时提示「请先选中收藏项」）
         *
         * @param item 右键点中的收藏（作用域 + 收藏项）
         */
        fun renameAlias(item: ScopedBookmark)
        fun deleteSelected()
        fun setMnemonicForSelected()
        fun moveSelectedTo(scope: FavoriteScope, listName: String)
        fun createList(scope: FavoriteScope)
        fun createSubGroup(scope: FavoriteScope, parentListName: String)
        fun renameList(scope: FavoriteScope, listName: String?)
        fun deleteList(scope: FavoriteScope, listName: String?)

        // 规则匹配（1.4.0 第三个根节点）
        fun createRule()
        fun refreshAllRules()
        fun refreshRule(rule: MatchRule)
        fun editRule(rule: MatchRule)
        fun toggleRuleEnabled(rule: MatchRule)
        fun deleteRule(rule: MatchRule)

        // 共享收藏（1.7.0 共享根/集合节点右键；1.7.1 配置来源项目模型）
        fun manageSharedRefs()

        /**
         * 移除共享项目（来源项目节点右键）：从本项目配置中移除该来源项目并刷新
         *
         * @param sourcePath 来源项目根路径
         */
        fun removeSharedSourceProject(sourcePath: String)
        fun unshareOwnSharedCollection(collectionId: String)

        /**
         * 右键「添加目录…」：弹 IntelliJ 平台目录选择器（仅可选目录），把选定目录作为文件夹收藏加入
         *
         * @param scope 目标作用域（仅项目/全局两条线开放入口）
         * @param listName 目标列表名（列表/分组节点=该分组自身）；null=作用域根触发，
         *                 按现有固定作用域写入逻辑取该作用域上次使用的分组（无记忆/已删除回退默认列表）
         */
        fun addDirectoryTo(scope: FavoriteScope, listName: String?)
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

        /** 行内容未加载完成时的占位文本（后台异步加载完成后替换为真实内容/描述兜底） */
        private const val LINE_CONTENT_PENDING = "…"

        /** 行内容随文件变动刷新的防抖毫秒数（连续保存/外部改动合并为一次刷新） */
        private const val LINE_REFRESH_DEBOUNCE_MS = 500

        /** 异步加载行内容的源文件大小上限（超过按读取失败处理回退收藏描述，避免后台加载超大文件） */
        private const val MAX_LINE_SOURCE_FILE_LENGTH = 20L * 1024 * 1024

        /** 全部展开时的行数安全上限（防止在超大真实目录树上无限懒加载） */
        private const val MAX_EXPAND_ROWS = 10000

        /** 数据库控制台收藏兜底：IDE 配置目录下的控制台根目录名 */
        private const val CONSOLES_DIR_NAME = "consoles"

        /** 数据库控制台收藏兜底：控制台脚本文件名 */
        private const val CONSOLE_FILE_NAME = "console.sql"

        /** 数据库控制台收藏兜底：最大遍历深度（相对 consoles 根，含根算 0 层） */
        private const val CONSOLES_MAX_DEPTH = 4

        /** 数据库控制台收藏兜底：最大遍历条目数（防超大目录卡顿） */
        private const val CONSOLES_MAX_ENTRIES = 2000

        /** 数据库控制台收藏路径尾部特征：`.../consoles/<id>/console.sql`（第 1 组=控制台 id） */
        private val CONSOLE_FILE_PATTERN =
            Regex("(?:^|/)consoles/([A-Za-z0-9_-]+)/console\\.sql$", RegexOption.IGNORE_CASE)

        /** 平台项目视图右键菜单动作组 id（1.7.2 收藏树右键「项目视图操作」子菜单的动作来源） */
        private const val PROJECT_VIEW_POPUP_MENU_ID = "ProjectViewPopupMenu"

        /** 「项目视图操作」子菜单标题 */
        private const val PROJECT_VIEW_ACTIONS_TITLE = "项目视图操作"

        /** 本插件自己的「收藏」右键组 id（平台项目视图菜单里也有，子菜单中需排除避免自我嵌套） */
        private const val FAVORITES_ACTION_GROUP_ID = "Favorites.Group"

        /** 「添加收藏」动作 id（项目视图/导航栏右键同款，收藏树文件/文件夹右键首项复用，三作用域通用） */
        private const val ADD_FAVORITE_ACTION_ID = "Favorites.AddFavorite"

        /**
         * 平台项目视图菜单中需排除的动作组 id（1.7.2）：
         * - `popup@ExpandableBookmarkContextMenu`：平台项目视图右键的原生「书签」弹出组
         *   （LangActions.xml 中 id 带 popup@ 前缀，与 [com.aogg.favorites.nativebookmarks.NativeBookmarkActionIntegration]
         *   隐藏原生书签界面时使用的 id 保持一致；本插件已接管原生书签，子菜单中不再重复出现）；
         * - `popup@BookmarkContextMenu`：行号区原生书签弹出组（项目视图菜单当前未引用，纳入保险）；
         * - `Favorites.Group`：本插件自己的「收藏」组（避免子菜单自我嵌套）。
         */
        private val EXCLUDED_PROJECT_VIEW_ACTION_IDS = setOf(
            "popup@ExpandableBookmarkContextMenu",
            "popup@BookmarkContextMenu",
            "ExpandableBookmarkContextMenu",
            FAVORITES_ACTION_GROUP_ID
        )

        /** 原生书签组类名/文案兜底标识（ActionManager 取不到 id 时用于排除判定） */
        private const val EXCLUDED_NATIVE_BOOKMARK_GROUP_NAME = "ExpandableBookmarkContextMenu"

        /** 原生书签组显示文案兜底（中英文 IDE，均可能是平台动作组未带 text 时的兜底文案） */
        private val EXCLUDED_PROJECT_VIEW_GROUP_TEXTS = setOf("书签", "Bookmarks")

        /**
         * IDE_VIEW 数据键名：2023.3 起该键常量位于 com.intellij.openapi.actionSystem.LangDataKeys，
         * 键名字符串（"IDEView"）是跨版本稳定契约（DataKey.create 按名称缓存）；
         * 这里按名称匹配，避免大版本间类包路径变化导致 NoClassDefFoundError
         */
        private const val DATA_ID_IDE_VIEW = "IDEView"
    }

    private var filterText: String = ""

    /** 当前各列表的文件系统骨架（key = spineKey(scope, listName)，refresh 时重建） */
    private var listSpines: Map<String, FavoritesListSpine> = emptyMap()

    /** 行内容缓存：文件绝对路径（正斜杠规范）→（行号 → 行文本；null=空白行/读取失败，显示时回退收藏描述）。仅 EDT 访问 */
    private val lineContentCache = HashMap<String, HashMap<Int, String?>>()

    /**
     * 「项目视图操作」子菜单的删除器（1.7.2）：只删除注入上下文里的右键目标
     * （[com.intellij.openapi.fileChooser.actions.VirtualFileDeleteProvider] 按 VIRTUAL_FILE_ARRAY 删除），
     * 绝不读取项目视图当前选区，避免误删用户之前选中的其它文件
     */
    private val targetDeleteProvider: DeleteProvider = VirtualFileDeleteProvider()

    /** 进行中的异步行内容加载请求键（缓存键#行号，防同一位置重复调度后台加载） */
    private val pendingLineLoadKeys = HashSet<String>()

    /** 待防抖刷新行内容的文件绝对路径集合（EDT 访问） */
    private val pendingLineRefreshPaths = LinkedHashSet<String>()

    /** 行内容防抖刷新定时器（Swing Timer 到点在 EDT 执行，仅刷新受影响文件的行内容并 nodeChanged） */
    private var lineRefreshTimer: Timer? = null

    /** 行内容实时更新相关资源的注销句柄（注册到项目：项目关闭时停定时器并清空缓存/监听自动注销） */
    private val lineContentDisposable = Disposable {
        lineRefreshTimer?.stop()
        lineRefreshTimer = null
        pendingLineRefreshPaths.clear()
        pendingLineLoadKeys.clear()
        lineContentCache.clear()
    }

    init {
        isRootVisible = false
        showsRootHandles = true
        // asksAllowsChildren 必须显式为 true：isLeaf 以节点 allowsChildren 为准（无子节点的目录仍是可展开节点）。
        // 默认 false 时「无子节点一律判为叶子、不出展开把手」，懒加载（treeWillExpand）永远无触发机会——
        // 1.3.0 起文件夹收藏/深层虚拟目录从未真正可展开的根因（1.6.0 修复）
        model = DefaultTreeModel(DefaultMutableTreeNode("root"), true)
        val renderer = BookmarksTreeCellRenderer(project)
        // 必须调用 setCellRenderer：在 JTree 子类内部裸写 `cellRenderer =`，Kotlin 会编译成直接写
        // JTree 的 protected 字段（putfield），不触发 CELL_RENDERER_PROPERTY 变更事件，
        // 树 UI（BasicTreeUI/DefaultTreeUI）在构造期缓存的 currentCellRenderer 一直是 NodeRenderer，
        // 自定义渲染器永远不参与绘制（1.3.0~1.5.1 全树无图标的真正原因）。
        setCellRenderer(renderer)
        // 安装取证（idea.log 搜 FavoritesTree）：对比设置值与 getCellRenderer() 实际返回，
        // 若两者类名不一致说明存在第三方胶水层在构造期换走 renderer
        log.info(
            "FavoritesTree renderer installed: ${renderer.javaClass.name}, " +
                "getCellRenderer()=${getCellRenderer()?.javaClass?.name}"
        )

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

        // Enter=跳转 / F2=重命名别名 / Del=删除
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

        // 行内容实时更新：监听文件内容变更（保存/外部改动）防抖刷新，项目关闭时随 Disposer 注销
        registerLineContentListener()

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

        // ////////////////  第一层：固定作用域节点（项目收藏→共享收藏→全局收藏，始终显示）  start  //
        for (scope in FavoriteScope.entries) {
            val manager = managerFor(scope)
            val scopeNode = DefaultMutableTreeNode(ScopeNode(scope, manager.getTotalCount()))

            // 作用域名命中过滤词时，该作用域整体放行（列表与收藏不再逐项过滤）
            val scopeMatches = query.isEmpty() || scope.label.lowercase(Locale.ROOT).contains(query)

            // ////////////////  第二层：作用域下的子树  start  ///////////////////////////
            if (scope == FavoriteScope.SHARED) {
                // 共享收藏：作用域根下先按集合分组（自己的/订阅的），集合节点下才是其分组树
                buildSharedCollectionsSubtree(scopeNode, query, scopeMatches, spines)
            } else {
                for (topInfo in manager.childrenOf(ListInfo.ROOT_PARENT_ID)) {
                    buildListSubtree(scopeNode, scope, topInfo, manager, query, scopeMatches, spines)
                }
            }
            // ////////////////  第二层  end  ///////////////////////////////////////////

            // 空作用域（无任何分组）保持叶子语义，不出空展开把手（asksAllowsChildren=true 后需显式标记）
            scopeNode.allowsChildren = scopeNode.childCount > 0

            root.add(scopeNode)
        }
        // ////////////////  第一层  end  ////////////////////////////////////////////////

        // ////////////////  第一层附加：规则匹配根节点（第四个根，排在全局收藏之后）  start //
        root.add(buildRulesSubtree(query))
        // ////////////////  第一层附加  end  ///////////////////////////////////////////

        // ////////////////  逻辑后：替换模型并恢复展开态  start  /////////////////////////
        listSpines = spines
        // asksAllowsChildren=true 与 init 构造保持一致：无子节点的目录不判为叶子，展开把手与懒加载可用
        model = DefaultTreeModel(root, true)
        for (scopeChild in root.children()) {
            restoreExpansion(scopeChild as DefaultMutableTreeNode, expandedDirKeys)
        }
        // 裁剪行内容缓存：仅保留当前树中行收藏仍引用的文件（避免内存无限增长）
        pruneLineContentCache()
        // ////////////////  逻辑后  end  ///////////////////////////////////////////////
    }

    /**
     * 从磁盘刷新收藏树（1.7.2 工具栏「刷新」的树侧统一入口）：
     * 先清空共享解析器的「VFS 未收录路径只尝试一次」缓存（[BookmarkFileResolver.clearRefreshTriedPaths]），再重建树——
     * 外部新增/删除的文件与目录立即可见；展开记忆与过滤关键字沿用 [refresh] 现成逻辑
     * （作用域/分组/规则层级始终展开，已展开目录按记忆恢复，其它现有行为不变）
     *
     * @param filter 过滤关键字（面板调用时传搜索框当前文本；默认沿用当前过滤词）
     */
    fun refreshFromDisk(filter: String = filterText) {
        // ////////////////  核心逻辑：清空 VFS 兜底缓存（让上次未收录路径重新尝试刷新）  start
        BookmarkFileResolver.clearRefreshTriedPaths()
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////

        // ////////////////  逻辑后：按展开记忆与过滤词重建树  start  ////////////////////
        refresh(filter)
        // ////////////////  逻辑后  end  //////////////////////////////////////////////
    }

    /**
     * 构建「共享收藏」根下的子树（1.7.0 按集合分组，1.7.1 改版为配置来源项目模型）：
     * 根下依次为自己的集合「我的共享（项目名）」（可写，无数据时跳过）与
     * 每个已配置来源项目的「共享自：来源项目」节点（只读展示其项目收藏，空数据也显示并可提示）；
     * 空态（无自己数据且未配置来源项目）显示提示叶子节点。
     *
     * 搜索过滤：集合显示名/来源项目名/来源路径命中时放行该集合整棵子树；否则按命中项过滤。
     *
     * @param parentNode 共享收藏作用域根节点
     * @param query 过滤关键字（小写；空串表示不过滤）
     * @param scopeMatches 作用域名是否已命中过滤词（命中时整棵放行）
     * @param spines 骨架缓存出口（key = spineKey(scope, listName, collectionId)，refresh 时重建）
     */
    private fun buildSharedCollectionsSubtree(
        parentNode: DefaultMutableTreeNode,
        query: String,
        scopeMatches: Boolean,
        spines: MutableMap<String, FavoritesListSpine>
    ) {
        // ////////////////  准备数据：共享仓库 + 来源项目配置（顺带完成旧订阅迁移）  start  //
        val store = runCatching { SharedBookmarkStore.getInstance() }.getOrNull() ?: return
        val sourcePaths = store.configuredSourceProjects(project)
        val ownId = store.collectionOf(project).id
        val collections = store.visibleCollections(project)
        // ////////////////  准备数据  end  /////////////////////////////////////////////

        // ////////////////  分支一：空态（无自己数据且未配置来源项目）显示提示叶子  start  //
        val ownHasData = collections.firstOrNull { it.id == ownId }?.core?.getTotalCount()?.let { it > 0 } == true
        if (!ownHasData && sourcePaths.isEmpty()) {
            if (query.isEmpty() || scopeMatches) {
                parentNode.add(DefaultMutableTreeNode(SharedEmptyHintNode(), false))
            }
            return
        }
        // ////////////////  分支一  end  ///////////////////////////////////////////////

        // ////////////////  分支二：自己的集合 + 旧订阅遗留集合子树  start  ////////////////
        for (collection in collections) {
            val mine = collection.id == ownId
            // 自己集合无数据且无自定义分组时跳过（避免出现空「我的共享」层，减少噪音）
            val ownEmptySkip = mine && collection.core.getTotalCount() == 0 &&
                collection.core.getLists().all { it.name == Bookmark.DEFAULT_LIST_NAME }
            if (ownEmptySkip) continue
            buildSharedCollectionNode(
                parentNode, query, scopeMatches, spines, collection, mine, sourceProject = false
            )
        }
        // ////////////////  分支二  end  ///////////////////////////////////////////////

        // ////////////////  分支三：配置的来源项目节点（只读，空数据也显示）  start  ////////
        val sourceCollections = SourceProjectStore.getInstance().collectionsFor(sourcePaths)
        for (source in sourceCollections) {
            buildSharedCollectionNode(
                parentNode, query, scopeMatches, spines, source, mine = false, sourceProject = true
            )
        }
        // ////////////////  分支三  end  ///////////////////////////////////////////////
    }

    /**
     * 构建单个共享集合/来源项目切片节点及其分组子树（分支二/三共用）：
     * 节点用户对象 [SharedCollectionNode]，之下经 [SharedCollectionManagerView] 复用
     * 分组 → 文件树渲染链路；过滤中未命中且无可见子级时整体隐藏
     */
    private fun buildSharedCollectionNode(
        parentNode: DefaultMutableTreeNode,
        query: String,
        scopeMatches: Boolean,
        spines: MutableMap<String, FavoritesListSpine>,
        collection: SharedBookmarkStore.SharedCollection,
        mine: Boolean,
        sourceProject: Boolean
    ) {
        // ////////////////  准备数据：显示文本与过滤放行判定  start  ///////////////////////
        val view = SharedCollectionManagerView(collection)
        // 来源项目切片空数据（无收藏且只有默认分组）：不建分组子树，节点保持空叶子（tooltip 提示）
        val emptySource = sourceProject && collection.core.getTotalCount() == 0 &&
            collection.core.getLists().all { it.name == Bookmark.DEFAULT_LIST_NAME }
        val displayText = if (mine) {
            "我的共享（${collection.ownerProjectName}）"
        } else {
            "共享自：${collection.ownerProjectName}"
        }
        // 集合显示名/来源项目名/来源路径命中时放行该集合整棵子树
        val collectionMatches = query.isEmpty() || scopeMatches ||
            displayText.lowercase(Locale.ROOT).contains(query) ||
            collection.ownerProjectName.lowercase(Locale.ROOT).contains(query) ||
            collection.ownerProjectPath.lowercase(Locale.ROOT).contains(query)
        // ////////////////  准备数据  end  /////////////////////////////////////////////

        // ////////////////  核心逻辑：集合节点 + 分组子树  start  /////////////////////////
        val collectionNode = DefaultMutableTreeNode(
            SharedCollectionNode(
                collectionId = collection.id,
                ownerProjectName = collection.ownerProjectName,
                ownerProjectPath = collection.ownerProjectPath,
                mine = mine,
                totalCount = collection.core.getTotalCount(),
                sourceProject = sourceProject
            )
        )
        var hasVisibleChild = false
        if (!emptySource) {
            for (topInfo in view.childrenOf(ListInfo.ROOT_PARENT_ID)) {
                if (buildListSubtree(
                        collectionNode,
                        FavoriteScope.SHARED,
                        topInfo,
                        view,
                        query,
                        collectionMatches,
                        spines,
                        collection.id
                    )
                ) {
                    hasVisibleChild = true
                }
            }
        }
        collectionNode.allowsChildren = collectionNode.childCount > 0

        // 过滤中且集合未命中、无任何可见子级时整体隐藏
        if (query.isNotEmpty() && !collectionMatches && !hasVisibleChild) return
        parentNode.add(collectionNode)
        // ////////////////  核心逻辑  end  /////////////////////////////////////////////
    }

    /**
     * 构建单个分组子树（递归）：分组节点的子节点 = [子分组节点（递归）..., 自身收藏的文件系统骨架树]
     *
     * 搜索过滤：分组名命中时放行该分组整棵子树（含后代分组与收藏，不再逐项过滤），
     * 否则按命中项过滤；过滤中且分组名未命中、自身收藏与全部后代均无命中时该分组整体隐藏
     * （不过滤时分组始终显示）。
     *
     * @param parentNode 挂载父节点（作用域节点/共享集合节点或上级分组节点）
     * @param scope 所属作用域
     * @param info 当前分组元信息
     * @param manager 分组所在作用域的管理器（共享作用域传集合维度的管理器视图）
     * @param query 过滤关键字（小写；空串表示不过滤）
     * @param subtreePass 父级是否已放行整棵子树（作用域名/共享集合名/上级分组名命中时为 true）
     * @param spines 骨架缓存出口（key = spineKey(scope, listName, collectionId)，refresh 时重建）
     * @param collectionId 共享作用域下的集合 id（骨架/展开记忆键隔离；其它作用域传 ""）
     * @return true=已挂载分组节点（false=过滤后无命中，未挂载）
     */
    private fun buildListSubtree(
        parentNode: DefaultMutableTreeNode,
        scope: FavoriteScope,
        info: ListInfo,
        manager: ScopedBookmarkManager,
        query: String,
        subtreePass: Boolean,
        spines: MutableMap<String, FavoritesListSpine>,
        collectionId: String = ""
    ): Boolean {
        // ////////////////  准备数据：自身收藏 + 过滤放行判定  start  //////////////////
        val allBookmarks = manager.getBookmarksByList(info.name)
        val selfPass = subtreePass || info.name.lowercase(Locale.ROOT).contains(query)
        val visibleBookmarks = if (selfPass) allBookmarks else allBookmarks.filter { bookmarkMatches(it, query) }
        // ////////////////  准备数据  end  ////////////////////////////////////////////

        // 徽标仍显示自身收藏数（不含子分组内收藏）
        val listNode = DefaultMutableTreeNode(BookmarkListNode(scope, info, allBookmarks.size))

        // ////////////////  核心逻辑一：子分组节点（递归，先于自身收藏骨架）  start  //////
        var hasVisibleChild = false
        for (child in manager.childrenOf(info.id)) {
            if (buildListSubtree(listNode, scope, child, manager, query, selfPass, spines, collectionId)) {
                hasVisibleChild = true
            }
        }
        // ////////////////  核心逻辑一  end  ////////////////////////////////////////

        // ////////////////  核心逻辑二：自身收藏的文件系统骨架树（按命中收藏重建）  start //
        val spine = FavoritesListSpine.build(scope, info.name, visibleBookmarks)
        spines[spineKey(scope, info.name, collectionId)] = spine
        if (spine.commonAncestorPath.isEmpty()) {
            // ////////////////  基准目录自身带文件夹收藏（如收藏了项目根）时物化为顶层节点  //
            val baseBookmark = spine.dirBookmarkAt("")
            if (baseBookmark != null) {
                val baseName = if (scope == FavoriteScope.PROJECT) {
                    project.basePath?.substringAfterLast('/') ?: "."
                } else {
                    // 共享/全局作用域为绝对路径语义，基准目录即文件系统根
                    "/"
                }
                val baseDir = DirNode(
                    scope = scope,
                    listName = info.name,
                    path = "",
                    name = baseName,
                    bookmark = baseBookmark,
                    fromFs = false,
                    missing = !isReadableDirectory(resolveNodePath(scope, "")),
                    collectionId = collectionId
                )
                val baseTreeNode = DefaultMutableTreeNode(baseDir, !baseDir.missing)
                appendSpineChildren(baseTreeNode, scope, info.name, spine, "", collectionId)
                listNode.add(baseTreeNode)
            } else {
                // 公共祖先即作用域基准目录（项目根/文件系统根）：骨架子项直接挂分组节点
                appendSpineChildren(listNode, scope, info.name, spine, "", collectionId)
            }
            // ////////////////  基准目录  end  /////////////////////////////////////
        } else {
            // 公共祖先目录自身作为顶层目录节点（保证文件始终有上级文件夹层级）
            val commonBookmark = spine.dirBookmarkAt(spine.commonAncestorPath)
            val commonDir = DirNode(
                scope = scope,
                listName = info.name,
                path = spine.commonAncestorPath,
                name = spine.commonAncestorPath.substringAfterLast('/'),
                bookmark = commonBookmark,
                fromFs = false,
                missing = commonBookmark != null &&
                    !isReadableDirectory(resolveNodePath(scope, spine.commonAncestorPath)),
                collectionId = collectionId
            )
            // 缺失态目录不挂子节点（叶子+红叉）
            val commonTreeNode = DefaultMutableTreeNode(commonDir, !commonDir.missing)
            appendSpineChildren(commonTreeNode, scope, info.name, spine, spine.commonAncestorPath, collectionId)
            listNode.add(commonTreeNode)

            // 文件夹收藏顶层锚定（1.6.1）：基准目录键下的顶层目录条目（仅文件夹收藏锚定在这里）
            // 与公共祖先节点平级挂在分组节点下——收藏的文件夹从它自己开始显示，不生成上级目录链
            appendSpineChildren(listNode, scope, info.name, spine, "", collectionId)
        }
        // ////////////////  核心逻辑二  end  ////////////////////////////////////////

        // ////////////////  逻辑后：空分组保持叶子语义（不出空展开把手）  start  ///////////
        // 无子分组且自身收藏骨架为空时标记为叶子（asksAllowsChildren=true 后 isLeaf 以 allowsChildren 为准）
        listNode.allowsChildren = listNode.childCount > 0
        // ////////////////  逻辑后  end  ///////////////////////////////////////////

        // ////////////////  逻辑后：过滤后无命中时整体隐藏  start  ////////////////////
        if (query.isNotEmpty() && !selfPass && visibleBookmarks.isEmpty() && !hasVisibleChild) {
            return false
        }
        parentNode.add(listNode)
        return true
        // ////////////////  逻辑后  end  ///////////////////////////////////////////
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
                ),
                // 无可见条目的规则保持叶子语义，不出空展开把手
                visibleItems.isNotEmpty()
            )
            for (item in visibleItems) {
                // 规则条目为叶子节点（不可展开）
                ruleTreeNode.add(DefaultMutableTreeNode(RuleItemNode(rule.id, item), false))
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
     * 选中分组节点→该分组自身；选中分组内收藏/目录节点→沿父链向上最近的所属分组
     * （1.5.0 嵌套分组下即"所选项自身所在分组"）；
     * 作用域节点/纯虚拟目录/真实未收藏节点→null（避免 Del 等列表操作被误触发）
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
     * 兼容两种作用域：项目收藏相对路径拼项目根；全局收藏绝对路径直用；
     * 路径中的 IDE 宏（$PROJECT_DIR$ / $APPLICATION_CONFIG_DIR$ 等）在解析期展开（1.7.3）
     */
    fun navigateToBookmark(bookmark: Bookmark) {
        // ////////////////  准备数据：解析收藏目标文件  start  //////////////////////////
        val file = resolveVirtualFile(bookmark)
        // ////////////////  准备数据  end  ////////////////////////////////////////////

        // ////////////////  核心逻辑一：解析失败/文件不存在→兜底查找 + 可诊断提示  start  //
        // 历史行为是静默 return，用户只能看到「双击没反应」；1.7.3 起先尝试数据库控制台收藏
        // 在 IDE 配置目录下的兜底查找，仍然失败才弹非模态警告通知（不阻塞 EDT）
        if (file == null || !file.exists()) {
            if (tryLocateConsoleFileAsync(bookmark)) return
            notifyOpenFailed(bookmark)
            return
        }
        // ////////////////  核心逻辑一  end  ///////////////////////////////////////////

        // ////////////////  核心逻辑二：按收藏类型跳转  start  //////////////////////////
        openBookmarkTarget(bookmark, file)
        // ////////////////  核心逻辑二  end  ///////////////////////////////////////////
    }

    /**
     * 按收藏类型打开已解析的文件（文件夹→Project 视图选中；文件/行→编辑器打开，行收藏再定位到目标行）
     *
     * @param bookmark 收藏
     * @param file 已解析且存在的虚拟文件
     */
    private fun openBookmarkTarget(bookmark: Bookmark, file: VirtualFile) {
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
     * 数据库控制台收藏兜底查找（1.7.3，只读）：路径尾部形如 `consoles/<id>/console.sql` 时，
     * 在 IDE 配置目录的 consoles 下按 `<id>/console.sql` 后缀做有限深度/有限条目的本地查找（非 EDT）。
     * 命中回 EDT 打开，未命中回 EDT 弹「无法打开收藏」通知。
     *
     * @param bookmark 收藏
     * @return true=已接管（异步流程负责后续打开/提示）；false=不适用（仍由调用方提示）
     */
    private fun tryLocateConsoleFileAsync(bookmark: Bookmark): Boolean {
        // ////////////////  校验参数：仅数据库控制台收藏走兜底  start  ////////////////////
        val consoleId = consoleDirectoryIdOf(bookmark.filePath) ?: return false
        val application = ApplicationManager.getApplication() ?: return false
        // ////////////////  校验参数  end  ////////////////////////////////////////////

        // ////////////////  核心逻辑一：后台只读查找（不阻塞 EDT，不缓存失败结果）  start  //
        application.executeOnPooledThread {
            val located = runCatching { findConsoleFileById(consoleId) }
                .onFailure { log.warn("数据库控制台收藏兜底查找失败：${bookmark.filePath}", it) }
                .getOrNull()

            // ////////////////  核心逻辑二：回 EDT 刷新并打开（失败则提示）  start  ////////
            application.invokeLater {
                val file = located?.let { consolePath ->
                    runCatching {
                        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(consolePath))
                    }.onFailure { log.warn("数据库控制台收藏兜底刷新失败：$consolePath", it) }.getOrNull()
                }
                if (file == null) {
                    notifyOpenFailed(bookmark)
                } else {
                    openBookmarkTarget(bookmark, file)
                }
                // ////////////////  核心逻辑二  end  ////////////////////////////////////
            }
        }
        // ////////////////  核心逻辑一  end  ///////////////////////////////////////////
        return true
    }

    /**
     * 从收藏路径尾部提取数据库控制台 id（`.../consoles/<id>/console.sql`）
     *
     * @param filePath 收藏路径
     * @return 控制台目录 id；不是控制台收藏返回 null
     */
    private fun consoleDirectoryIdOf(filePath: String): String? =
        CONSOLE_FILE_PATTERN.find(filePath.replace('\\', '/'))?.groupValues?.get(1)

    /**
     * 在 IDE 配置目录的 consoles 下按 `<id>/console.sql` 后缀查找控制台脚本（后台线程只读调用）。
     * 深度上限 [CONSOLES_MAX_DEPTH]、条目上限 [CONSOLES_MAX_ENTRIES]，超限立即放弃，避免卡顿。
     *
     * @param consoleId 控制台目录 id
     * @return 命中脚本的绝对路径（正斜杠风格）；未命中返回 null
     */
    private fun findConsoleFileById(consoleId: String): String? {
        // ////////////////  准备数据：配置目录 consoles 根 + 目标后缀  start  ////////////
        val consolesRoot = File(PathManager.getConfigPath(), CONSOLES_DIR_NAME)
        if (!consolesRoot.isDirectory) return null
        val targetSuffix = "/$CONSOLES_DIR_NAME/$consoleId/$CONSOLE_FILE_NAME"
        // ////////////////  准备数据  end  ////////////////////////////////////////////

        // ////////////////  核心逻辑：深度优先遍历（深度/条目双上限）  start  ////////////
        var visited = 0
        val stack = ArrayDeque<Pair<File, Int>>()
        stack.addLast(consolesRoot to 0)
        while (stack.isNotEmpty()) {
            val (directory, depth) = stack.removeLast()
            if (depth >= CONSOLES_MAX_DEPTH) continue
            val children = directory.listFiles() ?: continue
            for (child in children) {
                if (++visited > CONSOLES_MAX_ENTRIES) return null
                if (child.isDirectory) {
                    stack.addLast(child to (depth + 1))
                } else if (child.name.equals(CONSOLE_FILE_NAME, ignoreCase = true)) {
                    val normalized = child.absolutePath.replace('\\', '/')
                    if (normalized.endsWith(targetSuffix, ignoreCase = true)) return normalized
                }
            }
        }
        return null
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////
    }

    /**
     * 收藏打开失败提示（1.7.3）：日志 + 共享的非模态 WARNING 通知（内容含收藏名、原始路径、
     * 已尝试的候选路径；候选条数与单条长度的截断规则见 [BookmarkOpenNotifier]）
     *
     * @param bookmark 收藏
     */
    private fun notifyOpenFailed(bookmark: Bookmark) {
        // ////////////////  准备数据：诊断信息（候选列表同时用于日志与通知）  start  //////
        val displayName = bookmark.getDisplayName()
        val candidates = BookmarkPathMacros.candidates(bookmark.filePath, project.basePath)
        // ////////////////  准备数据  end  ////////////////////////////////////////////

        // ////////////////  核心逻辑：日志 + 非模态警告通知（不弹模态框、不阻塞 EDT）  start
        log.warn("无法打开收藏：名称=$displayName，原始路径=${bookmark.filePath}，候选=$candidates")
        BookmarkOpenNotifier.notifyOpenFailed(project, displayName, bookmark.filePath, candidates)
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////
    }

    /**
     * 右键菜单（列表操作按选中上下文的作用域执行；未收藏真实节点提供跳转+添加收藏，其余收藏类操作不可用；
     * 规则匹配子树提供独立菜单：规则根=新建/全刷、规则=刷新/编辑/启禁/删除、条目=跳转/查看详情；
     * 作用域根/列表分组节点（项目/全局）首项=添加目录…，弹平台目录选择器后写入收藏；
     * 右键文件/文件夹（收藏本体/懒加载真实子项）首项=添加收藏：平台同款动作，弹「作用域+分组」选择、
     * 已收藏切换移除，项目/共享/全局三作用域通用）
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

        // ////////////////  分支零 B：共享收藏 根/集合/空态节点独立右键菜单  start  ////////
        if (clickedUserObject is SharedCollectionNode || clickedUserObject is SharedEmptyHintNode ||
            (clickedUserObject is ScopeNode && clickedUserObject.scope == FavoriteScope.SHARED)
        ) {
            val sharedGroup = DefaultActionGroup()
            sharedGroup.add(object : AnAction("配置共享项目…", "打开配置共享项目对话框（选择要展示其项目收藏的来源项目）", null) {
                override fun actionPerformed(e: AnActionEvent) {
                    interactions.manageSharedRefs()
                }
            })
            if (clickedUserObject is SharedCollectionNode) {
                if (clickedUserObject.mine) {
                    sharedGroup.add(object : AnAction(
                        "取消共享…",
                        "取消共享并删除自己的共享数据（引用方将不再可见）",
                        null
                    ) {
                        override fun actionPerformed(e: AnActionEvent) {
                            interactions.unshareOwnSharedCollection(clickedUserObject.collectionId)
                        }
                    })
                } else if (clickedUserObject.sourceProject) {
                    // 1.7.1 配置来源项目模型：来源项目节点右键=移除共享项目
                    // （仅从本项目配置移除，来源项目数据不受影响）
                    sharedGroup.add(object : AnAction(
                        "移除共享项目",
                        "从共享配置移除该来源项目（本项目不再显示其项目收藏，来源项目数据不受影响）",
                        null
                    ) {
                        override fun actionPerformed(e: AnActionEvent) {
                            interactions.removeSharedSourceProject(clickedUserObject.ownerProjectPath)
                        }
                    })
                }
                // 旧订阅遗留集合（1.7.0）：迁移后不会出现，不再提供「停止引用」入口
            }
            val sharedPopup = ActionManager.getInstance()
                .createActionPopupMenu("FavoritesToolWindowPopupMenu", sharedGroup)
            sharedPopup.component.show(this, e.x, e.y)
            return
        }
        // ////////////////  分支零 B  end  /////////////////////////////////////////////

        val group = DefaultActionGroup()
        val selectedBookmarks = getSelectedBookmarks()
        val selectedList = getSelectedList()
        // 右键目标对应的虚拟文件（文件收藏/文件夹收藏/真实目录/未收藏真实文件；纯虚拟目录与缺失目录为 null）
        val clickedTargetFile = resolveClickedVirtualFile(clickedUserObject)
        // 右键目标为可展开目录节点（文件夹收藏或真实目录）时提供「刷新此目录」
        val clickedDirNode = clickedUserObject as? DirNode
        // 右键点中的收藏（「重命名别名…」直接作用于它，不依赖 JTree 当前选中项）
        val clickedBookmark = bookmarkOfNode(path.lastPathComponent)
        // 列表操作作用域：优先列表节点自身 → 收藏所属列表 → 选中上下文作用域，兜底项目收藏
        val listScope = selectedList?.scope
            ?: selectedBookmarks.firstOrNull()?.scope
            ?: getSelectedScope()
            ?: FavoriteScope.PROJECT
        // 外来共享集合上下文（订阅集合节点之下）：只读，隐藏分组 CRUD（写操作只对自己的集合开放）
        val foreignSharedContext = ancestorSharedCollection(path)?.mine == false

        // ////////////////  添加目录…：作用域根/列表分组节点（仅项目/全局两条线）右键入口  start  //
        val directoryAddScope = when (val clicked = clickedUserObject) {
            is ScopeNode -> clicked.scope
            is BookmarkListNode -> clicked.scope
            else -> null
        }?.takeIf { it == FavoriteScope.PROJECT || it == FavoriteScope.GLOBAL }
        if (directoryAddScope != null) {
            // 列表/分组节点=加入该分组自身；作用域根=listName 为空（按现有固定作用域写入逻辑取目标分组）
            val targetListName = (clickedUserObject as? BookmarkListNode)?.info?.name
            group.add(object : AnAction(
                "添加目录…",
                "弹出目录选择器（仅可选目录），把选定目录加入「${directoryAddScope.label}」" +
                    (targetListName?.let { "的「$it」" } ?: ""),
                null
            ) {
                override fun actionPerformed(e: AnActionEvent) {
                    interactions.addDirectoryTo(directoryAddScope, targetListName)
                }
            })
            group.add(Separator.getInstance())
        }
        // ////////////////  添加目录…  end  ///////////////////////////////////////////

        // ////////////////  添加收藏：右键文件/文件夹首项入口（三作用域通用）  start  ////////
        // 目标=收藏文件/文件夹收藏/真实目录/懒加载未收藏真实子项时，复用平台注册的项目视图同款
        // 动作（Favorites.AddFavorite）：弹「作用域+分组」选择（记住上次选择）、已收藏则切换移除，
        // 目标经下方弹窗注入的 DataContext（VIRTUAL_FILE(_ARRAY)=右键节点）取得，与项目视图右键
        // 行为/文案完全一致；项目/共享/全局三作用域下均可用。动作取不到时跳过该项，不影响其余菜单
        if (clickedTargetFile != null) {
            runCatching { ActionManager.getInstance().getAction(ADD_FAVORITE_ACTION_ID) }
                .onFailure { log.warn("获取动作 $ADD_FAVORITE_ACTION_ID 失败（收藏树右键不显示添加收藏入口）", it) }
                .getOrNull()
                ?.let { group.add(it) }
        }
        // ////////////////  添加收藏  end  ////////////////////////////////////////////

        if (selectedBookmarks.isNotEmpty()) {
            group.add(object : AnAction("跳转", "跳转到收藏位置", null) {
                override fun actionPerformed(e: AnActionEvent) {
                    selectedBookmarks.firstOrNull()?.let { navigateToBookmark(it.bookmark) }
                }
            })
            // 重命名别名（1.7.2）：直接作用于右键点中的收藏，不依赖 JTree 当前选中项
            group.add(object : AnAction("重命名别名…", "重命名该收藏的别名（留空恢复默认显示）", null) {
                override fun actionPerformed(e: AnActionEvent) {
                    val target = clickedBookmark ?: selectedBookmarks.firstOrNull() ?: return
                    interactions.renameAlias(target)
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
            // ////////////////  未收藏真实/容器节点：仅提供跳转（添加收藏由上方统一注入）  start  ///
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

        // ////////////////  刷新此目录：文件夹收藏/真实目录（1.7.2）  start  //////////////
        val clickedDirTreeNode = path.lastPathComponent as? DefaultMutableTreeNode
        if (clickedDirNode != null && clickedDirNode.fsBacked && clickedDirTreeNode != null) {
            group.add(object : AnAction(
                "刷新此目录",
                "刷新该目录在磁盘上的内容并重新加载其子项（外部新增/删除的文件目录立即可见）",
                null
            ) {
                override fun actionPerformed(e: AnActionEvent) {
                    refreshDirFromDisk(clickedDirTreeNode, clickedDirNode)
                }
            })
        }
        // ////////////////  刷新此目录  end  ////////////////////////////////////////////

        // ////////////////  分组CRUD：分组节点=新建子分组；默认列表/其它上下文=新建顶级列表  //
        // 外来共享集合上下文只读，隐藏分组 CRUD
        val directListNode = (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? BookmarkListNode
        if (directListNode != null && directListNode.info.name != Bookmark.DEFAULT_LIST_NAME && !foreignSharedContext) {
            group.add(object : AnAction(
                "新建子分组",
                "在「${directListNode.info.name}」下新建子分组（名称全作用域唯一）",
                null
            ) {
                override fun actionPerformed(e: AnActionEvent) {
                    interactions.createSubGroup(listScope, directListNode.info.name)
                }
            })
        } else if (!foreignSharedContext) {
            group.add(object : AnAction("新建列表", "新建一个顶级收藏列表", null) {
                override fun actionPerformed(e: AnActionEvent) {
                    interactions.createList(listScope)
                }
            })
        }

        if (selectedList != null && !foreignSharedContext) {
            group.add(object : AnAction("重命名列表", "重命名选中的列表（默认列表不可重命名；改名不影响层级）", null) {
                override fun actionPerformed(e: AnActionEvent) {
                    interactions.renameList(selectedList.scope, selectedList.name)
                }
            })
            if (selectedList.name != Bookmark.DEFAULT_LIST_NAME) {
                group.add(object : AnAction(
                    "删除列表",
                    "删除选中的分组（其子分组一并删除，组内收藏并入默认列表）",
                    null
                ) {
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

        // ////////////////  项目视图操作子菜单（1.7.2）：直接复用平台 ProjectViewPopupMenu  start
        if (clickedTargetFile != null) {
            val projectViewActions = buildProjectViewActionsSubmenu(clickedTargetFile)
            if (projectViewActions != null) {
                group.add(Separator.getInstance())
                group.add(projectViewActions)
            }
        }
        // ////////////////  项目视图操作子菜单  end  ////////////////////////////////////

        // ////////////////  弹窗：注入与右键节点一致的 DataContext 后显示  start  ////////
        val popup = ActionManager.getInstance()
            .createActionPopupMenu("FavoritesToolWindowPopupMenu", group)
        // 平台项目视图动作（新建/删除/重命名/复制粘贴/同步等）按该 DataContext 取目标，确保作用在右键节点上
        if (clickedTargetFile != null) {
            runCatching {
                val baseContext = DataManager.getInstance().getDataContext(this)
                val actionContext = buildTargetDataContext(clickedTargetFile, baseContext)
                popup.setDataContext(java.util.function.Supplier { actionContext })
            }.onFailure { log.warn("设置收藏树右键菜单 DataContext 失败（平台动作将回退默认上下文）", it) }
        }
        popup.component.show(this, e.x, e.y)
        // ////////////////  弹窗  end  /////////////////////////////////////////////////
    }

    /**
     * 构建"移动到列表▸"子菜单（按作用域分组，组内按分组层级嵌套展示，当前所在列表置灰）
     *
     * - 同作用域列表：普通列表移动；
     * - 跨作用域列表：调用转移 API（路径转换+助记符降级规则由 FavoritesTransferService 处理）。
     */
    private fun buildMoveToListGroup(selectedBookmarks: List<ScopedBookmark>): DefaultActionGroup {
        val group = DefaultActionGroup("移动到列表", true)
        val current = selectedBookmarks.firstOrNull()

        // ////////////////  按作用域分组：组内按分组层级嵌套  start  /////////////////////
        for (scope in FavoriteScope.entries) {
            // 共享收藏的移动目标分组=自己集合的分组（外来集合只读，不作为目标）
            val manager = if (scope == FavoriteScope.SHARED) {
                SharedBookmarkManager.getInstance(project).ownCollectionManager()
            } else {
                managerFor(scope)
            }
            val scopeGroup = DefaultActionGroup(scope.label, true)
            appendMoveToListActions(scopeGroup, scope, manager, ListInfo.ROOT_PARENT_ID, current)
            group.add(scopeGroup)
        }
        // ////////////////  按作用域分组  end  /////////////////////////////////////////
        return group
    }

    /**
     * 递归追加「移动到列表」目标项（按分组层级嵌套 DefaultActionGroup）：
     * - 叶子分组：普通菜单项；
     * - 含子分组的分组：子菜单（首项=移动到本分组，其后为各子分组子菜单）；
     * - 当前所在（作用域+列表）置灰语义保持不变。
     *
     * @param parentGroup 挂载的动作组
     * @param scope 目标作用域
     * @param manager 目标作用域管理器
     * @param parentId 当前层级的父分组 id（""=顶级）
     * @param current 首个选中收藏（当前所在列表置灰用；null=不置灰）
     */
    private fun appendMoveToListActions(
        parentGroup: DefaultActionGroup,
        scope: FavoriteScope,
        manager: ScopedBookmarkManager,
        parentId: String,
        current: ScopedBookmark?
    ) {
        for (info in manager.childrenOf(parentId)) {
            val targetName = info.name
            val children = manager.childrenOf(info.id)
            if (children.isEmpty()) {
                parentGroup.add(buildMoveToListAction(scope, manager, targetName, current))
            } else {
                val subGroup = DefaultActionGroup(targetName, true)
                subGroup.add(buildMoveToListAction(scope, manager, targetName, current))
                appendMoveToListActions(subGroup, scope, manager, info.id, current)
                parentGroup.add(subGroup)
            }
        }
    }

    /**
     * 构建单个「移动到列表」目标项（描述带完整层级路径；当前所在列表置灰）
     */
    private fun buildMoveToListAction(
        scope: FavoriteScope,
        manager: ScopedBookmarkManager,
        targetName: String,
        current: ScopedBookmark?
    ): AnAction = object : AnAction(targetName, "移动到「${scope.label}」${manager.fullPath(targetName)}", null) {
        override fun actionPerformed(e: AnActionEvent) {
            interactions.moveSelectedTo(scope, targetName)
        }

        override fun update(e: AnActionEvent) {
            // 当前所在（作用域+列表）置灰
            e.presentation.isEnabled =
                !(current != null && current.scope == scope && current.bookmark.listName == targetName)
        }
    }

    /**
     * 沿父链向上查找选中路径所属的共享集合节点（供外来集合只读上下文判定；无返回 null）
     *
     * @param path 选中/点击路径
     * @return 所属共享集合节点，或 null（不在共享集合子树下）
     */
    private fun ancestorSharedCollection(path: TreePath): SharedCollectionNode? {
        // ////////////////  核心逻辑：沿父链找最近的共享集合节点  start  ///////////////////
        var cursor = path.lastPathComponent as? DefaultMutableTreeNode
        while (cursor != null) {
            (cursor.userObject as? SharedCollectionNode)?.let { return it }
            cursor = cursor.parent as? DefaultMutableTreeNode
        }
        // ////////////////  核心逻辑  end  /////////////////////////////////////////////
        return null
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

    /**
     * 解析右键节点对应的虚拟文件（1.7.2「项目视图操作」子菜单适用性判定）：
     * - [FileNode]：文件收藏本体或仅承载行收藏的文件容器，按节点路径解析；
     * - [DirNode]：仅文件夹收藏（bookmark != null）或真实目录（fromFs=true）——
     *   纯虚拟目录（仅承载收藏路径的目录链）不参与；
     * - [RealFileNode]：文件夹收藏展开出的真实子文件；
     * - 其它节点类型（作用域/分组/规则/共享集合/行收藏等）不参与。
     *
     * @param userObject 右键节点的用户对象
     * @return 虚拟文件；节点不适用或路径解析失败（缺失态）返回 null
     */
    private fun resolveClickedVirtualFile(userObject: Any?): VirtualFile? = when (userObject) {
        is FileNode -> resolveNodePath(userObject.scope, userObject.path)?.takeIf { it.isValid }
        is DirNode -> if (userObject.fsBacked) {
            resolveNodePath(userObject.scope, userObject.path)?.takeIf { it.isValid && it.isDirectory }
        } else {
            null
        }
        is RealFileNode -> userObject.virtualFile.takeIf { it.isValid }
        else -> null
    }

    /**
     * 构建「项目视图操作」子菜单（1.7.2）：直接取平台 `ProjectViewPopupMenu` 动作组的子项
     * （平台动作实例，不手写复制一份动作），从而与项目视图右键的行为/可用性/子菜单保持一致。
     *
     * - 原生书签组 `ExpandableBookmarkContextMenu` 排除（本插件已接管原生书签，避免冲突）；
     *   插件自己的 `Favorites.Group` 同样排除（平台菜单里也挂着它，避免子菜单自我嵌套）；
     * - 首尾/连续分隔线归一化；动作组取不到或过滤后为空时降级为不显示该子菜单（仅 warn 日志）；
     * - 全程 runCatching，任何异常只记日志、不影响其它菜单项。
     *
     * @param targetFile 右键节点对应的虚拟文件（仅用于日志与调用方语义，动作本身经 DataContext 取目标）
     * @return 子菜单动作组；不可用时返回 null
     */
    private fun buildProjectViewActionsSubmenu(targetFile: VirtualFile): DefaultActionGroup? {
        // ////////////////  核心逻辑：取平台动作组子项 + 过滤排除项 + 分隔线归一化  start  ///
        return runCatching {
            val actionManager = ActionManager.getInstance()
            val platformGroup = actionManager.getAction(PROJECT_VIEW_POPUP_MENU_ID) as? ActionGroup
            if (platformGroup == null) {
                log.warn(
                    "未找到平台动作组 $PROJECT_VIEW_POPUP_MENU_ID，收藏树右键不显示「$PROJECT_VIEW_ACTIONS_TITLE」子菜单" +
                        "（目标：${targetFile.path}）"
                )
                return null
            }

            val submenu = DefaultActionGroup(PROJECT_VIEW_ACTIONS_TITLE, true)
            val keptActions = mutableListOf<AnAction>()
            val excludedIds = mutableListOf<String>()
            for (child in platformGroup.getChildren(null)) {
                if (isExcludedProjectViewAction(child, actionManager, excludedIds)) continue
                if (child is Separator) {
                    // 首部分隔线 / 连续分隔线丢弃，尾部在循环后统一裁剪
                    if (keptActions.isEmpty() || keptActions.last() is Separator) continue
                    keptActions.add(child)
                    continue
                }
                keptActions.add(child)
            }
            while (keptActions.isNotEmpty() && keptActions.last() is Separator) {
                keptActions.removeAt(keptActions.size - 1)
            }
            if (excludedIds.isNotEmpty()) {
                log.info("收藏树「$PROJECT_VIEW_ACTIONS_TITLE」已排除平台菜单项：${excludedIds.joinToString()}")
            }
            if (keptActions.isEmpty()) {
                log.warn(
                    "平台动作组 $PROJECT_VIEW_POPUP_MENU_ID 过滤后无可用子项，收藏树右键不显示「$PROJECT_VIEW_ACTIONS_TITLE」子菜单" +
                        "（目标：${targetFile.path}）"
                )
                return null
            }
            keptActions.forEach { submenu.add(it) }
            submenu
        }.onFailure {
            log.warn("构建收藏树右键「$PROJECT_VIEW_ACTIONS_TITLE」子菜单失败（仅保留收藏操作）", it)
        }.getOrNull()
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////
    }

    /**
     * 判断平台菜单项是否应从「项目视图操作」子菜单排除（1.7.2）：
     * 原生书签组（本插件已接管）+ 插件自己的「收藏」组（避免子菜单自我嵌套）。
     * 以 [ActionManager.getId] 为主判据（XML 中定义的动作组 id 原样返回，含 `popup@` 前缀）；
     * 取不到 id 时按类名/文案兜底，避免动态动作漏过滤。
     *
     * @param action 平台子项（可能是动作、动作组或分隔符）
     * @param actionManager 动作管理器（取动作 id）
     * @param excludedIds 排除结果收集（日志输出用）
     * @return true=排除
     */
    private fun isExcludedProjectViewAction(
        action: AnAction,
        actionManager: ActionManager,
        excludedIds: MutableList<String>
    ): Boolean {
        // ////////////////  校验参数：分隔符不参与排除判定  start  //////////////////////
        if (action is Separator) return false
        // ////////////////  校验参数  end  ////////////////////////////////////////////

        // ////////////////  核心逻辑：id 优先，取不到时类名/文案兜底  start  /////////////
        val actionId = runCatching { actionManager.getId(action) }
            .onFailure { log.warn("取平台菜单项 id 失败：${action.javaClass.name}", it) }
            .getOrNull()
        val excluded = if (actionId != null) {
            actionId in EXCLUDED_PROJECT_VIEW_ACTION_IDS
        } else {
            val templateText = runCatching { action.templatePresentation.text }.getOrNull()
            action.javaClass.name.contains(EXCLUDED_NATIVE_BOOKMARK_GROUP_NAME) ||
                templateText in EXCLUDED_PROJECT_VIEW_GROUP_TEXTS
        }
        if (excluded) excludedIds.add(actionId ?: action.javaClass.name)
        return excluded
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////
    }

    /**
     * 构建收藏树右键的 DataContext（1.7.2）：为平台项目视图动作提供与右键节点一致的目标，
     * 保证「新建/删除/重命名/复制粘贴/同步」等动作作用在右键点中的文件/文件夹上，
     * 而不是用户之前在其他视图里选中的其它文件（硬性安全要求）。
     *
     * 已知键返回目标节点对应值（取不到时的 null 也直接返回，不回退基础上下文——避免误用其它选中项）：
     * PROJECT / VIRTUAL_FILE(_ARRAY) / PSI_ELEMENT / PSI_FILE / PSI_ELEMENT_ARRAY / NAVIGATABLE(_ARRAY) /
     * IDE_VIEW / CONTEXT_COMPONENT / DELETE_ELEMENT_PROVIDER（目标删除器：只删右键目标，不碰项目视图选区）；
     * 未识别的键回退到 [baseContext]，任何键都不抛异常（异常仅记日志并按 null 返回）。
     *
     * @param targetFile 右键节点解析出的虚拟文件
     * @param baseContext 基础上下文（树组件处的平台上下文）
     * @return 目标节点上下文
     */
    private fun buildTargetDataContext(targetFile: VirtualFile, baseContext: DataContext): DataContext {
        // ////////////////  准备数据：目标节点的 PSI 元素 / PSI 文件 / NAVIGATABLE / IDE_VIEW  start  ////
        val psiElement = psiElementOf(targetFile)
        // PsiFile 供部分平台动作（按 PSI_FILE 优先取目标）使用
        val psiFile = psiElement as? PsiFile
        // PsiFile/PsiDirectory 均实现 Navigatable 时才可作为 NAVIGATABLE 提供
        val navigatable = psiElement as? Navigatable
        val targetIdeView = BookmarkTargetIdeView(targetFile)
        val virtualFileArray = arrayOf(targetFile)
        val psiElementArray = psiElement?.let { arrayOf(it) }
        val navigatableArray = navigatable?.let { arrayOf(it) }
        // ////////////////  准备数据  end  /////////////////////////////////////////////

        // ////////////////  核心逻辑：已知键返回目标值，未知键回退基础上下文  start  //////
        return DataContext { dataId ->
            when (dataId) {
                CommonDataKeys.PROJECT.name -> project
                CommonDataKeys.VIRTUAL_FILE.name -> targetFile
                CommonDataKeys.VIRTUAL_FILE_ARRAY.name -> virtualFileArray
                CommonDataKeys.PSI_ELEMENT.name -> psiElement
                CommonDataKeys.PSI_FILE.name -> psiFile
                PlatformCoreDataKeys.PSI_ELEMENT_ARRAY.name -> psiElementArray
                CommonDataKeys.NAVIGATABLE.name -> navigatable
                CommonDataKeys.NAVIGATABLE_ARRAY.name -> navigatableArray
                PlatformDataKeys.DELETE_ELEMENT_PROVIDER.name -> targetDeleteProvider
                DATA_ID_IDE_VIEW -> targetIdeView
                PlatformCoreDataKeys.CONTEXT_COMPONENT.name -> this@BookmarksTree
                else -> runCatching { baseContext.getData(dataId) }
                    .onFailure { log.warn("读取右键菜单基础上下文失败（键：$dataId）", it) }
                    .getOrNull()
            }
        }
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////
    }

    /**
     * 解析虚拟文件对应的 PSI 元素（文件→PsiFile，目录→PsiDirectory）：
     * 平台 PSI 解析需读动作保护，统一在 [ApplicationManager.runReadAction] 内执行；
     * 解析失败/文件无效返回 null（不抛异常，平台动作按"无 PSI 元素"降级）。
     *
     * @param file 虚拟文件
     * @return PSI 元素或 null
     */
    private fun psiElementOf(file: VirtualFile): PsiElement? = runCatching {
        ApplicationManager.getApplication().runReadAction<PsiElement?> {
            if (!file.isValid) return@runReadAction null
            val psiManager = PsiManager.getInstance(project)
            if (file.isDirectory) psiManager.findDirectory(file) else psiManager.findFile(file)
        }
    }.onFailure { log.warn("解析右键目标的 PSI 元素失败：${file.path}", it) }.getOrNull()

    /**
     * 轻量 IdeView 适配器（1.7.2）：按右键目标文件返回其所在目录的 PsiDirectory。
     *
     * 选择实现本适配器（方案 b）而不是「先用 ProjectView.select 选中目标再传真实 ProjectViewPane」（方案 a）：
     * - 方案 a 的 `select` 对不在项目视图树里的目标（全局收藏的项目外文件、被排除文件等）会静默失败，
     *   真实 ProjectViewPane 仍指向用户之前选中的节点——此时删除/重命名等会作用到错误文件（不安全）；
     * - 本适配器的一切返回值都由右键节点派生，不读取也不改动用户在项目视图中的选中状态，天然不会误伤；
     * - 代价：依赖项目视图面板自身提供的数据（如粘贴用的 PasteProvider）不会由本适配器伪造，
     *   相关菜单项会被平台按"上下文不可用"置灰，属于安全降级（不会作用到错误目标）。
     */
    private inner class BookmarkTargetIdeView(private val targetFile: VirtualFile) : IdeView {

        /**
         * 目标文件/目录所在目录（文件取父目录、目录取自身；解析不到返回空数组）
         */
        override fun getDirectories(): Array<PsiDirectory> {
            val directoryFile = if (targetFile.isDirectory) targetFile else targetFile.parent
            val psiDirectory = directoryFile?.let { psiElementOf(it) as? PsiDirectory }
            return psiDirectory?.let { arrayOf(it) } ?: emptyArray()
        }

        /**
         * 目标目录（用于"新建"类动作；不做目录选择弹窗，始终取右键目标，避免选到其它位置）
         */
        override fun getOrChooseDirectory(): PsiDirectory? = getDirectories().firstOrNull()
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
     * 解析节点路径对应的虚拟文件（节点路径已规范化：项目作用域=项目根相对；共享/全局作用域=绝对）
     *
     * @param scope 节点所属作用域
     * @param path 规范化路径（""=作用域基准目录）
     * @return 虚拟文件或 null（不存在/离线）
     */
    private fun resolveNodePath(scope: FavoriteScope, path: String): VirtualFile? =
        resolveByAbsolutePath(path, absolute = scope != FavoriteScope.PROJECT)

    /**
     * 按路径与绝对性解析虚拟文件（统一入口，供收藏/节点两类解析复用）：
     * 实现全部委托给共享解析器 [BookmarkFileResolver.findExistingFile]（1.7.3 起收藏树、快速访问弹窗、
     * 助记符跳转共用同一套「路径宏展开候选 → 逐候选查 VFS → 每候选绝对路径一次同步刷新兜底」逻辑）
     *
     * @param path 路径字符串（""=作用域基准目录）
     * @param absolute 是否绝对路径语义
     * @return 虚拟文件或 null（全部候选都不存在或兜底不可用）
     */
    private fun resolveByAbsolutePath(path: String, absolute: Boolean): VirtualFile? =
        BookmarkFileResolver.findExistingFile(path, project, absolute)

    /**
     * 路径是否解析为可读目录（文件夹收藏展开能力判定）
     */
    private fun isReadableDirectory(file: VirtualFile?): Boolean =
        file != null && file.isValid && file.isDirectory

    /**
     * 将骨架目录下的子项物化为树节点（目录条目在前、文件条目在后，保持收藏排序出现顺序）。
     * 幂等：同一目录节点只物化一次骨架子项（刷新时公共祖先/基准目录节点已立即物化过则跳过，
     * 防止展开懒加载重复追加产生同路径成对文件节点）；缺失态目录（allowsChildren=false）不挂子项。
     *
     * @param parentTreeNode 挂载的树节点
     * @param scope 作用域
     * @param listName 列表名
     * @param spine 列表文件系统骨架
     * @param dirPath 目录路径键（""=基准目录）
     * @param collectionId 共享作用域下的集合 id（目录节点骨架键隔离；其它作用域传 ""）
     */
    private fun appendSpineChildren(
        parentTreeNode: DefaultMutableTreeNode,
        scope: FavoriteScope,
        listName: String,
        spine: FavoritesListSpine,
        dirPath: String,
        collectionId: String = ""
    ) {
        // ////////////////  校验参数：骨架子项只物化一次 + 缺失态目录不挂子项  start  ///////
        val parentDir = parentTreeNode.userObject as? DirNode
        if (parentDir != null && parentDir.spineChildrenLoaded) return
        if (!parentTreeNode.allowsChildren) return
        // ////////////////  校验参数  end  ////////////////////////////////////////////

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
                                missing = missing,
                                collectionId = collectionId
                            ),
                            !missing
                        )
                    )
                }

                is SpineFileEntry -> parentTreeNode.add(buildFileTreeNode(scope, listName, entry))
            }
        }
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////

        // ////////////////  逻辑后：标记骨架子项已物化（展开懒加载不再重复追加）  start  //
        parentDir?.spineChildrenLoaded = true
        // ////////////////  逻辑后  end  /////////////////////////////////////////////
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
        val treeNode = DefaultMutableTreeNode(fileNode, entry.lineBookmarks.isNotEmpty())

        // ////////////////  核心逻辑：行收藏子节点（"行号: 行内容"，EDT 安全解析+异步兜底）  start
        for (lineBookmark in entry.lineBookmarks) {
            // 行收藏为叶子节点（不可展开，不出空展开把手）
            treeNode.add(
                DefaultMutableTreeNode(
                    LineEntryNode(scope, listName, lineBookmark, resolveLineContent(lineBookmark)),
                    false
                )
            )
        }
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////
        return treeNode
    }

    /**
     * 解析行收藏的行内容（EDT 安全，不读盘）：
     * 1) 命中缓存直接返回；
     * 2) 文件 Document 已加载（[FileDocumentManager.getCachedDocument]）时从 Document 读取并写缓存；
     * 3) 均未命中（未打开/未加载/大文件/二进制）返回占位「…」并调度后台异步加载，
     *    加载完成回 EDT 更新缓存与受影响节点（nodeChanged，不整棵重建，保持展开态）
     *
     * @param bookmark 行收藏
     * @return 行文本；「…」=异步加载中；null=空白行/读取失败（显示时回退收藏描述）
     */
    private fun resolveLineContent(bookmark: Bookmark): String? {
        // ////////////////  校验参数  start  ///////////////////////////////////////////
        if (!bookmark.isLineBookmark() || bookmark.lineNumber < 0) return null
        val cacheKey = lineCacheKey(bookmark) ?: return null
        // ////////////////  校验参数  end  /////////////////////////////////////////////

        // ////////////////  分支一：命中缓存直接返回  start  /////////////////////////////
        val lineCache = lineContentCache[cacheKey]
        if (lineCache != null && lineCache.containsKey(bookmark.lineNumber)) {
            return lineCache[bookmark.lineNumber]?.takeIf { it.isNotEmpty() }
        }
        // ////////////////  分支一  end  ///////////////////////////////////////////////

        // ////////////////  分支二：仅从已加载 Document 读（getCachedDocument 不触盘）  ///
        val document = resolveVirtualFile(bookmark)?.let { file ->
            runCatching { FileDocumentManager.getInstance().getCachedDocument(file) }.getOrNull()
        }
        if (document == null) {
            // 未加载：先显示占位，再交后台线程异步加载
            scheduleLineContentLoad(bookmark, cacheKey)
            return LINE_CONTENT_PENDING
        }
        val text = runCatching { extractLineText(document, bookmark.lineNumber) }.getOrNull()
        lineCacheText(cacheKey, bookmark.lineNumber, text)
        return text
        // ////////////////  分支二  end  ///////////////////////////////////////////////
    }

    /**
     * 后台异步加载行内容（后台线程读动作内加载 Document；文件缺失/二进制/超大/行号越界按读取失败处理），
     * 完成后回 EDT 写缓存并仅刷新受影响文件下的行收藏节点（不整棵重建，保持展开态）
     *
     * @param bookmark 行收藏
     * @param cacheKey 缓存键（文件绝对路径）
     */
    private fun scheduleLineContentLoad(bookmark: Bookmark, cacheKey: String) {
        // 同一文件同一行只调度一次（过滤输入等高频刷新下去重）
        val requestKey = "$cacheKey#${bookmark.lineNumber}"
        if (!pendingLineLoadKeys.add(requestKey)) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val text = runCatching {
                ApplicationManager.getApplication().runReadAction<String> {
                    if (project.isDisposed) return@runReadAction ""
                    val file = resolveVirtualFile(bookmark) ?: return@runReadAction ""
                    if (!file.isValid) return@runReadAction ""
                    val tooLarge = runCatching { file.length > MAX_LINE_SOURCE_FILE_LENGTH }.getOrDefault(true)
                    val binary = runCatching { file.fileType.isBinary }.getOrDefault(true)
                    if (tooLarge || binary) return@runReadAction ""
                    val document = FileDocumentManager.getInstance().getDocument(file) ?: return@runReadAction ""
                    if (bookmark.lineNumber >= document.lineCount) return@runReadAction ""
                    extractLineText(document, bookmark.lineNumber)
                }
            }.getOrDefault("")
            ApplicationManager.getApplication().invokeLater {
                pendingLineLoadKeys.remove(requestKey)
                if (project.isDisposed) return@invokeLater
                lineCacheText(cacheKey, bookmark.lineNumber, text.takeIf { it.isNotEmpty() })
                refreshLineContentsForPath(cacheKey)
            }
        }
    }

    /**
     * 刷新指定文件下全部行收藏节点的行内容（缓存/已加载 Document 优先，未命中异步兜底），
     * 仅对受影响节点 nodeChanged（不整棵重建，保持展开态）
     *
     * @param cacheKey 文件绝对路径（正斜杠规范）
     */
    private fun refreshLineContentsForPath(cacheKey: String) {
        // ////////////////  校验参数：模型存在才可遍历  start  ///////////////////////////
        val treeModel = model as? DefaultTreeModel ?: return
        val root = treeModel.root as? DefaultMutableTreeNode ?: return
        // ////////////////  校验参数  end  ////////////////////////////////////////////

        // ////////////////  核心逻辑：收集受影响节点并原位更新  start  ////////////////////
        val affected = mutableListOf<DefaultMutableTreeNode>()
        walkTreeNodes(root) { node ->
            val line = node.userObject as? LineEntryNode ?: return@walkTreeNodes
            if (lineCacheKey(line.bookmark) == cacheKey) {
                line.contentText = resolveLineContent(line.bookmark)
                affected.add(node)
            }
        }
        affected.forEach { treeModel.nodeChanged(it) }
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////
    }

    /**
     * 注册行内容实时更新监听：VirtualFileManager 的 contentChanged 覆盖保存与外部改动，
     * fileDeleted 清理对应缓存；监听挂 [lineContentDisposable] 并注册到项目，项目关闭时自动注销
     */
    private fun registerLineContentListener() {
        runCatching { Disposer.register(project, lineContentDisposable) }
            .onFailure { log.warn("注册行内容资源注销句柄失败", it) }
        runCatching {
            VirtualFileManager.getInstance().addVirtualFileListener(object : VirtualFileListener {
                override fun contentsChanged(event: VirtualFileEvent) {
                    if (project.isDisposed) return
                    // VFS 事件可能在非 EDT 线程触发（同步刷新等），统一转发 EDT 处理（防抖集合/树访问仅 EDT）
                    runOnEdt { scheduleLineRefreshForPath(event.file.path) }
                }

                override fun fileDeleted(event: VirtualFileEvent) {
                    runOnEdt { dropLineContentCache(event.file.path) }
                }
            }, lineContentDisposable)
        }.onFailure { log.warn("注册文件内容变更监听失败", it) }
    }

    /**
     * 在 EDT 执行动作（已在 EDT 直接执行，否则 invokeLater 转发；项目已关闭时忽略）
     *
     * @param action 待执行动作
     */
    private fun runOnEdt(action: () -> Unit) {
        val application = ApplicationManager.getApplication() ?: return
        if (application.isDispatchThread) {
            action()
        } else {
            application.invokeLater {
                if (!project.isDisposed) action()
            }
        }
    }

    /**
     * 防抖调度行内容刷新：树中存在该文件的行收藏节点时记入待刷新集合并重置定时器，
     * ~500ms 无新变更后仅刷新受影响文件的行内容（连续保存/外部改动合并为一次）
     *
     * @param path 变更文件的绝对路径（VFS 路径）
     */
    private fun scheduleLineRefreshForPath(path: String) {
        if (!hasLineEntryForPath(path)) return
        pendingLineRefreshPaths.add(path)
        val timer = lineRefreshTimer ?: Timer(LINE_REFRESH_DEBOUNCE_MS, null).also {
            it.isRepeats = false
            it.addActionListener {
                // Swing Timer 到点在 EDT 执行；pendingLineRefreshPaths 仅 EDT 访问
                val paths = pendingLineRefreshPaths.toList()
                pendingLineRefreshPaths.clear()
                paths.forEach { refreshLineContentsForPath(it) }
            }
            lineRefreshTimer = it
        }
        timer.restart()
    }

    /**
     * 树中是否存在指定文件的行收藏节点（无关文件的内容变更直接跳过，省去防抖与刷新开销）
     *
     * @param path 文件绝对路径（VFS 路径）
     * @return true=存在
     */
    private fun hasLineEntryForPath(path: String): Boolean {
        val root = (model as? DefaultTreeModel)?.root as? DefaultMutableTreeNode ?: return false
        var found = false
        walkTreeNodes(root) { node ->
            val line = node.userObject as? LineEntryNode ?: return@walkTreeNodes
            if (!found && lineCacheKey(line.bookmark) == path) found = true
        }
        return found
    }

    /**
     * 文件删除时清理对应行内容缓存（避免无效缓存驻留内存）
     *
     * @param path 被删除文件的绝对路径（VFS 路径）
     */
    private fun dropLineContentCache(path: String) {
        lineContentCache.remove(path)
    }

    /**
     * 树刷新后裁剪行内容缓存：仅保留当前树中行收藏仍引用的文件（避免内存无限增长）
     */
    private fun pruneLineContentCache() {
        val root = (model as? DefaultTreeModel)?.root as? DefaultMutableTreeNode ?: return
        val referenced = HashSet<String>()
        walkTreeNodes(root) { node ->
            val line = node.userObject as? LineEntryNode ?: return@walkTreeNodes
            lineCacheKey(line.bookmark)?.let { referenced.add(it) }
        }
        lineContentCache.keys.retainAll(referenced)
    }

    /**
     * 写入行内容缓存（空串统一归一为 null，节点显示时回退收藏描述）
     *
     * @param cacheKey 缓存键（文件绝对路径）
     * @param lineNumber 行号（0 基）
     * @param text 行文本（空串/null 均存 null）
     */
    private fun lineCacheText(cacheKey: String, lineNumber: Int, text: String?) {
        lineContentCache.getOrPut(cacheKey) { HashMap() }[lineNumber] = text?.takeIf { it.isNotEmpty() }
    }

    /**
     * 行内容缓存键：收藏路径换算成绝对路径并统一正斜杠（与 VFS 文件路径一致，供文件变动监听比对）
     *
     * @param bookmark 行收藏
     * @return 缓存键；无法换算（无项目基准路径）返回 null
     */
    private fun lineCacheKey(bookmark: Bookmark): String? {
        // ////////////////  校验参数  start  ///////////////////////////////////////////
        if (bookmark.filePath.isBlank()) return null
        // ////////////////  校验参数  end  /////////////////////////////////////////////

        // ////////////////  核心逻辑：绝对路径语义直用，项目作用域拼项目根  start  ///////////
        return if (FavoritesTreePaths.isAbsolutePath(bookmark.filePath)) {
            bookmark.filePath.replace('\\', '/')
        } else {
            project.basePath?.let { basePath -> File(basePath, bookmark.filePath).absolutePath.replace('\\', '/') }
        }
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////
    }

    /**
     * 从 Document 提取目标行文本（压缩空白保证树节点单行展示；超长按显示上限截断加省略号）
     *
     * @param document 已加载文档
     * @param lineNumber 行号（0 基，越界抛异常由调用方兜底）
     * @return 行文本（空白行为空串）
     */
    private fun extractLineText(document: Document, lineNumber: Int): String {
        val lineStart = document.getLineStartOffset(lineNumber)
        val lineEnd = minOf(document.getLineEndOffset(lineNumber), lineStart + LINE_READ_SAMPLE_LENGTH)
        val raw = document.getText(TextRange(lineStart, lineEnd)).replace(Regex("\\s+"), " ").trim()
        return when {
            raw.isEmpty() -> ""
            raw.length > MAX_LINE_TEXT_LENGTH -> raw.take(MAX_LINE_TEXT_LENGTH) + "…"
            else -> raw
        }
    }

    /**
     * 深度优先遍历树节点（含自身，递归辅助；行内容原位更新/缓存裁剪共用）
     *
     * @param node 当前树节点
     * @param action 节点访问动作
     */
    private fun walkTreeNodes(node: DefaultMutableTreeNode, action: (DefaultMutableTreeNode) -> Unit) {
        action(node)
        for (i in 0 until node.childCount) {
            walkTreeNodes(node.getChildAt(i) as DefaultMutableTreeNode, action)
        }
    }

    /**
     * 懒加载目录节点子项：骨架子项（虚拟目录链/文件/行收藏）未物化时补建（[appendSpineChildren] 幂等，
     * 刷新时已物化过的公共祖先/基准目录自动跳过，防重复节点）；
     * 文件夹收藏目录/真实目录再合并真实文件系统子项（收藏物化节点优先，同名真实子项跳过去重）
     *
     * @param treeNode 目录节点对应的树节点
     * @param dir 目录节点用户对象
     */
    private fun loadDirChildren(treeNode: DefaultMutableTreeNode, dir: DirNode) {
        // ////////////////  校验参数  start  ///////////////////////////////////////////
        if (dir.childrenLoaded) return
        val spine = listSpines[spineKey(dir.scope, dir.listName, dir.collectionId)]
        // ////////////////  校验参数  end  /////////////////////////////////////////////

        // ////////////////  核心逻辑一：骨架子项（虚拟目录链+文件/行收藏）  start  /////////
        if (spine != null) {
            appendSpineChildren(treeNode, dir.scope, dir.listName, spine, dir.path, dir.collectionId)
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
                                path = FavoritesTreePaths.childPath(
                                    dir.path,
                                    child.name,
                                    dir.scope != FavoriteScope.PROJECT
                                ),
                                name = child.name,
                                bookmark = null,
                                fromFs = true,
                                missing = false,
                                collectionId = dir.collectionId
                            )
                        )
                    } else {
                        // 未收藏真实文件为叶子节点（不可展开）
                        DefaultMutableTreeNode(RealFileNode(child), false)
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
     * 刷新单个目录节点（1.7.2 右键「刷新此目录」）：
     * 1) 目录本身缺失/不可解析 → 退化为整树重建（重算缺失态，保持现有语义、不抛异常）；
     * 2) 可解析 → 平台 [RefreshQueue] 异步刷新该目录 VFS（recursive=true，不在 EDT 阻塞），
     *    回调回 EDT 原位重置该节点并重新懒加载子项（已展开的目录保持展开）。
     *
     * @param treeNode 目录节点对应的树节点（右键点中时的节点实例）
     * @param dir 目录节点用户对象
     */
    private fun refreshDirFromDisk(treeNode: DefaultMutableTreeNode, dir: DirNode) {
        // ////////////////  准备数据：允许该路径重做一次 VFS 未收录兜底  start  ///////////
        // 目录此前被判缺失时可能已在「每路径一次」兜底里失败过；用户显式刷新意味着重新触盘，
        // 先移除该路径的一次性标记，让后续 resolve 能重新尝试刷新 VFS（外部新建目录立即可见）
        BookmarkFileResolver.forgetRefreshTriedPath(dir.path, project, dir.scope != FavoriteScope.PROJECT)
        // ////////////////  准备数据  end  ////////////////////////////////////////////

        // ////////////////  校验参数：目录缺失/不可解析时整树重建  start  /////////////////
        if (dir.missing) {
            refresh(filterText)
            return
        }
        val dirFile = resolveNodePath(dir.scope, dir.path)
        if (dirFile == null || !isReadableDirectory(dirFile)) {
            // 目录不在 VFS 或已变成非目录：整树重建重算缺失态（不抛异常）
            refresh(filterText)
            return
        }
        // ////////////////  校验参数  end  ////////////////////////////////////////////

        // ////////////////  核心逻辑：异步刷新该目录 VFS，回调回 EDT 原位重置并重新懒加载  start
        runCatching {
            RefreshQueue.getInstance().refresh(true, true, Runnable {
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    reloadDirChildren(treeNode, dir)
                }
            }, listOf(dirFile))
        }.onFailure { e ->
            log.warn("刷新目录失败，退化为整树重建：${dir.path}", e)
            refresh(filterText)
        }
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////
    }

    /**
     * 目录子项重新懒加载（EDT）：仅在该节点仍属于当前树模型时执行——
     * 清空已加载标记与子节点后重新走 [loadDirChildren]（骨架子项 + 真实子项），
     * 刷新前处于展开态的节点及其子级目录保持展开
     *
     * @param treeNode 目录节点对应的树节点
     * @param dir 目录节点用户对象
     */
    private fun reloadDirChildren(treeNode: DefaultMutableTreeNode, dir: DirNode) {
        // ////////////////  校验参数：节点仍在当前树内（整树重建后旧节点已脱离模型）  start
        val treeModel = model as? DefaultTreeModel ?: return
        if (treeNode.root !== treeModel.root) return
        // ////////////////  校验参数  end  ////////////////////////////////////////////

        // ////////////////  准备数据：记录刷新前的展开态（含子级目录）  start  ////////////
        val wasExpanded = isExpanded(TreePath(treeNode.path))
        val expandedDirKeys = HashSet<String>()
        walkForExpandedDirKeys(treeNode, expandedDirKeys)
        // ////////////////  准备数据  end  ////////////////////////////////////////////

        // ////////////////  核心逻辑：清空子节点后重新懒加载  start  /////////////////////
        dir.childrenLoaded = false
        dir.spineChildrenLoaded = false
        treeNode.removeAllChildren()
        if (wasExpanded) {
            // 重新物化子项（loadDirChildren 内部会 nodeStructureChanged）并保持展开
            loadDirChildren(treeNode, dir)
            expandPath(TreePath(treeNode.path))
            // 子级目录按刷新前记忆恢复展开（逐级触发懒加载），避免刷新子项后深层目录全部折叠
            restoreExpansion(treeNode, expandedDirKeys)
        } else {
            treeModel.nodeStructureChanged(treeNode)
        }
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////
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
            // ////////////////  作用域/集合/列表/规则根/规则层级：始终展开并继续向下  start  //
            is ScopeNode, is SharedCollectionNode, is BookmarkListNode, is RulesRootNode, is RuleNode -> {
                expandPath(TreePath(node.path))
                for (child in node.children()) {
                    restoreExpansion(child as DefaultMutableTreeNode, expandedDirKeys)
                }
            }
            // ////////////////  作用域/集合/列表/规则根/规则层级  end  ////////////////////////

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
     * 列表骨架查找键（作用域+集合 id+列表名；集合 id 用于共享作用域跨集合同名列表隔离）
     */
    private fun spineKey(scope: FavoriteScope, listName: String, collectionId: String = ""): String =
        "${scope.name}|$collectionId|$listName"

    /**
     * 目录节点展开记忆键（作用域+集合 id+列表名+目录路径）
     */
    private fun dirKey(dir: DirNode): String = "${dir.scope.name}|${dir.collectionId}|${dir.listName}|${dir.path}"
}
