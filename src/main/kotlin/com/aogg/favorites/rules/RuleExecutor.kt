package com.aogg.favorites.rules

import com.aogg.favorites.model.MatchRule
import com.intellij.openapi.project.Project

/**
 * 规则执行器接口：由 RuleMatchManager 按规则类型路由
 * （REGEX 已由 [RegexRuleExecutor] 实现；SQL/SSH 执行器下一步实现并注册）
 */
interface RuleExecutor {

    /**
     * 执行规则并同步返回结果条目（应在后台线程调用，不要在 EDT 直接执行）
     *
     * @param project 项目
     * @param rule 规则
     * @return 结果条目列表
     * @throws Exception 执行失败（由管理器捕获转为 ERROR 结果，不影响其它功能）
     */
    fun execute(project: Project, rule: MatchRule): List<RuleItem>
}
