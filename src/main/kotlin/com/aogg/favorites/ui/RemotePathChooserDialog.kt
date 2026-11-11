package com.aogg.favorites.ui

import com.aogg.favorites.helper.BookmarkIconHelper
import com.aogg.favorites.rules.RuleItem
import com.aogg.favorites.rules.SshDirRuleExecutor
import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * 远端路径浏览选择对话框（1.8.1 远程文件夹/远程文件规则新增）：
 * - 顶部路径框（可手输，回车/「转到」按输入路径列目录）+ 条目列表（目录带 `/` 后缀与文件夹图标，
 *   文件带扩展名图标）；
 * - 导航：双击目录进入、选中文件（fileMode 可双击直接选定）、左侧「上级目录」「刷新」动作；
 * - 列目录经 [ProgressManager.runProcessWithProgressSynchronously] 后台执行（模态进度条），
 *   失败弹错误提示（提示可手输绝对路径），不关闭对话框；
 * - 「选择」：dirMode=选当前目录；fileMode=选当前选中的文件条目（未选中条目时选当前目录）。
 *
 * @param project 项目
 * @param fileMode true=SSH_FILE（选文件）；false=SSH_DIR（选目录）
 * @param initialPath 初始目录路径（已归一）
 * @param lister 列目录函数：输入目录路径，返回子条目（RemoteFileItem.path=子条目完整远端路径）；
 *   失败抛异常（由对话框统一转错误提示）
 */
