package com.aogg.favorites.action

import com.aogg.favorites.model.Bookmark
import com.aogg.favorites.model.BookmarkManager
import com.aogg.favorites.ui.BookmarksToolWindowPanel
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager

/**
 * 编辑收藏动作
 * 用于编辑助记符收藏的描述信息
 */
class EditBookmarkAction : AnAction("编辑收藏描述", "编辑收藏的描述信息", null) {
    
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
        val bookmark = panel.getBookmarksTree().getSelectedBookmark() ?: return
        
        // 只有助记符收藏可以编辑描述
        if (!bookmark.isMnemonic()) {
            Messages.showInfoMessage(
                project,
                "只有助记符收藏可以编辑描述",
                "编辑收藏"
            )
            return
        }
        
        // 显示编辑对话框
        val currentDescription = bookmark.description ?: ""
        val newDescription = Messages.showInputDialog(
            project,
            "请输入收藏描述：",
            "编辑收藏描述",
            Messages.getQuestionIcon(),
            currentDescription,
            null
        )
        
        if (newDescription != null) {
            // 更新描述
            bookmarkManager.updateBookmarkDescription(bookmark.id, newDescription)
            
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
        
        val bookmark = panel.getBookmarksTree().getSelectedBookmark()
        e.presentation.isEnabled = bookmark != null && bookmark.isMnemonic()
    }
}
