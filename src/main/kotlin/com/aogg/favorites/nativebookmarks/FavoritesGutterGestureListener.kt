package com.aogg.favorites.nativebookmarks

import com.aogg.favorites.action.AddAnonymousBookmarkAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities

/**
 * 编辑器 gutter 书签手势监听（接管原生「Ctrl+点击 行号区/图标区 创建书签」手势）
 *
 * SDK 结论（2023.3 ideaIC sources 只读核实）：
 * - 原生手势不走动作调用：[com.intellij.ide.bookmark.providers.LineBookmarkProvider] 是项目级
 *   EditorMouseListener（注册于 EditorFactory.eventMulticaster），在 mouseClicked 中硬编码
 *   BookmarksManager.add/remove（Mac=⌘/其它=Ctrl + 左键，且仅认 LINE_MARKERS_AREA），
 *   动作替换（replaceAction）覆盖不到；
 * - EditorImpl.MyMouseAdapter.mouseReleased 以「mousePressed 事件未被消费」为闸门才进入
 *   mouseClicked 监听链与 gutter 图标处理（invokeGutterAction/processMouseReleased 同闸门）；
 * - 因此本监听在 mousePressed 阶段消费事件即可确定性阻断原生手势（与监听注册顺序无关），
 *   同一次手势只走一条路径，不会与替换后的 ToggleBookmark 委托重复触发。
 *
 * 行为约定：
 * - Ctrl（Mac 为 ⌘）+ 单击 行号区（LINE_NUMBERS_AREA）或图标区（LINE_MARKERS_AREA）→
 *   当前项目匿名收藏切换（加入/移除，固定项目作用域，不弹作用域二选一）；
 * - 无视「隐藏原生书签界面」开关，始终生效（快捷键/手势始终由本插件接管）；
 * - 不带修饰键的点击（断点/跳转/选择）完全不受影响；
 * - 全程 runCatching + 判空，失败仅记日志，不影响其它功能。
 *
 * 注册：项目打开时 [register]（以 project 为 Disposable，项目关闭自动注销）
 */
class FavoritesGutterGestureListener(private val project: Project) : EditorMouseListener {

    /**
     * 手势识别并消费：Ctrl/⌘ + 左键单击 gutter 行号区/图标区
     */
    override fun mousePressed(event: EditorMouseEvent) {
        runCatching {
            // ////////////////  校验参数：项目归属/未消费/单击  start  //////////////////
            if (event.isConsumed) return
            val editor = event.editor
            val editorProject = editor.project
            if (editorProject != null && editorProject != project) return
            val mouseEvent = event.mouseEvent
            if (mouseEvent.clickCount != 1) return
            // ////////////////  校验参数  end  ///////////////////////////////////////

            // ////////////////  校验参数：区域与修饰键  start  /////////////////////////
            val area = event.area
            if (area != EditorMouseEventArea.LINE_NUMBERS_AREA &&
                area != EditorMouseEventArea.LINE_MARKERS_AREA
            ) return
            if (!isBookmarkGesture(mouseEvent)) return
            // ////////////////  校验参数  end  ///////////////////////////////////////

            // ////////////////  核心逻辑：先消费事件（阻断原生手势/图标点击/选择），再切换  ///
            val line = event.logicalPosition.line
            if (line < 0 || line >= editor.document.lineCount) return
            event.consume()
            AddAnonymousBookmarkAction.toggleProjectLineBookmark(project, editor, line)
            // ////////////////  核心逻辑  end  ///////////////////////////////////////
        }.onFailure { log.warn("gutter 书签手势处理失败（忽略）", it) }
    }

    /**
     * 是否为书签手势：左键 + 非弹出菜单 + Mac 用 ⌘（Meta）、其它平台用 Ctrl（与原生手势修饰键一致）
     */
    private fun isBookmarkGesture(e: MouseEvent): Boolean =
        SwingUtilities.isLeftMouseButton(e) &&
            !e.isPopupTrigger &&
            (if (SystemInfo.isMac) e.isMetaDown else e.isControlDown)

    companion object {

        private val log = Logger.getInstance(FavoritesGutterGestureListener::class.java)

        /**
         * 注册手势监听（项目打开时调用；以 project 为 Disposable，项目关闭自动注销）
         */
        @JvmStatic
        fun register(project: Project) {
            runCatching {
                EditorFactory.getInstance().eventMulticaster
                    .addEditorMouseListener(FavoritesGutterGestureListener(project), project)
            }.onFailure { log.info("注册 gutter 书签手势监听失败（忽略）", it) }
        }
    }
}