class RemotePathChooserDialog(
    private val project: Project,
    private val fileMode: Boolean,
    initialPath: String,
    private val lister: (String) -> List<RuleItem.RemoteFileItem>
) : DialogWrapper(project) {

    companion object {
        private val log = Logger.getInstance(RemotePathChooserDialog::class.java)
    }

    /** 当前目录路径框（可手输；回车转到） */
    private val pathField = JBTextField()

    /** 条目列表（当前目录子条目） */
    private val entryList = JBList(emptyList<String>())

    /** 当前目录子条目（与列表条目按下标对应） */
    private var entries: List<RuleItem.RemoteFileItem> = emptyList()

    /** 最终选择的远端路径（OK 后有效） */
    var chosenPath: String = initialPath
        private set

    init {
        title = "选择远端路径"
        setOKButtonText("选择")
        init()
        pathField.text = initialPath
        loadEntries(initialPath)
        pathField.addActionListener { goToTypedPath() }
    }

    /**
     * 布局：North=路径行；Center=条目列表；South=操作提示
     */
    override fun createCenterPanel(): JComponent {
        // ////////////////  路径行：目录路径 + 可手输  start  //////////////////////////////////
        val pathRow = JPanel(BorderLayout(8, 0))
        pathRow.add(JBLabel("目录:"), BorderLayout.WEST)
        pathRow.add(pathField, BorderLayout.CENTER)
        // ////////////////  路径行  end  ////////////////////////////////////////////////////

        // ////////////////  条目列表：单选 + 图标渲染 + 双击导航  start  ///////////////////////
        entryList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        entryList.cellRenderer = object : ColoredListCellRenderer<String>() {
            override fun customizeCellRenderer(
                list: JList<out String>,
                value: String?,
                index: Int,
                selected: Boolean,
                focus: Boolean
            ) {
                val entry = entries.getOrNull(index) ?: return
                if (entry.isDir) {
                    icon = AllIcons.Nodes.Folder
                } else {
                    icon = BookmarkIconHelper.getFileIcon(null, entry.path.substringAfterLast('/'))
                }
                append(displayOf(entry), SimpleTextAttributes.REGULAR_ATTRIBUTES)
            }
        }
        entryList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2) return
                val entry = entries.getOrNull(entryList.selectedIndex) ?: return
                if (entry.isDir) {
                    navigateTo(entry.path)
                } else if (fileMode) {
                    chosenPath = entry.path
                    doOKAction()
                }
            }
        })
        val scroll = JBScrollPane(entryList)
        scroll.preferredSize = Dimension(520, 360)
        // ////////////////  条目列表  end  //////////////////////////////////////////////////

        // ////////////////  组装：路径行 + 列表 + 提示  start  ////////////////////////////////
        val panel = JPanel(BorderLayout(0, 6))
        panel.add(pathRow, BorderLayout.NORTH)
        panel.add(scroll, BorderLayout.CENTER)
        val hint = JBLabel(
            if (fileMode) "双击进入目录；双击文件=选择该文件；「选择」未选文件时选当前目录"
            else "双击进入目录；「选择」选当前目录"
        )
        hint.foreground = JBColor.GRAY
        panel.add(hint, BorderLayout.SOUTH)
        // ////////////////  组装  end  ////////////////////////////////////////////////////

        return panel
    }

    /**
     * 左侧动作：上级目录 / 刷新（DialogWrapper 左侧按钮位；swing AbstractAction 直挂按钮）
     */
    override fun createLeftSideActions(): Array<Action> = arrayOf(
        object : javax.swing.AbstractAction("上级目录") {
            override fun actionPerformed(e: java.awt.event.ActionEvent) {
                navigateTo(parentPathOf(currentPath()))
            }
        },
        object : javax.swing.AbstractAction("刷新") {
            override fun actionPerformed(e: java.awt.event.ActionEvent) {
                loadEntries(currentPath())
            }
        }
    )

    /**
     * OK：按模式确定最终路径（fileMode 优先选中文件条目，否则当前目录）
     */
    override fun doOKAction() {
        // ////////////////  核心逻辑：确定选择结果  start  ///////////////////////////////////
        val selectedFile = entries.getOrNull(entryList.selectedIndex)?.takeIf { !it.isDir }
        chosenPath = SshDirRuleExecutor.normalizeRemotePath(
            if (fileMode && selectedFile != null) selectedFile.path else currentPath()
        )
        // ////////////////  核心逻辑  end  ///////////////////////////////////////////////
        super.doOKAction()
    }

    /**
     * 当前目录路径（取路径框输入，未归一；空输入按根目录）
     */
    private fun currentPath(): String = pathField.text.trim().ifEmpty { "/" }

    /**
     * 按路径框输入转到（回车触发）：归一后列目录
     */
    private fun goToTypedPath() {
        val normalized = SshDirRuleExecutor.normalizeRemotePath(currentPath())
        if (normalized.isNotEmpty()) {
            loadEntries(normalized)
        }
    }

    /**
     * 进入目录（双击/上级目录触发）
     *
     * @param path 目标目录路径
     */
    private fun navigateTo(path: String) {
        pathField.text = path
        loadEntries(path)
    }

    /**
     * 后台列目录并刷新列表（模态进度条；失败弹提示保留原列表，提示可手输绝对路径）
     *
     * @param path 目标目录路径
     */
    private fun loadEntries(path: String) {
        // ////////////////  核心逻辑：模态进度内后台执行 ls，失败提示不关窗  start  /////////////
        // runProcessWithProgressSynchronously(Runnable…) 返回 boolean（取消=false），
        // 列目录结果经闭包变量带出（后台任务在对话框关闭前完成，仅 EDT 读回写值）
        var loaded: Result<List<RuleItem.RemoteFileItem>>? = null
        val confirmed = ProgressManager.getInstance().runProcessWithProgressSynchronously(
            {
                loaded = runCatching { lister(path) }
            },
            "正在读取远端目录…",
            true,
            project
        )
        if (!confirmed) return
        val loadedResult = loaded ?: Result.failure<List<RuleItem.RemoteFileItem>>(
            IllegalStateException("远端目录列取任务未执行")
        )
        loadedResult.fold(
            onSuccess = { items ->
                entries = items
                entryList.setListData(items.map { displayOf(it) }.toTypedArray())
                pathField.text = SshDirRuleExecutor.normalizeRemotePath(path)
            },
            onFailure = { error ->
                log.warn("浏览远端目录失败：$path", error)
                Messages.showErrorDialog(
                    project,
                    "读取远端目录失败：${error.message ?: error.javaClass.simpleName}\n" +
                        "可直接在上方路径框输入远端绝对路径后回车重试",
                    "浏览远端路径"
                )
            }
        )
        // ////////////////  核心逻辑  end  ///////////////////////////////////////////////
    }

    /**
     * 列表显示名（目录带尾部 `/` 提示）
     */
    private fun displayOf(entry: RuleItem.RemoteFileItem): String =
        entry.path.substringAfterLast('/') + if (entry.isDir) "/" else ""

    /**
     * 目录路径的父目录（根目录/家目录的父仍是自身；一级子目录回退到 `/` 或 `~`）
     *
     * @param path 当前路径
     */
    private fun parentPathOf(path: String): String {
        val normalized = SshDirRuleExecutor.normalizeRemotePath(path)
        if (normalized == "/" || normalized == "~") return normalized
        val parent = normalized.substringBeforeLast('/', "")
        return when {
            parent.isEmpty() -> if (normalized.startsWith("/")) "/" else "~"
            parent == "~" -> "~"
            else -> parent
        }
    }
}
