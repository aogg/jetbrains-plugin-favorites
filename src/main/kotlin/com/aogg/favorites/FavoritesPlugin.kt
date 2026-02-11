package com.aogg.favorites

import com.aogg.favorites.listener.BookmarkEditorListener
import com.aogg.favorites.listener.BookmarkProjectListener
import com.aogg.favorites.model.BookmarkManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.vfs.VirtualFileManager

/**
 * 收藏插件主类
 * 负责插件初始化和生命周期管理
 */
class FavoritesPlugin : StartupActivity {
    override fun runActivity(project: Project) {
        // 初始化收藏管理器
        BookmarkManager.getInstance(project)

        // 注册监听器（阶段七：监听器和优化）
        val connection = project.messageBus.connect(project)
        connection.subscribe(VirtualFileManager.VFS_CHANGES, BookmarkProjectListener(project))
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, BookmarkEditorListener(project))
    }
}
