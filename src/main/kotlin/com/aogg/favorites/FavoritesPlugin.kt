package com.aogg.favorites

import com.aogg.favorites.listener.BookmarkEditorListener
import com.aogg.favorites.listener.BookmarkProjectListener
import com.aogg.favorites.model.BookmarkManager
import com.aogg.favorites.nativebookmarks.FavoritesFirstRunGuide
import com.aogg.favorites.nativebookmarks.NativeBookmarksToolWindowHider
import com.aogg.favorites.settings.FavoritesSettings
import com.intellij.openapi.application.ApplicationManager
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

        // 阶段 B：隐藏原生书签入口的工具窗口部分（动作接管由 ActionConfigurationCustomizer 完成）
        // 尽力而为：隐藏不掉只记日志，不影响插件其它功能
        if (FavoritesSettings.getInstance().state.hideNativeBookmarks) {
            NativeBookmarksToolWindowHider.hide(project)
        }
        // 兜底监听始终注册：内部自行判断开关状态（被显示就再藏 / 开关关闭时不作为）
        NativeBookmarksToolWindowHider.registerListener(project)

        // 阶段 B：首次引导（!importedNative && !dontAskAgain 且检测到原生书签数>0 时弹 BALLOON）
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            FavoritesFirstRunGuide.maybeNotify(project)
        }
    }
}
