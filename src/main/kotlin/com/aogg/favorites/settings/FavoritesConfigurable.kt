package com.aogg.favorites.settings

import com.aogg.favorites.nativebookmarks.FavoritesNativeIntegration
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBCheckBox
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Settings → Tools → Favorites 设置页
 *
 * - ☑ 隐藏 PhpStorm 原生书签入口（默认勾选）：
 *   apply 时立即生效/恢复（动作接管 + 所有已打开项目的原生 Bookmarks 工具窗口显隐）
 * - 按钮：重置"导入提示"标记（重新触发首次引导检测）
 */
class FavoritesConfigurable : Configurable {

    private var hideNativeCheckBox: JBCheckBox? = null
    private var mainPanel: JPanel? = null

    override fun getDisplayName(): String = "Favorites"

    override fun createComponent(): JComponent {
        val settings = FavoritesSettings.getInstance()
        val checkBox = JBCheckBox(
            "隐藏 PhpStorm 原生书签入口（F11/Ctrl+F11/Shift+F11/Alt+2/Ctrl+0-9 由本插件接管）",
            settings.state.hideNativeBookmarks
        )
        hideNativeCheckBox = checkBox

        val panel = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT))
        panel.add(checkBox)
        val resetButton = javax.swing.JButton("重置导入提示")
        resetButton.toolTipText = "清除所有已打开项目的\"已导入/不再提示\"标记，重新触发原生书签导入引导"
        resetButton.addActionListener { resetImportState() }
        panel.add(resetButton)

        mainPanel = panel
        return panel
    }

    override fun isModified(): Boolean {
        val checkBox = hideNativeCheckBox ?: return false
        return checkBox.isSelected != FavoritesSettings.getInstance().state.hideNativeBookmarks
    }

    override fun apply() {
        val checkBox = hideNativeCheckBox ?: return
        val settings = FavoritesSettings.getInstance()
        val old = settings.state.hideNativeBookmarks
        val new = checkBox.isSelected
        settings.state.hideNativeBookmarks = new

        // 勾选变化时立即应用/恢复（动作替换 + 工具窗口显隐，覆盖所有已打开项目）
        if (old != new) {
            FavoritesNativeIntegration.applyOrRestore()
        }
    }

    override fun reset() {
        val checkBox = hideNativeCheckBox ?: return
        checkBox.isSelected = FavoritesSettings.getInstance().state.hideNativeBookmarks
    }

    override fun disposeUIResources() {
        hideNativeCheckBox = null
        mainPanel = null
    }

    /**
     * 清空所有已打开项目的 FavoritesProjectImportState（importedNative=false、dontAskAgain=false）
     */
    private fun resetImportState() {
        var resetCount = 0
        runCatching {
            ProjectManager.getInstance().openProjects.forEach { project ->
                if (project.isDisposed) return@forEach
                val state = FavoritesProjectImportState.getInstance(project).state
                state.importedNative = false
                state.dontAskAgain = false
                resetCount++
            }
        }
        Messages.showInfoMessage(
            mainPanel,
            "已重置 $resetCount 个项目的导入提示标记，下次打开项目时将重新检测原生书签并提示导入。",
            "重置导入提示"
        )
    }
}
