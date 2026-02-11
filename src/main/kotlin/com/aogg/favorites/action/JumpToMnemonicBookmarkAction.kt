package com.aogg.favorites.action

import com.aogg.favorites.model.BookmarkManager
import com.aogg.favorites.model.BookmarkType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem

/**
 * 跳转到助记符收藏的动作
 * 支持 36 个快捷键（0-9, A-Z）
 * 从 Action ID 中提取助记符（格式：Favorites.JumpToMnemonic.[0-9/A-Z]）
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
        
        // 获取助记符对应的收藏
        val bookmark = bookmarkManager.getBookmarkByMnemonic(mnemonic) ?: return
        
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
        
        val bookmarkManager = BookmarkManager.getInstance(project)
        val bookmark = bookmarkManager.getBookmarkByMnemonic(mnemonic)
        e.presentation.isEnabled = bookmark != null
        
        // 更新显示文本
        e.presentation.text = "跳转到收藏 [$mnemonic]"
    }

    /**
     * 导航到收藏位置
     */
    private fun navigateToBookmark(project: Project, bookmark: com.aogg.favorites.model.Bookmark) {
        // 获取项目根目录
        val projectBaseDir = project.baseDir ?: return
        
        // 构建完整文件路径
        val filePath = if (java.io.File(bookmark.filePath).isAbsolute) {
            bookmark.filePath
        } else {
            java.io.File(projectBaseDir.path, bookmark.filePath).absolutePath
        }
        
        // 查找虚拟文件
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return
        
        if (!virtualFile.exists()) {
            return
        }
        
        // 打开文件
        val fileEditorManager = FileEditorManager.getInstance(project)
        fileEditorManager.openFile(virtualFile, true)
        
        // 如果是代码行收藏，跳转到指定行
        if (bookmark.type == BookmarkType.LINE && bookmark.lineNumber >= 0) {
            // 等待文件打开
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
    }
}
