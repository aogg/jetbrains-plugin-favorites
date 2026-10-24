package com.aogg.favorites.action

import com.aogg.favorites.model.Bookmark
import com.aogg.favorites.model.BookmarkType
import com.aogg.favorites.model.FavoriteScope
import com.aogg.favorites.model.FavoritesScopeResolver
import com.aogg.favorites.model.GlobalBookmarkManager
import com.aogg.favorites.model.BookmarkManager
import com.aogg.favorites.ui.ScopedBookmark
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

/**
 * 「固定作用域」执行契约（内部，1.4.x 原生书签快捷键接管新增）
 *
 * 实现者：添加类收藏动作（[AddAnonymousBookmarkAction]/[AddMnemonicBookmarkAction]）。
 * - 原生书签快捷键接管路径（DelegateToFavoritesAction）固定为当前项目作用域，
 *   直接执行、不弹 [com.aogg.favorites.ui.ScopeGroupChooserPopup] 分组树选择；
 *   目标分组取「该作用域上次使用的分组」，无记忆/记忆已删除则回退默认「收藏」（[resolveTargetGroup]）；
 * - 右键菜单等原入口仍走分组树选择（默认高亮并记住上次选择），行为不变。
 */
interface ScopedAddAction {

    /**
     * 以固定作用域执行添加/切换（原生动作接管路径专用）
     *
     * @param e 动作事件（委托动作已透传原数据上下文，编辑器/项目视图等上下文可用）
     * @param scope 固定作用域（接管路径固定为 [FavoriteScope.PROJECT]）
     */
    fun performWithFixedScope(e: AnActionEvent, scope: FavoriteScope)
}

/**
 * 添加收藏动作的共享支撑（1.2.0 新增）
 *
 * 统一"目标位置"描述与双作用域的存在性检测，供匿名/助记符两个添加动作复用：
 * - 项目作用域按【相对路径】匹配；全局作用域按【绝对路径】匹配；
 * - 项目外的位置只可能存在于全局作用域（项目收藏无法保存）。
 */
object AddActionSupport {

    /**
     * 待添加的目标位置
     *
     * @param type 收藏类型（行/文件/文件夹）
     * @param absolutePath 规范化（正斜杠）绝对路径
     * @param relativePath 项目内相对路径；位于项目之外时为 null
     * @param lineNumber 行收藏为 0 基行号；文件/文件夹收藏为 -1
     */
    data class PendingLocation(
        val type: BookmarkType,
        val absolutePath: String,
        val relativePath: String?,
        val lineNumber: Int
    )

    /**
     * 由虚拟文件构造目标位置（自动判定类型与相对路径）
     *
     * @param lineNumber 行收藏传 0 基行号；文件/文件夹传 -1
     */
    fun locationOf(project: Project, virtualFile: VirtualFile, lineNumber: Int): PendingLocation {
        // ////////////////  准备数据：绝对路径规范化 + 相对路径换算  start  //////////////////
        val absolutePath = virtualFile.path.replace('\\', '/')
        // ////////////////  准备数据  end  /////////////////////////////////////////////

        // ////////////////  返回  start  /////////////////////////////////////////////
        return PendingLocation(
            type = if (virtualFile.isDirectory) BookmarkType.FOLDER else BookmarkType.FILE,
            absolutePath = absolutePath,
            relativePath = relativePathOf(project, absolutePath),
            lineNumber = lineNumber
        )
        // ////////////////  返回  end  ///////////////////////////////////////////////
    }

    /**
     * 绝对路径转项目内相对路径（位于项目外返回 null）
     */
    fun relativePathOf(project: Project, absolutePath: String): String? {
        // ////////////////  校验参数：无项目根无法换算  start  /////////////////////////////
        val basePath = project.basePath ?: return null
        // ////////////////  校验参数  end  ///////////////////////////////////////////

        // ////////////////  核心逻辑：前缀判定（目录边界对齐）后截取  start  //////////////
        val normalizedBase = basePath.replace('\\', '/').trimEnd('/')
        val normalizedPath = absolutePath.replace('\\', '/')
        val underBase = normalizedPath == normalizedBase || normalizedPath.startsWith("$normalizedBase/")
        return if (underBase) {
            normalizedPath.removePrefix(normalizedBase).trimStart('/')
        } else {
            null
        }
        // ////////////////  核心逻辑  end  ///////////////////////////////////////////
    }

