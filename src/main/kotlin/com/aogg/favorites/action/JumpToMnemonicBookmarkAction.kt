package com.aogg.favorites.action

import com.aogg.favorites.helper.BookmarkFileResolver
import com.aogg.favorites.helper.BookmarkOpenNotifier
import com.aogg.favorites.helper.BookmarkPathMacros
import com.aogg.favorites.model.BookmarkManager
import com.aogg.favorites.model.BookmarkType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project

/**
 * 跳转到助记符收藏的动作
 * 支持 36 个快捷键（0-9, A-Z）
 * 从 Action ID 中提取助记符（格式：Favorites.JumpToMnemonic.[0-9/A-Z]）
 *
 * 1.2.0 双作用域：先查项目作用域，查不到再查全局作用域。
 * 1.7.0 三作用域：跳转链改为 项目 → 共享 → 全局。
 */
class JumpToMnemonicBookmarkAction : AnAction() {

    /**
     * 从 Action ID 中提取助记符
     */
    private fun getMnemonic(e: AnActionEvent): Char? {
        val actionId = ActionManager.getInstance().getId(this) ?: return null
        // Action ID 格式：Favorites.JumpToMnemonic.[0-9/A-Z]
        val mnemonicStr = actionId.substringAfterLast(".")
        if (mnemonicStr.length == 1) {
            val char = mnemonicStr[0]
            if ((char in '0'..'9') || (char in 'A'..'Z')) {
                return char
            }
        }
        return null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val mnemonic = getMnemonic(e) ?: return
        val bookmarkManager = BookmarkManager.getInstance(project)

        // 获取助记符对应的收藏：先查项目作用域，查不到再查共享作用域，最后查全局作用域
        val bookmark = bookmarkManager.getBookmarkByMnemonic(mnemonic)
            ?: com.aogg.favorites.model.SharedBookmarkManager.getInstance(project).getBookmarkByMnemonic(mnemonic)
            ?: com.aogg.favorites.model.GlobalBookmarkManager.getInstance().getBookmarkByMnemonic(mnemonic)
            ?: return

        // 导航到收藏位置
        navigateToBookmark(project, bookmark)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val mnemonic = getMnemonic(e)

        if (project == null || mnemonic == null) {
            e.presentation.isEnabled = false
            return
        }

        // 助记符在任一作用域存在即可跳转（先项目后共享再全局）
        val bookmarkManager = BookmarkManager.getInstance(project)
        val bookmark = bookmarkManager.getBookmarkByMnemonic(mnemonic)
            ?: com.aogg.favorites.model.SharedBookmarkManager.getInstance(project).getBookmarkByMnemonic(mnemonic)
            ?: com.aogg.favorites.model.GlobalBookmarkManager.getInstance().getBookmarkByMnemonic(mnemonic)
        e.presentation.isEnabled = bookmark != null

        // 更新显示文本
        e.presentation.text = "跳转到收藏 [$mnemonic]"
    }

    /**
     * 导航到收藏位置（1.7.3 起走共享解析器 [BookmarkFileResolver]：路径宏展开候选 + 逐候选查 VFS +
     * 每路径一次刷新兜底，与收藏树/快速访问弹窗同一实现；解析失败发非模态警告通知，不再静默返回）
     *
     * @param project 当前项目
     * @param bookmark 助记符收藏
     */
    private fun navigateToBookmark(project: Project, bookmark: com.aogg.favorites.model.Bookmark) {
        // ////////////////  准备数据：共享解析器解析收藏目标文件  start  //////////////////
        val virtualFile = BookmarkFileResolver.findExistingFile(bookmark.filePath, project)
        // ////////////////  准备数据  end  ////////////////////////////////////////////

        // ////////////////  校验参数：解析失败/文件不存在时明确提示（日志 + 通知）  start  ////
        if (virtualFile == null || !virtualFile.exists()) {
            notifyOpenFailed(project, bookmark)
            return
        }
        // ////////////////  校验参数  end  ////////////////////////////////////////////

        // ////////////////  核心逻辑一：打开文件  start  ////////////////////////////////
        val fileEditorManager = FileEditorManager.getInstance(project)
        fileEditorManager.openFile(virtualFile, true)
        // ////////////////  核心逻辑一  end  //////////////////////////////////////////

        // ////////////////  核心逻辑二：行收藏定位到指定行（lineNumber 为 0 基）  start  ////
        if (bookmark.type == BookmarkType.LINE && bookmark.lineNumber >= 0) {
            // 等待文件打开完成后再定位，避免选中的编辑器还是旧文件
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                val editor = fileEditorManager.selectedTextEditor ?: return@invokeLater
                val document = editor.document
                val lineIndex = bookmark.lineNumber
                if (lineIndex < document.lineCount) {
                    val lineStartOffset = document.getLineStartOffset(lineIndex)
                    editor.caretModel.moveToOffset(lineStartOffset)
                    editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)
                }
            }
        }
        // ////////////////  核心逻辑二  end  //////////////////////////////////////////
    }

    /**
     * 收藏打开失败提示（1.7.3）：日志 + 共享的非模态 WARNING 通知（候选路径超长/过多自动截断）
     *
     * @param project 当前项目
     * @param bookmark 助记符收藏
     */
    private fun notifyOpenFailed(project: Project, bookmark: com.aogg.favorites.model.Bookmark) {
        // ////////////////  核心逻辑：日志 + 非模态警告通知（不阻塞 EDT、不静默）  start  ///
        log.warn("无法打开收藏（助记符跳转）：名称=${bookmark.getDisplayName()}，路径=${bookmark.filePath}")
        BookmarkOpenNotifier.notifyOpenFailed(
            project,
            bookmark.getDisplayName(),
            bookmark.filePath,
            BookmarkPathMacros.candidates(bookmark.filePath, project.basePath)
        )
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////
    }

    companion object {
        /** 日志 */
        private val log = Logger.getInstance(JumpToMnemonicBookmarkAction::class.java)
    }
}
