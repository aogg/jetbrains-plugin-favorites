package com.aogg.favorites.ui

import com.aogg.favorites.model.Bookmark
import com.aogg.favorites.model.FavoriteScope
import com.aogg.favorites.model.FavoritesScopeResolver
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.util.ui.GridBag
import com.intellij.util.ui.JBUI
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.GridLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * 助记符选择对话框
 * 0-9 / A-Z 网格按钮，已占用项置灰禁用（当前助记符除外），可输入描述信息
 *
 * 1.2.0 起支持作用域：占用检测按 [scope] 所属管理器进行（项目/全局作用域各自独立唯一），
 * 标题与提示带上作用域名。
 *
 * @param project 当前项目
 * @param scope 助记符归属的作用域（默认项目收藏）
 * @param currentMnemonic 当前助记符（修改场景预选；无则 null）
 * @param currentDescription 当前描述（修改场景回填）
 */
class MnemonicSelectorDialog(
    project: Project,
    private val scope: FavoriteScope = FavoriteScope.PROJECT,
    private val currentMnemonic: Char? = null,
    currentDescription: String? = null
) : DialogWrapper(project) {

    companion object {
        private val SELECTED_COLOR = JBColor(java.awt.Color(0x9FC6F7), java.awt.Color(0x2E436E))
    }

    // 按作用域解析管理器：占用检测/可用性按该作用域进行
    private val bookmarkManager = FavoritesScopeResolver.managerFor(project, scope)
    private val descriptionField = JBTextField(currentDescription ?: "")

    private val buttons = LinkedHashMap<Char, JButton>()
    private var selectedButton: JButton? = null

    var selectedMnemonic: Char? = null
        private set
    var description: String? = null
        private set

    init {
        title = "选择助记符（${scope.label}）"
        init()

        // 初始可用状态：已有助记符时默认选中
        okAction.isEnabled = currentMnemonic != null
        currentMnemonic?.let { selectMnemonic(it) }
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 8))

        val hintLabel = JBLabel("点击选择助记符（灰色为「${scope.label}」中已占用，不可选）：")
        panel.add(hintLabel, BorderLayout.NORTH)

        // 0-9 / A-Z 网格（每行10个）
        val used = bookmarkManager.getUsedMnemonics()
        val grid = JPanel(GridLayout(0, 10, 4, 4))
        for (mnemonic in ('0'..'9') + ('A'..'Z')) {
            val available = mnemonic == currentMnemonic || !used.contains(mnemonic)
            val button = JButton(mnemonic.toString())
            button.isFocusable = false
            button.isOpaque = true
            button.margin = JBUI.insets(2)
            button.preferredSize = JBUI.size(44, 34)
            button.isEnabled = available
            if (!available) {
                button.toolTipText = "[$mnemonic] 已被占用"
            } else {
                button.toolTipText = "助记符 [$mnemonic]"
                button.addActionListener { selectMnemonic(mnemonic) }
            }
            buttons[mnemonic] = button
            grid.add(button)
        }
        panel.add(grid, BorderLayout.CENTER)

        // 描述输入
        val descriptionPanel = JPanel(GridBagLayout())
        val gb = GridBag()
        descriptionPanel.add(JBLabel("描述 (可选):"), gb.nextLine().next().weightx(0.0).anchor(GridBag.WEST))
        descriptionField.columns = 30
        descriptionPanel.add(descriptionField, gb.next().weightx(1.0).fillCellHorizontally())
        val gbc = gb.nextLine().next().anchor(GridBag.WEST) as GridBagConstraints
        gbc.gridwidth = GridBagConstraints.REMAINDER
        descriptionPanel.add(JBLabel(" "), gbc)

        panel.add(descriptionPanel, BorderLayout.SOUTH)
        return panel
    }

    /**
     * 选中助记符（高亮按钮）
     */
    private fun selectMnemonic(mnemonic: Char) {
        val button = buttons[mnemonic] ?: return
        if (!button.isEnabled) return
        selectedButton?.background = null
        button.background = SELECTED_COLOR
        selectedButton = button
        selectedMnemonic = mnemonic
        okAction.isEnabled = true
    }

    override fun doOKAction() {
        val mnemonic = selectedMnemonic
        if (mnemonic == null || !Bookmark.isValidMnemonic(mnemonic)) {
            // 未选择助记符时不关闭对话框
            return
        }
        description = descriptionField.text.trim().ifEmpty { null }
        super.doOKAction()
    }

    override fun doCancelAction() {
        selectedMnemonic = null
        description = null
        super.doCancelAction()
    }
}
