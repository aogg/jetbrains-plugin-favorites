package com.aogg.favorites.ui

import com.aogg.favorites.model.BookmarkManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.GridBag
import java.awt.GridBagLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextField

/**
 * 助记符选择对话框
 * 显示可用和已用的标识符，支持输入描述信息
 */
class MnemonicSelectorDialog(
    project: Project,
    private val currentMnemonic: Char? = null
) : DialogWrapper(project) {

    private val bookmarkManager = BookmarkManager.getInstance(project)
    private val mnemonicField = JBTextField()
    private val descriptionField = JBTextField()

    var selectedMnemonic: Char? = null
        private set
    var description: String? = null
        private set

    init {
        title = "选择助记符"
        init()
        setupUI()
    }

    private fun setupUI() {
        // 设置助记符输入框
        mnemonicField.columns = 1
        mnemonicField.maxLength = 1
        mnemonicField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) {
                validateInput()
            }

            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) {
                validateInput()
            }

            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) {
                validateInput()
            }
        })

        // 如果已有助记符，预填充
        if (currentMnemonic != null) {
            mnemonicField.text = currentMnemonic.toString()
        }

        // 设置描述输入框
        descriptionField.columns = 30
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        val gb = GridBag()

        // 助记符标签和输入框
        panel.add(JBLabel("助记符 (0-9 或 A-Z):"), gb.nextLine().next().weightx(0.0).anchor(GridBag.WEST))
        panel.add(mnemonicField, gb.next().weightx(1.0).fillCellHorizontally())

        // 描述标签和输入框
        panel.add(JBLabel("描述 (可选):"), gb.nextLine().next().weightx(0.0).anchor(GridBag.WEST))
        panel.add(descriptionField, gb.next().weightx(1.0).fillCellHorizontally())

        // 可用助记符提示
        val availableMnemonics = bookmarkManager.getAvailableMnemonics()
        val usedMnemonics = bookmarkManager.getUsedMnemonics()
        val hintText = buildString {
            append("可用: ")
            if (availableMnemonics.isNotEmpty()) {
                append(availableMnemonics.take(10).joinToString(", "))
                if (availableMnemonics.size > 10) {
                    append("... (共 ${availableMnemonics.size} 个)")
                }
            } else {
                append("无")
            }
            if (usedMnemonics.isNotEmpty()) {
                append(" | 已用: ")
                append(usedMnemonics.sorted().joinToString(", "))
            }
        }
        panel.add(JBLabel(hintText), gb.nextLine().next().spanX(2).anchor(GridBag.WEST))

        return panel
    }

    private fun validateInput() {
        val text = mnemonicField.text.trim()
        val isValid = when {
            text.isEmpty() -> false
            text.length == 1 -> {
                val char = text[0]
                (char in '0'..'9' || char in 'A'..'Z') && 
                (char == currentMnemonic || bookmarkManager.isMnemonicAvailable(char))
            }
            else -> false
        }
        okAction.isEnabled = isValid
    }

    override fun doOKAction() {
        val text = mnemonicField.text.trim()
        if (text.length == 1) {
            val char = text[0]
            if (char in '0'..'9' || char in 'A'..'Z') {
                selectedMnemonic = char
                description = descriptionField.text.takeIf { it.isNotBlank() }
                super.doOKAction()
            }
        }
    }

    override fun doCancelAction() {
        selectedMnemonic = null
        description = null
        super.doCancelAction()
    }
}
