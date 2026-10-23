package com.aogg.favorites.ui

import com.aogg.favorites.model.MatchRule
import com.aogg.favorites.model.RegexTarget
import com.aogg.favorites.model.RuleType
import com.aogg.favorites.rules.RegexRuleExecutor
import com.aogg.favorites.rules.RegexRuleMatcher
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.JBColor
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.util.UUID
import java.util.regex.Pattern
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants

/**
 * 规则类型下拉项（SQL/SSH 本步置灰不可选；data class：与下拉模型条目值相等，回退选中可命中）
 *
 * @param type 规则类型
 * @param enabled 是否可选（false=开发中）
 */
private data class RuleTypeItem(val type: RuleType, val enabled: Boolean) {
    override fun toString(): String = type.label + if (enabled) "" else "（开发中）"
}

/**
 * 正则匹配目标下拉项
 */
private data class RegexTargetItem(val target: RegexTarget) {
    override fun toString(): String = target.label
}

/**
 * 规则编辑对话框（新建与编辑共用，1.4.0）：
 * - 类型下拉 REGEX/SQL/SSH 切换三块表单；SQL/SSH 本步置灰并带「（开发中）」后缀（选中自动回退正则）；
 * - REGEX 表单：名称、根目录（空=整项目，带提示）、正则（多行）、匹配目标下拉、忽略大小写、上限；
 * - 「测试」按钮：用当前表单值跑一次正则匹配（复用 RegexRuleExecutor 逻辑，不落结果缓存），
 *   后台执行不阻塞 UI，完成弹「匹配到 N 条」；
 * - OK 校验：名称非空；正则可编译（Pattern.compile try-catch 就地提示）；上限 1..10000。
 *
 * 编辑既有 SQL/SSH 规则（后续版本才能产生）时：类型保持不变，仅名称/上限等公共字段可改，
 * REGEX 表单值不覆盖其类型专属字段。
 *
 * @param project 项目
 * @param existing 编辑的既有规则（null=新建）
 */
