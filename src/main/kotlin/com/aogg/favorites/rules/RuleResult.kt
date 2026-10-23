package com.aogg.favorites.rules

/**
 * 规则执行状态
 */
enum class RuleStatus {

    /**
     * 执行中（后台线程运行，树节点显示「⏳ 执行中」）
     */
    LOADING,

    /**
     * 执行成功（items 为结果条目，树节点显示「✅ M」）
     */
    OK,

    /**
     * 执行失败（error 含原因，树节点显示「❌」且 tooltip 展示原因）
     */
    ERROR
}

/**
 * 规则执行结果（RuleMatchManager 结果缓存 Map<ruleId, RuleResult> 的值）
 *
 * @param status 执行状态
 * @param items 结果条目（仅 OK 有值）
 * @param error 失败原因（仅 ERROR 有值；树节点 tooltip 展示）
 * @param finishedAt 完成时间戳（LOADING 为 0）
 */
data class RuleResult(
    val status: RuleStatus,
    val items: List<RuleItem> = emptyList(),
    val error: String? = null,
    val finishedAt: Long = 0L
)
