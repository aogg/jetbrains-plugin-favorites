package com.aogg.favorites.helper

import com.aogg.favorites.model.Bookmark
import com.aogg.favorites.model.BookmarkManager
import com.aogg.favorites.model.BookmarkType
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace

/**
 * 编辑器行号区域图标显示助手
 * 使用 LineMarkerProvider 在编辑器行号区域显示收藏图标
 */
class BookmarkGutterHelper : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // 只处理非空白元素的第一行
        if (element is PsiWhiteSpace) {
            return null
        }

        val project = element.project
        val file = element.containingFile ?: return null
        val virtualFile = file.virtualFile ?: return null

        val document = file.viewProvider.document ?: return null
        val lineNumber = document.getLineNumber(element.textOffset)

        // 只处理每行的第一个元素
        val lineStartOffset = document.getLineStartOffset(lineNumber)
        if (element.textOffset != lineStartOffset) {
            return null
        }

        val bookmarkManager = BookmarkManager.getInstance(project)
        val filePath = getRelativeFilePath(project, virtualFile.path) ?: return null

        // 查找该行的收藏
        val bookmarks = bookmarkManager.getLineBookmarks(filePath, lineNumber)
            .filter { it.type == BookmarkType.LINE }

        if (bookmarks.isEmpty()) {
            return null
        }

        // 优先显示助记符收藏，如果没有则显示匿名收藏
        val mnemonicBookmark = bookmarks.firstOrNull { it.isMnemonic() }
        val bookmark = mnemonicBookmark ?: bookmarks.firstOrNull { it.isAnonymous() } ?: return null

        // 创建图标
        val icon = if (bookmark.isMnemonic() && bookmark.mnemonic != null) {
            BookmarkIconHelper.getMnemonicIcon(bookmark.mnemonic)
        } else {
            BookmarkIconHelper.ANONYMOUS_ICON
        }

        // 创建行标记信息
        return LineMarkerInfo(
            element,
            element.textRange,
            icon,
            { createTooltip(bookmark) },
            { _, _ -> navigateToBookmark(project, bookmark) },
            GutterIconRenderer.Alignment.LEFT,
            { "收藏: ${bookmark.getDisplayName()}" }
        )
    }

    /**
     * 创建工具提示文本
     */
    private fun createTooltip(bookmark: Bookmark): String {
        return buildString {
            if (bookmark.isMnemonic() && bookmark.mnemonic != null) {
                append("助记符收藏 [${bookmark.mnemonic}]")
            } else {
                append("匿名收藏")
            }
            if (bookmark.description != null && bookmark.description.isNotBlank()) {
                append(": ${bookmark.description}")
            }
            append("\n文件: ${bookmark.filePath}")
            if (bookmark.lineNumber >= 0) {
                append("\n行号: ${bookmark.lineNumber + 1}")
            }
        }
    }

    /**
     * 导航到收藏位置
     */
    @Suppress("UNUSED_PARAMETER")
    private fun navigateToBookmark(project: Project, bookmark: Bookmark) {
        // 导航逻辑将在后续实现
        // 这里先留空，后续在动作类中实现
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
}
