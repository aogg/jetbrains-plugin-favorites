package com.aogg.favorites.ui

import com.aogg.favorites.helper.BookmarkIconHelper
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import java.io.File
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

/**
 * 收藏树单元格渲染器（1.3.0 PhpStorm 风格，改用 ColoredTreeCellRenderer）：
 * - 主文本正常色 + 右侧灰色说明文字（SimpleTextAttributes.GRAYED_ATTRIBUTES），替代原 DefaultTreeCellRenderer
 * - 作用域节点：作用域图标 + "作用域名 (总数)"
 * - 列表节点：文件夹图标 + "名称 (收藏数)"
 * - 目录节点：文件夹图标（展开/折叠同图标）+ 目录名；文件夹收藏节点灰色补充路径；目录缺失/不可读显示红叉图标
 * - 文件节点：IDE 文件类型图标（.php/.bat/.sql 等按扩展名自然区分）+ 文件名；文件收藏节点灰色补充父目录路径与描述
 * - 行收藏节点：助记符/匿名书签图标 + "行号:" + 灰色行内容
 * - 未收藏真实文件节点：文件类型图标 + 文件名
 * 各节点 toString 返回主文本，作为 TreeSpeedSearch 的匹配文本（灰色说明文字不参与速度搜索）
 *
 * @param project 项目（解析 VirtualFile 计算文件类型图标）
 */
class BookmarksTreeCellRenderer(private val project: Project) : ColoredTreeCellRenderer() {

    override fun customizeCellRenderer(
        tree: JTree,
        value: Any,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean
    ) {
        // ////////////////  准备数据：取节点用户对象  start  ///////////////////////////
        val node = (value as? DefaultMutableTreeNode)?.userObject
        // ////////////////  准备数据  end  ////////////////////////////////////////////

        // ////////////////  核心逻辑：按节点类型渲染图标与主文本/灰色说明  start  ///////
        when (node) {
            // 作用域节点（含总数徽标文案）
            is ScopeNode -> {
                icon = BookmarkIconHelper.getScopeIcon(node.scope)
                append(node.toString())
            }
            // 列表节点（含收藏数徽标文案）
            is BookmarkListNode -> {
                icon = AllIcons.Nodes.Folder
                append(node.toString())
            }
            // 目录节点：虚拟目录链/文件夹收藏/真实目录（展开折叠态同用文件夹图标）
            is DirNode -> {
                icon = if (node.missing) AllIcons.General.Error else AllIcons.Nodes.Folder
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
        }
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////
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
