package com.aogg.favorites.helper

import com.aogg.favorites.model.MatchRule
import com.aogg.favorites.model.RegexTarget
import com.aogg.favorites.model.RuleType
import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * 匹配规则持久化存储助手（项目级，1.4.0 规则匹配新增）
 *
 * 存储位置：随项目保存到 .idea/favorites.rules.xml（组件名 FavoritesMatchRules），
 * 模式与 [BookmarkStorageHelper] 一致：State 用 XmlSerializer 友好的可变 bean 列表存规则，
 * 规则增删改后整体重写 state.rules 即可标记变更并持久化。
 *
 * 兼容与安全约定：
 * - 未知规则类型/匹配目标（跨版本脏数据）加载时保守回退（REGEX/ALL），不抛异常；
 * - sqlPassword 刻意不做持久化字段（运行期字段，下一步接入 PasswordSafe，XML 里不落明文）。
 */
@State(
    name = "FavoritesMatchRules",
    storages = [Storage(value = "favorites.rules.xml", roamingType = RoamingType.DISABLED)]
)
@Service(Service.Level.PROJECT)
class RuleStorageHelper : PersistentStateComponent<RuleStorageHelper.State> {

    /**
     * 根 State：规则 bean 列表
     */
    data class State(
        var rules: MutableList<RuleData> = mutableListOf()
    ) {
        // 空构造函数用于序列化
        constructor() : this(mutableListOf())
    }

    /**
     * 规则数据（用于序列化；与 MatchRule 字段一一对应，除 sqlPassword 外全部持久化）
     * 全字段默认值：无参构造由编译器合成，满足 XmlSerializer 反序列化要求
     */
    data class RuleData(
        var id: String = "",
        var name: String = "",
        var type: String = RuleType.REGEX.name,
        var enabled: Boolean = true,
        var createdAt: Long = 0L,
        // REGEX
        var regexBaseDir: String = "",
        var regexPattern: String = "",
        var regexTarget: String = RegexTarget.ALL.name,
        var caseInsensitive: Boolean = false,
        // SQL（本步仅持久化非敏感字段）
        var sqlDatasourceName: String = "",
        var sqlJdbcUrl: String = "",
        var sqlUser: String = "",
        var sqlQuery: String = "",
        var sqlPathColumn: String = "",
        // SSH（本步仅持久化非敏感字段）
        var sshHostAlias: String = "",
        var sshManualHost: String = "",
        var sshManualPort: Int = 22,
        var sshManualUser: String = "",
        var sshCommand: String = "",
        var limit: Int = 500
    ) {
        constructor() : this("", "", RuleType.REGEX.name, true, 0L)

        /**
         * 转业务模型（未知枚举值保守回退，不抛异常）
         */
        fun toMatchRule(): MatchRule {
            return MatchRule(
                id = id,
                name = name,
                type = RuleType.entries.firstOrNull { it.name == type } ?: RuleType.REGEX,
                enabled = enabled,
                createdAt = createdAt,
                regexBaseDir = regexBaseDir,
                regexPattern = regexPattern,
                regexTarget = RegexTarget.entries.firstOrNull { it.name == regexTarget } ?: RegexTarget.ALL,
                caseInsensitive = caseInsensitive,
                sqlDatasourceName = sqlDatasourceName,
                sqlJdbcUrl = sqlJdbcUrl,
                sqlUser = sqlUser,
                // 明文密码不落盘：运行期经 PasswordSafe 读取（下一步实现），此处恒为空串
                sqlPassword = "",
                sqlQuery = sqlQuery,
                sqlPathColumn = sqlPathColumn,
                sshHostAlias = sshHostAlias,
                sshManualHost = sshManualHost,
                sshManualPort = sshManualPort,
                sshManualUser = sshManualUser,
                sshCommand = sshCommand,
                limit = limit
            )
        }

        companion object {
            /**
             * 由业务模型构建序列化 bean（sqlPassword 刻意不落盘）
             */
            fun fromMatchRule(rule: MatchRule): RuleData {
                return RuleData(
                    id = rule.id,
                    name = rule.name,
                    type = rule.type.name,
                    enabled = rule.enabled,
                    createdAt = rule.createdAt,
                    regexBaseDir = rule.regexBaseDir,
                    regexPattern = rule.regexPattern,
                    regexTarget = rule.regexTarget.name,
                    caseInsensitive = rule.caseInsensitive,
                    sqlDatasourceName = rule.sqlDatasourceName,
                    sqlJdbcUrl = rule.sqlJdbcUrl,
                    sqlUser = rule.sqlUser,
                    sqlQuery = rule.sqlQuery,
                    sqlPathColumn = rule.sqlPathColumn,
                    sshHostAlias = rule.sshHostAlias,
                    sshManualHost = rule.sshManualHost,
                    sshManualPort = rule.sshManualPort,
                    sshManualUser = rule.sshManualUser,
                    sshCommand = rule.sshCommand,
                    limit = rule.limit
                )
            }
        }
    }

    private var state = State()

    override fun getState(): State {
        return state
    }

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    /**
     * 读取全部规则（脏数据容错：未知枚举回退默认值）
     *
     * @return 规则列表（只读快照，修改请走 [saveRules]）
     */
    fun getRules(): List<MatchRule> {
        return state.rules.map { it.toMatchRule() }
    }

    /**
     * 全量覆盖保存规则（增删改后调用即触发持久化）
     *
     * @param rules 规则列表
     */
    fun saveRules(rules: List<MatchRule>) {
        state.rules = rules.map { RuleData.fromMatchRule(it) }.toMutableList()
    }

    companion object {
        /**
         * 获取项目实例
         */
        @JvmStatic
        fun getInstance(project: Project): RuleStorageHelper {
            return project.service()
        }
    }
}
