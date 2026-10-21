package com.aogg.favorites.nativebookmarks

import com.aogg.favorites.settings.FavoritesProjectImportState
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * 首次引导：项目启动后检测到原生书签且未导入过时，弹出 BALLOON 通知引导一键导入
 *
 * 触发条件（见 docs/v2-design.md 2.5）：
 * - 项目级标记 !importedNative && !dontAskAgain
 * - 检测到原生书签数 > 0（轻量通道，避免拖慢启动）
 */
object FavoritesFirstRunGuide {

    private val log = Logger.getInstance(FavoritesFirstRunGuide::class.java)

    /**
     * 检测并弹出首次引导通知（不满足条件时静默返回）
     */
    @JvmStatic
    fun maybeNotify(project: Project) {
        runCatching {
            if (project.isDisposed) return
            val importState = FavoritesProjectImportState.getInstance(project).state
            if (importState.importedNative || importState.dontAskAgain) return

            // 轻量检测：优先运行时 API 数量统计，失败转 XML 统计（文件小，开销可忽略）
            val count = runCatching {
                NativeBookmarkImportService.getInstance(project).countNativeBookmarks()
            }.getOrDefault(0)
            if (count <= 0) return

            val notification = Notification(
                "Favorites.Notifications",
                "Favorites 收藏",
                "检测到 $count 条 PhpStorm 原生书签，可一键导入到本插件",
                NotificationType.INFORMATION
            )
            notification.addAction(
                NotificationAction.createSimple("立即导入") { runImport(project) }
            )
            notification.addAction(
                NotificationAction.createSimple("不再提示") {
                    runCatching {
                        FavoritesProjectImportState.getInstance(project).state.dontAskAgain = true
                    }.onFailure { log.warn("保存不再提示标记失败", it) }
                }
            )
            Notifications.Bus.notify(notification, project)
        }.onFailure { log.warn("首次引导检测失败（忽略）", it) }
    }

    private fun runImport(project: Project) {
        if (project.isDisposed) return
        NativeBookmarkImportService.importAndNotify(project)
    }
}
