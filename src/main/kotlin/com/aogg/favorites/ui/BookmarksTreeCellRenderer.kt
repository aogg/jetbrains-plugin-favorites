package com.aogg.favorites.ui

import com.aogg.favorites.helper.BookmarkIconHelper
import com.aogg.favorites.model.RuleType
import com.aogg.favorites.rules.RuleItem
import com.aogg.favorites.rules.RuleStatus
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.render.RenderingUtil
import com.intellij.ui.speedSearch.SpeedSearchUtil
import java.awt.Component
import java.awt.Graphics
import java.io.File
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreeCellRenderer

/**
 * 收藏树单元格渲染器（1.5.1 加固：直接实现 TreeCellRenderer，不再继承 ColoredTreeCellRenderer）：
 * - 绕开平台 ColoredTreeCellRenderer.rendererComponentInner 胶水层（其内部对宽选/焦点边框/isForPainting
 *   的处理链在第三方主题/图标插件环境下有被接管清空图标的先例），改由本类自管完整渲染时序：
 *   clear → 字体/选中态配色 → customize（按节点类型设图标与文本）→ 图标兜底 → 速度搜索高亮 → 返回自身
 * - 1.5.x 起图标全量字符绘制（[CharIcon]）：历史 4 次图标加固（换 AllIcons 常量/渲染器直接实现/
 *   绘制级兜底/运行时取证）在用户环境下全部无效，判断为 AllIcons / SVG / 文件类型图标的整条平台
 *   加载链被第三方主题/图标插件接管清空；字符绘制不走任何图标加载链（paintIcon 直接 drawString），
 *   主题/第三方插件无法清空字符，从根上绕开该问题。各节点字符语义见 customize 各分支注释
 * - 行内容非空必有图标（双重兜底，兜底图标同为字符 📄）：
 *   1) getTreeCellRendererComponent 尾部 icon==null 时补 CharIcon.of("📄")
 *   2) paintComponent 绘制前 icon==null 且已有文本片段时补 CharIcon.of("📄")
 *      （绘制级保险：即使存在任何胶水层在渲染后清图标，绘制时也会补回）
 * - 选中态背景/前景与 ColoredTreeCellRenderer 非宽选路径语义一致（RenderingUtil 取树当前配色，不写死色值）
 * - 速度搜索高亮沿用 SpeedSearchUtil.applySpeedSearchHighlightingFiltered（主文本参与匹配，行为同平台）
 * - 运行时取证日志（idea.log 搜 FavoritesTree 前缀）：
 *   安装时 BookmarksTree 记 renderer 是否真实生效；本渲染器前 [DIAGNOSTIC_LOG_LIMIT] 次调用逐次记
 *   节点/图标信息；若发现 this !== tree.getCellRenderer()（第三方插件运行时换走 renderer）只报一次
 *   error——继续留证观察字符图标方案下树节点是否仍有异常
 * 各节点 toString 返回主文本，作为 TreeSpeedSearch 的匹配文本（灰色说明文字不参与速度搜索）
 *
 * @param project 项目（解析 VirtualFile 计算文件图标）
 */
class BookmarksTreeCellRenderer(private val project: Project) : SimpleColoredComponent(), TreeCellRenderer {

    companion object {
        private val log = Logger.getInstance(BookmarksTreeCellRenderer::class.java)

        /** 渲染取证日志节流：仅前 N 次调用逐次记录，之后静默（防 idea.log 刷屏） */
        private const val DIAGNOSTIC_LOG_LIMIT = 30
    }

    /** 渲染调用计数（节流取证日志用，仅 EDT 访问） */
    private var renderCount = 0

    /** 「renderer 被运行时替换」错误日志是否已报过（只报一次防刷屏，仅 EDT 访问） */
    private var replacedLogged = false

    override fun getTreeCellRendererComponent(
        tree: JTree,
        value: Any,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean
    ): Component {
        // ////////////////  前置：清空上一行残留（图标/文本/备注/tooltip 一并复位）  start  //
        clear()
        toolTipText = null
        // ////////////////  前置  end  ////////////////////////////////////////////////

        // ////////////////  字体与选中态配色（与 ColoredTreeCellRenderer 非宽选路径语义一致）  //
        font = tree.font
        background = if (selected) RenderingUtil.getSelectionBackground(tree) else null
        foreground = RenderingUtil.getForeground(tree, selected)
        isOpaque = true
        // ////////////////  字体与选中态配色  end  ////////////////////////////////////

        // ////////////////  核心逻辑：按节点类型渲染图标与主文本/灰色说明  start  ///////
        icon = null
        customize(tree, value)
        // 兜底一：任何分支漏配/图标解析失败时保底通用图标（与 customize 各分支设 icon 语义衔接）
        if (icon == null) {
            icon = CharIcon.of("📄")
        }
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////

        // 速度搜索高亮（主文本参与匹配，行为同平台 ColoredTreeCellRenderer 胶水层）
        SpeedSearchUtil.applySpeedSearchHighlightingFiltered(tree, value, this, true, selected)

        // 运行时取证：节流日志 + renderer 被换监测（在 customize/兜底完成后取最终值）
        logDiagnostics(tree, value)
        return this
    }

