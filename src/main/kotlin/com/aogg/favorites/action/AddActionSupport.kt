package com.aogg.favorites.action

import com.aogg.favorites.model.Bookmark
import com.aogg.favorites.model.BookmarkType
import com.aogg.favorites.model.FavoriteScope
import com.aogg.favorites.model.GlobalBookmarkManager
import com.aogg.favorites.model.BookmarkManager
import com.aogg.favorites.ui.ScopedBookmark
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

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
