package com.aogg.favorites.ui

import com.aogg.favorites.action.AddActionSupport
import com.aogg.favorites.helper.BookmarkIconHelper
import com.aogg.favorites.model.Bookmark
import com.aogg.favorites.model.FavoriteScope
import com.aogg.favorites.model.FavoritesScopeResolver
import com.aogg.favorites.model.ListInfo
import com.aogg.favorites.settings.FavoritesSettings
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import java.awt.event.ActionEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.JTree
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * 添加收藏时的「作用域 + 分组」树选择弹窗（1.5.x 新增，替代 AddScopeChooser 的职责）
 *
 * 行为约定（与需求一致）：
 * - 弹分组树：根为两个作用域节点「项目收藏」「全局收藏」（沿用 BookmarkIconHelper.getScopeIcon 图标），
 *   其下按 childrenOf 递归展示该作用域的分组树（含默认列表「收藏」置顶；默认列表不可作为子分组但可选为目标）；
 * - 输入即过滤：连续键入字母/数字时按「作用域名/分组完整路径」做前置匹配（自带过滤，兼容 233~262），
 *   命中即选中并滚动到位，短暂停顿自动清空过滤词；
 * - Enter / 双击 = 选中该「作用域+分组」（选中作用域根节点等价于选其默认列表「收藏」）；
 *   Esc / 点击弹窗外 = 取消（取消则不添加任何收藏，onChosen 不会被触发）；
 * - 记住上次选择：按作用域记住上次使用的分组（FavoritesSettings.lastGroupPerScope，与 lastAddScope 同一存储模式），
 *   弹窗打开时默认选中「上次作用域 + 该作用域的上次分组」（高亮 + 滚动到位）；
 *   记住的分组已被删除时回退默认「收藏」（复用 AddActionSupport.resolveTargetGroup）；
 * - 确认选择后同时记住作用域与分组，供下次默认选中与原生快捷键接管路径共用；
 * - 「已存在收藏 → 切换移除」语义由调用方（AddAnonymousBookmarkAction 等）在弹窗之前/之后处理，本弹窗不感知。
 */
object ScopeGroupChooserPopup {

    /**
     * 过滤输入的重置间隔（毫秒）：停顿超过该时长视为新一次搜索
     */
    private const val SEARCH_RESET_DELAY_MS = 1200

    /**
     * 弹窗内容首选尺寸（宽 x 高，像素）
     */
    private val POPUP_SIZE = JBUI.size(320, 360)

    /**
     * Enter 确认 / Esc 取消在树上的 InputMap/ActionMap 键名
     */
    private const val ACTION_CONFIRM = "favoritesChooseScopeGroup"
    private const val ACTION_CANCEL = "favoritesCancelScopeGroup"

    /**
     * 树节点负载：区分作用域根节点与分组节点
     */
    private sealed class NodePayload {

        /**
         * 作用域根节点
         *
         * @param scope 作用域（选中等价于选该作用域默认列表「收藏」）
         */
        class ScopeNode(val scope: FavoriteScope) {
            override fun toString(): String = scope.label
        }

        /**
         * 分组节点
         *
         * @param scope 所属作用域（确认时回传）
         * @param listName 分组名（作用域内唯一，作为目标分组写入）
         * @param path 完整层级路径（tooltip 展示用，如「父组/子组」）
         * @param isDefault 是否默认列表「收藏」（置顶、无子分组）
         */
        class GroupNode(val scope: FavoriteScope, val listName: String, val path: String, val isDefault: Boolean) {
            override fun toString(): String = listName
        }
    }

