package com.aogg.favorites.nativebookmarks

import com.aogg.favorites.settings.FavoritesSettings
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener

/**
 * 原生 Bookmarks 工具窗口隐藏器（尽力而为，失败仅记日志，不影响插件其它功能）
 *
 * - setAvailable(false) 隐藏入口
 * - 注册 ToolWindowManagerListener 兜底：被显示就再藏
 * - 关闭开关时恢复 available=true（对所有已打开项目立即生效）
 */
object NativeBookmarksToolWindowHider {

    private val log = Logger.getInstance(NativeBookmarksToolWindowHider::class.java)

    /**
     * 原生书签工具窗口 id
     */
    const val NATIVE_TOOL_WINDOW_ID = "Bookmarks"

    /**
     * 隐藏原生 Bookmarks 工具窗口（项目打开后调用）
     */
    @JvmStatic
    fun hide(project: Project) {
        runCatching {
            val toolWindow = ToolWindowManager.getInstance(project)
                .getToolWindow(NATIVE_TOOL_WINDOW_ID) ?: return
            toolWindow.isAvailable = false
            if (toolWindow.isVisible) {
                toolWindow.hide(null)
            }
        }.onFailure { log.info("隐藏原生 Bookmarks 工具窗口失败（忽略）", it) }
    }

    /**
     * 恢复原生 Bookmarks 工具窗口（关闭开关时调用）
     */
    @JvmStatic
    fun restore(project: Project) {
        runCatching {
            ToolWindowManager.getInstance(project)
                .getToolWindow(NATIVE_TOOL_WINDOW_ID)?.let { toolWindow ->
                    toolWindow.isAvailable = true
                }
        }.onFailure { log.info("恢复原生 Bookmarks 工具窗口失败（忽略）", it) }
    }

    /**
     * 注册兜底监听：原生工具窗口被显示时再次隐藏（监听器内部自行判断开关状态）
     */
    @JvmStatic
    fun registerListener(project: Project) {
        runCatching {
            project.messageBus.connect(project)
                .subscribe(ToolWindowManagerListener.TOPIC, RehideListener(project))
        }.onFailure { log.info("注册原生工具窗口兜底监听失败（忽略）", it) }
    }

    /**
     * 兜底监听：工具窗口显示事件中，若开关开启且显示的是原生 Bookmarks，再藏一次
     */
    private class RehideListener(private val project: Project) : ToolWindowManagerListener {

        override fun toolWindowShown(toolWindow: ToolWindow) {
            if (toolWindow.id != NATIVE_TOOL_WINDOW_ID) return
            val hideNative = runCatching {
                FavoritesSettings.getInstance().state.hideNativeBookmarks
            }.getOrDefault(true)
            if (!hideNative) return
            runCatching { toolWindow.hide(null) }
            runCatching { toolWindow.isAvailable = false }
        }
    }
}
