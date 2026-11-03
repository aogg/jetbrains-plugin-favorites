package com.aogg.favorites.ui

import com.aogg.favorites.helper.BookmarkRefreshHelper
import com.aogg.favorites.model.SharedBookmarkStore
import com.aogg.favorites.model.SourceProjectStore
import com.intellij.icons.AllIcons
import com.intellij.ide.RecentProjectsManager
import com.intellij.ide.RecentProjectsManagerBase
import com.intellij.ide.RecentProjectManagerState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.table.AbstractTableModel

/**
 * 配置共享项目对话框（1.7.0 为「管理共享引用」订阅集合表格，1.7.1 改版）
 *
 * 列出 IDE 已知的全部项目（[RecentProjectsManager] 历史项目 + 当前打开项目兜底，含已关闭项目），
 * 勾选哪些项目的「项目收藏」在本项目「共享收藏」根下只读展示：
 * - 列：配置（勾选框）/ 项目名 / 项目路径 / 状态（打开中/已关闭）/ 项目收藏条数
 *   （未打开项目解析其 .idea/favorites.xml 统计，文件缺失/解析失败显示 "-"）；
 * - 不能选择当前项目自身（路径规范化后排除，单向选择）；
 * - 列表上方「刷新」按钮重新扫描项目列表与条数；
 * - OK=保存来源项目配置并刷新收藏树；Cancel/Esc 不做任何变更；
 * - 单向只读：仅保存本项目配置，被共享项目无需任何开启动作，来源项目文件绝不回写。
 *
 * 扫描时机：对话框打开与点击刷新时在 EDT 同步扫描（收藏文件小，解析可接受，不额外引入后台时序）。
 *
 * @param project 当前项目
 */
class SharedRefManagerDialog(private val project: Project) : DialogWrapper(project) {

    companion object {
        private val log = Logger.getInstance(SharedRefManagerDialog::class.java)

        /**
         * 表头列名（配置/项目名/项目路径/状态/项目收藏条数）
         */
        private val COLUMN_NAMES = arrayOf("配置", "项目名", "项目路径", "状态", "项目收藏条数")
    }

    /**
     * 表格行数据：一个已知项目 + 本项目是否配置其为共享来源
     *
     * @param projectPath 项目根路径（规范化正斜杠）
     * @param projectName 项目显示名（取路径最后一段）
     * @param isOpen 是否在本 IDE 打开中
     * @param bookmarkCount 项目收藏条数（null=不可解析，显示 "-"）
     * @param selected 是否配置为共享来源
     */
    private data class ProjectRow(
        val projectPath: String,
        val projectName: String,
        val isOpen: Boolean,
        val bookmarkCount: Int?,
        var selected: Boolean
    )

    /**
     * 表格模型：第 0 列为配置勾选框（可编辑），其余列只读
     */
    private inner class ProjectTableModel(rows: List<ProjectRow>) : AbstractTableModel() {

        private val rows = rows.toMutableList()

        /**
         * 读取当前行数据（OK 保存配置用）
         */
        fun allRows(): List<ProjectRow> = rows.toList()

        override fun getRowCount(): Int = rows.size

        override fun getColumnCount(): Int = COLUMN_NAMES.size

        override fun getColumnName(column: Int): String = COLUMN_NAMES[column]

        override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
            0 -> java.lang.Boolean::class.java
            else -> String::class.java
        }

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = columnIndex == 0

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = when (columnIndex) {
            0 -> rows[rowIndex].selected
            1 -> rows[rowIndex].projectName
            2 -> rows[rowIndex].projectPath
            3 -> if (rows[rowIndex].isOpen) "打开中" else "已关闭"
            else -> rows[rowIndex].bookmarkCount?.toString() ?: "-"
        }

