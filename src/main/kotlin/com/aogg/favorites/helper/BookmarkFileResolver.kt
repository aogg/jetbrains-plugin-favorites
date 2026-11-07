package com.aogg.favorites.helper

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 收藏路径解析器（1.7.3）
 *
 * 背景：收藏树、快速访问弹窗、助记符跳转三个入口原先各写了一套「路径 → VirtualFile」换算，
 * 且都没有路径宏展开（`$APPLICATION_CONFIG_DIR$` 等），导入的原生书签收藏点了没反应。
 * 本对象是唯一实现：宏展开候选 → 逐候选查 VFS → 每候选绝对路径一次同步刷新兜底。
 */
object BookmarkFileResolver {

    /** 盘符段正则（Windows 绝对路径跨平台判定，与 `FavoritesTreePaths.isAbsolutePath` 语义一致） */
    private val DRIVE_SEGMENT = Regex("^[A-Za-z]:$")

    /** 日志 */
    private val log = Logger.getInstance(BookmarkFileResolver::class.java)

    /**
     * 已做过 VFS 同步刷新兜底的绝对路径集合（每路径只试一次，防过滤输入/渲染等高频调用反复触盘；并发安全）
     */
    private val refreshTriedPaths: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * 解析路径对应的已存在虚拟文件（收藏树/快速访问/助记符跳转共用入口）
     *
     * 语义边界：
     * - 路径先经 [BookmarkPathMacros.candidates] 展开成按优先级排序的候选（宏已展开的绝对路径在前）；
     * - 每个候选按「自身是否绝对路径 / [absolute] 参数」换算成绝对路径后查 VFS 缓存，首个命中即返回；
     * - VFS 未收录时做一次同步刷新兜底（提升目录/文件首次出现即可见），同一绝对路径只尝试一次
     *   （见 [refreshTriedPaths]），[clearRefreshTriedPaths] 可整体重置；
     * - read/write action 内不刷新（平台断言），只查缓存；
     * - `path` 为空串=作用域基准目录（[absolute]=true → "/"；否则项目根，项目根缺失返回 null），
     *   保留收藏树原有「空路径即作用域根」语义；
     * - [absolute] 为 null 时按路径自身形态自动判定（[File.isAbsolute] 或盘符前缀）。
     *
     * @param path 收藏/节点路径（可含未展开的 IDE 路径宏、file:// 协议前缀、反斜杠分隔符）
     * @param project 当前项目（可空；为空时相对路径无法换算，只能解析绝对路径候选）
     * @param absolute 是否绝对路径语义（null=按路径自动判定）
     * @return 虚拟文件；全部候选都不存在（或无法换算）时返回 null
     */
    fun findExistingFile(path: String, project: Project?, absolute: Boolean? = null): VirtualFile? {
        // ////////////////  准备数据：候选路径 + 项目根 + 刷新可用性  start  //////////////
        val basePath = project?.basePath
        val candidates = if (path.isEmpty()) listOf("") else BookmarkPathMacros.candidates(path, basePath)
        val effectiveAbsolute = absolute ?: looksLikeAbsolutePath(path)
        // read/write action 内禁止同步刷新（平台断言），此时只查缓存不刷新
        val application = ApplicationManager.getApplication()
        val refreshAllowed = application != null &&
            !application.isReadAccessAllowed && !application.isWriteAccessAllowed
        val localFileSystem = LocalFileSystem.getInstance()
        // ////////////////  准备数据  end  //////////////////////////////////////////////

        // ////////////////  核心逻辑一：逐候选查 VFS 缓存  start  ////////////////////////
        for (candidate in candidates) {
            // 候选自身是绝对路径（宏已展开成绝对路径）时按绝对路径语义换算
            val absolutePath = toAbsolutePath(
                candidate,
                basePath,
                effectiveAbsolute || looksLikeAbsolutePath(candidate)
            ) ?: continue
            // findFileByPath 需正斜杠风格路径
            val normalizedPath = absolutePath.replace('\\', '/')
            localFileSystem.findFileByPath(normalizedPath)?.let { return it }

            // ////////////////  核心逻辑二：VFS 未收录时同步刷新兜底（每路径一次）  start  //
            if (!refreshAllowed) continue
            if (!refreshTriedPaths.add(normalizedPath)) continue
            val refreshed = runCatching {
                localFileSystem.refreshAndFindFileByIoFile(File(normalizedPath))
            }.onFailure { log.warn("VFS 同步刷新兜底失败：$normalizedPath", it) }.getOrNull()
            if (refreshed != null) return refreshed
            // ////////////////  核心逻辑二  end  ////////////////////////////////////////
        }
        // ////////////////  核心逻辑一  end  ////////////////////////////////////////////

        // ////////////////  返回：全部候选都未命中  start  //////////////////////////////
        return null
        // ////////////////  返回  end  /////////////////////////////////////////////////
    }

    /**
     * 让某个路径重新获得「VFS 同步刷新兜底」机会（用户显式刷新时调用）
     *
     * @param path 路径（收藏/节点路径，可含宏；候选换算后逐个移除标记）
     * @param project 当前项目（可空）
     * @param absolute 是否绝对路径语义（null=按路径自动判定）
     */
    fun forgetRefreshTriedPath(path: String, project: Project?, absolute: Boolean? = null) {
        // ////////////////  校验参数：空路径=作用域基准目录，无兜底标记  start  ///////////
        if (path.isEmpty()) return
        // ////////////////  校验参数  end  ////////////////////////////////////////////

        // ////////////////  核心逻辑：移除各候选绝对路径的一次性标记  start  ///////////////
        val basePath = project?.basePath
        val effectiveAbsolute = absolute ?: looksLikeAbsolutePath(path)
        for (candidate in BookmarkPathMacros.candidates(path, basePath)) {
            val absolutePath = toAbsolutePath(
                candidate,
                basePath,
                effectiveAbsolute || looksLikeAbsolutePath(candidate)
            ) ?: continue
            refreshTriedPaths.remove(absolutePath.replace('\\', '/'))
        }
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////
    }

    /**
     * 清空「VFS 未收录路径只尝试一次」缓存（整树刷新时调用，让上次未收录路径重新尝试刷新）
     */
    fun clearRefreshTriedPaths() {
        refreshTriedPaths.clear()
    }

    /**
     * 候选路径 → 绝对路径（空串代表作用域基准目录）
     *
     * @param candidate 候选路径（已归一为正斜杠；""=作用域基准目录）
     * @param basePath 项目根路径（相对路径换算基准；可空）
     * @param absolute 是否绝对路径语义
     * @return 绝对路径；相对路径且缺少项目根时返回 null
     */
    private fun toAbsolutePath(candidate: String, basePath: String?, absolute: Boolean): String? {
        // ////////////////  核心逻辑：按绝对性换算  start  /////////////////////////////
        return if (absolute) {
            if (candidate.isEmpty()) "/" else candidate
        } else {
            basePath?.let { base -> if (candidate.isEmpty()) base else File(base, candidate).absolutePath }
        }
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////
    }

    /**
     * 路径是否形如绝对路径（[File.isAbsolute] 与盘符前缀两种判定合并，兼容跨平台路径）
     *
     * @param path 路径字符串
     * @return true=绝对路径
     */
    private fun looksLikeAbsolutePath(path: String): Boolean {
        val normalized = path.replace('\\', '/')
        return File(normalized).isAbsolute || DRIVE_SEGMENT.matches(normalized.substringBefore('/'))
    }
}
