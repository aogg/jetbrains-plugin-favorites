package com.aogg.favorites.helper

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * 收藏打开失败提示（1.7.3）
 *
 * 背景：旧实现在解析失败时静默 return，用户只看到「双击/回车没反应」。本对象统一发非模态
 * WARNING 通知（收藏名 + 原始路径 + 已尝试候选），让失败可诊断；内容对三个入口保持一致。
 */
object BookmarkOpenNotifier {

    /** 通知组 id（见 plugin.xml 的 notificationGroup） */
    private const val NOTIFICATION_GROUP_ID = "Favorites.Notifications"

    /** 通知标题 */
    private const val NOTIFICATION_TITLE = "无法打开收藏"

    /** 通知里展示的候选路径条数上限（避免刷屏） */
    private const val MAX_NOTIFY_CANDIDATES = 3

    /** 通知里单条路径的显示长度上限（超出截断加省略号） */
    private const val NOTIFY_PATH_MAX_LENGTH = 120

    /** 日志 */
    private val log = Logger.getInstance(BookmarkOpenNotifier::class.java)

    /**
     * 弹出「无法打开收藏」非模态警告通知（不阻塞 EDT）
     *
     * @param project 当前项目（可空；用于通知归属项目，不影响气泡展示）
     * @param displayName 收藏显示名（原名或别名）
     * @param rawPath 收藏的原始路径（未展开宏的原样值，便于用户对照）
     * @param candidates 已尝试的候选路径（[BookmarkPathMacros.candidates] 的结果）
     */
    fun notifyOpenFailed(project: Project?, displayName: String, rawPath: String, candidates: List<String>) {
        // ////////////////  准备数据：候选最多 3 条，单条过长截断  start  ////////////////
        val triedPaths = candidates.take(MAX_NOTIFY_CANDIDATES)
            .joinToString("；") { truncatePath(it) }
            .ifBlank { "（无可用候选，路径宏未能展开）" }
        // ////////////////  准备数据  end  ////////////////////////////////////////////

        // ////////////////  核心逻辑：非模态 WARNING 通知（失败只记日志，不影响调用方流程）  start
        val notification = Notification(
            NOTIFICATION_GROUP_ID,
            NOTIFICATION_TITLE,
            "名称：$displayName\n原始路径：$rawPath\n已尝试：$triedPaths",
            NotificationType.WARNING
        )
        runCatching { Notifications.Bus.notify(notification, project) }
            .onFailure { log.warn("弹出「无法打开收藏」通知失败", it) }
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////
    }

    /**
     * 通知展示用的路径截断（超长截断加省略号，避免通知气泡被撑爆）
     *
     * @param path 候选路径
     * @return 截断后的路径文本
     */
    private fun truncatePath(path: String): String =
        if (path.length > NOTIFY_PATH_MAX_LENGTH) path.take(NOTIFY_PATH_MAX_LENGTH) + "…" else path
}
