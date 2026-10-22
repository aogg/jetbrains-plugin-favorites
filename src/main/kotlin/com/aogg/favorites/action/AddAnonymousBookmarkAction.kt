package com.aogg.favorites.action

import com.aogg.favorites.helper.BookmarkRefreshHelper
import com.aogg.favorites.model.Bookmark
import com.aogg.favorites.model.BookmarkType
import com.aogg.favorites.model.FavoriteScope
import com.aogg.favorites.model.FavoritesScopeResolver
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
 *
 * 1.2.0 双作用域语义：
 * - 若目标位置在任一作用域已存在收藏 → 维持原有切换语义：直接移除并通知（不弹选择）；
 * - 否则弹出「项目收藏/全局收藏」二选一（默认高亮上次使用的作用域，选择后记住），
 *   加入 GLOBAL 时写入绝对路径；项目外位置只能加入 GLOBAL；
 * - 项目视图多选批量：只弹一次选择，全部加入同一作用域。
 */
class AddAnonymousBookmarkAction : AnAction("添加匿名收藏", "添加匿名收藏", null) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        // 尝试从编辑器获取位置
        val editor = e.getData(CommonDataKeys.EDITOR)
        if (editor != null) {
            handleEditorContext(project, editor)
            return
        }

        // 尝试从项目窗口获取文件/文件夹
        val virtualFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        if (virtualFiles != null && virtualFiles.isNotEmpty()) {
            handleProjectContext(project, virtualFiles)
            return
        }

        // 尝试从标签页右键菜单获取文件（EditorTabPopupMenu）
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        if (virtualFile != null) {
            handleFileContext(project, virtualFile, editor)
            return
        }

        // 尝试从当前打开的文件获取
        val fileEditorManager = FileEditorManager.getInstance(project)
        val selectedFiles = fileEditorManager.selectedFiles
        if (selectedFiles.isNotEmpty()) {
            handleFileContext(project, selectedFiles[0], editor)
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
    private fun handleEditorContext(project: Project, editor: Editor) {
        val document = editor.document
        val currentOffset = editor.caretModel.offset
        val lineNumber = document.getLineNumber(currentOffset)

        val virtualFile = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
            .getFile(document) ?: return

        // 构造行收藏目标位置（项目外文件 relativePath 为 null，只能加入全局收藏）
        val location = AddActionSupport.locationOf(project, virtualFile, lineNumber)
        processLocations(project, listOf(location.copy(type = BookmarkType.LINE)), lineMode = true)
    }

    /**
     * 处理项目窗口上下文（文件/文件夹收藏，支持多选批量）
     */
    private fun handleProjectContext(project: Project, virtualFiles: Array<VirtualFile>) {
        val locations = virtualFiles.map { AddActionSupport.locationOf(project, it, -1) }
        processLocations(project, locations, lineMode = false)
    }

    /**
     * 处理文件上下文（标签页收藏）
     */
    private fun handleFileContext(project: Project, virtualFile: VirtualFile, editor: Editor?) {
        if (editor != null) {
            // 如果有编辑器，收藏当前行
            handleEditorContext(project, editor)
        } else {
            // 否则收藏文件
            processLocations(project, listOf(AddActionSupport.locationOf(project, virtualFile, -1)), lineMode = false)
        }
    }

    /**
     * 统一处理目标位置集合：先按切换语义拆分"已存在→移除"与"待新增"，再决定是否弹作用域选择
     *
     * @param lineMode true=编辑器行上下文（切换检测不限匿名/助记符）；false=文件/文件夹上下文（仅检测匿名收藏）
     */
    private fun processLocations(project: Project, locations: List<AddActionSupport.PendingLocation>, lineMode: Boolean) {
        // ////////////////  校验参数  start  //////////////////////////////////////////
        if (locations.isEmpty()) return
        // 历史行为：行上下文移除该行全部收藏；文件/文件夹上下文仅切换匿名收藏
        val extra: (Bookmark) -> Boolean = if (lineMode) {
            { _: Bookmark -> true }
        } else {
            { it.isAnonymous() }
        }
        // ////////////////  校验参数  end  ////////////////////////////////////////////

        // ////////////////  准备数据：拆分 已存在(切换移除) / 待新增  start  //////////////
        val toRemove = locations.flatMap { AddActionSupport.findExisting(project, it, extra) }
        val candidates = locations.filter { AddActionSupport.findExisting(project, it, extra).isEmpty() }
        // ////////////////  准备数据  end  ////////////////////////////////////////////

        // ////////////////  核心逻辑：全部已存在 → 直接切换移除（不弹选择）  start  ////////
        if (candidates.isEmpty()) {
            toRemove.forEach { removeBookmark(project, it) }
            if (toRemove.isNotEmpty()) {
                Messages.showInfoMessage(project, removalMessage(lineMode), "移除收藏")
                BookmarkRefreshHelper.refreshAfterChange(project)
            }
            return
        }
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////

        // ////////////////  核心逻辑：有待新增 → 弹作用域二选一（批量只弹一次）  start /////
        AddScopeChooser.chooseScope(project) { scope ->
            performAdd(project, scope, candidates, toRemove, lineMode)
        }
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////
    }

    /**
     * 将待新增位置加入所选作用域，并移除切换命中的已有收藏
     */
    private fun performAdd(
        project: Project,
        scope: FavoriteScope,
        candidates: List<AddActionSupport.PendingLocation>,
        toRemove: List<com.aogg.favorites.ui.ScopedBookmark>,
        lineMode: Boolean
    ) {
        // ////////////////  核心逻辑：先移除切换命中的收藏（两个作用域）  start  //////////
        toRemove.forEach { removeBookmark(project, it) }
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////

        // ////////////////  核心逻辑：按作用域写入收藏（GLOBAL=绝对路径）  start  /////////
        val manager = FavoritesScopeResolver.managerFor(project, scope)
        var successCount = 0
        var skipped = 0
        for (location in candidates) {
            val storagePath = AddActionSupport.storagePathFor(scope, location)
            if (storagePath == null) {
                // 项目外位置无法加入项目收藏（全局收藏可存绝对路径）
                skipped++
                continue
            }
            val bookmark = Bookmark(
                id = UUID.randomUUID().toString(),
                type = location.type,
                filePath = storagePath,
                lineNumber = location.lineNumber,
                mnemonic = null,
                description = null
            )
            if (manager.addBookmark(bookmark)) {
                successCount++
            }
        }
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////

        // ////////////////  逻辑后：结果通知与刷新  start  //////////////////////////////
        val removeCount = toRemove.size
        val scopeText = scope.label
        val message = when {
            successCount > 0 && removeCount > 0 -> "已添加 $successCount 个收藏到「$scopeText」，移除 $removeCount 个收藏"
            successCount > 0 && candidates.size == 1 && lineMode ->
                "已添加匿名收藏（行 ${candidates.first().lineNumber + 1}）到「$scopeText」"
            successCount > 0 -> "已添加 $successCount 个匿名收藏到「$scopeText」"
            else -> removalMessage(lineMode)
        }.let { base ->
            if (skipped > 0) "$base\n（跳过 $skipped 个项目外位置：项目收藏仅支持项目内路径）" else base
        }
        Messages.showInfoMessage(project, message, if (successCount > 0) "添加收藏成功" else "收藏操作")
        if (successCount > 0 || removeCount > 0) {
            BookmarkRefreshHelper.refreshAfterChange(project)
        }
        // ////////////////  逻辑后  end  //////////////////////////////////////////////
    }

    /**
     * 按作用域移除收藏
     */
    private fun removeBookmark(project: Project, item: com.aogg.favorites.ui.ScopedBookmark) {
        FavoritesScopeResolver.managerFor(project, item.scope).removeBookmark(item.bookmark.id)
    }

    /**
     * 切换移除的提示文案（沿用历史措辞）
     */
    private fun removalMessage(lineMode: Boolean): String =
        if (lineMode) "已移除该行的收藏" else "已移除收藏"

    /**
     * 按上下文启用：编辑器→行收藏；项目视图/标签页→文件/文件夹收藏。
     * 无有效上下文时隐藏，避免灰色无效项常驻。
     */
    override fun update(e: AnActionEvent) {
        val project = e.project
        val hasContext = project != null &&
            (e.getData(CommonDataKeys.EDITOR) != null ||
                !e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY).isNullOrEmpty())
        e.presentation.isEnabledAndVisible = hasContext
    }
}
