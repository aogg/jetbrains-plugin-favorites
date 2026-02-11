package com.aogg.favorites.action

import com.aogg.favorites.ui.BookmarkPopup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project

/**
 * 显示收藏弹出窗口的动作
 * 支持过滤和搜索
 */
class ShowBookmarksPopupAction : AnAction("显示收藏列表", "显示收藏快速访问弹出窗口", null) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)
        
        // 显示弹出窗口
        val popup = BookmarkPopup(project, editor)
        popup.show()
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null
    }
}
