package com.aogg.favorites.model

import com.aogg.favorites.helper.RuleStorageHelper
import com.aogg.favorites.rules.RegexRuleExecutor
import com.aogg.favorites.rules.RuleExecutor
import com.aogg.favorites.rules.RuleResult
import com.aogg.favorites.rules.RuleStatus
import com.aogg.favorites.rules.SqlRuleExecutor
import com.aogg.favorites.rules.SshRuleExecutor
import com.aogg.favorites.ui.BookmarksToolWindowPanel
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.ConcurrentHashMap

/**
 * 规则匹配管理器（项目级单例，1.4.0 新增）：
 * - 规则 CRUD：读写项目级 [RuleStorageHelper]（.idea/favorites.rules.xml）；
 * - 结果缓存：Map<ruleId, [RuleResult]>（ConcurrentHashMap，EDT 渲染与后台执行并发读写安全）；
 * - 刷新：[refreshRule] 单规则后台执行，[refreshAll] 对启用规则并行刷新
 *   （未注册执行器的类型兜底置 ERROR「执行器待实现」）；
 * - 执行状态变化后统一 invokeLater 刷新收藏工具窗口（项目已关闭则跳过）。
 *
 * 线程约定：CRUD 仅约定在 EDT 调用（与既有管理器风格一致）；refreshRule/refreshAll 幂等，
 * 内部自行切后台线程。
 */
@Service(Service.Level.PROJECT)
class RuleMatchManager(private val project: Project) {

    companion object {
        private val log = Logger.getInstance(RuleMatchManager::class.java)

        /** 无执行器类型的统一失败文案（兜底分支，当前三类执行器均已注册） */
        private const val EXECUTOR_NOT_IMPLEMENTED = "执行器待实现"

        /**
         * 获取项目实例
         */
        @JvmStatic
        fun getInstance(project: Project): RuleMatchManager {
            return project.service()
        }
    }

    /** 执行器注册表（按规则类型路由；三类执行器 init 注册，新类型可经 [registerExecutor] 扩展） */
    private val executors = ConcurrentHashMap<RuleType, RuleExecutor>().apply {
        put(RuleType.REGEX, RegexRuleExecutor())
        put(RuleType.SQL, SqlRuleExecutor())
        put(RuleType.SSH, SshRuleExecutor())
    }

    /** 规则结果缓存（ruleId → 最近一次执行结果） */
    private val resultCache = ConcurrentHashMap<String, RuleResult>()

    /** 正在后台执行的规则 id 集合（防重复提交，LOADING 期间重复刷新被忽略） */
    private val refreshingRules: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // ==================== 执行器注册（供新类型执行器扩展） ====================

    /**
     * 注册/替换某类型的执行器（兜底能力：新类型/测试替身可经此接入）
     *
     * @param type 规则类型
     * @param executor 执行器
     */
    fun registerExecutor(type: RuleType, executor: RuleExecutor) {
        executors[type] = executor
    }

    // ==================== 规则 CRUD（读写项目级存储） ====================

    /**
     * 获取全部规则（读取失败兜底返回空列表，不影响其它功能）
     */
    fun getRules(): List<MatchRule> {
        return runCatching { RuleStorageHelper.getInstance(project).getRules() }
            .onFailure { log.warn("读取匹配规则失败", it) }
            .getOrDefault(emptyList())
    }

    /**
     * 按 id 获取规则
     *
     * @param ruleId 规则 id
     * @return 规则或 null（不存在）
     */
    fun getRule(ruleId: String): MatchRule? {
        return getRules().firstOrNull { it.id == ruleId }
    }

    /**
     * 新增规则（持久化；不触发执行，需要立即出结果请随后调用 [refreshRule]）
     */
    fun addRule(rule: MatchRule) {
        mutateRules { it + rule }
    }

    /**
     * 更新规则（按 id 覆盖；不触发执行）
     */
    fun updateRule(rule: MatchRule) {
        mutateRules { list -> list.map { if (it.id == rule.id) rule else it } }
    }

    /**
     * 删除规则（同时清除其结果缓存）
     */
    fun removeRule(ruleId: String) {
        mutateRules { list -> list.filterNot { it.id == ruleId } }
        resultCache.remove(ruleId)
    }

