package com.aogg.favorites.settings

import com.aogg.favorites.nativebookmarks.FavoritesNativeIntegration
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.Box
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSeparator

/**
 * Settings → Tools → Favorites 设置页
 *
 * - ☑ 隐藏 PhpStorm 原生书签界面（默认开启）：
 *   只控制原生 Bookmarks 工具窗口与残余原生动作/动作组可见性；
 *   快捷键（F11/Ctrl+F11/Shift+F11/Alt+2/Ctrl+0-9 等）与 Ctrl+点击行号区手势
 *   始终由本插件接管，不受本开关影响
 * - apply 时界面显隐立即生效/恢复（动作组可见性 + 所有已打开项目的原生 Bookmarks 工具窗口）
 * - 按钮：重置"导入提示"标记（重新触发首次引导检测）
 * - 布局：垂直排列（开关 → 分隔线 → 重置按钮 + 说明），1.2.0 整理
 */
class FavoritesConfigurable : Configurable {

    private var hideNativeCheckBox: JBCheckBox? = null
    private var mainPanel: JPanel? = null

    override fun getDisplayName(): String = "Favorites"

    override fun createComponent(): JComponent {
        // ////////////////  准备数据：读取当前设置  start  /////////////////////////////
        val settings = FavoritesSettings.getInstance()
        // ////////////////  准备数据  end  ///////////////////////////////////////////

        // ////////////////  核心逻辑：垂直布局组装  start  /////////////////////////////
        val checkBox = JBCheckBox(
            "隐藏 PhpStorm 原生书签界面（快捷键始终由本插件接管，收藏加入当前项目）",
            settings.state.hideNativeBookmarks
        )
        hideNativeCheckBox = checkBox

        val panel = JPanel(BorderLayout())
        val topBox = Box.createVerticalBox()
        topBox.border = JBUI.Borders.empty(4, 8)
        topBox.add(checkBox)
        topBox.add(Box.createVerticalStrut(JBUI.scale(8)))
        topBox.add(JSeparator())
        topBox.add(Box.createVerticalStrut(JBUI.scale(8)))

        val resetRow = JPanel(BorderLayout())
        val resetButton = javax.swing.JButton("重置导入提示")
        resetButton.toolTipText = "清除所有已打开项目的\"已导入/不再提示\"标记，重新触发原生书签导入引导"
        resetButton.addActionListener { resetImportState() }
        resetRow.add(resetButton, BorderLayout.WEST)
        val hintLabel = JBLabel("重置后下次打开项目时将重新检测原生书签并提示导入")
        hintLabel.border = JBUI.Borders.emptyLeft(8)
        resetRow.add(hintLabel, BorderLayout.CENTER)
        topBox.add(resetRow)
        topBox.add(Box.createVerticalGlue())

        panel.add(topBox, BorderLayout.NORTH)
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////

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

        // 勾选变化时立即生效/恢复原生界面显隐（残余动作可见性 + 工具窗口，覆盖所有已打开项目）
        // 快捷键接管为无条件，不随开关恢复
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
