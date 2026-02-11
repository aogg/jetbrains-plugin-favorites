package com.aogg.favorites.ui

import com.aogg.favorites.model.BookmarkManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import javax.swing.JPanel
import javax.swing.JScrollPane
import java.awt.BorderLayout

/**
 * 收藏工具窗口
 * 提供统一的收藏管理界面
 */
class BookmarksToolWindowFactory : ToolWindowFactory {
    
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val toolWindowPanel = BookmarksToolWindowPanel(project)
        val content = ContentFactory.SERVICE.getInstance().createContent(toolWindowPanel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}

/**
 * 收藏工具窗口面板
 */
class BookmarksToolWindowPanel(project: Project) : JPanel(BorderLayout()) {
    
    private val bookmarkManager = BookmarkManager.getInstance(project)
    private val projectInstance = project
    private val bookmarksTree: BookmarksTree
    
    init {
        // 创建收藏树
        bookmarksTree = BookmarksTree(project)
        
        // 添加到滚动面板
        val scrollPane = JScrollPane(bookmarksTree)
        scrollPane.border = null
        add(scrollPane, BorderLayout.CENTER)
    }
    
    /**
     * 刷新工具窗口内容
     */
    fun refresh() {
        bookmarksTree.refresh()
    }
    
    /**
     * 获取收藏树
     */
    fun getBookmarksTree(): BookmarksTree {
        return bookmarksTree
    }
    
    companion object {
        /**
         * 刷新指定项目的工具窗口
         */
        @JvmStatic
        fun refreshToolWindow(project: Project) {
            val toolWindow = ToolWindowManager.getInstance(project)
                .getToolWindow("Favorites") ?: return
            
            val content = toolWindow.contentManager.selectedContent ?: toolWindow.contentManager.contents.firstOrNull() ?: return
            val panel = content.component as? BookmarksToolWindowPanel ?: return
            
            panel.refresh()
        }
    }
}
