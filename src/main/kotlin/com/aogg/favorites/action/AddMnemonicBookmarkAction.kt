package com.aogg.favorites.action

import com.aogg.favorites.helper.BookmarkRefreshHelper
import com.aogg.favorites.model.Bookmark
import com.aogg.favorites.model.BookmarkType
import com.aogg.favorites.model.FavoriteScope
import com.aogg.favorites.model.FavoritesScopeResolver
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
 *
 * 1.2.0 双作用域语义：
 * - 先弹「项目收藏/全局收藏」二选一（默认高亮上次使用的作用域，选择后记住）；
 * - 再打开 MnemonicSelectorDialog（标题/提示带作用域名，占用检测按该作用域）；
 * - 同一位置的旧助记符收藏（两个作用域都查）在新增时被替换，维持"重新设置助记符"语义；
 * - 加入 GLOBAL 时写入绝对路径；项目外位置只能加入 GLOBAL。
 *
 * 1.4.x 原生书签快捷键接管：实现 [ScopedAddAction]，
 * 固定作用域入口（如原生 ToggleBookmarkWithMnemonic 委托）不弹作用域选择，
 * 直接按指定作用域（当前项目）打开助记符对话框；右键菜单等原入口行为不变。
 */
class AddMnemonicBookmarkAction : AnAction("添加助记符收藏", "添加助记符收藏", null), ScopedAddAction {

    override fun actionPerformed(e: AnActionEvent) {
        handleAction(e, fixedScope = null)
    }

    /**
     * 固定作用域执行入口（原生动作接管路径专用）：不弹作用域二选一，直接按 [scope] 打开助记符对话框
     */
    override fun performWithFixedScope(e: AnActionEvent, scope: FavoriteScope) {
        handleAction(e, fixedScope = scope)
    }