    /**
     * 修改规则集并持久化（失败仅记日志，不影响其它功能）
     */
    private fun mutateRules(transform: (List<MatchRule>) -> List<MatchRule>) {
        runCatching {
            val storage = RuleStorageHelper.getInstance(project)
            storage.saveRules(transform(storage.getRules()))
        }.onFailure { log.warn("保存匹配规则失败", it) }
    }

    // ==================== 结果缓存与刷新 ====================

    /**
     * 获取规则最近一次执行结果（未执行过返回 null）
     *
     * @param ruleId 规则 id
     */
    fun getResult(ruleId: String): RuleResult? {
        return resultCache[ruleId]
    }

    /**
     * 刷新单个规则：
     * - 无执行器类型（兜底：未来新类型未注册时）→ 直接置 ERROR「执行器待实现」；
     * - 有执行器类型 → 置 LOADING 后交后台线程执行，完成后 invokeLater 刷新工具窗口。
     * 同一规则执行中重复调用会被忽略（防重复提交）；项目已关闭/规则不存在时跳过。
     *
     * @param ruleId 规则 id
     */
    fun refreshRule(ruleId: String) {
        // ////////////////  校验参数：项目未关闭 + 规则存在 + 未在执行中  start  ////////////
        if (project.isDisposed) return
        val rule = getRule(ruleId) ?: return
        if (!refreshingRules.add(ruleId)) return
        // ////////////////  校验参数  end  ////////////////////////////////////////////

        // ////////////////  分支一：无执行器类型兜底置 ERROR  start  ///////////////////
        val executor = executors[rule.type]
        if (executor == null) {
            refreshingRules.remove(ruleId)
            resultCache[ruleId] = RuleResult(
                status = RuleStatus.ERROR,
                items = emptyList(),
                error = EXECUTOR_NOT_IMPLEMENTED,
                finishedAt = System.currentTimeMillis()
            )
            notifyToolWindowRefresh()
            return
        }
        // ////////////////  分支一  end  /////////////////////////////////////////////

        // ////////////////  分支二：置 LOADING 并交后台线程执行  start  ///////////////////
        resultCache[ruleId] = RuleResult(status = RuleStatus.LOADING)
        notifyToolWindowRefresh()
        runCatching {
            AppExecutorUtil.getAppExecutorService().submit {
                // 执行失败统一转 ERROR（文案取异常 message，兜底异常类名），不影响其它规则
                val result = runCatching { executor.execute(project, rule) }.fold(
                    onSuccess = {
                        RuleResult(RuleStatus.OK, it, null, System.currentTimeMillis())
                    },
                    onFailure = { error ->
                        log.warn("规则「${rule.name}」执行失败", error)
                        RuleResult(
                            RuleStatus.ERROR,
                            emptyList(),
                            error.message?.takeIf { message -> message.isNotBlank() }
                                ?: error.javaClass.simpleName,
                            System.currentTimeMillis()
                        )
                    }
                )
                resultCache[ruleId] = result
                refreshingRules.remove(ruleId)
                notifyToolWindowRefresh()
            }
        }.onFailure { error ->
            // 提交后台任务失败兜底：恢复状态，避免规则永远停留在 LOADING
            log.warn("规则「${rule.name}」后台任务提交失败", error)
            refreshingRules.remove(ruleId)
            resultCache[ruleId] = RuleResult(
                RuleStatus.ERROR, emptyList(), "后台任务提交失败", System.currentTimeMillis()
            )
            notifyToolWindowRefresh()
        }
        // ////////////////  分支二  end  /////////////////////////////////////////////
    }

    /**
     * 刷新全部启用规则（并行：每条规则独立提交后台线程池；项目已关闭直接跳过）
     */
    fun refreshAll() {
        if (project.isDisposed) return
        getRules().filter { it.enabled }.forEach { refreshRule(it.id) }
    }

    /**
     * 执行状态变化后统一刷新收藏工具窗口（invokeLater 不阻塞；项目已关闭则跳过；失败仅记日志）
     */
    private fun notifyToolWindowRefresh() {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            runCatching { BookmarksToolWindowPanel.refreshToolWindow(project) }
                .onFailure { log.warn("规则执行后刷新工具窗口失败", it) }
        }
    }
}
