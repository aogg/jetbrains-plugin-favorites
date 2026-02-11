package com.aogg.favorites.action

import com.aogg.favorites.model.Bookmark
import com.aogg.favorites.model.BookmarkManager
import com.aogg.favorites.model.BookmarkType
import com.aogg.favorites.ui.BookmarksToolWindowPanel
import com.aogg.favorites.ui.MnemonicSelectorDialog
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
 * 添加助记符收藏动作
 * 弹出助记符选择对话框，处理标识符冲突
 */
class AddMnemonicBookmarkAction : AnAction("添加助记符收藏", "添加助记符收藏", null) {

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

        // 检查是否已存在该行的助记符收藏
        val existingBookmark = bookmarkManager.getAllBookmarks()
            .firstOrNull { it.type == BookmarkType.LINE && it.filePath == filePath && it.lineNumber == lineNumber && it.isMnemonic() }

        // 显示助记符选择对话框
        val dialog = MnemonicSelectorDialog(project, existingBookmark?.mnemonic)
        if (dialog.showAndGet()) {
            val selectedMnemonic = dialog.selectedMnemonic ?: return

            // 如果已存在该助记符的收藏，先移除
            val existingMnemonicBookmark = bookmarkManager.getBookmarkByMnemonic(selectedMnemonic)
            if (existingMnemonicBookmark != null) {
                bookmarkManager.removeBookmark(existingMnemonicBookmark.id)
            }

            // 如果当前行已有助记符收藏，先移除
            if (existingBookmark != null) {
                bookmarkManager.removeBookmark(existingBookmark.id)
            }

            // 创建新的助记符收藏
            val bookmark = Bookmark(
                id = UUID.randomUUID().toString(),
                type = BookmarkType.LINE,
                filePath = filePath,
                lineNumber = lineNumber,
                mnemonic = selectedMnemonic,
                description = dialog.description
            )

            if (bookmarkManager.addBookmark(bookmark)) {
                val mnemonicText = "[$selectedMnemonic]"
                val descText = if (dialog.description != null) ": ${dialog.description}" else ""
                Messages.showInfoMessage(
                    project,
                    "已添加助记符收藏 $mnemonicText$descText（行 ${lineNumber + 1}）",
                    "添加收藏成功"
                )
                BookmarksToolWindowPanel.refreshToolWindow(project)
            } else {
                Messages.showErrorDialog(
                    project,
                    "添加收藏失败：助记符冲突",
                    "添加收藏失败"
                )
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
        // 助记符收藏每个标识符只能使用一次，所以只处理第一个文件/文件夹
        // 如果选择了多个，提示用户
        if (virtualFiles.size > 1) {
            Messages.showInfoMessage(
                project,
                "助记符收藏每个标识符只能使用一次，已为第一个选中的项目添加收藏。如需为多个项目添加收藏，请分别操作。",
                "批量选择提示"
            )
        }

        val virtualFile = virtualFiles[0]
        val filePath = getRelativeFilePath(project, virtualFile.path) ?: return

        val bookmarkType = if (virtualFile.isDirectory) {
            BookmarkType.FOLDER
        } else {
            BookmarkType.FILE
        }

        // 检查是否已存在该文件/文件夹的助记符收藏
        val existingBookmark = bookmarkManager.getAllBookmarks()
            .firstOrNull { it.type == bookmarkType && it.filePath == filePath && it.isMnemonic() }

        // 显示助记符选择对话框
        val dialog = MnemonicSelectorDialog(project, existingBookmark?.mnemonic)
        if (dialog.showAndGet()) {
            val selectedMnemonic = dialog.selectedMnemonic ?: return

            // 如果已存在该助记符的收藏，先移除
            val existingMnemonicBookmark = bookmarkManager.getBookmarkByMnemonic(selectedMnemonic)
            if (existingMnemonicBookmark != null) {
                bookmarkManager.removeBookmark(existingMnemonicBookmark.id)
            }

            // 如果当前文件/文件夹已有助记符收藏，先移除
            if (existingBookmark != null) {
                bookmarkManager.removeBookmark(existingBookmark.id)
            }

            // 创建新的助记符收藏
            val bookmark = Bookmark(
                id = UUID.randomUUID().toString(),
                type = bookmarkType,
                filePath = filePath,
                lineNumber = -1,
                mnemonic = selectedMnemonic,
                description = dialog.description
            )

            if (bookmarkManager.addBookmark(bookmark)) {
                val mnemonicText = "[$selectedMnemonic]"
                val descText = if (dialog.description != null) ": ${dialog.description}" else ""
                val typeText = if (bookmarkType == BookmarkType.FILE) "文件" else "文件夹"
                Messages.showInfoMessage(
                    project,
                    "已添加助记符收藏 $mnemonicText$descText（$typeText）",
                    "添加收藏成功"
                )
                BookmarksToolWindowPanel.refreshToolWindow(project)
            } else {
                Messages.showErrorDialog(
                    project,
                    "添加收藏失败：助记符冲突",
                    "添加收藏失败"
                )
            }
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
        if (editor != null) {
            // 如果有编辑器，收藏当前行
            handleEditorContext(project, bookmarkManager, editor)
        } else {
            // 否则收藏文件
            val filePath = getRelativeFilePath(project, virtualFile.path) ?: return

            val bookmarkType = if (virtualFile.isDirectory) {
                BookmarkType.FOLDER
            } else {
                BookmarkType.FILE
            }

            val existingBookmark = bookmarkManager.getAllBookmarks()
                .firstOrNull { it.type == bookmarkType && it.filePath == filePath && it.isMnemonic() }

            val dialog = MnemonicSelectorDialog(project, existingBookmark?.mnemonic)
            if (dialog.showAndGet()) {
                val selectedMnemonic = dialog.selectedMnemonic ?: return

                val existingMnemonicBookmark = bookmarkManager.getBookmarkByMnemonic(selectedMnemonic)
                if (existingMnemonicBookmark != null) {
                    bookmarkManager.removeBookmark(existingMnemonicBookmark.id)
                }

                if (existingBookmark != null) {
                    bookmarkManager.removeBookmark(existingBookmark.id)
                }

                val bookmark = Bookmark(
                    id = UUID.randomUUID().toString(),
                    type = bookmarkType,
                    filePath = filePath,
                    lineNumber = -1,
                    mnemonic = selectedMnemonic,
                    description = dialog.description
                )

                if (bookmarkManager.addBookmark(bookmark)) {
                    val mnemonicText = "[$selectedMnemonic]"
                    val descText = if (dialog.description != null) ": ${dialog.description}" else ""
                    Messages.showInfoMessage(
                        project,
                        "已添加助记符收藏 $mnemonicText$descText",
                        "添加收藏成功"
                    )
                    BookmarksToolWindowPanel.refreshToolWindow(project)
                } else {
                    Messages.showErrorDialog(
                        project,
                        "添加收藏失败：助记符冲突",
                        "添加收藏失败"
                    )
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
