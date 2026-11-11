package com.aogg.favorites.rules

/**
 * 规则结果条目（规则执行产物，树「规则匹配」条目节点数据来源）
 *
 * FileItem 为正则规则产物（可跳转）；RowItem/LineItem 为 SQL/SSH 产物；RemoteFileItem 为
 * 远程文件夹/远程文件规则产物（1.8.1 新增，远端路径条目，不可本地跳转）。
 */
sealed class RuleItem {

    /**
     * 文件/目录条目（正则规则产物）：可跳转
     *
     * @param path 相对项目根路径（'/' 分隔、无前导斜杠）
     * @param isFolder true=目录（跳转时 Project 视图选中），false=文件（跳转时编辑器打开）
     */
    data class FileItem(val path: String, val isFolder: Boolean) : RuleItem()

    /**
     * SQL 行条目
     *
     * @param cells 各列文本（按查询列顺序）
     * @param path 可选跳转路径（sqlPathColumn 指定列的值；非空可跳转）
     */
    data class RowItem(val cells: List<String>, val path: String?) : RuleItem()

    /**
     * SSH 输出行条目（纯展示，不可跳转）
     */
    data class LineItem(val text: String) : RuleItem()

    /**
     * 远端文件/目录条目（SSH_DIR/SSH_FILE 规则产物，1.8.1 新增）：
     * 本地不可跳转；目录条目在树里可懒加载展开（ssh ls 子目录），文件条目可查看内容（ssh cat）
     *
     * @param path 远端路径（'/' 分隔，如 /var/www/app；SSH_DIR/SSH_FILE 根条目即规则 sshRemotePath）
     * @param isDir true=目录（树里带文件夹图标、可展开），false=文件
     */
    data class RemoteFileItem(val path: String, val isDir: Boolean) : RuleItem()

    /**
     * 参与搜索过滤与展示的完整文本（树过滤按此匹配）
     */
    fun searchText(): String = when (this) {
        is FileItem -> path
        is RowItem -> buildString {
            append(cells.joinToString("\t"))
            if (!path.isNullOrBlank()) {
                append('\t').append(path)
            }
        }
        is LineItem -> text
        is RemoteFileItem -> path
    }
}
