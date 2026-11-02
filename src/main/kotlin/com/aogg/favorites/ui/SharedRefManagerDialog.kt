package com.aogg.favorites.ui

import com.aogg.favorites.helper.BookmarkRefreshHelper
import com.aogg.favorites.helper.SharedRefStorageHelper
import com.aogg.favorites.model.SharedBookmarkStore
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.table.AbstractTableModel

/**
 * 管理共享引用对话框（1.7.0 新增）
 *
 * 表格展示共享仓库中全部集合（勾选=本项目订阅状态）：
 * - 列：订阅（勾选框）/ 共享来源项目名（自己的集合标注「（我的共享）」）/ 项目路径 / 收藏条数 / 更新时间；
 * - OK 应用订阅变更（subscribe/unsubscribe），完成后刷新收藏树与 gutter；
 * - 取消/Esc 不做任何变更。
 *
 * @param project 当前项目（订阅状态按项目级引用存储读写）
 */
class SharedRefManagerDialog(private val project: Project) : DialogWrapper(project) {

    /**
     * 表格行数据：集合 + 本项目是否订阅
     */
    private data class CollectionRow(
        val collectionId: String,
        val ownerProjectName: String,
        val ownerProjectPath: String,
        val bookmarkCount: Int,
        val updatedTime: Long,
        val mine: Boolean,
        var subscribed: Boolean
    )

    /**
     * 表格模型：第 0 列为订阅勾选框（可编辑），其余列只读
     */
    private inner class RefTableModel(rows: List<CollectionRow>) : AbstractTableModel() {

        private val rows = rows.toMutableList()

        /**
         * 读取当前行集合（OK 应用变更用）
         */
        fun allRows(): List<CollectionRow> = rows.toList()

        override fun getRowCount(): Int = rows.size

        override fun getColumnCount(): Int = COLUMN_NAMES.size

        override fun getColumnName(column: Int): String = COLUMN_NAMES[column]

        override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
            0 -> java.lang.Boolean::class.java
            else -> String::class.java
        }

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean =
            columnIndex == 0 && !rows[rowIndex].mine

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = when (columnIndex) {
            0 -> rows[rowIndex].subscribed
            1 -> rows[rowIndex].ownerProjectName + if (rows[rowIndex].mine) "（我的共享）" else ""
            2 -> rows[rowIndex].ownerProjectPath
            3 -> rows[rowIndex].bookmarkCount.toString()
            else -> TIME_FORMAT.format(Date(rows[rowIndex].updatedTime))
        }

        override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
            if (columnIndex != 0) return
            rows[rowIndex].subscribed = aValue == true
            fireTableCellUpdated(rowIndex, columnIndex)
        }
    }

    private val tableModel: RefTableModel

    init {
        title = "管理共享引用"
        setOKButtonText("应用")
        tableModel = buildTableModel()
        init()
    }

    /**
     * 构建表格模型：仓库全部集合 + 本项目当前订阅状态（自己的集合恒可见，勾选仅作展示不可改）
     */
    private fun buildTableModel(): RefTableModel {
        // ////////////////  准备数据：全部集合（含更新时间）+ 本项目订阅集合 id  start  ///////
        val store = SharedBookmarkStore.getInstance()
        val ownId = store.collectionOf(project).id
        val subscribedIds = SharedRefStorageHelper.getInstance(project).state.refs.toHashSet()
        val rows = store.listAllCollections().map { collection ->
            CollectionRow(
                collectionId = collection.id,
                ownerProjectName = collection.ownerProjectName,
                ownerProjectPath = collection.ownerProjectPath,
                bookmarkCount = collection.totalCount(),
                updatedTime = collection.updatedTime,
                mine = collection.id == ownId,
                subscribed = collection.id == ownId || collection.id in subscribedIds
            )
        }
        // ////////////////  准备数据  end  /////////////////////////////////////////////

        // ////////////////  返回  start  //////////////////////////////////////////////
        return RefTableModel(rows)
        // ////////////////  返回  end  ////////////////////////////////////////////////
    }

    /**
     * 对话框内容：集合表格（订阅勾选在首列）
     */
    override fun createCenterPanel(): JComponent {
        // ////////////////  核心逻辑：表格构建与列宽  start  /////////////////////////////
        val table = JBTable(tableModel).apply {
            columnModel.getColumn(0).apply {
                preferredWidth = JBUI.scale(60)
                minWidth = JBUI.scale(60)
                maxWidth = JBUI.scale(80)
                headerValue = "订阅"
            }
            autoResizeMode = javax.swing.JTable.AUTO_RESIZE_LAST_COLUMN
            showVerticalLines = true
        }
        // ////////////////  核心逻辑  end  /////////////////////////////////////////////

        // ////////////////  返回：滚动容器 + 顶部说明  start  ///////////////////////////
        val panel = JPanel(BorderLayout(JBUI.scale(0), JBUI.scale(8)))
        panel.add(
            com.intellij.ui.components.JBLabel(
                "勾选=本项目引用该共享集合（只读展示）；自己的共享恒可见。取消勾选并应用后不再显示。",
                AllIcons.General.Information,
                javax.swing.SwingConstants.LEFT
            ),
            BorderLayout.NORTH
        )
        panel.add(JScrollPane(table), BorderLayout.CENTER)
        return panel
        // ////////////////  返回  end  ////////////////////////////////////////////////
    }

    /**
     * OK 应用订阅变更：与初始订阅状态比对差量（新增=订阅、移除=取消订阅），随后刷新树与 gutter
     */
    override fun doOKAction() {
        // ////////////////  准备数据：差量计算（当前勾选 vs 初始订阅）  start  //////////////
        val store = SharedBookmarkStore.getInstance()
        val refsHelper = SharedRefStorageHelper.getInstance(project)
        val initialSubscribed = refsHelper.state.refs.toHashSet()
        val ownId = store.collectionOf(project).id
        val rows = tableModel.allRows()
        val toSubscribe = rows.filter { it.subscribed && it.collectionId !in initialSubscribed }
        val toUnsubscribe = rows.filter { !it.subscribed && it.collectionId in initialSubscribed && it.collectionId != ownId }
        // ////////////////  准备数据  end  /////////////////////////////////////////////

        // ////////////////  核心逻辑：应用订阅变更（自己的集合不允许被取消，跳过）  start  //
        toSubscribe.forEach { store.subscribe(project, it.collectionId) }
        toUnsubscribe.forEach { store.unsubscribe(project, it.collectionId) }
        // ////////////////  核心逻辑  end  /////////////////////////////////////////////

        // ////////////////  逻辑后：有关闭对话框并刷新界面  start  ///////////////////////
        super.doOKAction()
        if (toSubscribe.isNotEmpty() || toUnsubscribe.isNotEmpty()) {
            BookmarkRefreshHelper.refreshAfterChange(project)
        }
        // ////////////////  逻辑后  end  ///////////////////////////////////////////////
    }

    /**
     * 动作集合（默认 OK/Cancel）
     */
    override fun createActions(): Array<Action> = arrayOf(okAction, cancelAction)

    companion object {
        private val TIME_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        /**
         * 表头列名（订阅/来源项目/项目路径/收藏条数/更新时间）
         */
        private val COLUMN_NAMES = arrayOf("订阅", "共享来源项目", "项目路径", "收藏条数", "更新时间")
    }
}
