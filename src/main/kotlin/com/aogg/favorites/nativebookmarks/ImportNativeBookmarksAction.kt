package com.aogg.favorites.nativebookmarks

import com.aogg.favorites.helper.FavoritesActionTexts
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys

/**
 * 导入原生书签动作（id=Favorites.ImportNativeBookmarks）
 *
 * 双通道读取 PhpStorm 原生书签（运行时 API + .idea XML 容错解析），
 * 按 (相对路径+行号)/路径 去重后增量合并到收藏列表，完成后弹出结果通知（含[查看]）。
 */
class ImportNativeBookmarksAction : AnAction(
    "导入原生书签",
    "导入 PhpStorm 原生书签到收藏列表（可重复执行，自动去重）",
    AllIcons.ToolbarDecorator.Import
) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        NativeBookmarkImportService.importAndNotify(project)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.getData(CommonDataKeys.PROJECT) != null
        // 1.8.0 菜单/查找操作文案：短名 / 短名+插件名后缀（按「隐藏原生书签界面」设置实时切换）
        e.presentation.text = FavoritesActionTexts.of(BASE_TEXT)
    }

    companion object {
        /** 菜单 base 文案（是否加「（Favorites）」后缀由 FavoritesActionTexts 决定） */
        private const val BASE_TEXT = "导入原生书签"
    }
}
