package com.aogg.favorites.ui

import com.aogg.favorites.helper.RulePasswordStore
import com.aogg.favorites.model.MatchRule
import com.aogg.favorites.model.RegexTarget
import com.aogg.favorites.model.RuleType
import com.aogg.favorites.rules.RegexRuleExecutor
import com.aogg.favorites.rules.RegexRuleMatcher
import com.aogg.favorites.rules.RuleExecutor
import com.aogg.favorites.rules.SqlRuleExecutor
import com.aogg.favorites.rules.SshConfigReader
import com.aogg.favorites.rules.SshDirRuleExecutor
import com.aogg.favorites.rules.SshRuleExecutor
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
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.util.UUID
import java.util.regex.Pattern
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants

/**
 * 规则类型下拉项（data class：与下拉模型条目值相等，选中回退可命中）
 *
 * @param type 规则类型（REGEX/SQL/SSH/SSH_DIR/SSH_FILE 各执行器均已注册，均可选）
 */
private data class RuleTypeItem(val type: RuleType) {
    override fun toString(): String = type.label
}

/**
 * 正则匹配目标下拉项
 */
private data class RegexTargetItem(val target: RegexTarget) {
    override fun toString(): String = target.label
}

/**
 * SQL 连接方式下拉项
 *
 * @param useIdeDatasource true=IDE 数据源；false=手动连接
 */
private data class SqlModeItem(val useIdeDatasource: Boolean) {
    override fun toString(): String = if (useIdeDatasource) "IDE 数据源" else "手动连接"
}

/**
 * SSH 主机来源下拉项
 *
 * @param useExistingConfig true=现有 SSH 配置（别名下拉）；false=手动填写
 */
private data class SshModeItem(val useExistingConfig: Boolean) {
    override fun toString(): String = if (useExistingConfig) "现有 SSH 配置" else "手动填写"
}

