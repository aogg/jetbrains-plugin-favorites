package com.aogg.favorites.action

import com.aogg.favorites.model.BookmarkManager
import com.aogg.favorites.ui.BookmarksToolWindowPanel
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager

/**
 * 删除收藏动作
 */
class DeleteBookmarkAction : AnAction("删除收藏", "删除选中的收藏", null) {
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val bookmarkManager = BookmarkManager.getInstance(project)
        
        // 获取工具窗口
        val toolWindow = ToolWindowManager.getInstance(project)
            .getToolWindow("Favorites") ?: return
        
        // 获取工具窗口面板
        val content = toolWindow.contentManager.selectedContent ?: return
        val panel = content.component as? BookmarksToolWindowPanel ?: return
        
        // 获取选中的收藏
        val selectedBookmarks = panel.getBookmarksTree().getSelectedBookmarks()
        if (selectedBookmarks.isEmpty()) {
            return
        }
        
        // 确认删除
        val message = if (selectedBookmarks.size == 1) {
            "确定要删除这个收藏吗？"
        } else {
            "确定要删除这 ${selectedBookmarks.size} 个收藏吗？"
        }
        
        val result = Messages.showYesNoDialog(
            project,
            message,
            "删除收藏",
            Messages.getQuestionIcon()
        )
        
        if (result == Messages.YES) {
            // 删除收藏
            selectedBookmarks.forEach { bookmark ->
                bookmarkManager.removeBookmark(bookmark.id)
            }
            
            // 刷新工具窗口
            panel.refresh()
        }
    }
    
    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabled = false
            return
        }
        
        // 检查是否有选中的收藏
        val toolWindow = ToolWindowManager.getInstance(project)
            .getToolWindow("Favorites") ?: run {
            e.presentation.isEnabled = false
            return
        }
        
        val content = toolWindow.contentManager.selectedContent ?: run {
            e.presentation.isEnabled = false
            return
        }
        
        val panel = content.component as? BookmarksToolWindowPanel ?: run {
            e.presentation.isEnabled = false
            return
        }
        
        val selectedBookmarks = panel.getBookmarksTree().getSelectedBookmarks()
        e.presentation.isEnabled = selectedBookmarks.isNotEmpty()
    }
}
