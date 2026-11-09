package com.aogg.favorites.action

import com.aogg.favorites.helper.FavoritesActionTexts
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.vfs.VirtualFile

/**
 * 收藏文件夹/收藏文件直捷动作（id=Favorites.AddFavorite）
 *
 * 「添加匿名收藏」在文件/文件夹上下文的直捷入口（收藏子菜单第一位，导航栏/项目视图/标签页右键可见）：
 * - 复用 [AddAnonymousBookmarkAction] 的项目上下文流程（弹「作用域+分组」分组树、
 *   默认记住上次选择、多选批量只弹一次、已收藏则切换移除），行为与其文件上下文完全一致；
 * - 菜单文案按选中类型动态显示：全部是文件夹→「收藏文件夹」；全部是文件（单/多选）→「收藏文件」；
 *   文件夹+文件混合多选→「收藏」；
 * - 1.8.0：文案再按「隐藏原生书签界面」开关决定是否追加「（Favorites）」后缀（见 [FavoritesActionTexts]）。
 */
class AddFileFolderFavoriteAction : AnAction("收藏", DESCRIPTION, null) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        // ////////////////  准备数据：选中文件集合（多选数组优先，为空回退单个）  start  ///////
        val files = selectedFiles(e)
        if (files.isEmpty()) return
        // ////////////////  准备数据  end  /////////////////////////////////////////////

        // ////////////////  核心逻辑：复用匿名收藏的文件/文件夹流程（弹分组树/切换移除）  start  /
        AddAnonymousBookmarkAction().handleProjectContext(project, files, fixedScope = null)
        // ////////////////  核心逻辑  end  /////////////////////////////////////////////
    }

    /**
     * 按上下文启用：有项目、非编辑器上下文（行收藏由「添加匿名收藏」负责，避免重复入口）且文件集合非空。
     * 文件集合支持单个回退（导航栏/标签页等上下文可能只提供单个文件）。
     * 无有效上下文时隐藏，避免灰色无效项常驻。
     */
    override fun update(e: AnActionEvent) {
        // ////////////////  可见性：有项目 + 非编辑器上下文 + 文件集合非空  start  ///////////
        val files = if (e.project != null && e.getData(CommonDataKeys.EDITOR) == null) {
            selectedFiles(e)
        } else {
            VirtualFile.EMPTY_ARRAY
        }
        e.presentation.isEnabledAndVisible = files.isNotEmpty()
        // ////////////////  可见性  end  /////////////////////////////////////////////////

        // ////////////////  文案动态：按选中类型显示 收藏文件夹/收藏文件/收藏  start  ////////
        // 1.8.0：base 文案再按「隐藏原生书签界面」开关决定是否追加「（Favorites）」后缀（菜单打开即刷新）
        e.presentation.text = FavoritesActionTexts.of(if (files.isNotEmpty()) presentationText(files) else BASE_TEXT)
        if (files.isNotEmpty()) {
            e.presentation.description = DESCRIPTION
        }
        // ////////////////  文案动态  end  ///////////////////////////////////////////////
    }

    /**
     * 选中文件集合：多选数组优先，为空时回退单个（标签页/导航栏等上下文可能只给单个）
     */
    private fun selectedFiles(e: AnActionEvent): Array<VirtualFile> {
        val array = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        if (!array.isNullOrEmpty()) return array
        val single = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return VirtualFile.EMPTY_ARRAY
        return arrayOf(single)
    }

    /**
     * 菜单文案：全部文件夹→「收藏文件夹」；全部文件（单/多选）→「收藏文件」；混合多选→「收藏」
     */
    private fun presentationText(files: Array<VirtualFile>): String = when {
        files.all { it.isDirectory } -> "收藏文件夹"
        files.none { it.isDirectory } -> "收藏文件"
        // 文件夹+文件混合多选
        else -> "收藏"
    }

    companion object {

        /** 菜单 base 文案（无选中/混合多选；是否加「（Favorites）」后缀由 FavoritesActionTexts 决定） */
        private const val BASE_TEXT = "收藏"

        /** 动作描述：中文说明会弹出「作用域+分组」选择（供菜单 tooltip / 查找操作展示） */
        private const val DESCRIPTION =
            "收藏选中的文件/文件夹：弹出「作用域+分组」选择（默认记住上次选择，多选批量一次）；已收藏的位置则切换移除"
    }
}
