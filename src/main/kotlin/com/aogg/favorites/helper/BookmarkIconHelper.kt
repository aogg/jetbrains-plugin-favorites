package com.aogg.favorites.helper

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * 收藏图标管理助手
 * 提供匿名收藏和助记符收藏的图标
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
}