    /**
     * 绘制级兜底（兜底二）：绘制前若行内容非空（已有文本片段）却无图标，先补通用图标再绘制。
     * 保证任何情况下（第三方主题/插件胶水层清掉图标等）行内容非空必有图标可显示
     */
    override fun paintComponent(g: Graphics) {
        if (icon == null && fragmentCount > 0) {
            icon = CharIcon.of("📄")
        }
        super.paintComponent(g)
    }

    /**
     * 按节点类型设置图标与主文本/灰色说明（1.3.0 起各分支语义保持不变，仅从 customizeCellRenderer 平移）
     *
     * @param tree 树组件（当前未直接使用，保留参数与原方法签名一致）
     * @param value 树节点（DefaultMutableTreeNode 包装用户对象）
     */
    private fun customize(tree: JTree, value: Any) {
        // ////////////////  准备数据：取节点用户对象  start  //////////////////////////
        val node = (value as? DefaultMutableTreeNode)?.userObject
        // ////////////////  准备数据  end  ////////////////////////////////////////////

        when (node) {
            // 作用域节点（含总数徽标文案）
            is ScopeNode -> {
                icon = BookmarkIconHelper.getScopeIcon(node.scope)
                append(node.toString())
            }
            // 列表节点（含收藏数徽标文案）
            is BookmarkListNode -> {
                icon = CharIcon.of("🗂️")
                append(node.toString())
            }
            // 目录节点：虚拟目录链/文件夹收藏/真实目录（缺失态 ⚠️，正常态 📁，展开折叠态同用）
            is DirNode -> {
                icon = if (node.missing) CharIcon.of("⚠️") else CharIcon.of("📁")
                val bookmark = node.bookmark
                if (bookmark?.mnemonic != null) {
                    append(BookmarkIconHelper.getMnemonicText(bookmark.mnemonic) + " ")
                }
                append(node.name)
                if (node.missing) {
                    // 缺失态：灰色补充缺失说明（叶子节点，由树侧不挂子节点控制）
                    append("  （目录不存在或不可读）", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                } else if (bookmark != null && node.path.isNotEmpty()) {
                    // 文件夹收藏：灰色补充完整路径（基准目录无路径则省略）
                    append("  ${node.path}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
            // 文件节点：FILE 收藏本体或仅承载行收藏的文件容器
            is FileNode -> {
                icon = BookmarkIconHelper.getFileIcon(resolveNodeFile(node), node.name)
                val bookmark = node.fileBookmark
                if (bookmark?.mnemonic != null) {
                    append(BookmarkIconHelper.getMnemonicText(bookmark.mnemonic) + " ")
                }
                append(node.name)
                if (bookmark != null) {
                    // 文件收藏：灰色补充父目录路径（基准目录下的文件无父路径则省略）
                    val parentPath = FavoritesTreePaths.parentPath(node.path)
                    if (parentPath.isNotEmpty()) {
                        append("  $parentPath", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                    // 有描述时灰色补充（速度搜索仍可通过收藏过滤命中描述）
                    if (!bookmark.description.isNullOrBlank()) {
                        append("  ${bookmark.description}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                }
            }
            // 行收藏节点：行号（主文本）+ 行内容（灰色）
            is LineEntryNode -> {
                val bookmark = node.bookmark
                icon = if (bookmark.isMnemonic() && bookmark.mnemonic != null) {
                    BookmarkIconHelper.getMnemonicIcon(bookmark.mnemonic)
                } else {
                    BookmarkIconHelper.ANONYMOUS_ICON
                }
                if (bookmark.mnemonic != null) {
                    append(BookmarkIconHelper.getMnemonicText(bookmark.mnemonic) + " ")
                }
                append("${bookmark.lineNumber + 1}:")
                append("  ${node.grayText()}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            // 未收藏真实文件节点（文件夹收藏展开出的真实子文件）
            is RealFileNode -> {
                icon = BookmarkIconHelper.getFileIcon(
                    node.virtualFile.takeIf { it.isValid },
                    node.virtualFile.name
                )
                append(node.virtualFile.name)
            }
            // 规则匹配根节点（第三个根节点，1.4.0）
            is RulesRootNode -> {
                icon = CharIcon.of("🧩")
                append(node.toString())
            }
            // 规则节点：按类型给字符图标（正则 🔍 / 数据库 🗄️ / 终端 🖥️）+ 状态后缀（ERROR 时 tooltip=失败原因，1.4.0）
            is RuleNode -> {
                icon = when (node.rule.type) {
                    RuleType.REGEX -> CharIcon.of("🔍")
                    RuleType.SQL -> CharIcon.of("🗄️")
                    RuleType.SSH -> CharIcon.of("🖥️")
                }
                append(node.rule.name)
                when (node.status) {
                    RuleStatus.OK -> append("  ✅ ${node.count}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    RuleStatus.LOADING -> append("  ⏳ 执行中", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    RuleStatus.ERROR -> append("  ❌", SimpleTextAttributes.ERROR_ATTRIBUTES)
                    null -> {}
                }
                if (!node.rule.enabled) {
                    append("（已禁用）", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                // ERROR：tooltip 展示失败原因（其余节点保持无 tooltip）
                toolTipText = node.error?.takeIf { it.isNotBlank() }
            }
            // 规则结果条目节点（1.4.0）：FileItem=按扩展名映射文件图标（文件名+灰色父路径）；RowItem/LineItem=通用文档 📄
            is RuleItemNode -> {
                when (val item = node.item) {
                    is RuleItem.FileItem -> {
                        val fileName = item.path.substringAfterLast('/')
                        icon = BookmarkIconHelper.getFileIcon(resolveRuleItemFile(item.path), fileName)
                        append(fileName)
                        if (item.isFolder) {
                            append("  （目录）", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        }
                        val parentPath = item.path.substringBeforeLast('/', "")
                        if (parentPath.isNotEmpty() && parentPath != item.path) {
                            append("  $parentPath", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        }
                    }
                    is RuleItem.RowItem -> {
                        icon = CharIcon.of("📄")
                        append(node.toString())
                    }
                    is RuleItem.LineItem -> {
                        icon = CharIcon.of("📄")
                        append(node.toString())
                    }
                }
            }
            // 兜底：未识别节点类型（分支漏配/新增节点类型未配时）给通用字符图标 + toString 主文本，保证任何节点都有图标可显示
            else -> {
                icon = CharIcon.of("📄")
                append(node?.toString() ?: "")
            }
        }
    }

    /**
     * 运行时取证（节流）：
     * - this !== tree.getCellRenderer() 说明渲染器被第三方插件/主题在运行时换走（无图标问题的决定性证据），
     *   用布尔标记只报一次 error，不受计数限制
     * - 前 [DIAGNOSTIC_LOG_LIMIT] 次调用逐次记 info：节点类型/图标类型/图标宽高/一致性，
     *   用于确认本渲染器正常工作（icon 非空、consistent=true）
     *
     * @param tree 树组件（取其当前 renderer 对比一致性）
     * @param value 当前渲染的树节点
     */
    private fun logDiagnostics(tree: JTree, value: Any) {
        val currentRenderer = tree.getCellRenderer()
        if (currentRenderer !== this && !replacedLogged) {
            replacedLogged = true
            log.error("FavoritesTree renderer replaced at runtime by: " + currentRenderer?.javaClass?.name)
        }
        if (renderCount < DIAGNOSTIC_LOG_LIMIT) {
            renderCount++
            val node = (value as? DefaultMutableTreeNode)?.userObject
            log.info(
                "FavoritesTreeRenderer node=${node?.javaClass?.simpleName} " +
                    "icon=${icon?.javaClass?.simpleName} " +
                    "w=${icon?.iconWidth ?: -1} h=${icon?.iconHeight ?: -1} " +
                    "consistent=${this === currentRenderer}"
            )
        }
    }

    /**
     * 解析规则条目路径为虚拟文件（相对项目根拼接为主，兼容绝对路径）
     * 仅用于渲染器计算文件类型图标，解析失败返回 null（走文件名推断兜底）
     *
     * @param path 规则条目路径
     * @return 虚拟文件或 null
     */
    private fun resolveRuleItemFile(path: String): VirtualFile? {
        // ////////////////  准备数据：项目根路径（显式可空类型，分支内可安全判空使用）  start  //
        val basePath: String? = project.basePath
        // ////////////////  准备数据  end  ///////////////////////////////////////////

        // ////////////////  核心逻辑：按绝对性换算绝对路径后查 VFS 缓存  start  ///////////
        return try {
            val absolutePath = when {
                FavoritesTreePaths.isAbsolutePath(path) -> path
                basePath != null -> File(basePath, path).absolutePath
                else -> return null
            }
            // findFileByPath 需要正斜杠风格路径
            LocalFileSystem.getInstance().findFileByPath(absolutePath.replace('\\', '/'))
            // ////////////////  核心逻辑  end  ///////////////////////////////////////
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 解析文件节点路径为虚拟文件（项目作用域=项目根拼接相对路径；全局作用域=绝对路径直用）
     * 仅用于渲染器计算文件类型图标，解析失败返回 null（走文件名推断兜底）
     *
     * @param node 文件节点
     * @return 虚拟文件或 null
     */
    private fun resolveNodeFile(node: FileNode): VirtualFile? {
        // ////////////////  准备数据：项目根路径（显式可空类型，分支内可安全判空使用）  start  //
        val basePath: String? = project.basePath
        // ////////////////  准备数据  end  ///////////////////////////////////////////

        // ////////////////  核心逻辑：按作用域换算绝对路径后查 VFS 缓存  start  ///////////
        return try {
            val absolutePath = when {
                FavoritesTreePaths.isAbsolutePath(node.path) -> node.path
                basePath != null -> File(basePath, node.path).absolutePath
                else -> return null
            }
            // findFileByPath 需要正斜杠风格路径
            LocalFileSystem.getInstance().findFileByPath(absolutePath.replace('\\', '/'))
            // ////////////////  核心逻辑  end  ///////////////////////////////////////
        } catch (e: Exception) {
            null
        }
    }
}
