package com.aogg.favorites.model

import com.aogg.favorites.helper.RuleStorageHelper
import com.aogg.favorites.rules.RegexRuleExecutor
import com.aogg.favorites.rules.RuleExecutor
import com.aogg.favorites.rules.RuleItem
import com.aogg.favorites.rules.RuleResult
import com.aogg.favorites.rules.RuleStatus
import com.aogg.favorites.rules.SqlRuleExecutor
import com.aogg.favorites.rules.SshDirRuleExecutor
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
 * - 执行状态变化后统一 invokeLater 刷新收藏工具窗口（项目已关闭则跳过）；
 * - 远端目录缓存（1.8.1 远程文件夹/远程文件新增）：Map<"ruleId|路径", 条目列表>，收藏树懒加载
 *   展开远端目录时先查缓存、命中不重复拉取（「已拉取的存客户端缓存、异步逐项刷新、不全量拉取」），
 *   规则增删改时清空对应规则缓存。
 *
 * 线程约定：CRUD 仅约定在 EDT 调用（与既有管理器风格一致）；refreshRule/refreshAll 幂等，
 * 内部自行切后台线程；listRemoteDirAsync/readRemoteFileAsync 亦内部切后台线程、回调回 EDT。
 */
@Service(Service.Level.PROJECT)
class RuleMatchManager(private val project: Project) {

    companion object {
        private val log = Logger.getInstance(RuleMatchManager::class.java)

        /** 无执行器类型的统一失败文案（兜底分支，当前五类执行器均已注册） */
        private const val EXECUTOR_NOT_IMPLEMENTED = "执行器待实现"

        /**
         * 远端目录缓存键：ruleId|远端路径（路径已归一；`|` 不会出现在规则 id 与归一路径里）
         */
        private fun remoteDirKey(ruleId: String, path: String): String = "$ruleId|$path"

        /**
         * 获取项目实例
         */
        @JvmStatic
        fun getInstance(project: Project): RuleMatchManager {
            return project.service()
        }
    }

    /** 执行器注册表（按规则类型路由；五类执行器 init 注册，新类型可经 [registerExecutor] 扩展） */
    private val executors = ConcurrentHashMap<RuleType, RuleExecutor>().apply {
        put(RuleType.REGEX, RegexRuleExecutor())
        put(RuleType.SQL, SqlRuleExecutor())
        put(RuleType.SSH, SshRuleExecutor())
        put(RuleType.SSH_DIR, SshDirRuleExecutor())
        put(RuleType.SSH_FILE, SshDirRuleExecutor())
    }

    /** 规则结果缓存（ruleId → 最近一次执行结果） */
    private val resultCache = ConcurrentHashMap<String, RuleResult>()

    /** 正在后台执行的规则 id 集合（防重复提交，LOADING 期间重复刷新被忽略） */
    private val refreshingRules: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** 远端目录子项缓存（"ruleId|路径" → 条目列表；懒加载命中不重复 ssh ls） */
    private val remoteDirCache = ConcurrentHashMap<String, List<RuleItem.RemoteFileItem>>()

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
     * 更新规则（按 id 覆盖；不触发执行；远端目录缓存一并清除，避免旧主机/旧路径脏数据）
     */
    fun updateRule(rule: MatchRule) {
        mutateRules { list -> list.map { if (it.id == rule.id) rule else it } }
        clearRemoteCacheForRule(rule.id)
    }

