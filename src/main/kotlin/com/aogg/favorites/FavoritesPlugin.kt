package com.aogg.favorites

import com.aogg.favorites.model.BookmarkManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity

/**
 * 收藏插件主类
 * 负责插件初始化和生命周期管理
 */
class FavoritesPlugin : StartupActivity {
    override fun runActivity(project: Project) {
        // 初始化收藏管理器
        BookmarkManager.getInstance(project)
    }
}
