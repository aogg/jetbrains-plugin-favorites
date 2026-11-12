package com.aogg.favorites.ui

import com.aogg.favorites.action.AddActionSupport
import com.aogg.favorites.helper.BookmarkIconHelper
import com.aogg.favorites.helper.BookmarkRefreshHelper
import com.aogg.favorites.model.Bookmark
import com.aogg.favorites.model.FavoriteScope
import com.aogg.favorites.model.FavoritesScopeResolver
import com.aogg.favorites.model.ListInfo
import com.aogg.favorites.model.ScopedBookmarkManager
import com.aogg.favorites.model.SharedBookmarkManager
import com.aogg.favorites.settings.FavoritesSettings
import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
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
import javax.swing.tree.TreeSelectionModel

/**
 * 添加收藏时的「作用域 + 分组」树选择弹窗（1.5.x 新增，替代 AddScopeChooser 的职责）
 *
 * 行为约定（与需求一致）：
 * - 弹分组树：根为作用域节点「项目收藏」「共享收藏」「全局收藏」（沿用 BookmarkIconHelper.getScopeIcon 图标），
 *   其下按 childrenOf 递归展示该作用域的分组树（含默认列表「收藏」置顶；默认列表不可作为子分组但可选为目标；
 *   共享收藏的分组只取自己集合的分组，写入路径按绝对路径处理）；
 * - 输入即过滤：连续键入字母/数字时按「作用域名/分组完整路径」做前置匹配（自带过滤，兼容 233~262），
 *   命中即选中并滚动到位，短暂停顿自动清空过滤词；
 * - Enter / 双击 = 选中该「作用域+分组」（选中作用域根节点等价于选其默认列表「收藏」）；
 *   Esc / 点击弹窗外 = 取消（取消则不添加任何收藏，onChosen 不会被触发）；
 * - 记住上次选择：按作用域记住上次使用的分组（FavoritesSettings.lastGroupPerScope，与 lastAddScope 同一存储模式），
 *   弹窗打开时默认选中「上次作用域 + 该作用域的上次分组」（高亮 + 滚动到位）；
 *   记住的分组已被删除时回退默认「收藏」（复用 AddActionSupport.resolveTargetGroup）；
 * - 确认选择后同时记住作用域与分组，供下次默认选中与原生快捷键接管路径共用；
 * - 「已存在收藏 → 切换移除」语义由调用方（AddAnonymousBookmarkAction 等）在弹窗之前/之后处理，本弹窗不感知。
 *
 * 1.8.1 新增右键分组管理（语义完全对齐收藏工具窗口 BookmarksTree 的分组 CRUD）：
 * - 右键【作用域根节点】或【默认列表「收藏」】→「新建列表」（在该作用域新建顶级列表）；
 * - 右键【分组节点】→「新建子分组」（该分组下）、「重命名列表」（默认列表不可重命名）、「删除列表」
 *   （级联删除全部后代子分组，确认框说明组内收藏并入默认列表「收藏」不丢数据）；
 * - 三个作用域都支持；共享作用域只展示/只写自己集合的分组（与树展示口径一致，无只读分支）；
 * - 右键先选中被点节点（标准树行为）；操作完成后弹窗不关闭，重建弹窗树（尽量保留展开状态），
 *   并自动选中新建/重命名后的分组，方便用户直接 Enter 确认；
 * - 右键操作不改变「记住上次选择」（lastGroupPerScope 只在确认选择时更新）。
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
     * 日志（分组管理操作的取证信息，idea.log 按「Favorites 弹窗分组管理」搜索）
     */
    private val log = Logger.getInstance(ScopeGroupChooserPopup::class.java)

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
     * @param project 当前项目（解析各作用域管理器的分组树）
     * @param onChosen 确认回调，参数为 (作用域, 目标分组名)，在 EDT 执行（弹窗已关闭后触发）
     */
    fun chooseScopeAndGroup(project: Project, onChosen: (scope: FavoriteScope, groupName: String) -> Unit) {
        // 构建控制器（持有弹窗与树，右键分组管理需要在原地重建树，不能一次性搭完就丢引用）
        ScopeGroupChooserController(project, onChosen).show()
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

    /**
     * 「作用域+分组」弹窗控制器：持有弹窗、树与回调，负责
     * 构建树、键位与双击确认、右键分组管理（新建/重命名/删除后原地重建树并保持选中目标）
     *
     * 独立成类的目的：右键管理操作完成后「弹窗不关闭、重建树、保留展开与选中」，
     * 需要跨操作持有弹窗与树的引用（object 单例没法承载每次弹出的独立状态）
     */
    private class ScopeGroupChooserController(
        private val project: Project,
        private val onChosen: (scope: FavoriteScope, groupName: String) -> Unit
    ) {

        /** 弹窗（操作完成后需要保持打开，原地重建树） */
        private lateinit var popup: JBPopup

        /** 分组树（右键管理后重建 model，组件本身复用，监听器/键位/渲染器不重装） */
        private lateinit var tree: Tree

        /** 树模型不可见根（每次重建 model 时重新生成） */
        private lateinit var rootNode: DefaultMutableTreeNode

        /** 上次作用域（打开时默认选中；仅确认选择时才写回设置） */
        private val lastScope = runCatching { FavoritesSettings.getInstance().getLastAddScope() }
            .getOrDefault(FavoriteScope.PROJECT)

        /** 上次作用域的分组（已被删除时由 resolveTargetGroup 回退默认「收藏」） */
        private val lastGroup = AddActionSupport.resolveTargetGroup(project, lastScope)

        /**
         * 构建弹窗并显示：建树 → 全展开 → 组装弹窗 → 键位/右键 → 默认选中上次选择 → 显示并滚动到位
         */
        fun show() {
            // ////////////////  核心逻辑：构建分组树与弹窗  start  //////////////////////////
            rootNode = buildTreeModel()
            tree = Tree(DefaultTreeModel(rootNode)).apply {
                isRootVisible = false
                showsRootHandles = true
                selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
                // 显式调用 setCellRenderer（此处虽非 JTree 子类内部，仍统一写法避免同类"裸字段赋值不触发
                // property change"陷阱）
                setCellRenderer(ScopeGroupNodeRenderer())
                // 自带输入过滤：连续键入即按路径前置匹配并选中
                addKeyListener(TypeAheadSelector(this))
                installDoubleClickConfirm(this)
                // 右键分组管理（1.8.1）：先选中被点节点再弹菜单
                installContextMenu(this)
            }
            expandAllRows(tree)

            // 弹窗内容：滚动容器指定首选尺寸，避免分组多时弹窗过高/分组少时过窄
            val content = JBScrollPane(tree).apply { preferredSize = POPUP_SIZE }

            popup = JBPopupFactory.getInstance()
                .createComponentPopupBuilder(content, tree)
                .setTitle("添加到哪个收藏分组？")
                .setMovable(false)
                .setResizable(true)
                .setRequestFocus(true)
                .createPopup()
                .apply {
                    // 底部灰色提示行：交互说明
                    setAdText("输入过滤 / Enter 选中 / Esc 取消 / 右键管理分组", javax.swing.SwingConstants.CENTER)
                }
            installKeyBindings(tree, popup)

            // 默认选中上次使用的「作用域+分组」（找不到回退上次作用域根，再回退不选中）
            tree.selectionPath = findSelectionPath(rootNode, lastScope, lastGroup)
            // ////////////////  核心逻辑  end  /////////////////////////////////////////////

            // ////////////////  显示并滚动默认选中行到位  start  ///////////////////////////
            popup.showInFocusCenter()
            SwingUtilities.invokeLater { tree.selectionPath?.let { tree.scrollPathToVisible(it) } }
            // ////////////////  显示  end  /////////////////////////////////////////////////
        }

        /**
         * 构建整棵选择树：作用域根节点（项目收藏/共享收藏/全局收藏），其下按 childrenOf 递归挂分组子树
         * （共享收藏的分组只取自己集合的分组——写操作只落自己集合，外来集合分组不作为写入目标）
         *
         * @return 不可见根节点
         */
        private fun buildTreeModel(): DefaultMutableTreeNode {
            val root = DefaultMutableTreeNode("root", true)
            for (scope in FavoriteScope.entries) {
                val scopeNode = DefaultMutableTreeNode(NodePayload.ScopeNode(scope), true)
                root.add(scopeNode)
                appendGroupChildren(scope, parentGroupId = ListInfo.ROOT_PARENT_ID, parentNode = scopeNode, visited = HashSet())
            }
            return root
        }

        /**
         * 递归挂载某父分组下的直接子分组（默认列表「收藏」置顶且作为叶子，不可再嵌套）
         *
         * @param scope 所属作用域
         * @param parentGroupId 父分组 id（""=顶级）
         * @param parentNode 树上的父节点
         * @param visited 已访问分组 id 集合（环数据兜底防死循环；正常数据加载时已归一）
         */
        private fun appendGroupChildren(
            scope: FavoriteScope,
            parentGroupId: String,
            parentNode: DefaultMutableTreeNode,
            visited: MutableSet<String>
        ) {
            // 平台 API 兜底：管理器/查询失败时按空子树处理，绝不阻断弹窗。
            // 共享收藏目标分组只取自己集合的分组（写操作只落自己集合，外来集合只读不作为目标）
            val manager = runCatching { managerFor(scope) }.getOrNull()
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
                    appendGroupChildren(scope, info.id, node, visited)
                }
            }
        }

        /**
         * 按作用域取分组管理器：共享作用域固定取自己集合（ownCollectionManager），
         * 其余走 FavoritesScopeResolver 统一路由（与工具窗口 managerFor 同语义）
         *
         * @param scope 作用域
         * @return 分组管理器
         */
        private fun managerFor(scope: FavoriteScope): ScopedBookmarkManager =
            if (scope == FavoriteScope.SHARED) {
                SharedBookmarkManager.getInstance(project).ownCollectionManager()
            } else {
                FavoritesScopeResolver.managerFor(project, scope)
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
         * 在树上定位「作用域+分组」对应的路径（供打开弹窗时默认选中/操作后选中新建分组）
         *
         * @param rootNode 不可见根节点
         * @param scope 作用域
         * @param group 分组名
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
        private fun installKeyBindings(tree: Tree, popup: JBPopup) {
            // ////////////////  核心逻辑：统一确认入口  start  /////////////////////////////
            val confirmAction = object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) {
                    val path = tree.selectionPath ?: return
                    val payload = (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject
                    when (payload) {
                        // 作用域根=选中其默认列表「收藏」
                        is NodePayload.ScopeNode -> confirmAndClose(popup, payload.scope, Bookmark.DEFAULT_LIST_NAME)
                        is NodePayload.GroupNode -> confirmAndClose(popup, payload.scope, payload.listName)
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
         * 注意：只有确认选择才写 lastGroupPerScope，右键分组管理不改变「记住上次选择」
         */
        private fun confirmAndClose(popup: JBPopup, scope: FavoriteScope, groupName: String) {
            runCatching {
                FavoritesSettings.getInstance().setLastAddScope(scope)
                FavoritesSettings.getInstance().setLastGroupForScope(scope, groupName)
            }
            popup.cancel()
            onChosen(scope, groupName)
        }

        // ////////  右键分组管理（1.8.1，语义对齐收藏工具窗口 BookmarksTree 的分组 CRUD）  start  ////////

        /**
         * 安装右键菜单：右键先选中被点节点（标准树行为），再在被点位置弹分组管理菜单
         * （点在树空白处不选中不弹菜单）
         */
        private fun installContextMenu(tree: Tree) {
            tree.addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    handleRightClick(tree, e)
                }

                override fun mouseReleased(e: MouseEvent) {
                    handleRightClick(tree, e)
                }
            })
        }

        /**
         * 右键处理：点中节点且未选中时先选中之（标准树行为），弹出触发时在光标处弹上下文菜单
         * （popup trigger 平台差异：Windows 在 RELEASE、Linux/macOS 在 PRESS，两个事件都检查）
         *
         * @param tree 分组树
         * @param e 鼠标事件
         */
        private fun handleRightClick(tree: Tree, e: MouseEvent) {
            // ////////////////  校验参数：仅处理右键  start  ///////////////////////////////
            if (!SwingUtilities.isRightMouseButton(e)) return
            // ////////////////  校验参数  end  ////////////////////////////////////////////

            // ////////////////  核心逻辑一：右键先选中被点节点（标准树行为）  start  /////////
            val clickedPath = tree.getPathForLocation(e.x, e.y) ?: return
            if (!tree.isPathSelected(clickedPath)) {
                tree.selectionPath = clickedPath
            }
            // ////////////////  核心逻辑一  end  //////////////////////////////////////////

            // ////////////////  核心逻辑二：弹出触发时在光标处弹菜单  start  ///////////////
            if (e.isPopupTrigger) {
                showContextMenu(e)
            }
            // ////////////////  核心逻辑二  end  //////////////////////////////////////////
        }

        /**
         * 在鼠标位置弹出分组管理上下文菜单（菜单项按被点节点类型生成，见 [buildCrudActionGroup]）
         *
         * @param e 触发弹出的鼠标事件（菜单显示在该位置）
         */
        private fun showContextMenu(e: MouseEvent) {
            // ////////////////  准备数据：按当前选中节点取菜单项  start  ///////////////////
            val payload = (tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject
            val actionGroup = buildCrudActionGroup(payload)
            // ////////////////  准备数据  end  ///////////////////////////////////////////

            // ////////////////  校验参数：空白处/无可用操作不弹空菜单  start  ///////////////
            if (actionGroup.childrenCount == 0) return
            // ////////////////  校验参数  end  ///////////////////////////////////////////

            // ////////////////  核心逻辑：在光标处显示菜单  start  /////////////////////////
            val dataContext = DataManager.getInstance().getDataContext(tree)
            JBPopupFactory.getInstance()
                .createActionGroupPopup(null, actionGroup, dataContext, JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false)
                .show(RelativePoint(e))
            // ////////////////  核心逻辑  end  ///////////////////////////////////////////
        }

        /**
         * 按被点节点类型生成分组管理菜单（语义对齐收藏工具窗口 BookmarksTree 的分组 CRUD）：
         * - 作用域根节点 / 默认列表「收藏」→「新建列表」（该作用域新建顶级列表）；
         * - 其它分组节点 →「新建子分组」（该分组下）+「重命名列表」+「删除列表」
         *
         * @param payload 被点节点负载（null=未知上下文，不给菜单）
         * @return 动作组
         */
        private fun buildCrudActionGroup(payload: Any?): DefaultActionGroup {
            val group = DefaultActionGroup()
            when (payload) {
                is NodePayload.ScopeNode -> group.add(createNewListAction(payload.scope))
                is NodePayload.GroupNode ->
                    if (payload.isDefault) {
                        // 默认列表「收藏」不可重命名/删除：只支持在该作用域新建顶级列表（与工具窗口一致）
                        group.add(createNewListAction(payload.scope))
                    } else {
                        group.add(createNewSubGroupAction(payload.scope, payload.listName))
                        group.add(renameListAction(payload.scope, payload.listName))
                        group.add(deleteListAction(payload.scope, payload.listName))
                    }
                else -> Unit
            }
            return group
        }

        /**
         * 「新建列表」菜单项：在该作用域新建顶级列表
         *
         * @param scope 目标作用域
         */
        private fun createNewListAction(scope: FavoriteScope): AnAction =
            object : AnAction("新建列表", "新建一个顶级收藏列表", null) {
                override fun actionPerformed(e: AnActionEvent) {
                    createTopLevelList(scope)
                }
            }

        /**
         * 「新建子分组」菜单项：在指定分组下新建子分组
         *
         * @param scope 目标作用域
         * @param parentListName 父分组名
         */
        private fun createNewSubGroupAction(scope: FavoriteScope, parentListName: String): AnAction =
            object : AnAction("新建子分组", "在「$parentListName」下新建子分组（名称全作用域唯一）", null) {
                override fun actionPerformed(e: AnActionEvent) {
                    createSubGroupUnder(scope, parentListName)
                }
            }

        /**
         * 「重命名列表」菜单项：重命名指定分组（改名不影响层级）
         *
         * @param scope 目标作用域
         * @param listName 待重命名的分组名
         */
        private fun renameListAction(scope: FavoriteScope, listName: String): AnAction =
            object : AnAction("重命名列表", "重命名选中的列表（默认列表不可重命名；改名不影响层级）", null) {
                override fun actionPerformed(e: AnActionEvent) {
                    renameGroupIn(scope, listName)
                }
            }

        /**
         * 「删除列表」菜单项：级联删除指定分组及其全部后代子分组（组内收藏并入默认列表，不丢数据）
         *
         * @param scope 目标作用域
         * @param listName 待删除的分组名
         */
        private fun deleteListAction(scope: FavoriteScope, listName: String): AnAction =
            object : AnAction("删除列表", "删除选中的分组（其子分组一并删除，组内收藏并入默认列表）", null) {
                override fun actionPerformed(e: AnActionEvent) {
                    deleteGroupCascade(scope, listName)
                }
            }

        /**
         * 新建顶级列表（输入框 + 名称唯一性校验，文案与校验语义对齐收藏工具窗口）
         *
         * @param scope 目标作用域
         */
        private fun createTopLevelList(scope: FavoriteScope) {
            // ////////////////  准备数据：目标作用域管理器  start  /////////////////////////
            val manager = managerFor(scope)
            // ////////////////  准备数据  end  ///////////////////////////////////////////

            // ////////////////  核心逻辑一：输入名称（重名/空白校验在 InputValidator）  start  //
            val name = promptUniqueName(
                title = "新建列表",
                message = "请输入新列表名称（加入「${scope.label}」）：",
                initialValue = "",
                manager = manager,
                allowSameAs = null
            ) ?: return
            // ////////////////  核心逻辑一  end  ////////////////////////////////////////

            // ////////////////  核心逻辑二：创建并原地重建树（选中新建分组）  start  ///////
            val created = manager.createList(name)
            log.info(
                "Favorites 弹窗分组管理：action=createList（新建列表）, scope=${scope.name}（作用域）, " +
                    "name=$name（名称）, success=$created（是否成功）"
            )
            if (created) {
                afterGroupStructureChanged(scope, selectGroup = name, cascadeDataChanged = false)
            } else {
                Messages.showErrorDialog(project, "新建列表失败：名称不能为空且不能与现有分组重名", "新建列表")
            }
            // ////////////////  核心逻辑二  end  ////////////////////////////////////////
        }

        /**
         * 在指定分组下新建子分组（名称仍全作用域唯一，父子按 id 关联）
         *
         * @param scope 目标作用域
         * @param parentListName 父分组名
         */
        private fun createSubGroupUnder(scope: FavoriteScope, parentListName: String) {
            // ////////////////  准备数据：目标作用域管理器  start  /////////////////////////
            val manager = managerFor(scope)
            // ////////////////  准备数据  end  ///////////////////////////////////////////

            // ////////////////  核心逻辑一：输入名称（重名/空白校验在 InputValidator）  start  //
            val name = promptUniqueName(
                title = "新建子分组",
                message = "请在「$parentListName」下输入子分组名称（加入「${scope.label}」）：",
                initialValue = "",
                manager = manager,
                allowSameAs = null
            ) ?: return
            // ////////////////  核心逻辑一  end  ////////////////////////////////////////

            // ////////////////  核心逻辑二：创建并原地重建树（选中新建子分组）  start  /////
            val created = manager.createSubGroup(parentListName, name)
            log.info(
                "Favorites 弹窗分组管理：action=createSubGroup（新建子分组）, scope=${scope.name}（作用域）, " +
                    "parent=$parentListName（父分组）, name=$name（名称）, success=$created（是否成功）"
            )
            if (created) {
                afterGroupStructureChanged(scope, selectGroup = name, cascadeDataChanged = false)
            } else {
                Messages.showErrorDialog(project, "新建子分组失败：名称不能为空且不能与现有分组重名", "新建子分组")
            }
            // ////////////////  核心逻辑二  end  ////////////////////////////////////////
        }

        /**
         * 重命名分组（改名不影响层级；输入框预填原名，允许原名确认）
         *
         * @param scope 目标作用域
         * @param listName 待重命名的分组名
         */
        private fun renameGroupIn(scope: FavoriteScope, listName: String) {
            // ////////////////  准备数据：目标作用域管理器  start  /////////////////////////
            val manager = managerFor(scope)
            // ////////////////  准备数据  end  ///////////////////////////////////////////

            // ////////////////  核心逻辑一：输入新名称（允许原名；重名校验在 InputValidator）  start
            val newName = promptUniqueName(
                title = "重命名列表",
                message = "请输入新的列表名称：",
                initialValue = listName,
                manager = manager,
                allowSameAs = listName
            ) ?: return
            // ////////////////  核心逻辑一  end  ////////////////////////////////////////

            // ////////////////  核心逻辑二：重命名并原地重建树（选中改名后的分组）  start  //
            val renamed = manager.renameList(listName, newName)
            log.info(
                "Favorites 弹窗分组管理：action=renameList（重命名列表）, scope=${scope.name}（作用域）, " +
                    "oldName=$listName（原名）, newName=$newName（新名）, success=$renamed（是否成功）"
            )
            if (renamed) {
                afterGroupStructureChanged(scope, selectGroup = newName, cascadeDataChanged = false)
            } else {
                Messages.showErrorDialog(project, "重命名列表失败：名称不能为空且不能与现有列表重名", "重命名列表")
            }
            // ////////////////  核心逻辑二  end  ////////////////////////////////////////
        }

        /**
         * 删除分组（级联，二次确认）：确认框列明将连删的子分组数与并入默认列表的收藏数
         * （组内收藏并入默认列表「收藏」，不丢数据——文案与收藏工具窗口删除列表一致）
         *
         * @param scope 目标作用域
         * @param listName 待删除的分组名
         */
        private fun deleteGroupCascade(scope: FavoriteScope, listName: String) {
            // ////////////////  准备数据：目标作用域管理器 + 级联删除预览  start  ///////////
            val manager = managerFor(scope)
            val preview = manager.previewCascadeDelete(listName)
            // ////////////////  准备数据  end  ///////////////////////////////////////////

            // ////////////////  校验参数：分组不存在时明确提示（数据可能已被别处删除）  start  //
            if (preview == null) {
                Messages.showInfoMessage(project, "列表「$listName」不存在", "删除列表")
                return
            }
            // ////////////////  校验参数  end  ///////////////////////////////////////////

            // ////////////////  核心逻辑一：二次确认（说明级联范围与收藏去向）  start  ///////
            val message = if (preview.subGroupCount > 0) {
                "确定要删除「${scope.label}」中的分组「$listName」吗？\n" +
                    "将连同其 ${preview.subGroupCount} 个子分组一起删除（共 ${preview.groupNames.size} 个分组），\n" +
                    "其中 ${preview.movedBookmarkCount} 条收藏将并入默认列表「${Bookmark.DEFAULT_LIST_NAME}」。"
            } else {
                "确定要删除「${scope.label}」中的列表「$listName」吗？\n" +
                    "其中的 ${preview.movedBookmarkCount} 条收藏将并入默认列表「${Bookmark.DEFAULT_LIST_NAME}」。"
            }
            if (Messages.showYesNoDialog(project, message, "删除列表", Messages.getQuestionIcon()) != Messages.YES) return
            // ////////////////  核心逻辑一  end  ////////////////////////////////////////

            // ////////////////  核心逻辑二：级联删除并原地重建树  start  ///////////////////
            val deleteResult = manager.deleteListCascade(listName)
            log.info(
                "Favorites 弹窗分组管理：action=deleteListCascade（删除分组）, scope=${scope.name}（作用域）, " +
                    "name=$listName（名称）, success=${deleteResult.success}（是否成功）, " +
                    "deletedGroups=${deleteResult.deletedGroupNames}（删除的分组）, " +
                    "movedBookmarks=${deleteResult.movedBookmarkCount}（并入默认列表的收藏数）"
            )
            if (deleteResult.success) {
                // 被删分组内的收藏并入默认列表，选中回退该作用域根（= 默认列表）
                afterGroupStructureChanged(scope, selectGroup = null, cascadeDataChanged = true)
            }
            // ////////////////  核心逻辑二  end  ////////////////////////////////////////
        }

        /**
         * 输入分组名称（重名/空白校验与收藏工具窗口一致：validator 阻断无效输入，
         * 写入失败再弹明确错误）。重命名场景允许与原名相同（[allowSameAs]）。
         *
         * @param title 对话框标题
         * @param message 提示文案
         * @param initialValue 初始值（重命名时预填原名）
         * @param manager 目标作用域管理器（查名称是否存在）
         * @param allowSameAs 允许与现有哪个名称相同（重命名=原名；新建传 null）
         * @return 裁剪后的名称；用户取消返回 null
         */
        private fun promptUniqueName(
            title: String,
            message: String,
            initialValue: String,
            manager: ScopedBookmarkManager,
            allowSameAs: String?
        ): String? =
            Messages.showInputDialog(
                project,
                message,
                title,
                Messages.getInformationIcon(),
                initialValue,
                object : InputValidator {
                    override fun checkInput(inputString: String): Boolean =
                        inputString.isNotBlank() &&
                            (!manager.hasList(inputString.trim()) || inputString.trim() == allowSameAs)

                    override fun canClose(inputString: String): Boolean = checkInput(inputString)
                }
            )?.trim()

        /**
         * 分组结构变化后的统一收尾：
         * 1) 弹窗不关闭——原地重建树（尽量保留展开状态），选中目标分组并滚动到位；
         * 2) 刷新背后的收藏工具窗口：删除分组时组内收藏并入默认列表（收藏数据变更），
         *    走 BookmarkRefreshHelper 全量刷新（工具窗口 + gutter daemon，与工具窗口
         *    deleteSelectedList 的 afterDataChanged 同语义）；新建/重命名只改分组结构，
         *    只刷工具窗口树（与工具窗口 refreshTreeOnly 同语义，不重启 daemon）
         *
         * @param scope 操作所在作用域
         * @param selectGroup 重建后要选中的分组名（新建/重命名后的分组；删除传 null=保留原选中）
         * @param cascadeDataChanged 是否发生收藏数据变更（删除分组并入默认列表=true）
         */
        private fun afterGroupStructureChanged(scope: FavoriteScope, selectGroup: String?, cascadeDataChanged: Boolean) {
            // ////////////////  核心逻辑一：原地重建弹窗树并选中目标  start  ///////////////
            rebuildTree(selectGroup = selectGroup?.let { Pair(scope, it) })
            // ////////////////  核心逻辑一  end  ////////////////////////////////////////

            // ////////////////  核心逻辑二：刷新背后的收藏工具窗口（与工具窗口同语义）  start  //
            if (cascadeDataChanged) {
                BookmarkRefreshHelper.refreshAfterChange(project)
            } else {
                BookmarksToolWindowPanel.refreshToolWindow(project)
            }
            // ////////////////  核心逻辑二  end  ////////////////////////////////////////
        }

        /**
         * 原地重建弹窗树：组件复用（监听器/键位/渲染器不重装），model 换新后
         * 尽量保留展开状态，并选中目标分组（找不到目标时保留原选中）
         *
         * @param selectGroup 重建后要选中的「作用域+分组」（null=保留原选中）
         */
        private fun rebuildTree(selectGroup: Pair<FavoriteScope, String>?) {
            // ////////////////  准备数据：记录旧展开状态与旧选中（按节点稳定 key）  start  //
            val expandedKeys = captureExpandedKeys()
            val oldSelectionKey = tree.selectionPath?.let { pathKey(it) }
            // ////////////////  准备数据  end  ///////////////////////////////////////////

            // ////////////////  核心逻辑一：重建树模型（组件与监听器全部复用）  start  //////
            rootNode = buildTreeModel()
            tree.model = DefaultTreeModel(rootNode)
            // ////////////////  核心逻辑一  end  ////////////////////////////////////////

            // ////////////////  核心逻辑二：恢复展开状态  start  //////////////////////////
            restoreExpandedKeys(expandedKeys)
            // ////////////////  核心逻辑二  end  ////////////////////////////////////////

            // ////////////////  核心逻辑三：选中目标分组（缺失回退原选中）并滚动到位  start  //
            val targetPath = selectGroup?.let { (scope, group) -> findSelectionPath(rootNode, scope, group) }
                ?: oldSelectionKey?.let { findPathByKey(rootNode, it) }
            tree.selectionPath = targetPath
            // scrollPathToVisible 会展开目标父链，保证新建的子分组立即可见
            targetPath?.let { tree.scrollPathToVisible(it) }
            // ////////////////  核心逻辑三  end  ////////////////////////////////////////
        }

        /**
         * 记录当前所有展开行的稳定 key（按树行扫描，与 [restoreExpandedKeys] 配对使用）
         *
         * @return 展开 key 集合
         */
        private fun captureExpandedKeys(): Set<String> {
            val keys = HashSet<String>()
            var row = 0
            while (row < tree.rowCount) {
                if (tree.isExpanded(row)) {
                    tree.getPathForRow(row)?.let { keys.add(pathKey(it)) }
                }
                row++
            }
            return keys
        }

        /**
         * 按旧展开 key 集合恢复展开状态（新树里仍存在的路径才恢复，删除的分组自然不再展开）
         *
         * @param keys 旧展开 key 集合
         */
        private fun restoreExpandedKeys(keys: Set<String>) {
            var row = 0
            while (row < tree.rowCount) {
                val path = tree.getPathForRow(row)
                if (path != null && pathKey(path) in keys) {
                    tree.expandRow(row)
                }
                row++
            }
        }

        /**
         * 路径的稳定 key：各级节点用「作用域名 / 分组名」串联（分组名作用域内唯一，
         * 重建树前后同名节点 key 相同；不可见根节点无负载，自动跳过）
         *
         * @param path 树路径
         * @return 形如「PROJECT/父组/子组」的 key
         */
        private fun pathKey(path: TreePath): String =
            path.path.filterIsInstance<DefaultMutableTreeNode>()
                .mapNotNull { node ->
                    when (val payload = node.userObject) {
                        is NodePayload.ScopeNode -> payload.scope.name
                        is NodePayload.GroupNode -> payload.listName
                        else -> null
                    }
                }
                .joinToString("/")

        /**
         * 在新树里按稳定 key 找路径（供重建后保留原选中）
         *
         * @param startNode 搜索起点（不可见根）
         * @param key 目标 key
         * @return 命中路径；找不到返回 null
         */
        private fun findPathByKey(startNode: DefaultMutableTreeNode, key: String): TreePath? {
            val enumeration = startNode.depthFirstEnumeration()
            while (enumeration.hasMoreElements()) {
                val node = enumeration.nextElement() as? DefaultMutableTreeNode ?: continue
                val path = TreePath(node.path)
                if (pathKey(path) == key) return path
            }
            return null
        }
        // ////////  右键分组管理  end  //////////////////////////////////////////////////////////////////////////
    }
}
