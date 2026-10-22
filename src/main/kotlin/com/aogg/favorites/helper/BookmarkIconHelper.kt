package com.aogg.favorites.helper

import com.aogg.favorites.model.BookmarkType
import com.aogg.favorites.model.FavoriteScope
import com.intellij.icons.AllIcons
import javax.swing.Icon

/**
 * 收藏图标管理助手
 * 提供匿名收藏、助记符收藏、作用域（1.2.0）、类型分组（1.2.0）的图标
 *
 * 图标选型说明（基于 PhpStorm 2023.3 实测可用常量）：
 * - 全局作用域：AllIcons.Nodes.Palette 在 2023.3 不存在，改用 AllIcons.General.Web（地球图标，语义最接近"全局"）；
 * - 文件类型组：AllIcons.Nodes.FileText 在 2023.3 不存在，改用 AllIcons.FileTypes.Text（文档图标）。
 */
object BookmarkIconHelper {

    /**
     * 匿名收藏图标（使用 IntelliJ 内置图标）
     */
    val ANONYMOUS_ICON: Icon = AllIcons.Gutter.Bookmark

    /**
     * 获取助记符收藏图标
     * 使用带框的字母/数字图标
     *
     * @param mnemonic 助记符（0-9 或 A-Z）
     * @return 图标
     */
    fun getMnemonicIcon(mnemonic: Char): Icon {
        // 使用 IntelliJ 内置的带框图标
        // 对于数字，使用数字图标；对于字母，使用字母图标
        return when {
            mnemonic in '0'..'9' -> AllIcons.Gutter.Bookmark
            mnemonic in 'A'..'Z' -> AllIcons.Gutter.Bookmark
            else -> AllIcons.Gutter.Bookmark
        }
    }

    /**
     * 获取助记符文本（用于显示在图标上）
     *
     * @param mnemonic 助记符
     * @return 显示文本（如 "[1]" 或 "[A]"）
     */
    fun getMnemonicText(mnemonic: Char): String {
        return "[$mnemonic]"
    }

    /**
     * 获取作用域节点图标（树第一层 / 弹窗分组用）
     *
     * @param scope 作用域（项目=项目图标；全局=地球图标）
     */
    fun getScopeIcon(scope: FavoriteScope): Icon = when (scope) {
        FavoriteScope.PROJECT -> AllIcons.Nodes.Project
        FavoriteScope.GLOBAL -> AllIcons.General.Web
    }

    /**
     * 获取收藏类型分组图标（树第三层类型组 / 收藏节点按类型用）
     *
     * @param type 收藏类型（行=书签图标；文件=文档图标；文件夹=文件夹图标）
     */
    fun getTypeIcon(type: BookmarkType): Icon = when (type) {
        BookmarkType.LINE -> AllIcons.Gutter.Bookmark
        BookmarkType.FILE -> AllIcons.FileTypes.Text
        BookmarkType.FOLDER -> AllIcons.Nodes.Folder
    }
}