        override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
            if (columnIndex != 0) return
            rows[rowIndex].selected = aValue == true
            fireTableCellUpdated(rowIndex, columnIndex)
        }
    }

    private var tableModel: ProjectTableModel

    private var table: JBTable? = null

    init {
        title = "配置共享项目"
        setOKButtonText("保存")
        tableModel = buildTableModel()
        init()
    }

    /**
     * 扫描 IDE 已知全部项目并构建表格模型（当前配置中的来源项目预勾选）
     */
    private fun buildTableModel(): ProjectTableModel {
        // ////////////////  返回  start  //////////////////////////////////////////////
        return ProjectTableModel(scanProjectRows())
        // ////////////////  返回  end  ////////////////////////////////////////////////
    }

    /**
     * 扫描 IDE 已知项目列表（含已关闭项目）：
     * RecentProjectsManager 历史 recentPaths 为准，当前打开项目兜底补入；
     * 排除当前项目自身与空路径；打开中的排前、其余按项目名排序
     */
    private fun scanProjectRows(): List<ProjectRow> {
        // ////////////////  准备数据：IDE 已知全部项目路径 + 当前配置  start  /////////////
        val ownPath = SharedBookmarkStore.normalizeProjectPath(project.basePath ?: "")
        val knownPaths = LinkedHashSet<String>()
        // 2023.3 无 RecentProjectsManagerEx/RecentProjectsManagerState：
        // 实现类 RecentProjectsManagerBase.getState()，state 类型 RecentProjectManagerState（仅 recentPaths）
        val state: RecentProjectManagerState? =
            (RecentProjectsManager.getInstance() as? RecentProjectsManagerBase)?.state
        state?.let { s ->
            s.recentPaths.forEach { knownPaths.add(SharedBookmarkStore.normalizeProjectPath(it)) }
        }
        // 兜底：当前打开项目一定在列表中（历史列表未及时收录新开项目时）
        ProjectManager.getInstanceIfCreated()?.openProjects?.forEach { opened ->
            opened.basePath?.let { knownPaths.add(SharedBookmarkStore.normalizeProjectPath(it)) }
        }
        val configured = SharedBookmarkStore.getInstance().configuredSourceProjects(project)
            .map { SharedBookmarkStore.normalizeProjectPath(it) }.toHashSet()
        // ////////////////  准备数据  end  /////////////////////////////////////////////

        // ////////////////  核心逻辑：逐项目构建行（排除自身/空路径，打开中排前）  start  //
        return knownPaths.filter { it.isNotEmpty() && it != ownPath }
            .map { path ->
                val open = isOpenProject(path)
                ProjectRow(
                    projectPath = path,
                    projectName = path.substringAfterLast('/').ifBlank { path },
                    isOpen = open,
                    bookmarkCount = countBookmarks(path),
                    selected = path in configured
                )
            }
            .sortedWith(compareByDescending<ProjectRow> { it.isOpen }.thenBy { it.projectName.lowercase() })
        // ////////////////  核心逻辑  end  /////////////////////////////////////////////
    }

    /**
     * 判断项目是否在本 IDE 打开中（按规范化根路径与打开项目比对）
     */
    private fun isOpenProject(path: String): Boolean {
        val projectManager = ProjectManager.getInstanceIfCreated() ?: return false
        return projectManager.openProjects.any {
            !it.isDisposed && SharedBookmarkStore.normalizeProjectPath(it.basePath ?: "") == path
        }
    }

    /**
     * 统计项目收藏条数：[SourceProjectStore.countBookmarks]（已打开=其实时总数；
     * 未打开=解析其 .idea/favorites.xml；不可解析返回 null 显示 "-"）。
     * 解析失败原因由 [SourceProjectStore] 记日志，这里不再吞错
     */
    private fun countBookmarks(path: String): Int? =
        SourceProjectStore.getInstance().countBookmarks(path)

    /**
     * 对话框内容：顶部说明+刷新按钮，中部项目表格
     */
    override fun createCenterPanel(): JComponent {
        // ////////////////  核心逻辑：表格构建与列宽  start  /////////////////////////////
        val table = JBTable(tableModel).apply {
            columnModel.getColumn(0).apply {
                preferredWidth = JBUI.scale(56)
                minWidth = JBUI.scale(56)
                maxWidth = JBUI.scale(70)
                headerValue = "配置"
            }
            columnModel.getColumn(1).preferredWidth = JBUI.scale(150)
            columnModel.getColumn(2).preferredWidth = JBUI.scale(300)
            columnModel.getColumn(3).preferredWidth = JBUI.scale(80)
            columnModel.getColumn(4).preferredWidth = JBUI.scale(100)
            setShowGrid(true)
        }
        this.table = table
        // ////////////////  核心逻辑  end  /////////////////////////////////////////////

        // ////////////////  顶部：说明 + 刷新按钮  start  ///////////////////////////////
        val northPanel = JPanel(BorderLayout())
        northPanel.add(
            JBLabel(
                "勾选=本项目「共享收藏」只读显示该项目收藏（单向选择，来源项目无需开启，且不能选择当前项目自身）。" +
                    "列表为 IDE 全部已知项目（含已关闭）。",
                AllIcons.General.Information,
                javax.swing.SwingConstants.LEFT
            ),
            BorderLayout.CENTER
        )
        val refreshButton = javax.swing.JButton("刷新", AllIcons.Actions.Refresh)
        refreshButton.toolTipText = "重新扫描项目列表与收藏条数"
        refreshButton.addActionListener {
            // 刷新：重新扫描并整体替换模型（保留对话框打开状态）
            val newModel = buildTableModel()
            tableModel = newModel
            table.model = newModel
        }
        val refreshPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0))
        refreshPanel.add(refreshButton)
        northPanel.add(refreshPanel, BorderLayout.EAST)
        // ////////////////  顶部  end  ////////////////////////////////////////////////

        // ////////////////  返回：面板组装  start  /////////////////////////////////////
        val panel = JPanel(BorderLayout(JBUI.scale(0), JBUI.scale(8)))
        panel.add(northPanel, BorderLayout.NORTH)
        panel.add(JScrollPane(table), BorderLayout.CENTER)
        panel.preferredSize = JBUI.size(760, 420)
        return panel
        // ////////////////  返回  end  ////////////////////////////////////////////////
    }

    /**
     * OK 保存来源项目配置并刷新收藏树：
     * 整体覆盖写本项目配置（内部已规范化并强制排除当前项目自身），随后刷新树与 gutter
     */
    override fun doOKAction() {
        // ////////////////  准备数据：当前勾选的来源项目路径  start  //////////////////////
        val selectedPaths = tableModel.allRows().filter { it.selected }.map { it.projectPath }
        // ////////////////  准备数据  end  /////////////////////////////////////////////

        // ////////////////  核心逻辑：保存配置  start  ///////////////////////////////////
        SharedBookmarkStore.getInstance().setConfiguredSourceProjects(project, selectedPaths)
        // ////////////////  核心逻辑  end  /////////////////////////////////////////////

        // ////////////////  逻辑后：关闭对话框并刷新收藏树  start  ///////////////////////
        super.doOKAction()
        BookmarkRefreshHelper.refreshAfterChange(project)
        log.info("共享来源项目配置已保存：${selectedPaths.size} 项（项目=${project.name}）")
        // ////////////////  逻辑后  end  ///////////////////////////////////////////////
    }

    /**
     * 动作集合（默认 OK/Cancel）
     */
    override fun createActions(): Array<Action> = arrayOf(okAction, cancelAction)
}