class RuleEditDialog(
    private val project: Project,
    private val existing: MatchRule?
) : DialogWrapper(project) {

    companion object {
        private val log = Logger.getInstance(RuleEditDialog::class.java)

        /** 结果条数上限边界（与 RegexRuleExecutor 硬上限一致） */
        private const val MAX_RESULT_LIMIT = RegexRuleExecutor.MAX_RESULT_LIMIT
    }

    /** 公共字段：名称 */
    private val nameField = JBTextField()

    /** 公共字段：类型下拉（SQL/SSH 置灰） */
    private val typeCombo = JComboBox<RuleTypeItem>()

    /** REGEX 表单：根目录（相对项目根，空=整项目） */
    private val baseDirField = JBTextField()

    /** REGEX 表单：正则（多行编辑） */
    private val regexArea = JBTextArea(6, 44)

    /** REGEX 表单：匹配目标下拉 */
    private val targetCombo = JComboBox<RegexTargetItem>()

    /** REGEX 表单：忽略大小写 */
    private val caseBox = JBCheckBox("忽略大小写")

    /** REGEX 表单：结果上限 */
    private val limitSpinner = JBIntSpinner(
        existing?.limit?.coerceIn(1, MAX_RESULT_LIMIT) ?: 500, 1, MAX_RESULT_LIMIT, 50
    )

    /** 类型切换卡片布局（SQL/SSH 表单位本步预留） */
    private val cardLayout = CardLayout()

    /** 类型切换卡片容器 */
    private val cardPanel = JPanel(cardLayout)

    /** 测试按钮（执行期间禁用，防并发重复测试） */
    private val testAction = object : AbstractAction("测试") {
        override fun actionPerformed(e: java.awt.event.ActionEvent?) {
            runRegexTest()
        }
    }

    init {
        // ////////////////  类型下拉：SQL/SSH 置灰 + 选中自动回退  start  //////////////////
        typeCombo.model = DefaultComboBoxModel(
            arrayOf(
                RuleTypeItem(RuleType.REGEX, true),
                RuleTypeItem(RuleType.SQL, false),
                RuleTypeItem(RuleType.SSH, false)
            )
        )
        typeCombo.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>,
                value: Any?,
                index: Int,
                selected: Boolean,
                focused: Boolean
            ): Component {
                val component = super.getListCellRendererComponent(list, value, index, selected, focused)
                if (value is RuleTypeItem && !value.enabled) {
                    component.foreground = JBColor.GRAY
                }
                return component
            }
        }
        typeCombo.addActionListener {
            val item = typeCombo.selectedItem as? RuleTypeItem ?: return@addActionListener
            if (!item.enabled) {
                // 开发中类型：回退到正则并提示（覆盖鼠标与键盘两种切换途径）
                typeCombo.selectedItem = RuleTypeItem(RuleType.REGEX, true)
                Messages.showInfoMessage(
                    project,
                    "「${item.type.label}」执行器将在后续版本提供，当前请使用正则匹配。",
                    "规则类型"
                )
                return@addActionListener
            }
            cardLayout.show(cardPanel, item.type.name)
        }
        typeCombo.selectedItem = RuleTypeItem(RuleType.REGEX, true)
        // ////////////////  类型下拉  end  ////////////////////////////////////////////

        // ////////////////  匹配目标下拉  start  ///////////////////////////////////////
        targetCombo.model = DefaultComboBoxModel(
            RegexTarget.entries.map { RegexTargetItem(it) }.toTypedArray()
        )
        targetCombo.selectedItem = RegexTargetItem(RegexTarget.ALL)
        // ////////////////  匹配目标下拉  end  ////////////////////////////////////////

        title = if (existing == null) "新建规则" else "编辑规则"
        init()
        existing?.let { fillForm(it) }
    }

    // ==================== 界面构建 ====================

    override fun createCenterPanel(): JComponent {
        // ////////////////  公共行：名称 / 类型  start  /////////////////////////////////
        val panel = JPanel(GridBagLayout())
        val constraints = GridBagConstraints().apply {
            insets = Insets(6, 10, 6, 10)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
        }

        constraints.gridx = 0
        constraints.gridy = 0
        constraints.weightx = 0.0
        panel.add(JBLabel("名称:"), constraints)
        constraints.gridx = 1
        constraints.weightx = 1.0
        panel.add(nameField, constraints)

        constraints.gridx = 0
        constraints.gridy = 1
        constraints.weightx = 0.0
        panel.add(JBLabel("类型:"), constraints)
        constraints.gridx = 1
        constraints.weightx = 1.0
        panel.add(typeCombo, constraints)
        // ////////////////  公共行  end  //////////////////////////////////////////////

        // ////////////////  类型表单卡片（REGEX 可用；SQL/SSH 本步占位）  start  //////////
        cardPanel.add(buildRegexFormPanel(), RuleType.REGEX.name)
        cardPanel.add(buildDevPlaceholderPanel("SQL 查询"), RuleType.SQL.name)
        cardPanel.add(buildDevPlaceholderPanel("SSH 命令"), RuleType.SSH.name)
        constraints.gridx = 0
        constraints.gridy = 2
        constraints.gridwidth = 2
        constraints.fill = GridBagConstraints.BOTH
        constraints.weighty = 1.0
        panel.add(cardPanel, constraints)
        // ////////////////  类型表单卡片  end  ////////////////////////////////////////

        return panel
    }

    /**
     * REGEX 表单：根目录（空=整项目带提示）、正则（多行）、匹配目标、忽略大小写、上限
     */
    private fun buildRegexFormPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        val constraints = GridBagConstraints().apply {
            insets = Insets(4, 10, 4, 10)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
        }

        // 根目录（空=整项目，输入框内灰字提示）
        baseDirField.emptyText.text = "留空 = 整个项目（相对项目根，如 src/main/php）"
        constraints.gridx = 0
        constraints.gridy = 0
        constraints.weightx = 0.0
        panel.add(JBLabel("根目录:"), constraints)
        constraints.gridx = 1
        constraints.weightx = 1.0
        panel.add(baseDirField, constraints)

        // 根目录提示（灰色说明）
        constraints.gridx = 1
        constraints.gridy = 1
        val baseDirHint = JBLabel("匹配路径始终相对项目根；目录不存在时执行报错")
        baseDirHint.foreground = JBColor.GRAY
        panel.add(baseDirHint, constraints)

        // 正则（多行）
        regexArea.lineWrap = false
        constraints.gridx = 0
        constraints.gridy = 2
        constraints.weightx = 0.0
        panel.add(JBLabel("正则:"), constraints)
        constraints.gridx = 1
        constraints.gridy = 2
        constraints.weightx = 1.0
        constraints.weighty = 1.0
        constraints.fill = GridBagConstraints.BOTH
        panel.add(JScrollPane(
            regexArea,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        ), constraints)

        // 匹配目标 + 忽略大小写 + 上限（同一行）
        constraints.gridy = 3
        constraints.gridx = 0
        constraints.weightx = 0.0
        constraints.weighty = 0.0
        constraints.fill = GridBagConstraints.HORIZONTAL
        panel.add(JBLabel("匹配目标:"), constraints)
        constraints.gridx = 1
        constraints.weightx = 1.0
        val targetRow = JPanel(BorderLayout(8, 0))
        targetRow.add(targetCombo, BorderLayout.WEST)
        targetRow.add(caseBox, BorderLayout.CENTER)
        val limitRow = JPanel(BorderLayout(4, 0))
        limitRow.add(JBLabel("上限:"), BorderLayout.WEST)
        limitRow.add(limitSpinner, BorderLayout.CENTER)
        targetRow.add(limitRow, BorderLayout.EAST)
        panel.add(targetRow, constraints)

        return panel
    }

    /**
     * SQL/SSH 本步占位表单（UI 位置留好，字段与执行器下一步接入）
     */
    private fun buildDevPlaceholderPanel(typeLabel: String): JComponent {
        val panel = JPanel(BorderLayout())
        panel.add(
            JBLabel(
                "<html><body style=\"width: 360px\">「$typeLabel」表单与执行器将在后续版本提供，" +
                    "字段位（数据源/连接/SQL 与 SSH 主机/命令等）已预留。</body></html>"
            ),
            BorderLayout.NORTH
        )
        return panel
    }

    override fun getPreferredFocusedComponent(): JComponent = nameField

    /**
     * 动作行：测试 / OK / 取消
     */
    override fun createActions(): Array<Action> = arrayOf(testAction, okAction, cancelAction)

    // ==================== 数据回填与产出 ====================

    /**
     * 用既有规则回填表单（编辑入口）
     */
    private fun fillForm(rule: MatchRule) {
        nameField.text = rule.name
        baseDirField.text = rule.regexBaseDir
        regexArea.text = rule.regexPattern
        caseBox.isSelected = rule.caseInsensitive
        limitSpinner.number = rule.limit.coerceIn(1, MAX_RESULT_LIMIT)
        val targetItem = (0 until targetCombo.itemCount)
            .map { targetCombo.getItemAt(it) }
            .filterNotNull()
            .firstOrNull { it.target == rule.regexTarget }
        if (targetItem != null) {
            targetCombo.selectedItem = targetItem
        }
    }

    /**
     * 按当前表单构建规则（校验失败返回 null）：
     * 新建=生成 UUID、默认启用；编辑=保留 id/createdAt/enabled 与 SQL/SSH 字段。
     */
    fun buildRule(): MatchRule? {
        // ////////////////  校验参数  start  ///////////////////////////////////////////
        if (doValidate() != null) return null
        // ////////////////  校验参数  end  /////////////////////////////////////////////

        // ////////////////  返回：既有规则 copy（保类型专属字段）或新建  start  ///////////
        val base = existing ?: MatchRule(
            id = UUID.randomUUID().toString(),
            name = "",
            type = RuleType.REGEX
        )
        return base.copy(
            name = nameField.text.trim(),
            regexBaseDir = RegexRuleMatcher.normalizeRelativePath(baseDirField.text),
            regexPattern = regexArea.text,
            regexTarget = (targetCombo.selectedItem as? RegexTargetItem)?.target ?: RegexTarget.ALL,
            caseInsensitive = caseBox.isSelected,
            limit = limitSpinner.number.coerceIn(1, MAX_RESULT_LIMIT)
        )
        // ////////////////  返回  end  ///////////////////////////////////////////////
    }

    // ==================== 校验与测试 ====================

    /**
     * OK 前校验：名称非空；正则可编译（就地提示编译错误）；上限 1..10000
     */
    override fun doValidate(): ValidationInfo? {
        // ////////////////  校验一：名称非空  start  ///////////////////////////////////
        if (nameField.text.isBlank()) {
            return ValidationInfo("名称不能为空", nameField)
        }
        // ////////////////  校验一  end  //////////////////////////////////////////////

        // ////////////////  校验二：正则可编译  start  //////////////////////////////////
        try {
            Pattern.compile(
                regexArea.text,
                if (caseBox.isSelected) Pattern.CASE_INSENSITIVE else 0
            )
        } catch (e: Exception) {
            return ValidationInfo("正则表达式无法编译：${e.message ?: "未知错误"}", regexArea)
        }
        // ////////////////  校验二  end  //////////////////////////////////////////////

        // ////////////////  校验三：上限范围  start  ////////////////////////////////////
        val limit = limitSpinner.number
        if (limit < 1 || limit > MAX_RESULT_LIMIT) {
            return ValidationInfo("上限必须在 1..$MAX_RESULT_LIMIT 之间", limitSpinner)
        }
        // ////////////////  校验三  end  //////////////////////////////////////////////

        return null
    }

    /**
     * 「测试」按钮：用当前表单值后台跑一次正则匹配（复用 RegexRuleExecutor，不落结果缓存），
     * 完成后弹「匹配到 N 条」；执行失败弹错误详情。执行期间按钮禁用防并发重复测试。
     */
    private fun runRegexTest() {
        // ////////////////  校验参数：先做与 OK 相同的基础校验  start  ///////////////////
        val validationError = doValidate()
        if (validationError != null) {
            Messages.showErrorDialog(project, validationError.message, "规则测试")
            return
        }
        val rule = buildRule() ?: return
        // ////////////////  校验参数  end  /////////////////////////////////////////////

        // ////////////////  核心逻辑：后台执行 + invokeLater 弹结果  start  ///////////////
        testAction.isEnabled = false
        val submitted = runCatching {
            AppExecutorUtil.getAppExecutorService().submit {
                val items = runCatching { RegexRuleExecutor().execute(project, rule) }
                ApplicationManager.getApplication().invokeLater {
                    testAction.isEnabled = true
                    if (project.isDisposed) return@invokeLater
                    items.fold(
                        onSuccess = { result ->
                            Messages.showInfoMessage(project, "匹配到 ${result.size} 条", "规则测试")
                        },
                        onFailure = { error ->
                            log.warn("规则「${rule.name}」测试执行失败", error)
                            Messages.showErrorDialog(
                                project,
                                "测试失败：${error.message ?: error.javaClass.simpleName}",
                                "规则测试"
                            )
                        }
                    )
                }
            }
        }
        // 提交失败兜底：恢复按钮可用并提示
        if (submitted.isFailure) {
            testAction.isEnabled = true
            log.warn("规则测试任务提交失败", submitted.exceptionOrNull())
            Messages.showErrorDialog(project, "测试任务提交失败", "规则测试")
        }
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////
    }
}
