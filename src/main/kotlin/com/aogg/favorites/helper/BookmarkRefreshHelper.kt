package com.aogg.favorites.helper

import com.aogg.favorites.ui.BookmarksToolWindowPanel
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * 收藏变更后的统一刷新入口：
 * - 刷新收藏工具窗口
 * - 重启 Daemon 以刷新编辑器行号区（gutter）收藏图标
 *
 * 所有添加/删除/移动收藏的路径都应调用本方法，确保 gutter 图标即时更新。
 */
object BookmarkRefreshHelper {

    private val log = Logger.getInstance(BookmarkRefreshHelper::class.java)

    /**
     * 收藏数据发生变化后刷新工具窗口与 gutter
     */
    @JvmStatic
    fun refreshAfterChange(project: Project) {
        if (project.isDisposed) return
        BookmarksToolWindowPanel.refreshToolWindow(project)
        try {
            DaemonCodeAnalyzer.getInstance(project).restart()
        } catch (e: Exception) {
            // gutter 刷新失败不应影响收藏数据本身，记录日志便于排查
            log.warn("收藏变更后刷新 gutter 失败", e)
        }
    }
}
