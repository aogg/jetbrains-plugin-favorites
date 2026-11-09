package com.aogg.favorites.action

import com.aogg.favorites.helper.FavoritesActionTexts
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.vfs.VirtualFile

/**
 * 「收藏」右键子菜单组（id=Favorites.Group，plugin.xml 以 class 挂载本类）
 *
 * 1.6.1 新增：组自身按上下文控制可见性，消除「灰色幽灵项」：
 * - 现状根因：组无 class 时为平台 DefaultActionGroup，自身 update 不改可见性；
 *   三个子项在「无编辑器且无选中文件」的上下文（如项目视图空白处右键，VIRTUAL_FILE_ARRAY 为空）
 *   全部隐藏后，平台对 popup 组默认不「空则隐藏」（hideIfNoVisibleChildren=false），
 *   组仍渲染为一个空的灰色二级菜单项；
 * - 目标态：有项目且（编辑器上下文 或 选中文件集合非空）时整组可见可用
 *   （行号区=EDITOR 非空；项目视图/标签页/导航栏=文件集合非空，单文件上下文回退 VIRTUAL_FILE）；
 *   上下文不适用时整组隐藏，不留灰色幽灵项；
 * - 子项自身的可见性仍由各子项 update 负责（如行号区隐藏「收藏文件夹/收藏文件」直捷项）。
 *
 * 1.8.0 两点变化：
 * - 组不再挂到编辑器正文右键（EditorPopupMenu）与行号区右键（EditorGutterPopupMenu）——这两个位置
 *   原生 F11 / Ctrl+F11 已被接管为一级菜单项「添加收藏」「添加助记收藏」，子菜单纯属重复；
 *   仍挂 项目视图 / 编辑器标签页 / 导航栏 右键（要收藏文件/文件夹走这三处）；
 * - 组标题文案按「隐藏原生书签界面」开关动态加「（Favorites）」后缀（见 [FavoritesActionTexts]），
 *   与同屏出现的原生「书签」组区分；菜单每次打开都会 update，切换设置后无需重启。
 */
class FavoritesActionGroup : DefaultActionGroup() {

    /**
     * 按上下文启用/隐藏整组：有项目 +（编辑器上下文 或 选中文件集合非空）才可见，否则整组隐藏
     */
    override fun update(e: AnActionEvent) {
        // ////////////////  校验参数：无项目上下文直接隐藏  start  ///////////////////////
        val project = e.project
        if (project == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        // ////////////////  校验参数  end  /////////////////////////////////////////////

        // ////////////////  准备数据：编辑器上下文 与 选中文件集合（数组优先，回退单个）  start
        val editor = e.getData(CommonDataKeys.EDITOR)
        val files = if (editor == null) selectedFiles(e) else VirtualFile.EMPTY_ARRAY
        // ////////////////  准备数据  end  /////////////////////////////////////////////

        // ////////////////  核心逻辑：编辑器上下文或文件集合非空才显示，其余上下文整组隐藏  start
        e.presentation.isEnabledAndVisible = editor != null || files.isNotEmpty()
        // ////////////////  核心逻辑  end  /////////////////////////////////////////////

        // ////////////////  文案动态：组标题按「隐藏原生书签界面」开关加插件名后缀（1.8.0）  start
        e.presentation.text = FavoritesActionTexts.of(BASE_TEXT)
        // ////////////////  文案动态  end  /////////////////////////////////////////////
    }

    /**
     * 选中文件集合：多选数组优先，为空时回退单个（标签页/导航栏等上下文可能只提供单个文件）
     *
     * @param e 动作事件
     * @return 选中的虚拟文件数组（无选中返回空数组）
     */
    private fun selectedFiles(e: AnActionEvent): Array<VirtualFile> {
        // ////////////////  核心逻辑：多选数组优先，空则回退单个文件  start  ///////////////
        val array = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        if (!array.isNullOrEmpty()) return array
        val single = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return VirtualFile.EMPTY_ARRAY
        return arrayOf(single)
        // ////////////////  核心逻辑  end  /////////////////////////////////////////////
    }

    companion object {

        /** 组标题 base 文案（是否加「（Favorites）」后缀由 FavoritesActionTexts 决定） */
        private const val BASE_TEXT = "收藏"
    }
}
