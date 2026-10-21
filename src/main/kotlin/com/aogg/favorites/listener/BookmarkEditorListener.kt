package com.aogg.favorites.listener

import com.aogg.favorites.helper.BookmarkRefreshHelper
import com.aogg.favorites.model.BookmarkManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * 编辑器侧收藏监听器：
 * - 文件打开/关闭时，清理该文件无效的行收藏（行号超出范围）
 * - 触发工具窗口与 gutter 刷新
 */
class BookmarkEditorListener(private val project: Project) : FileEditorManagerListener {

    private val log = Logger.getInstance(BookmarkEditorListener::class.java)

    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        cleanupInvalidLineBookmarksIfNeeded(file)
    }

    override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
        cleanupInvalidLineBookmarksIfNeeded(file)
    }

    private fun cleanupInvalidLineBookmarksIfNeeded(file: VirtualFile) {
        if (project.isDisposed) return
        val rel = toRelativePath(file.path) ?: return
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return

        val removed = BookmarkManager.getInstance(project).cleanupInvalidLineBookmarks(rel, document.lineCount)
        if (removed > 0) {
            BookmarkRefreshHelper.refreshAfterChange(project)
            log.info("已清理无效行收藏：$removed 条，文件：$rel")
        }
    }

    private fun toRelativePath(absolutePath: String): String? {
        val basePath = project.basePath ?: return null
        val normalizedBase = basePath.replace('\\', '/').trimEnd('/')
        val normalizedAbs = absolutePath.replace('\\', '/')
        if (!normalizedAbs.startsWith(normalizedBase)) return null
        return normalizedAbs.substring(normalizedBase.length).trimStart('/')
    }
}