    /**
     * 统一入口：按上下文收集目标位置后处理（fixedScope 为空时保持原作用域选择行为）
     */
    private fun handleAction(e: AnActionEvent, fixedScope: FavoriteScope?) {
        val project = e.project ?: return

        // 尝试从编辑器获取位置
        val editor = e.getData(CommonDataKeys.EDITOR)
        if (editor != null) {
            handleEditorContext(project, editor, fixedScope)
            return
        }

        // 尝试从项目窗口获取文件/文件夹
        val virtualFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        if (virtualFiles != null && virtualFiles.isNotEmpty()) {
            handleProjectContext(project, virtualFiles, fixedScope)
            return
        }

        // 尝试从标签页右键菜单获取文件（EditorTabPopupMenu）
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        if (virtualFile != null) {
            handleFileContext(project, virtualFile, editor, fixedScope)
            return
        }

        // 尝试从当前打开的文件获取
        val fileEditorManager = FileEditorManager.getInstance(project)
        val selectedFiles = fileEditorManager.selectedFiles
        if (selectedFiles.isNotEmpty()) {
            handleFileContext(project, selectedFiles[0], editor, fixedScope)
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
    private fun handleEditorContext(project: Project, editor: Editor, fixedScope: FavoriteScope?) {
        val document = editor.document
        val currentOffset = editor.caretModel.offset
        val lineNumber = document.getLineNumber(currentOffset)

        val virtualFile = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
            .getFile(document) ?: return

        // 行收藏位置（type 显式覆盖为 LINE）
        val location = AddActionSupport.locationOf(project, virtualFile, lineNumber)
            .copy(type = BookmarkType.LINE)
        processLocation(project, location, fixedScope)
    }

    /**
     * 处理项目窗口上下文（文件/文件夹收藏）
     */
    private fun handleProjectContext(project: Project, virtualFiles: Array<VirtualFile>, fixedScope: FavoriteScope?) {
        // 助记符收藏每个标识符只能使用一次，所以只处理第一个文件/文件夹
        // 如果选择了多个，提示用户
        if (virtualFiles.size > 1) {
            Messages.showInfoMessage(
                project,
                "助记符收藏每个标识符只能使用一次，已为第一个选中的项目添加收藏。如需为多个项目添加收藏，请分别操作。",
                "批量选择提示"
            )
        }

        processLocation(project, AddActionSupport.locationOf(project, virtualFiles[0], -1), fixedScope)
    }

    /**
     * 处理文件上下文（标签页收藏）
     */
    private fun handleFileContext(project: Project, virtualFile: VirtualFile, editor: Editor?, fixedScope: FavoriteScope?) {
        if (editor != null) {
            // 如果有编辑器，收藏当前行
            handleEditorContext(project, editor, fixedScope)
        } else {
            // 否则收藏文件
            processLocation(project, AddActionSupport.locationOf(project, virtualFile, -1), fixedScope)
        }
    }

    /**
     * 统一处理单个目标位置：选作用域 → 助记符对话框 → 替换写入
     *
     * @param fixedScope 非空时跳过作用域选择，直接按该作用域打开助记符对话框；null 时保持原行为
     */
    private fun processLocation(
        project: Project,
        location: AddActionSupport.PendingLocation,
        fixedScope: FavoriteScope? = null
    ) {
        // ////////////////  准备数据：该位置已有助记符收藏（两作用域，用于预填）  start  ///
        val existingAtPosition = AddActionSupport.findExisting(project, location) { it.isMnemonic() }
        val prefillMnemonic = existingAtPosition.firstOrNull()?.bookmark?.mnemonic
        // ////////////////  准备数据  end  ////////////////////////////////////////////

        // ////////////////  核心逻辑：先定作用域（固定或选择），再开助记符对话框  start  ///
        if (fixedScope != null) {
            openMnemonicDialogAndAdd(project, fixedScope, location, prefillMnemonic, existingAtPosition)
        } else {
            AddScopeChooser.chooseScope(project) { scope ->
                openMnemonicDialogAndAdd(project, scope, location, prefillMnemonic, existingAtPosition)
            }
        }
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////
    }

    /**
     * 打开助记符对话框并在确认后写入（作用域由调用方决定：固定作用域或弹窗选择）
     */
    private fun openMnemonicDialogAndAdd(
        project: Project,
        scope: FavoriteScope,
        location: AddActionSupport.PendingLocation,
        prefillMnemonic: Char?,
        existingAtPosition: List<com.aogg.favorites.ui.ScopedBookmark>
    ) {
        val dialog = MnemonicSelectorDialog(
            project,
            scope = scope,
            currentMnemonic = prefillMnemonic
        )
        if (!dialog.showAndGet()) return
        val selectedMnemonic = dialog.selectedMnemonic ?: return
        performAdd(project, scope, location, selectedMnemonic, dialog.description, existingAtPosition)
    }

    /**
     * 按所选作用域写入助记符收藏（替换旧助记符/占位符，与历史语义一致）
     */
    private fun performAdd(
        project: Project,
        scope: FavoriteScope,
        location: AddActionSupport.PendingLocation,
        selectedMnemonic: Char,
        description: String?,
        existingAtPosition: List<com.aogg.favorites.ui.ScopedBookmark>
    ) {
        // ////////////////  校验参数：项目收藏无法保存项目外位置  start  //////////////////
        val storagePath = AddActionSupport.storagePathFor(scope, location)
        if (storagePath == null) {
            Messages.showErrorDialog(
                project,
                "该位置在项目目录之外，只能加入「全局收藏」。",
                "添加收藏失败"
            )
            return
        }
        // ////////////////  校验参数  end  ////////////////////////////////////////////

        // ////////////////  核心逻辑：清占位（按所选作用域）+ 替换同位置旧助记符  start  ///
        val manager = FavoritesScopeResolver.managerFor(project, scope)
        // 该助记符已被其它收藏占用 → 先移除（历史语义：新设置顶替旧收藏）
        manager.getBookmarkByMnemonic(selectedMnemonic)?.let {
            manager.removeBookmark(it.id)
        }
        // 同一位置的旧助记符收藏（两个作用域都清理，避免跨作用域重复）
        existingAtPosition.forEach {
            FavoritesScopeResolver.managerFor(project, it.scope).removeBookmark(it.bookmark.id)
        }
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////

        // ////////////////  核心逻辑：写入新助记符收藏（GLOBAL=绝对路径）  start  /////////
        val bookmark = Bookmark(
            id = UUID.randomUUID().toString(),
            type = location.type,
            filePath = storagePath,
            lineNumber = location.lineNumber,
            mnemonic = selectedMnemonic,
            description = description
        )
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////

        // ////////////////  逻辑后：结果通知与刷新  start  //////////////////////////////
        if (manager.addBookmark(bookmark)) {
            val mnemonicText = "[$selectedMnemonic]"
            val descText = if (description != null) ": $description" else ""
            val typeText = when (location.type) {
                BookmarkType.LINE -> "行 ${location.lineNumber + 1}"
                BookmarkType.FILE -> "文件"
                BookmarkType.FOLDER -> "文件夹"
            }
            Messages.showInfoMessage(
                project,
                "已添加助记符收藏 $mnemonicText$descText 到「${scope.label}」（$typeText）",
                "添加收藏成功"
            )
            BookmarkRefreshHelper.refreshAfterChange(project)
        } else {
            Messages.showErrorDialog(
                project,
                "添加收藏失败：助记符 [$selectedMnemonic] 在「${scope.label}」中已被占用",
                "添加收藏失败"
            )
        }
        // ////////////////  逻辑后  end  //////////////////////////////////////////////
    }

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
