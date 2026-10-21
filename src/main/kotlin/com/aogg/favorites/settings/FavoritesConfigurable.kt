package com.aogg.favorites.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import javax.swing.JComponent

/**
 * Settings → Tools → Favorites 设置页
 *
 * 阶段说明：本阶段只提供 UI 与偏好持久化；
 * "隐藏原生书签入口"的实际生效（动作接管/工具窗口隐藏）在原生集成版本实现。
 */
class FavoritesConfigurable : Configurable {

    private var hideNativeCheckBox: JBCheckBox? = null

    override fun getDisplayName(): String = "Favorites"

    override fun createComponent(): JComponent {
        val settings = FavoritesSettings.getInstance()
        val checkBox = JBCheckBox(
            "隐藏 PhpStorm 原生书签入口（偏好将被保存，随原生书签集成版本生效）",
            settings.state.hideNativeBookmarks
        )
        hideNativeCheckBox = checkBox
        return checkBox
    }

    override fun isModified(): Boolean {
        val checkBox = hideNativeCheckBox ?: return false
        return checkBox.isSelected != FavoritesSettings.getInstance().state.hideNativeBookmarks
    }

    override fun apply() {
        val checkBox = hideNativeCheckBox ?: return
        FavoritesSettings.getInstance().state.hideNativeBookmarks = checkBox.isSelected
    }

    override fun reset() {
        val checkBox = hideNativeCheckBox ?: return
        checkBox.isSelected = FavoritesSettings.getInstance().state.hideNativeBookmarks
    }

    override fun disposeUIResources() {
        hideNativeCheckBox = null
    }
}
