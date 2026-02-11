package com.aogg.favorites.action

import com.aogg.favorites.model.Bookmark
import com.aogg.favorites.model.BookmarkManager
import com.aogg.favorites.model.BookmarkType
import com.aogg.favorites.ui.BookmarksToolWindowPanel
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import java.util.*

/**
 * 添加匿名收藏动作
 * 支持编辑器、项目窗口、标签页上下文
 */
class AddAnonymousBookmarkAction : AnAction("添加匿名收藏", "添加匿名收藏", null) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val bookmarkManager = BookmarkManager.getInstance(project)

        // 尝试从编辑器获取位置
        val editor = e.getData(CommonDataKeys.EDITOR)
        if (editor != null) {
            handleEditorContext(project, bookmarkManager, editor)
            return
        }

        // 尝试从项目窗口获取文件/文件夹
        val virtualFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        if (virtualFiles != null && virtualFiles.isNotEmpty()) {
            handleProjectContext(project, bookmarkManager, virtualFiles)
            return
        }

        // 尝试从标签页右键菜单获取文件（EditorTabPopupMenu）
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        if (virtualFile != null) {
            handleFileContext(project, bookmarkManager, virtualFile, editor)
            return
        }

        // 尝试从当前打开的文件获取
        val fileEditorManager = FileEditorManager.getInstance(project)
        val selectedFiles = fileEditorManager.selectedFiles
        if (selectedFiles.isNotEmpty()) {
            handleFileContext(project, bookmarkManager, selectedFiles[0], editor)
            return
        }

        Messages.showWarningDialog(
            project,
            "无法确定要收藏的位置。请在编辑器中选择一行，或在项目窗口中选择文件/文件夹。",
            "添加收藏失败"
        )
    }

    /**
     * 处理编辑器上下文（代码行收藏）
     */
    private fun handleEditorContext(
        project: Project,
        bookmarkManager: BookmarkManager,
        editor: Editor
    ) {
        val document = editor.document
        val caretModel = editor.caretModel
        val currentOffset = caretModel.offset
        val lineNumber = document.getLineNumber(currentOffset)

        val virtualFile = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
            .getFile(document) ?: return

        val filePath = getRelativeFilePath(project, virtualFile.path) ?: return

        // 检查是否已存在该行的收藏
        val existingBookmarks = bookmarkManager.getAllBookmarks()
            .filter { it.type == BookmarkType.LINE && it.filePath == filePath && it.lineNumber == lineNumber }

        if (existingBookmarks.isNotEmpty()) {
            // 如果已存在，则移除
            existingBookmarks.forEach { bookmarkManager.removeBookmark(it.id) }
            Messages.showInfoMessage(
                project,
                "已移除该行的匿名收藏",
                "移除收藏"
            )
            BookmarksToolWindowPanel.refreshToolWindow(project)
        } else {
            // 创建新的匿名收藏
            val bookmark = Bookmark(
                id = UUID.randomUUID().toString(),
                type = BookmarkType.LINE,
                filePath = filePath,
                lineNumber = lineNumber,
                mnemonic = null,
                description = null
            )

            if (bookmarkManager.addBookmark(bookmark)) {
                Messages.showInfoMessage(
                    project,
                    "已添加匿名收藏（行 ${lineNumber + 1}）",
                    "添加收藏成功"
                )
                BookmarksToolWindowPanel.refreshToolWindow(project)
            }
        }
    }

    /**
     * 处理项目窗口上下文（文件/文件夹收藏）
     */
    private fun handleProjectContext(
        project: Project,
        bookmarkManager: BookmarkManager,
        virtualFiles: Array<VirtualFile>
    ) {
        var successCount = 0
        var removeCount = 0

        for (virtualFile in virtualFiles) {
            val filePath = getRelativeFilePath(project, virtualFile.path) ?: continue

            val bookmarkType = if (virtualFile.isDirectory) {
                BookmarkType.FOLDER
            } else {
                BookmarkType.FILE
            }

            // 检查是否已存在收藏
            val existingBookmarks = bookmarkManager.getAllBookmarks()
                .filter { it.type == bookmarkType && it.filePath == filePath && it.isAnonymous() }

            if (existingBookmarks.isNotEmpty()) {
                // 移除现有收藏
                existingBookmarks.forEach { bookmarkManager.removeBookmark(it.id) }
                removeCount++
            } else {
                // 创建新收藏
                val bookmark = Bookmark(
                    id = UUID.randomUUID().toString(),
                    type = bookmarkType,
                    filePath = filePath,
                    lineNumber = -1,
                    mnemonic = null,
                    description = null
                )

                if (bookmarkManager.addBookmark(bookmark)) {
                    successCount++
                }
            }
        }

        val message = when {
            successCount > 0 && removeCount > 0 -> "已添加 $successCount 个收藏，移除 $removeCount 个收藏"
            successCount > 0 -> "已添加 $successCount 个匿名收藏"
            removeCount > 0 -> "已移除 $removeCount 个收藏"
            else -> "操作完成"
        }

        Messages.showInfoMessage(project, message, "收藏操作")
        if (successCount > 0 || removeCount > 0) {
            BookmarksToolWindowPanel.refreshToolWindow(project)
        }
    }

    /**
     * 处理文件上下文（标签页收藏）
     */
    private fun handleFileContext(
        project: Project,
        bookmarkManager: BookmarkManager,
        virtualFile: VirtualFile,
        editor: Editor?
    ) {
        val filePath = getRelativeFilePath(project, virtualFile.path) ?: return

        if (editor != null) {
            // 如果有编辑器，收藏当前行
            handleEditorContext(project, bookmarkManager, editor)
        } else {
            // 否则收藏文件
            val bookmarkType = if (virtualFile.isDirectory) {
                BookmarkType.FOLDER
            } else {
                BookmarkType.FILE
            }

            val existingBookmarks = bookmarkManager.getAllBookmarks()
                .filter { it.type == bookmarkType && it.filePath == filePath && it.isAnonymous() }

            if (existingBookmarks.isNotEmpty()) {
                existingBookmarks.forEach { bookmarkManager.removeBookmark(it.id) }
                Messages.showInfoMessage(project, "已移除收藏", "移除收藏")
                BookmarksToolWindowPanel.refreshToolWindow(project)
            } else {
                val bookmark = Bookmark(
                    id = UUID.randomUUID().toString(),
                    type = bookmarkType,
                    filePath = filePath,
                    lineNumber = -1,
                    mnemonic = null,
                    description = null
                )

                if (bookmarkManager.addBookmark(bookmark)) {
                    Messages.showInfoMessage(project, "已添加匿名收藏", "添加收藏成功")
                    BookmarksToolWindowPanel.refreshToolWindow(project)
                }
            }
        }
    }

    /**
     * 获取相对文件路径
     */
    private fun getRelativeFilePath(project: Project, absolutePath: String): String? {
        val basePath = project.basePath ?: return null
        return if (absolutePath.startsWith(basePath)) {
            absolutePath.substring(basePath.length).replace('\\', '/').trimStart('/')
        } else {
            null
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null
    }
}
