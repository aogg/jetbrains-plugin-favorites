package com.aogg.favorites.action

import com.aogg.favorites.helper.BookmarkIconHelper
import com.aogg.favorites.model.FavoriteScope
import com.aogg.favorites.settings.FavoritesSettings
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import javax.swing.Icon

/**
 * 添加收藏时的作用域二选一弹窗（1.2.0 新增）
 *
 * 行为约定（与需求一致）：
 * - 列表弹窗「项目收藏」「全局收藏」二选一；
 * - 默认高亮"上次使用的作用域"（应用级设置 lastAddScope，默认 PROJECT）：
 *   通过 createListPopup(step, defaultOptionIndex) 指定默认选中行；
 * - 选择后立即记住该作用域，供下次默认高亮；
 * - 取消弹窗则不执行任何添加（onChosen 不会被触发）。
 */
object AddScopeChooser {

    /**
     * 弹窗条目：作用域 + 展示图标
     *
     * @param scope 作用域
     * @param icon 条目图标
     */
    private data class ScopeItem(val scope: FavoriteScope, val icon: Icon) {
        override fun toString(): String = scope.label
    }

    /**
     * 弹出作用域选择，用户选中后回调 [onChosen]（取消弹窗不回调）
     *
     * @param project 当前项目（保留参数以保持调用方签名稳定）
     * @param onChosen 选中作用域回调（在 EDT 执行）
     */
    fun chooseScope(project: Project, onChosen: (FavoriteScope) -> Unit) {
        // ////////////////  准备数据：定位上次使用的作用域作为默认选中行  start  ////////////
        val settings = FavoritesSettings.getInstance()
        val lastScope = settings.getLastAddScope()
        val items = FavoriteScope.entries.map { scope ->
            ScopeItem(scope = scope, icon = BookmarkIconHelper.getScopeIcon(scope))
        }
        val defaultIndex = items.indexOfFirst { it.scope == lastScope }.coerceAtLeast(0)
        // ////////////////  准备数据  end  ////////////////////////////////////////////////

        // ////////////////  核心逻辑：构建并显示二选一弹窗  start  //////////////////////////
        val step = object : BaseListPopupStep<ScopeItem>("添加到哪个收藏？", items) {
            override fun getIconFor(aValue: ScopeItem): Icon = aValue.icon

            override fun getTextFor(aValue: ScopeItem): String = aValue.scope.label

            override fun onChosen(selectedValue: ScopeItem?, finalChoice: Boolean): PopupStep<*>? {
                // ////////////////  核心逻辑：记住选择并回调  start  ////////////////////////
                val chosen = selectedValue ?: return PopupStep.FINAL_CHOICE
                settings.setLastAddScope(chosen.scope)
                onChosen(chosen.scope)
                // ////////////////  核心逻辑  end  /////////////////////////////////////
                return PopupStep.FINAL_CHOICE
            }
        }

        JBPopupFactory.getInstance()
            .createListPopup(step, defaultIndex)
            .apply {
                // 底部灰色提示行，帮助用户理解两个作用域的差异
                // （2023.3 的 setAdText 需带对齐参数，取 SwingConstants.CENTER）
                setAdText("项目收藏随项目保存；全局收藏跨项目共享", javax.swing.SwingConstants.CENTER)
            }
            .showInFocusCenter()
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////////
    }
}