    /**
     * 按作用域取该位置的存储路径
     *
     * @return 项目作用域→相对路径；全局作用域→绝对路径；项目作用域+项目外→null（无法收藏）
     */
    fun storagePathFor(scope: FavoriteScope, location: PendingLocation): String? = when (scope) {
        FavoriteScope.PROJECT -> location.relativePath
        FavoriteScope.GLOBAL -> location.absolutePath
    }

    /**
     * 解析作用域的有效目标分组（1.5.x 分组选择新增）
     *
     * 读取「该作用域上次使用的分组」记忆（FavoritesSettings.lastGroupPerScope，与 lastAddScope 同一存储）；
     * 记忆为空或分组已被删除时回退默认列表 [com.aogg.favorites.model.Bookmark.DEFAULT_LIST_NAME]。
     * 供原生快捷键接管路径（不弹窗）与分组树弹窗的默认选中共用，存在性检测以当前管理器为准。
     *
     * @param project 当前项目（PROJECT 作用域解析管理器需要）
     * @param scope 目标作用域
     * @return 有效目标分组名（恒为该作用域下已存在的分组）
     */
    fun resolveTargetGroup(project: Project, scope: FavoriteScope): String {
        // ////////////////  准备数据：读取记忆中的上次分组  start  ///////////////////////
        val remembered = runCatching {
            com.aogg.favorites.settings.FavoritesSettings.getInstance().getLastGroupForScope(scope)
        }.getOrNull()
        // ////////////////  准备数据  end  /////////////////////////////////////////////

        // ////////////////  核心逻辑：记忆有效则用之，否则回退默认列表  start  ////////////
        val manager = FavoritesScopeResolver.managerFor(project, scope)
        val rememberedValid = remembered != null && runCatching { manager.hasList(remembered) }.getOrDefault(false)
        return if (rememberedValid) remembered!! else com.aogg.favorites.model.Bookmark.DEFAULT_LIST_NAME
        // ////////////////  核心逻辑  end  /////////////////////////////////////////////
    }

    /**
     * 检测某位置在两个作用域内是否已存在收藏（用于添加时的切换语义判定）
     *
     * @param extra 附加过滤（如仅匿名收藏、仅助记符收藏），与历史行为保持一致
     * @return 命中的收藏（带作用域；项目作用域按相对路径匹配，全局作用域按绝对路径匹配）
     */
    fun findExisting(
        project: Project,
        location: PendingLocation,
        extra: (Bookmark) -> Boolean = { true }
    ): List<ScopedBookmark> {
        // ////////////////  核心逻辑：项目作用域按相对路径匹配  start  ///////////////////
        val result = mutableListOf<ScopedBookmark>()
        val relativePath = location.relativePath
        if (relativePath != null) {
            BookmarkManager.getInstance(project).getAllBookmarks()
                .filter { matchesLocation(it, relativePath, location, extra) }
                .forEach { result.add(ScopedBookmark(FavoriteScope.PROJECT, it)) }
        }
        // ////////////////  核心逻辑：项目作用域  end  /////////////////////////////////

        // ////////////////  核心逻辑：全局作用域按绝对路径匹配  start  ///////////////////
        GlobalBookmarkManager.getInstance().getAllBookmarks()
            .filter { matchesLocation(it, location.absolutePath, location, extra) }
            .forEach { result.add(ScopedBookmark(FavoriteScope.GLOBAL, it)) }
        // ////////////////  核心逻辑：全局作用域  end  /////////////////////////////////

        // ////////////////  返回  start  /////////////////////////////////////////////
        return result
        // ////////////////  返回  end  ///////////////////////////////////////////////
    }

    /**
     * 单个收藏是否命中目标位置（类型/路径/行号一致且满足附加过滤）
     */
    private fun matchesLocation(
        bookmark: Bookmark,
        path: String,
        location: PendingLocation,
        extra: (Bookmark) -> Boolean
    ): Boolean {
        // 文件/文件夹收藏行号恒为 -1，统一比较行号即等价于历史行为
        return bookmark.type == location.type &&
            bookmark.filePath == path &&
            bookmark.lineNumber == location.lineNumber &&
            extra(bookmark)
    }

    /**
     * 绝对路径兜底工具（保留 File 语义，供外部复用）
     */
    fun isAbsolute(path: String): Boolean = File(path).isAbsolute
}
