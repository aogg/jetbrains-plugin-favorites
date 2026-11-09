package com.aogg.favorites.action

import com.aogg.favorites.helper.FavoritesActionTexts
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager

/**
 * 切换收藏工具窗口显示的动作
 */
class ToggleBookmarksToolWindowAction : AnAction("显示/隐藏收藏工具窗口", "切换收藏工具窗口的显示状态", null) {
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val toolWindowManager = ToolWindowManager.getInstance(project)
        val toolWindow = toolWindowManager.getToolWindow("Favorites") ?: return
        
        if (toolWindow.isVisible) {
            toolWindow.hide(null)
        } else {
            toolWindow.show(null)
        }
    }
    
    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null
        // 1.8.0 菜单文案：短名 / 短名+插件名后缀（按「隐藏原生书签界面」设置实时切换）
        e.presentation.text = FavoritesActionTexts.of(BASE_TEXT)
    }

    companion object {
        /** 菜单 base 文案（是否加「（Favorites）」后缀由 FavoritesActionTexts 决定） */
        private const val BASE_TEXT = "显示/隐藏收藏工具窗口"
    }
}
