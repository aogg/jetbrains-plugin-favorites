package com.aogg.favorites.helper

import com.aogg.favorites.model.BookmarkType
import com.aogg.favorites.model.FavoriteScope
import com.intellij.ide.bookmark.BookmarkType as PlatformBookmarkType
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.Icon

/**
 * 收藏图标管理助手
 * 提供匿名收藏、助记符收藏、作用域（1.2.0）、类型分组（1.2.0）、文件类型图标（1.3.0）
 *
 * 图标选型说明（基于 PhpStorm 2023.3 实测可用常量）：
 * - 全局作用域：AllIcons.Nodes.Palette 在 2023.3 不存在，改用 AllIcons.General.Web（地球图标，语义最接近"全局"）；
 * - 文件类型组：AllIcons.Nodes.FileText 在 2023.3 不存在，改用 AllIcons.FileTypes.Text（文档图标）；
 * - 树中文件节点：1.3.0 起改用 IDE 文件类型图标（.php/.bat/.sql 等按扩展名自然区分）。
 *
 * 1.8.1：助记符图标改回**平台原生助记符图标**（[PlatformBookmarkType.gutterIcon]）——
 * 平台 `BookmarkType` 就是「书签底图 + 数字/字母」的原生实现（`AllIcons.Gutter.Mnemonic` + `MnemonicPainter`，
 * 跟随主题色与新 UI 尺寸），与原生书签行号图标外观完全一致；
 * 1.8.0 自绘的 `MnemonicBookmarkIcon`（白字 + 深色描边）外观与原生不一致、维护成本高，已删除。
 */
object BookmarkIconHelper {

    /**
     * 匿名收藏图标（使用 IntelliJ 内置图标，与平台 `BookmarkType.DEFAULT.gutterIcon` 同一图标）
     */
    val ANONYMOUS_ICON: Icon = AllIcons.Gutter.Bookmark

    /**
     * 获取助记符收藏图标（1.8.1：直接取平台原生助记符 gutter 图标）
     *
     * 平台 `BookmarkType` 的 `gutterIcon` 是 lazy 缓存的不可变图标（同一字符恒返回同一实例），
     * 因此这里无需再做缓存，也不会造成 LineMarkerInfo 反复构造图标。
     * 非法/未知助记符（含 0 字符）由 `BookmarkType.get` 自然落到 `DEFAULT`，即普通书签图标。
     *
     * @param mnemonic 助记符（0-9 或 A-Z；小写统一按大写取图标）
     * @return 平台助记符 gutter 图标（书签底图 + 字符；DEFAULT 时为普通书签）
     */
    fun getMnemonicIcon(mnemonic: Char): Icon =
        PlatformBookmarkType.get(mnemonic.uppercaseChar()).gutterIcon

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
     * @param scope 作用域（项目=项目图标；共享=分享图标；全局=地球图标）
     */
    fun getScopeIcon(scope: FavoriteScope): Icon = when (scope) {
        FavoriteScope.PROJECT -> AllIcons.Nodes.Project
        FavoriteScope.SHARED -> AllIcons.Actions.Share
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

    /**
     * 获取树中文件节点图标（1.3.0 PhpStorm 风格树）：
     * 优先使用有效 VirtualFile 的文件类型图标（不同扩展名 .php/.bat/.sql 等自然区分）；
     * 离线/文件不存在时按文件名推断文件类型图标；再兜底任意文件图标
     *
     * @param virtualFile 已解析的虚拟文件（可为 null=离线/不存在）
     * @param fileName 文件名（兜底推断用）
     * @return 文件图标
     */
    fun getFileIcon(virtualFile: VirtualFile?, fileName: String): Icon {
        // ////////////////  优先：有效 VirtualFile 的文件类型图标  start  ////////////////
        if (virtualFile != null && virtualFile.isValid) {
            try {
                return virtualFile.fileType.icon
            } catch (e: Exception) {
                // 文件类型解析异常时走文件名推断兜底
            }
        }
        // ////////////////  优先  end  //////////////////////////////////////////////////

        // ////////////////  兜底一：按文件名推断文件类型图标  start  //////////////////////
        val fileTypeByFileName = try {
            FileTypeManager.getInstance().getFileTypeByFileName(fileName)
        } catch (e: Exception) {
            null
        }
        if (fileTypeByFileName != null && fileTypeByFileName !== UnknownFileType.INSTANCE) {
            return fileTypeByFileName.icon
        }
        // ////////////////  兜底一  end  //////////////////////////////////////////////////

        // ////////////////  兜底二：任意文件图标（2023.3 常量为 Any_type）  start  ///////////
        return AllIcons.FileTypes.Any_type
        // ////////////////  兜底二  end  //////////////////////////////////////////////////
    }
}