    /**
     * 弹出「作用域+分组」树选择，用户确认后回调 [onChosen]（取消弹窗不回调）
     *
     * @param project 当前项目（解析两作用域管理器的分组树）
     * @param onChosen 确认回调，参数为 (作用域, 目标分组名)，在 EDT 执行（弹窗已关闭后触发）
     */
    fun chooseScopeAndGroup(project: Project, onChosen: (scope: FavoriteScope, groupName: String) -> Unit) {
        // ////////////////  准备数据：默认选中 = 上次作用域 + 该作用域上次分组  start  ////////
        val lastScope = runCatching { FavoritesSettings.getInstance().getLastAddScope() }
            .getOrDefault(FavoriteScope.PROJECT)
        val lastGroup = AddActionSupport.resolveTargetGroup(project, lastScope)
        // ////////////////  准备数据  end  /////////////////////////////////////////////

        // ////////////////  核心逻辑：构建分组树与弹窗  start  //////////////////////////
        val rootNode = buildTreeModel(project)
        val tree = Tree(DefaultTreeModel(rootNode)).apply {
            isRootVisible = false
            showsRootHandles = true
            selectionModel.selectionMode = javax.swing.tree.TreeSelectionModel.SINGLE_TREE_SELECTION
            cellRenderer = ScopeGroupNodeRenderer()
            // 自带输入过滤：连续键入即按路径前置匹配并选中
            addKeyListener(TypeAheadSelector(this))
            installDoubleClickConfirm(this)
        }
        expandAllRows(tree)

        // 弹窗内容：滚动容器指定首选尺寸，避免分组多时弹窗过高/分组少时过窄
        val content = JBScrollPane(tree).apply { preferredSize = POPUP_SIZE }

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, tree)
            .setTitle("添加到哪个收藏分组？")
            .setMovable(false)
            .setResizable(true)
            .setRequestFocus(true)
            .createPopup()
            .apply {
                // 底部灰色提示行：交互说明
                setAdText("输入过滤 / Enter 选中 / Esc 取消", javax.swing.SwingConstants.CENTER)
            }
        installKeyBindings(tree, popup, onChosen)

        // 默认选中上次使用的「作用域+分组」（找不到回退上次作用域根，再回退不选中）
        tree.selectionPath = findSelectionPath(rootNode, lastScope, lastGroup)
        // ////////////////  核心逻辑  end  /////////////////////////////////////////////