    /**
     * 删除规则（同时清除其结果缓存与远端目录缓存）
     */
    fun removeRule(ruleId: String) {
        mutateRules { list -> list.filterNot { it.id == ruleId } }
        resultCache.remove(ruleId)
        clearRemoteCacheForRule(ruleId)
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

    // ==================== 远端目录缓存与懒加载（1.8.1 SSH_DIR/SSH_FILE） ====================

    /**
     * 读缓存的远端目录子项（懒加载命中不重复 ssh ls；未拉取过返回 null）
     *
     * @param ruleId 所属规则 id
     * @param path 远端目录路径（已归一）
     */
    fun cachedRemoteDirChildren(ruleId: String, path: String): List<RuleItem.RemoteFileItem>? {
        return remoteDirCache[remoteDirKey(ruleId, path)]
    }

    /**
     * 失效远端目录缓存（树「刷新此目录」入口）：
     * 移除该目录自身与全部子级目录的缓存（按 key 前缀匹配 `路径/`），下次展开重新 ssh ls
     *
     * @param ruleId 所属规则 id
     * @param path 远端目录路径（已归一）
     */
    fun invalidateRemoteDirCache(ruleId: String, path: String) {
        val prefix = remoteDirKey(ruleId, path) + "/"
        remoteDirCache.keys.removeIf { key -> key == remoteDirKey(ruleId, path) || key.startsWith(prefix) }
    }

    /**
     * 异步列远端目录（收藏树懒加载展开入口）：
     * 调用线程快照规则 → 后台线程走 [SshDirRuleExecutor.listRemoteDir]（ssh ls）→
     * 结果写入 [remoteDirCache] → 回调回 EDT（Result 含失败原因）；项目已关闭/规则不存在不回调。
     * 同一 (ruleId, path) 加载中重复调用被忽略（回调收 Result.failure("加载中") 不触发 UI 重复刷新）。
     *
     * @param ruleId 所属规则 id
     * @param path 远端目录路径（内部归一）
     * @param callback EDT 回调：成功=子条目列表；失败=异常（message 面向用户）
     */
    fun listRemoteDirAsync(
        ruleId: String,
        path: String,
        callback: (Result<List<RuleItem.RemoteFileItem>>) -> Unit
    ) {
        // ////////////////  校验参数：项目未关闭 + 规则存在 + 未在加载中  start  ////////////////
        if (project.isDisposed) return
        val rule = getRule(ruleId) ?: return
        if (rule.type != RuleType.SSH_DIR && rule.type != RuleType.SSH_FILE) return
        // ////////////////  校验参数  end  /////////////////////////////////////////////

        // ////////////////  核心逻辑：后台 ssh ls + 缓存 + EDT 回调  start  /////////////////
        runCatching {
            AppExecutorUtil.getAppExecutorService().submit {
                // SshDirRuleExecutor 的列目录/读文件走 companion 纯通道（不经执行器实例）
                val result = runCatching { SshDirRuleExecutor.listRemoteDir(project, rule, path) }
                if (result.isSuccess) {
                    remoteDirCache[remoteDirKey(ruleId, SshDirRuleExecutor.normalizeRemotePath(path))] =
                        result.getOrDefault(emptyList())
                }
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    callback(result)
                }
            }
        }.onFailure { error ->
            // 提交失败兜底：回调失败原因，避免树侧一直停在加载占位
            log.warn("远端目录列取任务提交失败：$path", error)
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                callback(Result.failure(error))
            }
        }
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////
    }

    /**
     * 异步读取远端文件内容（收藏树「查看内容」入口）：
     * 后台线程走 [SshDirRuleExecutor.readRemoteFile]（ssh head 限量拉取，不落项目内文件）→
     * 回调回 EDT；项目已关闭/规则不存在不回调
     *
     * @param ruleId 所属规则 id
     * @param path 远端文件路径
     * @param callback EDT 回调：成功=文件文本；失败=异常（message 面向用户）
     */
    fun readRemoteFileAsync(ruleId: String, path: String, callback: (Result<String>) -> Unit) {
        // ////////////////  校验参数：项目未关闭 + 规则存在  start  /////////////////////////
        if (project.isDisposed) return
        val rule = getRule(ruleId) ?: return
        if (rule.type != RuleType.SSH_DIR && rule.type != RuleType.SSH_FILE) return
        // ////////////////  校验参数  end  /////////////////////////////////////////////

        // ////////////////  核心逻辑：后台 ssh head + EDT 回调  start  //////////////////////
        runCatching {
            AppExecutorUtil.getAppExecutorService().submit {
                val result = runCatching { SshDirRuleExecutor.readRemoteFile(project, rule, path) }
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    callback(result)
                }
            }
        }.onFailure { error ->
            log.warn("远端文件读取任务提交失败：$path", error)
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                callback(Result.failure(error))
            }
        }
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////
    }

    /**
     * 清除某规则的全部远端目录缓存（规则增删改时调用，防止换主机/换路径后命中旧缓存）
     *
     * @param ruleId 规则 id
     */
    private fun clearRemoteCacheForRule(ruleId: String) {
        val prefix = "$ruleId|"
        remoteDirCache.keys.removeIf { key -> key.startsWith(prefix) }
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
