package com.aogg.favorites.helper

import com.aogg.favorites.model.Bookmark
import com.aogg.favorites.model.BookmarkManager
import com.aogg.favorites.model.BookmarkType
import com.aogg.favorites.model.GlobalBookmarkManager
import com.aogg.favorites.model.SharedBookmarkManager
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 编辑器行号区域图标显示助手
 * 使用 LineMarkerProvider 在编辑器行号区域显示收藏图标
 *
 * 1.2.0 双作用域：当前文件的行收藏图标同时覆盖两个作用域——
 * 项目作用域按【相对路径】匹配、全局作用域按【绝对路径】匹配；
 * 1.7.0 三作用域：共享收藏（自己+订阅集合，按【绝对路径】匹配）并入查询，
 * 优先级 项目 → 共享 → 全局；三个作用域都使用行收藏索引（O(1) 查找），保持现有性能（避免全量扫描）。
 * 1.8.0：助记符图标带字符（0-9/A-Z）、点击图标跳转到收藏行、tooltip 列出同一行的全部收藏。
 * 1.8.1：行锚点改为「该行唯一锚点」（行首叶子 / 缩进行第一个叶子，见 [BookmarkGutterAnchors]）——
 * 1.8.0 只认「元素起点 == 行首」，而 PSI 里缩进行的行首偏移上往往没有叶子（换行+缩进是同一个
 * 空白 token，起点落在上一行），导致所有缩进行永远不显示图标；同时补诊断日志（provider 是否被调用、
 * 命中哪一行），并实现 [DumbAware] 让索引期间也能显示图标。
 */
class BookmarkGutterHelper : LineMarkerProvider, DumbAware {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // ////////////////  诊断：第一次被平台调用时打一条 INFO  start  /////////////////
        // 用途：用户回传 idea.log 时按「Favorites 行收藏图标 provider 已激活」区分
        // 「provider 根本没被平台调用」与「被调用了但没命中收藏（锚点/数据问题）」
        if (activated.compareAndSet(false, true)) {
            log.info("Favorites 行收藏图标 provider 已激活（file=${element.containingFile?.name ?: "?"}）")
        }
        // ////////////////  诊断  end  ////////////////////////////////////////////////

        // ////////////////  校验参数：只处理叶子元素（平台要求），零长叶子跳过  start  ////
        if (element.firstChild != null) return null
        if (element.textLength <= 0) return null
        // ////////////////  校验参数  end  ////////////////////////////////////////////

        // ////////////////  行锚点判定：该行唯一锚点（行首叶子；行首无叶子时取缩进行第一个叶子）  start
        val file = element.containingFile ?: return null
        val virtualFile = file.virtualFile ?: return null
        val document = file.viewProvider.document ?: return null
        val elementOffset = element.textOffset
        val lineNumber = document.getLineNumber(elementOffset)
        val lineStartOffset = document.getLineStartOffset(lineNumber)
        // 前一个叶子：缩进行的行首缩进属于上一个（跨行）空白 token，据此判断「本行第一个叶子」
        val prevLeafOffset = PsiTreeUtil.prevLeaf(element)?.textOffset ?: -1
        if (!BookmarkGutterAnchors.isAnchorOfItsLine(
                isLeaf = true,
                textLength = element.textLength,
                elementOffset = elementOffset,
                lineStartOffset = lineStartOffset,
                prevLeafOffset = prevLeafOffset
            )) {
            return null
        }
        // ////////////////  行锚点判定  end  //////////////////////////////////////////

        // ////////////////  核心逻辑：查找该行的收藏（项目相对路径索引 + 共享/全局绝对路径索引）  start
        val project = element.project
        val bookmarks = findLineBookmarks(project, virtualFile.path, lineNumber)
            .filter { it.type == BookmarkType.LINE }

        if (bookmarks.isEmpty()) {
            return null
        }

        // 优先显示助记符收藏（项目作用域优先于共享/全局作用域），否则显示匿名收藏
        val mnemonicBookmark = bookmarks.firstOrNull { it.isMnemonic() }
        val bookmark = mnemonicBookmark ?: bookmarks.firstOrNull { it.isAnonymous() } ?: return null
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////

        // ////////////////  准备数据：图标（1.8.1 助记符 → 平台原生助记符 gutter 图标；匿名 → 原生书签）  start
        val icon = if (bookmark.isMnemonic() && bookmark.mnemonic != null) {
            BookmarkIconHelper.getMnemonicIcon(bookmark.mnemonic)
        } else {
            BookmarkIconHelper.ANONYMOUS_ICON
        }
        // ////////////////  准备数据  end  ////////////////////////////////////////////