        // ////////////////  显示并滚动默认选中行到位  start  ///////////////////////////
        popup.showInFocusCenter()
        SwingUtilities.invokeLater { tree.selectionPath?.let { tree.scrollPathToVisible(it) } }
        // ////////////////  显示  end  /////////////////////////////////////////////////
    }

    /**
     * 构建整棵选择树：两个作用域根节点，其下按 childrenOf 递归挂分组子树
     *
     * @param project 当前项目
     * @return 不可见根节点
     */
    private fun buildTreeModel(project: Project): DefaultMutableTreeNode {
        val root = DefaultMutableTreeNode("root", true)
        for (scope in FavoriteScope.entries) {
            val scopeNode = DefaultMutableTreeNode(NodePayload.ScopeNode(scope), true)
            root.add(scopeNode)
            appendGroupChildren(project, scope, parentGroupId = ListInfo.ROOT_PARENT_ID, parentNode = scopeNode, visited = HashSet())
        }
        return root
    }

    /**
     * 递归挂载某父分组下的直接子分组（默认列表「收藏」置顶且作为叶子，不可再嵌套）
     *
     * @param project 当前项目
     * @param scope 所属作用域
     * @param parentGroupId 父分组 id（""=顶级）
     * @param parentNode 树上的父节点
     * @param visited 已访问分组 id 集合（环数据兜底防死循环；正常数据加载时已归一）
     */
    private fun appendGroupChildren(
        project: Project,
        scope: FavoriteScope,
        parentGroupId: String,
        parentNode: DefaultMutableTreeNode,
        visited: MutableSet<String>
    ) {
        // 平台 API 兜底：管理器/查询失败时按空子树处理，绝不阻断弹窗
        val manager = runCatching { FavoritesScopeResolver.managerFor(project, scope) }.getOrNull()
        val children = manager?.let { runCatching { it.childrenOf(parentGroupId) }.getOrDefault(emptyList()) }
            ?: emptyList()

        for (info in children) {
            if (info.id.isNotBlank() && !visited.add(info.id)) continue
            val isDefault = info.name == Bookmark.DEFAULT_LIST_NAME
            val path = manager?.let { runCatching { it.fullPath(info.name) }.getOrDefault(info.name) } ?: info.name
            val node = DefaultMutableTreeNode(
                NodePayload.GroupNode(scope = scope, listName = info.name, path = path, isDefault = isDefault),
                // 默认列表不可作为父分组
                !isDefault
            )
            parentNode.add(node)
            if (!isDefault) {
                appendGroupChildren(project, scope, info.id, node, visited)
            }
        }
    }

    /**
     * 全部展开分组树（多层级结构默认可见，减少折叠操作）
     */
    private fun expandAllRows(tree: Tree) {
        var row = 0
        while (row < tree.rowCount) {
            runCatching { tree.expandRow(row) }
            row++
        }
    }

    /**
     * 在树上定位「作用域+分组」对应的路径（供打开弹窗时默认选中）
     *
     * @param rootNode 不可见根节点
     * @param scope 上次作用域
     * @param group 上次分组名
     * @return 命中路径；分组缺失回退作用域根节点；作用域缺失返回 null（不选中）
     */
    private fun findSelectionPath(rootNode: DefaultMutableTreeNode, scope: FavoriteScope, group: String): TreePath? {
        // ////////////////  核心逻辑：先定位作用域根，再广度优先找分组  start  ///////////
        val scopeNode = rootNode.children().toList()
            .filterIsInstance<DefaultMutableTreeNode>()
            .firstOrNull { (it.userObject as? NodePayload.ScopeNode)?.scope == scope }
            ?: return null

        val groupNode = scopeNode.breadthFirstEnumeration().toList()
            .filterIsInstance<DefaultMutableTreeNode>()
            .firstOrNull { (it.userObject as? NodePayload.GroupNode)?.listName == group }
        // ////////////////  核心逻辑  end  /////////////////////////////////////////////

        // ////////////////  返回：分组命中用分组路径，否则回退作用域根  start  ////////////
        return TreePath((groupNode ?: scopeNode).path)
        // ////////////////  返回  end  /////////////////////////////////////////////////
    }

    /**
     * 双击=确认选择（单击仅选中，与树习惯一致）
     */
    private fun installDoubleClickConfirm(tree: Tree) {
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                // 双击确认由 installKeyBindings 安装的回调处理
                if (e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    (tree.getClientProperty(DOUBLE_CLICKED_CONFIRM) as? () -> Unit)?.invoke()
                }
            }
        })
    }

    /**
     * 键位绑定与确认回调装配：Enter=确认选中项，Esc=取消；双击复用同一确认逻辑
     */
    private fun installKeyBindings(tree: Tree, popup: JBPopup, onChosen: (FavoriteScope, String) -> Unit) {
        // ////////////////  核心逻辑：统一确认入口  start  /////////////////////////////
        val confirmAction = object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                val path = tree.selectionPath ?: return
                val payload = (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject
                when (payload) {
                    // 作用域根=选中其默认列表「收藏」
                    is NodePayload.ScopeNode -> confirmAndClose(popup, payload.scope, Bookmark.DEFAULT_LIST_NAME, onChosen)
                    is NodePayload.GroupNode -> confirmAndClose(popup, payload.scope, payload.listName, onChosen)
                    else -> Unit
                }
            }
        }
        // ////////////////  核心逻辑  end  /////////////////////////////////////////////

        // ////////////////  核心逻辑：Enter 键位 + 双击复用确认入口  start  //////////////
        tree.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), ACTION_CONFIRM)
        tree.actionMap.put(ACTION_CONFIRM, confirmAction)
        tree.putClientProperty(DOUBLE_CLICKED_CONFIRM, { confirmAction.actionPerformed(null) })
        // ////////////////  核心逻辑  end  /////////////////////////////////////////////

        // ////////////////  核心逻辑：Esc 显式取消（组件弹窗默认也响应，双保险）  start  ///
        tree.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), ACTION_CANCEL)
        tree.actionMap.put(ACTION_CANCEL, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) = popup.cancel()
        })
        // ////////////////  核心逻辑  end  /////////////////////////////////////////////
    }

    /**
     * 记住本次「作用域+分组」→ 关闭弹窗 → 回调（取消语义：不调用 [onChosen] 即不添加任何收藏）
     */
    private fun confirmAndClose(
        popup: JBPopup,
        scope: FavoriteScope,
        groupName: String,
        onChosen: (FavoriteScope, String) -> Unit
    ) {
        runCatching {
            FavoritesSettings.getInstance().setLastAddScope(scope)
            FavoritesSettings.getInstance().setLastGroupForScope(scope, groupName)
        }
        popup.cancel()
        onChosen(scope, groupName)
    }

    /**
     * 自带输入过滤（替代 TreeSpeedSearch，规避 233~262 构造器差异）：
     * 连续键入字母/数字形成过滤词，按「作用域名/分组完整路径」前置匹配，
     * 命中第一个节点即选中并滚动到位；停顿 [SEARCH_RESET_DELAY_MS] 毫秒自动清空重开。
     */
    private class TypeAheadSelector(private val tree: JTree) : KeyAdapter() {

        private val buffer = StringBuilder()

        private val resetTimer = Timer(SEARCH_RESET_DELAY_MS) { buffer.setLength(0) }.apply { isRepeats = false }

        override fun keyTyped(e: KeyEvent) {
            // ////////////////  校验参数：仅接收可输入字符  start  ///////////////////////
            val ch = e.keyChar
            if (!ch.isLetterOrDigit() && ch != '/') return
            // ////////////////  校验参数  end  ///////////////////////////////////////////

            // ////////////////  核心逻辑：累积过滤词并选中首个命中节点  start  ///////////
            buffer.append(ch.lowercaseChar())
            resetTimer.restart()
            val query = buffer.toString()
            val match = firstMatchingPath(query)
            if (match != null) {
                tree.selectionPath = match
                tree.scrollPathToVisible(match)
            }
            // ////////////////  核心逻辑  end  ///////////////////////////////////////////
        }

        /**
         * 深度优先查找首个文本命中过滤词的节点（作用域按名称、分组按完整路径匹配，忽略大小写）
         */
        private fun firstMatchingPath(query: String): TreePath? {
            val root = tree.model.root as? DefaultMutableTreeNode ?: return null
            val enumeration = root.depthFirstEnumeration()
            while (enumeration.hasMoreElements()) {
                val node = enumeration.nextElement() as? DefaultMutableTreeNode ?: continue
                val searchable = when (val payload = node.userObject) {
                    is NodePayload.ScopeNode -> payload.scope.label
                    is NodePayload.GroupNode -> payload.path
                    else -> continue
                }
                if (searchable.lowercase().startsWith(query)) {
                    return TreePath(node.path)
                }
            }
            return null
        }
    }

    /**
     * 双击确认的客户端属性键名
     */
    private const val DOUBLE_CLICKED_CONFIRM = "favoritesScopeGroupDoubleClickedConfirm"

    /**
     * 节点渲染器：作用域根=作用域图标+名称；分组=文件夹图标+名称（默认列表追加灰色「（默认）」），
     * 分组 tooltip 展示完整层级路径
     */
    private class ScopeGroupNodeRenderer : ColoredTreeCellRenderer() {

        override fun customizeCellRenderer(
            tree: JTree,
            value: Any,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean
        ) {
            // ////////////////  准备数据：取节点负载并重置 tooltip（防上一行残留）  start  //
            val payload = (value as? DefaultMutableTreeNode)?.userObject
            toolTipText = null
            // ////////////////  准备数据  end  ///////////////////////////////////////////

            // ////////////////  核心逻辑：按节点类型渲染图标与文本  start  ///////////////
            when (payload) {
                is NodePayload.ScopeNode -> {
                    icon = BookmarkIconHelper.getScopeIcon(payload.scope)
                    append(payload.scope.label)
                }
                is NodePayload.GroupNode -> {
                    icon = AllIcons.Nodes.Folder
                    append(payload.listName)
                    if (payload.isDefault) {
                        append("（默认）", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    } else {
                        // 完整层级路径放 tooltip（树形已表达层级，不重复占宽度）
                        toolTipText = payload.path
                    }
                }
                else -> append(payload?.toString() ?: "")
            }
            // ////////////////  核心逻辑  end  ///////////////////////////////////////////
        }
    }
}
