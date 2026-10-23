package com.aogg.favorites.model

/**
 * 规则类型（1.4.0 规则匹配）
 *
 * REGEX 本步已实现（rules/RegexRuleExecutor）；SQL/SSH 本步仅预留数据结构与 UI 位置，执行器下一步提供。
 */
enum class RuleType(val label: String) {

    /**
     * 正则匹配：对相对项目根的路径做正则 find()
     */
    REGEX("正则匹配"),

    /**
     * SQL 查询（开发中，执行器下一步提供）
     */
    SQL("SQL 查询"),

    /**
     * SSH 命令（开发中，执行器下一步提供）
     */
    SSH("SSH 命令");
}

/**
 * 正则匹配目标（按文件系统条目类型过滤）
 */
enum class RegexTarget(val label: String) {

    /**
     * 仅文件
     */
    FILE("仅文件"),

    /**
     * 仅目录
     */
    FOLDER("仅目录"),

    /**
     * 文件与目录
     */
    ALL("文件与目录");

    companion object {
        /**
         * 按名称安全解析匹配目标（持久化字符串 → 枚举）
         *
         * @param name 匹配目标名称（FILE/FOLDER/ALL）
         * @return 对应枚举；未知/空值保守回退为 ALL
         */
        @JvmStatic
        fun fromNameOrDefault(name: String?): RegexTarget =
            entries.firstOrNull { it.name == name } ?: ALL
    }
}

/**
 * 匹配规则数据模型（树第三根节点「规则匹配」的数据来源）
 *
 * @param id 规则唯一标识（UUID）
 * @param name 规则名（树节点主文本）
 * @param type 规则类型
 * @param enabled 是否启用（禁用规则不参与 refreshAll，树中灰显「（已禁用）」）
 * @param createdAt 创建时间戳
 * @param regexBaseDir REGEX：规则根目录（相对项目根，'/' 分隔，空=整项目）
 * @param regexPattern REGEX：Java 正则（对相对项目根路径做 find()，路径 '/' 分隔、无前导斜杠）
 * @param regexTarget REGEX：匹配目标过滤（文件/目录/全部）
 * @param caseInsensitive REGEX：忽略大小写（RegexOption.IGNORE_CASE）
 * @param sqlDatasourceName SQL：IDE 数据源名（空=用手动连接；下一步使用）
 * @param sqlJdbcUrl SQL：手动连接 JDBC URL（下一步使用）
 * @param sqlUser SQL：手动连接用户名（下一步使用）
 * @param sqlPassword SQL：手动连接密码（仅运行期使用；持久化走 PasswordSafe，下一步处理，XML 里不存明文）
 * @param sqlQuery SQL：查询语句（下一步使用）
 * @param sqlPathColumn SQL：可选，该列值作为相对路径用于跳转（下一步使用）
 * @param sshHostAlias SSH：IDE SSH 配置或 ~/.ssh/config 的 Host 别名（空=手填；下一步使用）
 * @param sshManualHost SSH：手动主机（下一步使用）
 * @param sshManualPort SSH：手动端口（下一步使用）
 * @param sshManualUser SSH：手动用户名（下一步使用）
 * @param sshCommand SSH：待执行命令（下一步使用）
 * @param limit 结果条数上限（1..10000，各类执行器通用）
 */
data class MatchRule(
    val id: String,
    val name: String,
    val type: RuleType,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    // REGEX
    val regexBaseDir: String = "",
    val regexPattern: String = "",
    val regexTarget: RegexTarget = RegexTarget.ALL,
    val caseInsensitive: Boolean = false,
    // SQL（本步只留字段）
    val sqlDatasourceName: String = "",
    val sqlJdbcUrl: String = "",
    val sqlUser: String = "",
    val sqlPassword: String = "",
    val sqlQuery: String = "",
    val sqlPathColumn: String = "",
    // SSH（本步只留字段）
    val sshHostAlias: String = "",
    val sshManualHost: String = "",
    val sshManualPort: Int = 22,
    val sshManualUser: String = "",
    val sshCommand: String = "",
    val limit: Int = 500
)