        // ////////////////  诊断：每次成功生成图标打一条 INFO（命中行/助记符，用户按关键词搜索）  start
        log.info(
            "Favorites 行收藏图标：file=${virtualFile.path}, line=$lineNumber（0基）, 助记符=${bookmark.mnemonic ?: "无（匿名）"}"
        )
        // ////////////////  诊断  end  ////////////////////////////////////////////////

        // ////////////////  返回：行标记（tooltip 列出同一行全部收藏，点击跳转到图标对应收藏）  start
        val tooltipText = createTooltip(bookmarks, bookmark)
        return LineMarkerInfo(
            element,
            element.textRange,
            icon,
            { tooltipText },
            { _, _ -> navigateToBookmark(project, bookmark) },
            GutterIconRenderer.Alignment.LEFT,
            { "收藏: ${bookmark.getDisplayName()}" }
        )
        // ////////////////  返回  end  ////////////////////////////////////////////////
    }

    /**
     * 查询各作用域内某文件某行的行收藏（均走索引，避免全量扫描）
     *
     * @param project 当前项目
     * @param absolutePath 文件绝对路径（VFS 风格正斜杠）
     * @param lineNumber 0 基行号
     */
    private fun findLineBookmarks(project: Project, absolutePath: String, lineNumber: Int): List<Bookmark> {
        // ////////////////  项目作用域：相对路径匹配  start  /////////////////////////////
        val relativePath = getRelativeFilePath(project, absolutePath)
        val projectHits = relativePath?.let {
            BookmarkManager.getInstance(project).getLineBookmarks(it, lineNumber)
        } ?: emptyList()
        // ////////////////  项目作用域  end  //////////////////////////////////////////

        // ////////////////  共享作用域：绝对路径匹配（自己+订阅集合聚合）  start  //////////
        val sharedHits = SharedBookmarkManager.getInstance(project).getLineBookmarks(absolutePath, lineNumber)
        // ////////////////  共享作用域  end  //////////////////////////////////////////

        // ////////////////  全局作用域：绝对路径匹配  start  ////////////////////////////
        val globalHits = GlobalBookmarkManager.getInstance().getLineBookmarks(absolutePath, lineNumber)
        // ////////////////  全局作用域  end  //////////////////////////////////////////

        // ////////////////  返回：项目→共享→全局 依次在前（优先展示）  start  ///////////
        return projectHits + sharedHits + globalHits
        // ////////////////  返回  end  ///////////////////////////////////////////////
    }

    /**
     * 创建工具提示文本（1.8.0）
     *
     * 首行是图标对应（优先助记符）的那条收藏 + 「点击图标跳转」提示；
     * 同一行存在多条收藏（项目/共享/全局各一条）时把其余收藏也逐条列出（当前图标位置只看得到第一条），
     * 避免用户以为只收藏了一次。
     *
     * @param bookmarks 同一行的全部行收藏（顺序 项目 → 共享 → 全局）
     * @param primary 图标对应（点击即跳转）的收藏
     * @return tooltip 文本
     */
    private fun createTooltip(bookmarks: List<Bookmark>, primary: Bookmark): String = buildString {
        // ////////////////  第一段：主收藏信息 + 点击提示  start  ////////////////////////
        append(mnemonicLabel(primary))
        append("（点击图标跳转）")
        append("\n文件: ${primary.filePath}")
        if (primary.lineNumber >= 0) {
            append("\n行号: ${primary.lineNumber + 1}")
        }
        // ////////////////  第一段  end  ////////////////////////////////////////////

        // ////////////////  第二段：同一行其它收藏逐条列出（多于一条时才显示）  start  ////
        if (bookmarks.size > 1) {
            append("\n\n同一行共 ${bookmarks.size} 条收藏：")
            bookmarks.forEach { bookmark ->
                append("\n· ${mnemonicLabel(bookmark)}")
                if (bookmark === primary) append(" ← 图标")
            }
        }
        // ////////////////  第二段  end  ////////////////////////////////////////////
    }

    /**
     * 收藏的简短描述（助记符收藏带字符，附别名/分组信息）
     *
     * @param bookmark 收藏
     * @return 形如「助记符收藏 [3]: 登录入口 · 收藏」的文本
     */
    private fun mnemonicLabel(bookmark: Bookmark): String = buildString {
        if (bookmark.isMnemonic() && bookmark.mnemonic != null) {
            append("助记符收藏 [${bookmark.mnemonic}]")
        } else {
            append("匿名收藏")
        }
        if (!bookmark.description.isNullOrBlank()) {
            append(": ${bookmark.description}")
        }
        if (bookmark.listName.isNotBlank()) {
            append(" · 分组「${bookmark.listName}」")
        }
    }

    /**
     * 点击行号区图标跳转到收藏位置（1.8.0 补全，原为空实现）
     *
     * LineMarkerProvider 的回调在后台读 action 中触发，因此跳转必须回到 EDT 执行：
     * 打开文件（已打开时平台只激活已有 tab，不重复开）→ caret 移到收藏行（0 基越界保护）→
     * 居中滚动 → 编辑器聚焦；文件解析失败走与收藏树一致的「无法打开收藏」提示，不静默。
     *
     * @param project 当前项目
     * @param bookmark 图标对应的收藏（行收藏）
     */
    private fun navigateToBookmark(project: Project, bookmark: Bookmark) {
        // ////////////////  核心逻辑：后台回调 → 回到 EDT 执行跳转  start  ////////////////
        ApplicationManager.getApplication().invokeLater {
            // ////////////////  校验参数：项目已释放就不再处理  start  /////////////////////
            if (project.isDisposed) return@invokeLater
            // ////////////////  校验参数  end  ////////////////////////////////////////

            // ////////////////  准备数据：解析收藏目标文件（路径宏展开候选 + VFS 兜底，与收藏树同一解析器）  start
            val virtualFile = BookmarkFileResolver.findExistingFile(bookmark.filePath, project)
            // ////////////////  准备数据  end  ////////////////////////////////////////

            // ////////////////  逻辑后：解析失败 → 日志 + 非模态 WARNING 通知（与收藏树一致）  start
            if (virtualFile == null || !virtualFile.exists()) {
                log.warn(
                    "无法打开收藏（行号区图标点击）：名称=${bookmark.getDisplayName()}，路径=${bookmark.filePath}"
                )
                BookmarkOpenNotifier.notifyOpenFailed(
                    project,
                    bookmark.getDisplayName(),
                    bookmark.filePath,
                    BookmarkPathMacros.candidates(bookmark.filePath, project.basePath)
                )
                return@invokeLater
            }
            // ////////////////  逻辑后  end  //////////////////////////////////////////

            // ////////////////  核心逻辑一：打开文件（已打开时只激活，不重复开 tab）  start  ////
            val fileEditorManager = FileEditorManager.getInstance(project)
            fileEditorManager.openFile(virtualFile, true)
            // ////////////////  核心逻辑一  end  //////////////////////////////////////

            // ////////////////  核心逻辑二：行收藏定位（越界保护 + 居中滚动 + 聚焦）  start  ////
            if (bookmark.type != BookmarkType.LINE || bookmark.lineNumber < 0) return@invokeLater
            val editor = findEditor(fileEditorManager, virtualFile) ?: return@invokeLater
            val document = editor.document
            val lineIndex = bookmark.lineNumber
            if (lineIndex >= document.lineCount) return@invokeLater

            editor.caretModel.moveToOffset(document.getLineStartOffset(lineIndex))
            editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
            editor.contentComponent.requestFocus()
            // ////////////////  核心逻辑二  end  //////////////////////////////////////
        }
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////
    }

    /**
     * 取该文件已打开的第一个文本编辑器
     *
     * 优先按文件精确匹配（避免用 selectedTextEditor 定位到别的文件），取不到时退回当前选中编辑器。
     *
     * @param fileEditorManager 文件编辑器管理器
     * @param virtualFile 目标文件
     * @return 文本编辑器（没有可用编辑器时返回 null）
     */
    private fun findEditor(fileEditorManager: FileEditorManager, virtualFile: VirtualFile): Editor? {
        // ////////////////  核心逻辑：按文件精确匹配所有已打开编辑器  start  //////////////
        for (fileEditor in fileEditorManager.allEditors) {
            if (fileEditor is TextEditor && fileEditor.file == virtualFile) {
                return fileEditor.editor
            }
        }
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////

        // ////////////////  兜底：退回当前选中的文本编辑器  start  //////////////////////
        return fileEditorManager.selectedTextEditor
        // ////////////////  兜底  end  //////////////////////////////////////////////
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

    companion object {
        /** 日志 */
        private val log = Logger.getInstance(BookmarkGutterHelper::class.java)

        /**
         * 是否已经打过「provider 已激活」诊断日志（1.8.1）
         *
         * 只打一次：daemon 每轮都会调用 provider，重复打日志会刷屏；
         * 但这次必须能证明「平台确实调用了 provider」——没有该日志即说明 provider 未被注册/未被启用。
         */
        private val activated = AtomicBoolean(false)
    }
}
