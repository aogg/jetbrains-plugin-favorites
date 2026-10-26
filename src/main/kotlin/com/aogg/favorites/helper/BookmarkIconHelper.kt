package com.aogg.favorites.helper

import com.aogg.favorites.model.BookmarkType
import com.aogg.favorites.model.FavoriteScope
import com.aogg.favorites.ui.CharIcon
import com.intellij.openapi.vfs.VirtualFile
import java.util.Locale
import javax.swing.Icon

/**
 * 收藏图标管理助手
 * 提供匿名收藏、助记符收藏、作用域（1.2.0）、类型分组（1.2.0）、按扩展名文件图标（1.3.0 引入，1.5.x 字符化）
 *
 * 1.5.x 图标全量字符化说明（重要，勿删）：
 * - 历史 4 次图标加固（换 AllIcons 常量/渲染器直接实现/绘制级兜底/运行时取证）在用户环境下全部无效，
 *   判断为 AllIcons / SVG / 文件类型图标（FileTypeManager）的整条平台加载链被第三方主题/图标插件接管清空；
 * - 本类返回值类型保持 [Icon] 不变（调用点签名不动），实现全量换成 [CharIcon] 字符绘制：
 *   paintIcon 直接 drawString，不走任何图标加载链，主题/第三方插件无法清空字符，从根上绕开该问题；
 * - 助记符图标直接显示字母/数字本身（比原来统一书签图标信息量更大）；
 * - 文件图标改为按扩展名映射字符（目录优先判定），不再依赖 FileTypeManager / UnknownFileType。
 */
object BookmarkIconHelper {

    /**
     * 匿名收藏图标（字符书签 🔖，不走图标加载链）
     */
    val ANONYMOUS_ICON: Icon = CharIcon.of("🔖")

    /**
     * 获取助记符收藏图标
     * 1.5.x 起直接把助记符字母/数字本身绘制成图标（信息量优于原统一书签图标）
     *
     * @param mnemonic 助记符（0-9 或 A-Z）
     * @return 图标
     */
    fun getMnemonicIcon(mnemonic: Char): Icon {
        return CharIcon.of(mnemonic.toString())
    }

    /**
     * 获取助记符文本（用于显示在图标旁）
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
     * @param scope 作用域（项目=打开目录夹 📂；全局=地球 🌐）
     */
    fun getScopeIcon(scope: FavoriteScope): Icon = when (scope) {
        FavoriteScope.PROJECT -> CharIcon.of("📂")
        FavoriteScope.GLOBAL -> CharIcon.of("🌐")
    }

    /**
     * 获取收藏类型分组图标（树第三层类型组 / 收藏节点按类型用）
     *
     * @param type 收藏类型（行=书签 🔖；文件=文档 📄；文件夹=目录夹 📁）
     */
    fun getTypeIcon(type: BookmarkType): Icon = when (type) {
        BookmarkType.LINE -> CharIcon.of("🔖")
        BookmarkType.FILE -> CharIcon.of("📄")
        BookmarkType.FOLDER -> CharIcon.of("📁")
    }

    /**
     * 获取树中文件节点图标（1.5.x 字符化）：
     * 目录（virtualFile?.isDirectory 为 true）返回 📁；
     * 其余按文件名扩展名映射字符图标，无命中（无扩展名/未知扩展名）兜底 📄。
     * 不再依赖 virtualFile.fileType / FileTypeManager（图标加载链已被接管，不可用）
     *
     * @param virtualFile 已解析的虚拟文件（可为 null=离线/不存在，此时按文件名推断）
     * @param fileName 文件名（扩展名映射依据）
     * @return 文件图标
     */
    fun getFileIcon(virtualFile: VirtualFile?, fileName: String): Icon {
        // ////////////////  优先：目录判定（文件夹收藏/真实目录统一 📁）  start  ////////////////
        if (virtualFile?.isDirectory == true) {
            return CharIcon.of("📁")
        }
        // ////////////////  优先  end  //////////////////////////////////////////////////////

        // ////////////////  核心逻辑：按扩展名映射字符图标（Locale.ROOT 避免土耳其语 i 陷阱）  /
        return when (fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "php" -> CharIcon.of("🐘")
            "sql" -> CharIcon.of("🗄️")
            "java" -> CharIcon.of("☕")
            "kt", "kts" -> CharIcon.of("K")
            "js", "mjs", "cjs" -> CharIcon.of("🟨")
            "ts", "tsx" -> CharIcon.of("🔷")
            "html", "htm", "vue" -> CharIcon.of("🐥")
            "css", "scss", "less" -> CharIcon.of("🎨")
            "md", "markdown" -> CharIcon.of("📝")
            "bat", "cmd", "sh", "bash", "ps1" -> CharIcon.of("📜")
            "xml", "json", "yaml", "yml", "toml", "properties", "conf", "ini" -> CharIcon.of("🧾")
            // 无扩展名/未知扩展名兜底：通用文档 📄
            else -> CharIcon.of("📄")
        }
        // ////////////////  核心逻辑  end  //////////////////////////////////////////////////
    }
}
