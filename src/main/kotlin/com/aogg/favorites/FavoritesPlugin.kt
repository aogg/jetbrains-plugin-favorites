package com.aogg.favorites

import com.aogg.favorites.helper.BookmarkRefreshHelper
import com.aogg.favorites.listener.BookmarkEditorListener
import com.aogg.favorites.listener.BookmarkProjectListener
import com.aogg.favorites.model.BookmarkManager
import com.aogg.favorites.model.SharedBookmarkManager
import com.aogg.favorites.model.SharedBookmarkStore
import com.aogg.favorites.model.SharedFavoritesListener
import com.aogg.favorites.nativebookmarks.FavoritesFirstRunGuide
import com.aogg.favorites.nativebookmarks.FavoritesGutterGestureListener
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
        // 初始化收藏管理器（项目作用域 + 共享作用域，共享仓库为应用级只加载一次）
        BookmarkManager.getInstance(project)
        SharedBookmarkManager.getInstance(project)

        // 注册监听器（阶段七：监听器和优化）
        val connection = project.messageBus.connect(project)
        connection.subscribe(VirtualFileManager.VFS_CHANGES, BookmarkProjectListener(project))
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, BookmarkEditorListener(project))

        // 1.7.0 共享收藏跨项目实时可见：任何窗口写共享收藏并广播后，
        // 本项目收到变化即刷新树与 gutter（connection 随项目 dispose 自动退订）
        connection.subscribe(
            SharedBookmarkStore.SHARED_FAVORITES_TOPIC,
            object : SharedFavoritesListener {
                override fun onSharedChanged() {
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed) return@invokeLater
                        BookmarkRefreshHelper.refreshAfterChange(project)
                    }
                }
            }
        )

        // 阶段 B：隐藏原生书签入口的工具窗口部分（动作接管由 ActionConfigurationCustomizer 完成）
        // 尽力而为：隐藏不掉只记日志，不影响插件其它功能
        if (FavoritesSettings.getInstance().state.hideNativeBookmarks) {
            NativeBookmarksToolWindowHider.hide(project)
        }
        // 兜底监听始终注册：内部自行判断开关状态（被显示就再藏 / 开关关闭时不作为）
        NativeBookmarksToolWindowHider.registerListener(project)

        // 1.4.x：gutter 手势接管（Ctrl/⌘+点击 行号区/图标区 → 当前项目匿名收藏）
        // 无条件注册：手势与快捷键一样始终由本插件接管，不受"隐藏原生书签界面"开关影响
        FavoritesGutterGestureListener.register(project)

        // 阶段 B：首次引导（!importedNative && !dontAskAgain 且检测到原生书签数>0 时弹 BALLOON）
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            FavoritesFirstRunGuide.maybeNotify(project)
        }
    }
}