/**
 * 规则编辑对话框（新建与编辑共用，1.4.0；1.8.1 新增远程文件夹/远程文件类型）：
 * - 类型下拉 REGEX/SQL/SSH/SSH_DIR/SSH_FILE 切换表单（SSH 系三类共用一张主机来源表单卡片，
 *   按类型切换下方负载行：SSH=远程命令多行；SSH_DIR/SSH_FILE=远程路径 + 浏览…）；
 * - REGEX 表单：名称、根目录（空=整项目，带提示）、正则（多行）、匹配目标下拉、忽略大小写、上限；
 * - SQL 表单：连接方式（IDE 数据源=数据源名下拉，后台异步读取、读失败给提示可手输 /
 *   手动连接=JDBC URL、用户名、密码）+ SQL 多行 + 路径列(可选) + 上限；
 * - SSH 系表单：主机来源（现有 SSH 配置=别名下拉，后台异步读取、读失败给提示可手输 /
 *   手动填写=主机、端口、用户名）+ 远程命令/远程路径 + 上限；
 *   SSH_DIR/SSH_FILE 的「浏览…」后台 ssh ls 列远端目录弹选择器选文件夹/文件
 *   （[RemotePathChooserDialog]，失败给提示可手输绝对路径）；
 * - 数据源/SSH 别名列表各自独立后台任务读取（1.8.1 修复：原先串在同一个任务里，数据源枚举
 *   慢/阻塞会拖死 SSH 别名下拉，提示一直停在「正在读取…」），任何一方失败不影响另一方；
 * - 「测试」按钮：用当前表单值构造临时 MatchRule 直接调对应执行器（后台线程，不落结果缓存），
 *   完成弹「匹配到 N 条 / 查询到 N 行 / 输出 N 行 / 读取到 N 项」；执行期间按钮禁用；
 * - OK 校验：名称非空；按类型校验（SQL：数据源名或 JDBC URL 非空+SQL 非空；
 *   SSH：别名或手动主机非空+命令非空；SSH_DIR/SSH_FILE：别名或手动主机非空+远程路径非空；
 *   上限 1..10000）；
 * - 密码安全：手动连接密码经 PasswordSafe 存取（[RulePasswordStore]），对话框不回显已存值，
 *   编辑时留空=保留原密码（不覆盖），非空=覆盖写入；XML 永不落明文。
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

        /** 结果条数上限边界（与各执行器硬上限一致） */
        private const val MAX_RESULT_LIMIT = RegexRuleExecutor.MAX_RESULT_LIMIT

        /** SSH 系（SSH/SSH_DIR/SSH_FILE）共用表单卡片的 card key */
        private const val SSH_FAMILY_CARD_KEY = "SSH_FAMILY"

        /** SSH 系负载行卡片 key：远程命令 */
        private const val PAYLOAD_COMMAND_KEY = "COMMAND"

        /** SSH 系负载行卡片 key：远程路径 */
        private const val PAYLOAD_PATH_KEY = "PATH"

        /**
         * 规则类型 → 表单卡片 key：REGEX/SQL 各自独立卡片，SSH 系三类共用一张卡片
         */
        private fun cardKeyFor(type: RuleType): String = when (type) {
            RuleType.REGEX -> RuleType.REGEX.name
            RuleType.SQL -> RuleType.SQL.name
            else -> SSH_FAMILY_CARD_KEY
        }
    }

    /** 公共字段：名称 */
    private val nameField = JBTextField()

    /** 公共字段：类型下拉 */
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

    /** SQL 表单：连接方式下拉 */
    private val sqlModeCombo = JComboBox<SqlModeItem>()

    /** SQL 表单：连接方式卡片（IDE 数据源 / 手动连接） */
    private val sqlCardLayout = CardLayout()

    /** SQL 表单：连接方式卡片容器 */
    private val sqlCardPanel = JPanel(sqlCardLayout)

    /** SQL 表单：IDE 数据源名下拉（可手输；异步读取后刷新） */
    private val datasourceCombo = JComboBox<String>()

    /** SQL 表单：数据源下拉提示（读取结果说明） */
    private val sqlDatasourceHint = JBLabel()

    /** SQL 表单：JDBC URL（手动连接） */
    private val jdbcUrlField = JBTextField()

    /** SQL 表单：用户名（手动连接） */
    private val sqlUserField = JBTextField()

    /** SQL 表单：密码（手动连接；不回显已存值，留空=保留原密码） */
    private val sqlPasswordField = JBPasswordField()

    /** SQL 表单：查询语句（多行） */
    private val sqlArea = JBTextArea(6, 44)

    /** SQL 表单：路径列（可选） */
    private val pathColumnField = JBTextField()

    /** SQL 表单：结果上限 */
    private val sqlLimitSpinner = JBIntSpinner(
        existing?.limit?.coerceIn(1, MAX_RESULT_LIMIT) ?: 500, 1, MAX_RESULT_LIMIT, 50
    )

    /** SSH 表单：主机来源下拉 */
    private val sshModeCombo = JComboBox<SshModeItem>()

    /** SSH 表单：主机来源卡片（现有 SSH 配置 / 手动填写） */
    private val sshCardLayout = CardLayout()

    /** SSH 表单：主机来源卡片容器 */
    private val sshCardPanel = JPanel(sshCardLayout)

    /** SSH 表单：主机别名下拉（可手输；异步读取后刷新） */
    private val aliasCombo = JComboBox<String>()

    /** SSH 表单：别名下拉提示（读取结果说明） */
    private val sshAliasHint = JBLabel()

    /** SSH 表单：手动主机 */
    private val sshHostField = JBTextField()

    /** SSH 表单：手动端口 */
    private val sshPortSpinner = JBIntSpinner(
        existing?.sshManualPort?.coerceIn(1, 65535) ?: 22, 1, 65535, 1
    )

    /** SSH 表单：手动用户名 */
    private val sshUserField = JBTextField()

    /** SSH 表单：负载行卡片（远程命令 / 远程路径，按 SSH 系类型切换） */
    private val sshPayloadLayout = CardLayout()

    /** SSH 表单：负载行卡片容器 */
    private val sshPayloadPanel = JPanel(sshPayloadLayout)

    /** SSH 表单：远程命令（多行） */
    private val sshArea = JBTextArea(6, 44)

    /** SSH 表单：远程路径（SSH_DIR/SSH_FILE；远端绝对路径，可手输或「浏览…」选择） */
    private val sshRemotePathField = JBTextField()

    /** SSH 表单：结果上限 */
    private val sshLimitSpinner = JBIntSpinner(
        existing?.limit?.coerceIn(1, MAX_RESULT_LIMIT) ?: 500, 1, MAX_RESULT_LIMIT, 50
    )

    /** 类型切换卡片布局 */
    private val cardLayout = CardLayout()

    /** 类型切换卡片容器 */
    private val cardPanel = JPanel(cardLayout)

    /** 测试按钮（执行期间禁用，防并发重复测试） */
    private val testAction = object : AbstractAction("测试") {
        override fun actionPerformed(e: java.awt.event.ActionEvent?) {
            runTest()
        }
    }

    init {
        // ////////////////  类型下拉：五类均可选  start  //////////////////////////////////
        typeCombo.model = DefaultComboBoxModel(
            arrayOf(
                RuleTypeItem(RuleType.REGEX),
                RuleTypeItem(RuleType.SQL),
                RuleTypeItem(RuleType.SSH),
                RuleTypeItem(RuleType.SSH_DIR),
                RuleTypeItem(RuleType.SSH_FILE)
            )
        )
        typeCombo.addActionListener {
            val item = typeCombo.selectedItem as? RuleTypeItem ?: return@addActionListener
            // SSH 系三类共用一张表单卡片；负载行按类型切换（SSH=远程命令；SSH_DIR/SSH_FILE=远程路径）
            cardLayout.show(cardPanel, cardKeyFor(item.type))
            sshPayloadLayout.show(
                sshPayloadPanel,
                if (item.type == RuleType.SSH) PAYLOAD_COMMAND_KEY else PAYLOAD_PATH_KEY
            )
        }
        typeCombo.selectedItem = RuleTypeItem(RuleType.REGEX)
        // ////////////////  类型下拉  end  ////////////////////////////////////////////

        // ////////////////  匹配目标下拉  start  ///////////////////////////////////////
        targetCombo.model = DefaultComboBoxModel(
            RegexTarget.entries.map { RegexTargetItem(it) }.toTypedArray()
        )
        targetCombo.selectedItem = RegexTargetItem(RegexTarget.ALL)
        // ////////////////  匹配目标下拉  end  ////////////////////////////////////////

        // ////////////////  SQL/SSH 模式下拉  start  ///////////////////////////////////
        sqlModeCombo.model = DefaultComboBoxModel(
            arrayOf(SqlModeItem(true), SqlModeItem(false))
        )
        sqlModeCombo.selectedItem = SqlModeItem(true)
        sqlModeCombo.addActionListener {
            val item = sqlModeCombo.selectedItem as? SqlModeItem ?: return@addActionListener
            sqlCardLayout.show(sqlCardPanel, sqlModeKey(item))
        }
        sshModeCombo.model = DefaultComboBoxModel(
            arrayOf(SshModeItem(true), SshModeItem(false))
        )
        sshModeCombo.selectedItem = SshModeItem(true)
        sshModeCombo.addActionListener {
            val item = sshModeCombo.selectedItem as? SshModeItem ?: return@addActionListener
            sshCardLayout.show(sshCardPanel, sshModeKey(item))
        }
        // ////////////////  SQL/SSH 模式下拉  end  ////////////////////////////////////

        title = if (existing == null) "新建规则" else "编辑规则"
        init()
        existing?.let { fillForm(it) }
        // 数据源/SSH 别名列表读取放后台（读 IDE 配置可能慢），先显示空+提示，回来后刷新下拉
        scheduleOptionLoad()
    }

    /** SQL 连接方式卡片 key */
    private fun sqlModeKey(item: SqlModeItem): String = if (item.useIdeDatasource) "IDE" else "MANUAL"

    /** SSH 主机来源卡片 key */
    private fun sshModeKey(item: SshModeItem): String = if (item.useExistingConfig) "EXISTING" else "MANUAL"

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

        // ////////////////  类型表单卡片  start  ///////////////////////////////////////
        cardPanel.add(buildRegexFormPanel(), RuleType.REGEX.name)
        cardPanel.add(buildSqlFormPanel(), RuleType.SQL.name)
        cardPanel.add(buildSshFamilyFormPanel(), SSH_FAMILY_CARD_KEY)
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
     * SQL 表单：连接方式（IDE 数据源 / 手动连接）+ SQL 多行 + 路径列(可选) + 上限
     */
    private fun buildSqlFormPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        val constraints = GridBagConstraints().apply {
            insets = Insets(4, 10, 4, 10)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
        }

        // 连接方式
        constraints.gridx = 0
        constraints.gridy = 0
        constraints.weightx = 0.0
        panel.add(JBLabel("连接方式:"), constraints)
        constraints.gridx = 1
        constraints.weightx = 1.0
        panel.add(sqlModeCombo, constraints)

        // 连接方式卡片（IDE 数据源 / 手动连接）
        sqlCardPanel.add(buildSqlIdeDatasourceCard(), "IDE")
        sqlCardPanel.add(buildSqlManualCard(), "MANUAL")
        constraints.gridx = 0
        constraints.gridy = 1
        constraints.gridwidth = 2
        panel.add(sqlCardPanel, constraints)
        constraints.gridwidth = 1

        // SQL（多行）
        sqlArea.lineWrap = false
        constraints.gridx = 0
        constraints.gridy = 2
        constraints.weightx = 0.0
        panel.add(JBLabel("SQL:"), constraints)
        constraints.gridx = 1
        constraints.weightx = 1.0
        constraints.weighty = 1.0
        constraints.fill = GridBagConstraints.BOTH
        panel.add(JScrollPane(
            sqlArea,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        ), constraints)

        // 路径列（可选）
        constraints.gridy = 3
        constraints.gridx = 0
        constraints.weightx = 0.0
        constraints.weighty = 0.0
        constraints.fill = GridBagConstraints.HORIZONTAL
        panel.add(JBLabel("路径列(可选):"), constraints)
        constraints.gridx = 1
        constraints.weightx = 1.0
        val pathColumnRow = JPanel(BorderLayout(8, 0))
        pathColumnRow.add(pathColumnField, BorderLayout.CENTER)
        val pathColumnHint = JBLabel("该列值作为条目相对路径用于跳转")
        pathColumnHint.foreground = JBColor.GRAY
        pathColumnRow.add(pathColumnHint, BorderLayout.EAST)
        panel.add(pathColumnRow, constraints)

        // 上限
        constraints.gridy = 4
        constraints.gridx = 0
        constraints.weightx = 0.0
        panel.add(JBLabel("上限:"), constraints)
        constraints.gridx = 1
        constraints.weightx = 1.0
        val limitRow = JPanel(BorderLayout(4, 0))
        limitRow.add(JBLabel("行"), BorderLayout.EAST)
        limitRow.add(sqlLimitSpinner, BorderLayout.CENTER)
        panel.add(limitRow, constraints)

        return panel
    }

    /**
     * SQL 连接方式卡片：IDE 数据源（数据源名下拉 + 提示）
     */
    private fun buildSqlIdeDatasourceCard(): JComponent {
        val panel = JPanel(GridBagLayout())
        val constraints = GridBagConstraints().apply {
            insets = Insets(2, 0, 2, 0)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
        }

        // 数据源名下拉（可手输兜底；后台读取后刷新，不丢已选值）
        datasourceCombo.isEditable = true
        datasourceCombo.selectedItem = ""
        constraints.gridx = 0
        constraints.gridy = 0
        constraints.weightx = 0.0
        panel.add(JBLabel("数据源名:"), constraints)
        constraints.gridx = 1
        constraints.weightx = 1.0
        panel.add(datasourceCombo, constraints)

        // 提示（灰色说明；异步读取完成后更新文案）
        sqlDatasourceHint.text = "正在读取 IDE 数据源列表…"
        sqlDatasourceHint.foreground = JBColor.GRAY
        constraints.gridx = 1
        constraints.gridy = 1
        panel.add(sqlDatasourceHint, constraints)

        return panel
    }

    /**
     * SQL 连接方式卡片：手动连接（JDBC URL / 用户名 / 密码）
     */
    private fun buildSqlManualCard(): JComponent {
        val panel = JPanel(GridBagLayout())
        val constraints = GridBagConstraints().apply {
            insets = Insets(2, 0, 2, 0)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
        }

        // JDBC URL
        jdbcUrlField.emptyText.text = "如 jdbc:mysql://127.0.0.1:3306/db"
        constraints.gridx = 0
        constraints.gridy = 0
        constraints.weightx = 0.0
        panel.add(JBLabel("JDBC URL:"), constraints)
        constraints.gridx = 1
        constraints.weightx = 1.0
        panel.add(jdbcUrlField, constraints)

        // 用户名
        constraints.gridx = 0
        constraints.gridy = 1
        constraints.weightx = 0.0
        panel.add(JBLabel("用户名:"), constraints)
        constraints.gridx = 1
        constraints.weightx = 1.0
        panel.add(sqlUserField, constraints)

        // 密码（不回显已存值；留空=编辑时保留原密码）
        sqlPasswordField.echoChar = '•'
        constraints.gridx = 0
        constraints.gridy = 2
        constraints.weightx = 0.0
        panel.add(JBLabel("密码:"), constraints)
        constraints.gridx = 1
        constraints.weightx = 1.0
        val passwordRow = JPanel(BorderLayout(8, 0))
        passwordRow.add(sqlPasswordField, BorderLayout.CENTER)
        val passwordHint = JBLabel("经 PasswordSafe 安全存储；编辑留空=保留原密码")
        passwordHint.foreground = JBColor.GRAY
        passwordRow.add(passwordHint, BorderLayout.EAST)
        panel.add(passwordRow, constraints)

        return panel
    }

    /**
     * SSH 系表单（SSH/SSH_DIR/SSH_FILE 共用，1.8.1 改造）：
     * 主机来源（现有 SSH 配置 / 手动填写）+ 负载行卡片（SSH=远程命令多行；
     * SSH_DIR/SSH_FILE=远程路径 + 浏览…）+ 上限
     */
    private fun buildSshFamilyFormPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        val constraints = GridBagConstraints().apply {
            insets = Insets(4, 10, 4, 10)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
        }

        // 主机来源
        constraints.gridx = 0
        constraints.gridy = 0
        constraints.weightx = 0.0
        panel.add(JBLabel("主机来源:"), constraints)
        constraints.gridx = 1
        constraints.weightx = 1.0
        panel.add(sshModeCombo, constraints)

        // 主机来源卡片（现有 SSH 配置 / 手动填写）
        sshCardPanel.add(buildSshExistingCard(), "EXISTING")
        sshCardPanel.add(buildSshManualCard(), "MANUAL")
        constraints.gridx = 0
        constraints.gridy = 1
        constraints.gridwidth = 2
        panel.add(sshCardPanel, constraints)
        constraints.gridwidth = 1

        // 负载行卡片：SSH=远程命令多行；SSH_DIR/SSH_FILE=远程路径 + 浏览…
        sshPayloadPanel.add(buildSshCommandPayload(), PAYLOAD_COMMAND_KEY)
        sshPayloadPanel.add(buildSshRemotePathPayload(), PAYLOAD_PATH_KEY)
        constraints.gridx = 0
        constraints.gridy = 2
        constraints.gridwidth = 2
        constraints.weighty = 1.0
        constraints.fill = GridBagConstraints.BOTH
        panel.add(sshPayloadPanel, constraints)
        constraints.gridwidth = 1
        constraints.weighty = 0.0
        constraints.fill = GridBagConstraints.HORIZONTAL

        // 输出说明 + 上限（同一行）
        constraints.gridy = 3
        constraints.gridx = 0
        constraints.weightx = 0.0
        panel.add(JBLabel("上限:"), constraints)
        constraints.gridx = 1
        constraints.weightx = 1.0
        val limitRow = JPanel(BorderLayout(4, 0))
        val sshHint = JBLabel("v1 走本机 ssh 命令，仅支持密钥/agent 认证")
        sshHint.foreground = JBColor.GRAY
        limitRow.add(sshHint, BorderLayout.CENTER)
        val spinnerRow = JPanel(BorderLayout(4, 0))
        spinnerRow.add(JBLabel("行"), BorderLayout.EAST)
        spinnerRow.add(sshLimitSpinner, BorderLayout.CENTER)
        limitRow.add(spinnerRow, BorderLayout.EAST)
        panel.add(limitRow, constraints)

        return panel
    }

    /**
     * 负载行卡片：远程命令（SSH 类型；多行文本整体作为单个 ssh 参数下发）
     */
    private fun buildSshCommandPayload(): JComponent {
        val panel = JPanel(GridBagLayout())
        val constraints = GridBagConstraints().apply {
            insets = Insets(4, 0, 4, 0)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
        }

        // 远程命令（多行）
        sshArea.lineWrap = false
        constraints.gridx = 0
        constraints.gridy = 0
        constraints.weightx = 0.0
        panel.add(JBLabel("远程命令:"), constraints)
        constraints.gridx = 1
        constraints.weightx = 1.0
        constraints.weighty = 1.0
        constraints.fill = GridBagConstraints.BOTH
        panel.add(JScrollPane(
            sshArea,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        ), constraints)

        return panel
    }

    /**
     * 负载行卡片：远程路径（SSH_DIR/SSH_FILE 类型；可手输远端绝对路径或「浏览…」后台 ls 选择）
     */
    private fun buildSshRemotePathPayload(): JComponent {
        val panel = JPanel(GridBagLayout())
        val constraints = GridBagConstraints().apply {
            insets = Insets(4, 0, 4, 0)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
        }

        // 远程路径 + 浏览…（同一行）
        sshRemotePathField.emptyText.text = "远端路径，如 /var/www 或 /var/www/app；支持以 ~ 开头"
        constraints.gridx = 0
        constraints.gridy = 0
        constraints.weightx = 0.0
        panel.add(JBLabel("远程路径:"), constraints)
        constraints.gridx = 1
        constraints.weightx = 1.0
        val pathRow = JPanel(BorderLayout(8, 0))
        pathRow.add(sshRemotePathField, BorderLayout.CENTER)
        val browseAction = object : AbstractAction("浏览…") {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                browseRemotePath()
            }
        }
        pathRow.add(javax.swing.JButton(browseAction), BorderLayout.EAST)
        panel.add(pathRow, constraints)

        // 提示（灰色说明）
        constraints.gridx = 1
        constraints.gridy = 1
        val pathHint = JBLabel("「浏览…」后台 ssh ls 列远端目录选择；失败可直接手输绝对路径")
        pathHint.foreground = JBColor.GRAY
        panel.add(pathHint, constraints)

        return panel
    }

    /**
     * SSH 主机来源卡片：现有 SSH 配置（别名下拉 + 提示）
     */
    private fun buildSshExistingCard(): JComponent {
        val panel = JPanel(GridBagLayout())
        val constraints = GridBagConstraints().apply {
            insets = Insets(2, 0, 2, 0)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
        }

        // 主机别名下拉（可手输兜底；后台读取后刷新，不丢已选值）
        aliasCombo.isEditable = true
        aliasCombo.selectedItem = ""
        constraints.gridx = 0
        constraints.gridy = 0
        constraints.weightx = 0.0
        panel.add(JBLabel("主机别名:"), constraints)
        constraints.gridx = 1
        constraints.weightx = 1.0
        panel.add(aliasCombo, constraints)

        // 提示（灰色说明；异步读取完成后更新文案）
        sshAliasHint.text = "正在读取 SSH 配置（IDE + ~/.ssh/config）…"
        sshAliasHint.foreground = JBColor.GRAY
        constraints.gridx = 1
        constraints.gridy = 1
        panel.add(sshAliasHint, constraints)

        return panel
    }

    /**
     * SSH 主机来源卡片：手动填写（主机 / 端口 / 用户名）
     */
    private fun buildSshManualCard(): JComponent {
        val panel = JPanel(GridBagLayout())
        val constraints = GridBagConstraints().apply {
            insets = Insets(2, 0, 2, 0)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
        }

        // 主机
        constraints.gridx = 0
        constraints.gridy = 0
        constraints.weightx = 0.0
        panel.add(JBLabel("主机:"), constraints)
        constraints.gridx = 1
        constraints.weightx = 1.0
        panel.add(sshHostField, constraints)

        // 端口
        constraints.gridx = 0
        constraints.gridy = 1
        constraints.weightx = 0.0
        panel.add(JBLabel("端口:"), constraints)
        constraints.gridx = 1
        constraints.weightx = 1.0
        panel.add(sshPortSpinner, constraints)

        // 用户名
        constraints.gridx = 0
        constraints.gridy = 2
        constraints.weightx = 0.0
        panel.add(JBLabel("用户名:"), constraints)
        constraints.gridx = 1
        constraints.weightx = 1.0
        val userRow = JPanel(BorderLayout(8, 0))
        userRow.add(sshUserField, BorderLayout.CENTER)
        val userHint = JBLabel("留空 = 不带 user@ 前缀")
        userHint.foreground = JBColor.GRAY
        userRow.add(userHint, BorderLayout.EAST)
        panel.add(userRow, constraints)

        return panel
    }

    override fun getPreferredFocusedComponent(): JComponent = nameField

    /**
     * 动作行：测试 / OK / 取消
     */
    override fun createActions(): Array<Action> = arrayOf(testAction, okAction, cancelAction)

    // ==================== 后台读取数据源/SSH 别名列表 ====================

    /**
     * 后台读取 IDE 数据源名与 SSH 别名列表（1.8.1 拆分入口）：
     * 两者各自独立后台任务（数据源枚举可能因数据库插件类加载慢/阻塞，不能与 SSH 别名串行——
     * 否则别名下拉永远等不到，提示停在「正在读取…」），各自完成后独立刷新各自下拉与提示，
     * 任何一方失败/阻塞不影响另一方。
     */
    private fun scheduleOptionLoad() {
        scheduleDatasourceLoad()
        scheduleAliasLoad()
    }

    /**
     * 后台任务一：读取 IDE 数据源名列表（读 IDE 配置可能慢，不阻塞对话框打开）；
     * 回来后 invokeLater 独立刷新数据源下拉，保留用户已输入/已选值；读失败给空列表+提示文案
     */
    private fun scheduleDatasourceLoad() {
        // ////////////////  核心逻辑：独立后台读取 + invokeLater 独立刷新  start  ///////////////
        val submitted = runCatching {
            AppExecutorUtil.getAppExecutorService().submit {
                val datasourceNames = runCatching { SqlRuleExecutor.listIdeDatasourceNames(project) }
                    .onFailure { log.warn("读取 IDE 数据源列表失败", it) }
                    .getOrDefault(emptyList())
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    refreshDatasourceCombo(datasourceNames)
                }
            }
        }
        submitted.onFailure { log.warn("IDE 数据源列表读取任务提交失败", it) }
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////
    }

    /**
     * 后台任务二：读取 SSH 别名列表（与数据源任务互不影响）；
     * 回来后 invokeLater 独立刷新别名下拉，保留用户已输入/已选值；读失败给空列表+明确原因提示，
     * 不允许提示停在「正在读取…」不更新
     */
    private fun scheduleAliasLoad() {
        // ////////////////  核心逻辑：独立后台读取 + invokeLater 独立刷新  start  ///////////////
        val submitted = runCatching {
            AppExecutorUtil.getAppExecutorService().submit {
                val sshAliases = runCatching { SshConfigReader.listHosts(project).map { it.alias } }
                    .onFailure { log.warn("读取 SSH 配置列表失败", it) }
                    .getOrDefault(emptyList())
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    refreshAliasCombo(sshAliases)
                }
            }
        }
        submitted.onFailure {
            // 提交失败也要终结「正在读取…」提示（明确原因），避免一直停留在读取中
            log.warn("SSH 别名列表读取任务提交失败", it)
            if (!project.isDisposed) {
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    sshAliasHint.text = "SSH 别名列表读取任务提交失败，可手输别名或改手动填写"
                }
            }
        }
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////
    }

    /**
     * 刷新数据源名下拉（保留用户已输入值；空列表给提示文案）
     */
    private fun refreshDatasourceCombo(names: List<String>) {
        val previous = datasourceCombo.selectedItem as? String
        datasourceCombo.model = DefaultComboBoxModel(names.toTypedArray())
        if (!previous.isNullOrBlank()) {
            datasourceCombo.selectedItem = previous
        }
        sqlDatasourceHint.text = if (names.isEmpty()) {
            "未读取到 IDE 数据源（Database Tools 未启用或读取失败），可手输名称或改用手动连接"
        } else {
            "已读取到 ${names.size} 个 IDE 数据源"
        }
    }

    /**
     * 刷新 SSH 别名下拉（保留用户已输入值；空列表给提示文案）
     */
    private fun refreshAliasCombo(aliases: List<String>) {
        val previous = aliasCombo.selectedItem as? String
        aliasCombo.model = DefaultComboBoxModel(aliases.toTypedArray())
        if (!previous.isNullOrBlank()) {
            aliasCombo.selectedItem = previous
        }
        sshAliasHint.text = if (aliases.isEmpty()) {
            "未读取到 SSH 主机别名（IDE SSH 配置为空/读取失败，且 ~/.ssh/config 不存在或无 Host；" +
                "已支持 Include 引用文件），可手输别名或改手动填写"
        } else {
            "已读取到 ${aliases.size} 个 SSH 主机别名"
        }
    }

    // ==================== 数据回填与产出 ====================

    /**
     * 用既有规则回填表单（编辑入口；按类型只回填对应表单，不覆盖其它类型字段）
     */
    private fun fillForm(rule: MatchRule) {
        // ////////////////  公共字段：名称  start  /////////////////////////////////////
        nameField.text = rule.name
        // ////////////////  公共字段  end  ////////////////////////////////////////////

        // ////////////////  按类型回填对应表单  start  ///////////////////////////////////
        when (rule.type) {
            RuleType.REGEX -> {
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
            RuleType.SQL -> {
                if (rule.sqlDatasourceName.isNotBlank()) {
                    sqlModeCombo.selectedItem = SqlModeItem(true)
                    datasourceCombo.selectedItem = rule.sqlDatasourceName
                } else {
                    sqlModeCombo.selectedItem = SqlModeItem(false)
                    jdbcUrlField.text = rule.sqlJdbcUrl
                    sqlUserField.text = rule.sqlUser
                    // 密码不回显：留空 = 保留 PasswordSafe 中的原密码
                }
                sqlArea.text = rule.sqlQuery
                pathColumnField.text = rule.sqlPathColumn
                sqlLimitSpinner.number = rule.limit.coerceIn(1, MAX_RESULT_LIMIT)
            }
            RuleType.SSH, RuleType.SSH_DIR, RuleType.SSH_FILE -> {
                if (rule.sshHostAlias.isNotBlank()) {
                    sshModeCombo.selectedItem = SshModeItem(true)
                    aliasCombo.selectedItem = rule.sshHostAlias
                } else {
                    sshModeCombo.selectedItem = SshModeItem(false)
                    sshHostField.text = rule.sshManualHost
                    sshPortSpinner.number = rule.sshManualPort.coerceIn(1, 65535)
                    sshUserField.text = rule.sshManualUser
                }
                if (rule.type == RuleType.SSH) {
                    sshArea.text = rule.sshCommand
                } else {
                    sshRemotePathField.text = rule.sshRemotePath
                }
                sshLimitSpinner.number = rule.limit.coerceIn(1, MAX_RESULT_LIMIT)
            }
        }
        // ////////////////  按类型回填  end  //////////////////////////////////////////

        // ////////////////  类型下拉选中（触发卡片切换）  start  ///////////////////////////
        typeCombo.selectedItem = RuleTypeItem(rule.type)
        // ////////////////  类型下拉选中  end  ////////////////////////////////////////
    }

    /** 当前选中的规则类型 */
    private fun selectedType(): RuleType =
        (typeCombo.selectedItem as? RuleTypeItem)?.type ?: RuleType.REGEX

    /** SQL 表单当前是否为 IDE 数据源模式 */
    private fun isSqlIdeMode(): Boolean =
        (sqlModeCombo.selectedItem as? SqlModeItem)?.useIdeDatasource ?: true

    /** SSH 表单当前是否为现有配置模式 */
    private fun isSshExistingMode(): Boolean =
        (sshModeCombo.selectedItem as? SshModeItem)?.useExistingConfig ?: true

    /**
     * 「浏览…」：按当前表单主机配置后台 ssh ls 列远端目录，弹选择器选文件夹/文件；
     * 主机未填给就地提示；列目录失败由选择器弹错误提示（可关闭后手输绝对路径）
     */
    private fun browseRemotePath() {
        // ////////////////  校验参数：主机配置非空（别名或手填）  start  ///////////////////////
        if (isSshExistingMode() && comboText(aliasCombo) == null) {
            Messages.showInfoMessage(project, "请先选择或输入主机别名，再浏览远端路径", "浏览远端路径")
            return
        }
        if (!isSshExistingMode() && sshHostField.text.isBlank()) {
            Messages.showInfoMessage(project, "请先填写主机，再浏览远端路径", "浏览远端路径")
            return
        }
        // ////////////////  校验参数  end  /////////////////////////////////////////////

        // ////////////////  准备数据：临时规则承载主机配置（不走 buildRule，名称可暂空）  start  //
        val tempRule = MatchRule(
            id = "browse",
            name = "browse",
            type = selectedType(),
            sshHostAlias = if (isSshExistingMode()) comboText(aliasCombo).orEmpty() else "",
            sshManualHost = if (!isSshExistingMode()) sshHostField.text.trim() else "",
            sshManualPort = if (!isSshExistingMode()) sshPortSpinner.number else 22,
            sshManualUser = if (!isSshExistingMode()) sshUserField.text.trim() else "",
            sshRemotePath = sshRemotePathField.text
        )
        // ////////////////  准备数据  end  /////////////////////////////////////////////

        // ////////////////  核心逻辑：初始目录=当前输入（文件则退到其父目录；空=~ 家目录）  start //
        val normalized = SshDirRuleExecutor.normalizeRemotePath(sshRemotePathField.text)
        val initialDir = when {
            normalized.isEmpty() -> "~"
            selectedType() == RuleType.SSH_FILE && !normalized.endsWith("/") -> parentRemotePath(normalized)
            else -> normalized
        }
        // ////////////////  核心逻辑  end  /////////////////////////////////////////////

        // ////////////////  返回：弹选择器，OK 回填路径（保留用户手输格式）  start  ///////////////
        val dialog = RemotePathChooserDialog(
            project = project,
            fileMode = selectedType() == RuleType.SSH_FILE,
            initialPath = initialDir,
            lister = { path -> SshDirRuleExecutor.listRemoteDir(project, tempRule, path) }
        )
        if (dialog.showAndGet()) {
            sshRemotePathField.text = dialog.chosenPath
        }
        // ////////////////  返回  end  /////////////////////////////////////////////////
    }

    /**
     * 取远端路径的父目录（浏览 SSH_FILE 时从父目录起步；根/家目录的父仍是自身）
     */
    private fun parentRemotePath(path: String): String {
        if (path == "/" || path == "~") return path
        val parent = path.substringBeforeLast('/', "")
        return when {
            parent.isEmpty() -> if (path.startsWith("/")) "/" else "~"
            parent == "~" -> "~"
            else -> parent
        }
    }

    /** 下拉当前文本（含手输值；空白归一为 null） */
    private fun comboText(combo: JComboBox<String>): String? =
        (combo.selectedItem as? String)?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * 按当前表单构建规则（校验失败返回 null）：
     * 新建=生成 UUID、默认启用；编辑=保留 id/createdAt/enabled 与其它类型专属字段；
     * 仅回填当前选中类型的字段（其它类型字段保持既有值）。
     * SQL 手动连接密码非空时随临时规则携带（仅供执行器直读；持久化侧不落盘）。
     */
    fun buildRule(): MatchRule? {
        // ////////////////  校验参数  start  ///////////////////////////////////////////
        if (doValidate() != null) return null
        // ////////////////  校验参数  end  /////////////////////////////////////////////

        // ////////////////  返回：既有规则 copy 或新建  start  /////////////////////////////
        val base = existing ?: MatchRule(
            id = UUID.randomUUID().toString(),
            name = "",
            type = RuleType.REGEX
        )
        return when (selectedType()) {
            RuleType.REGEX -> base.copy(
                name = nameField.text.trim(),
                regexBaseDir = RegexRuleMatcher.normalizeRelativePath(baseDirField.text),
                regexPattern = regexArea.text,
                regexTarget = (targetCombo.selectedItem as? RegexTargetItem)?.target ?: RegexTarget.ALL,
                caseInsensitive = caseBox.isSelected,
                limit = limitSpinner.number.coerceIn(1, MAX_RESULT_LIMIT)
            )
            RuleType.SQL -> base.copy(
                name = nameField.text.trim(),
                sqlDatasourceName = if (isSqlIdeMode()) comboText(datasourceCombo).orEmpty() else "",
                sqlJdbcUrl = if (!isSqlIdeMode()) jdbcUrlField.text.trim() else "",
                sqlUser = if (!isSqlIdeMode()) sqlUserField.text.trim() else "",
                sqlPassword = String(sqlPasswordField.password).takeIf { it.isNotEmpty() }.orEmpty(),
                sqlQuery = sqlArea.text,
                sqlPathColumn = pathColumnField.text.trim(),
                limit = sqlLimitSpinner.number.coerceIn(1, MAX_RESULT_LIMIT)
            )
            RuleType.SSH -> base.copy(
                name = nameField.text.trim(),
                sshHostAlias = if (isSshExistingMode()) comboText(aliasCombo).orEmpty() else "",
                sshManualHost = if (!isSshExistingMode()) sshHostField.text.trim() else "",
                sshManualPort = if (!isSshExistingMode()) sshPortSpinner.number else 22,
                sshManualUser = if (!isSshExistingMode()) sshUserField.text.trim() else "",
                sshCommand = sshArea.text,
                limit = sshLimitSpinner.number.coerceIn(1, MAX_RESULT_LIMIT)
            )
            RuleType.SSH_DIR, RuleType.SSH_FILE -> base.copy(
                name = nameField.text.trim(),
                sshHostAlias = if (isSshExistingMode()) comboText(aliasCombo).orEmpty() else "",
                sshManualHost = if (!isSshExistingMode()) sshHostField.text.trim() else "",
                sshManualPort = if (!isSshExistingMode()) sshPortSpinner.number else 22,
                sshManualUser = if (!isSshExistingMode()) sshUserField.text.trim() else "",
                sshRemotePath = SshDirRuleExecutor.normalizeRemotePath(sshRemotePathField.text),
                limit = sshLimitSpinner.number.coerceIn(1, MAX_RESULT_LIMIT)
            )
        }
        // ////////////////  返回  end  ///////////////////////////////////////////////
    }

    // ==================== 校验与测试 ====================

    /**
     * OK 前校验：名称非空；按类型校验；上限 1..10000
     */
    override fun doValidate(): ValidationInfo? {
        // ////////////////  校验一：名称非空  start  ///////////////////////////////////
        if (nameField.text.isBlank()) {
            return ValidationInfo("名称不能为空", nameField)
        }
        // ////////////////  校验一  end  //////////////////////////////////////////////

        // ////////////////  校验二：按类型校验  start  //////////////////////////////////
        when (selectedType()) {
            RuleType.REGEX -> {
                // 正则可编译（就地提示编译错误）
                try {
                    Pattern.compile(
                        regexArea.text,
                        if (caseBox.isSelected) Pattern.CASE_INSENSITIVE else 0
                    )
                } catch (e: Exception) {
                    return ValidationInfo("正则表达式无法编译：${e.message ?: "未知错误"}", regexArea)
                }
                val limitError = validateLimit(limitSpinner.number, limitSpinner)
                if (limitError != null) return limitError
            }
            RuleType.SQL -> {
                // IDE 数据源模式：数据源名非空；手动模式：JDBC URL 非空
                if (isSqlIdeMode()) {
                    if (comboText(datasourceCombo) == null) {
                        return ValidationInfo("数据源名不能为空", datasourceCombo)
                    }
                } else if (jdbcUrlField.text.isBlank()) {
                    return ValidationInfo("JDBC URL 不能为空", jdbcUrlField)
                }
                if (sqlArea.text.isBlank()) {
                    return ValidationInfo("SQL 不能为空", sqlArea)
                }
                val limitError = validateLimit(sqlLimitSpinner.number, sqlLimitSpinner)
                if (limitError != null) return limitError
            }
            RuleType.SSH, RuleType.SSH_DIR, RuleType.SSH_FILE -> {
                // 现有配置模式：别名非空；手动模式：主机非空（SSH 系共用）
                if (isSshExistingMode()) {
                    if (comboText(aliasCombo) == null) {
                        return ValidationInfo("主机别名不能为空", aliasCombo)
                    }
                } else if (sshHostField.text.isBlank()) {
                    return ValidationInfo("主机不能为空", sshHostField)
                }
                if (selectedType() == RuleType.SSH) {
                    if (sshArea.text.isBlank()) {
                        return ValidationInfo("远程命令不能为空", sshArea)
                    }
                } else {
                    // SSH_DIR/SSH_FILE：远程路径非空（归一后校验，~ 前缀等合法输入不受影响）
                    if (SshDirRuleExecutor.normalizeRemotePath(sshRemotePathField.text).isEmpty()) {
                        return ValidationInfo("远程路径不能为空", sshRemotePathField)
                    }
                }
                val limitError = validateLimit(sshLimitSpinner.number, sshLimitSpinner)
                if (limitError != null) return limitError
            }
        }
        // ////////////////  校验二  end  //////////////////////////////////////////////

        return null
    }

    /**
     * 上限范围校验（1..MAX_RESULT_LIMIT，就地提示）
     */
    private fun validateLimit(limit: Int, component: JComponent): ValidationInfo? {
        if (limit < 1 || limit > MAX_RESULT_LIMIT) {
            return ValidationInfo("上限必须在 1..$MAX_RESULT_LIMIT 之间", component)
        }
        return null
    }

    /**
     * OK：校验通过后，SQL 手动连接密码非空时后台写入 PasswordSafe（留空=保留原密码不覆盖），
     * 写入失败仅记日志不阻塞关闭
     */
    override fun doOKAction() {
        // ////////////////  校验参数：框架已先跑 doValidate，双保险不通过则留在对话框  start  //
        val rule = if (doValidate() == null) buildRule() else null
        if (rule == null) return
        // ////////////////  校验参数  end  /////////////////////////////////////////////

        // ////////////////  SQL 手动连接密码后台落 PasswordSafe  start  ////////////////////
        val password = String(sqlPasswordField.password)
        if (selectedType() == RuleType.SQL && !isSqlIdeMode() && password.isNotEmpty()) {
            val submitted = runCatching {
                AppExecutorUtil.getAppExecutorService().submit {
                    RulePasswordStore.save(rule.id, rule.sqlUser, password)
                }
            }
            submitted.onFailure { log.warn("SQL 规则密码保存任务提交失败", it) }
        }
        // ////////////////  SQL 手动连接密码  end  ////////////////////////////////////

        super.doOKAction()
    }

    /**
     * 「测试」按钮：用当前表单值构造临时 MatchRule 直接调对应类型执行器
     * （后台线程，不落结果缓存），完成弹「匹配到 N 条 / 查询到 N 行 / 输出 N 行」；
     * 执行失败弹错误详情。执行期间按钮禁用防并发重复测试。
     */
    private fun runTest() {
        // ////////////////  校验参数：先做与 OK 相同的基础校验  start  ///////////////////
        val validationError = doValidate()
        if (validationError != null) {
            Messages.showErrorDialog(project, validationError.message, "规则测试")
            return
        }
        val rule = buildRule() ?: return
        val executor: RuleExecutor = when (rule.type) {
            RuleType.REGEX -> RegexRuleExecutor()
            RuleType.SQL -> SqlRuleExecutor()
            RuleType.SSH -> SshRuleExecutor()
            RuleType.SSH_DIR, RuleType.SSH_FILE -> SshDirRuleExecutor()
        }
        // ////////////////  校验参数  end  /////////////////////////////////////////////

        // ////////////////  核心逻辑：后台执行 + invokeLater 弹结果  start  ///////////////
        testAction.isEnabled = false
        val submitted = runCatching {
            AppExecutorUtil.getAppExecutorService().submit {
                val items = runCatching { executor.execute(project, rule) }
                ApplicationManager.getApplication().invokeLater {
                    testAction.isEnabled = true
                    if (project.isDisposed) return@invokeLater
                    items.fold(
                        onSuccess = { result ->
                            val unit = when (rule.type) {
                                RuleType.REGEX -> "匹配到 ${result.size} 条"
                                RuleType.SQL -> "查询到 ${result.size} 行"
                                RuleType.SSH -> "输出 ${result.size} 行"
                                RuleType.SSH_DIR, RuleType.SSH_FILE -> "读取到 ${result.size} 项"
                            }
                            Messages.showInfoMessage(project, unit, "规则测试")
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
