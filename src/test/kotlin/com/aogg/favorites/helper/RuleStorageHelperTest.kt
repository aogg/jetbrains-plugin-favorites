package com.aogg.favorites.helper

import com.aogg.favorites.model.MatchRule
import com.aogg.favorites.model.RegexTarget
import com.aogg.favorites.model.RuleType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * RuleStorageHelper 序列化模型单元测试（纯 bean 转换，不依赖 IntelliJ 平台）
 *
 * 覆盖：RuleData.fromMatchRule → toMatchRule 往返字段一致（sqlPassword 恒空串不落盘）、
 * 未知 type/regexTarget 字符串保守回退 REGEX/ALL、State 默认空列表、saveRules/getRules 快照往返。
 */
@DisplayName("RuleStorageHelper 规则序列化模型往返与脏数据回退")
class RuleStorageHelperTest {

    private fun fullRule() = MatchRule(
        id = "rule-1",
        name = "测试规则",
        type = RuleType.SQL,
        enabled = false,
        createdAt = 1700000000000L,
        regexBaseDir = "src/main",
        regexPattern = "Test\\.php$",
        regexTarget = RegexTarget.FILE,
        caseInsensitive = true,
        sqlDatasourceName = "本地 MySQL",
        sqlJdbcUrl = "jdbc:mysql://localhost:3306/db",
        sqlUser = "root",
        sqlPassword = "secret-should-not-persist",
        sqlQuery = "SELECT name, path FROM files",
        sqlPathColumn = "path",
        sshHostAlias = "web1",
        sshManualHost = "1.2.3.4",
        sshManualPort = 2222,
        sshManualUser = "ubuntu",
        sshCommand = "uptime",
        limit = 42
    )

    // ==================== 往返一致 ====================

    @Test
    fun `fromMatchRule then toMatchRule roundtrip keeps all persisted fields`() {
        val rule = fullRule()
        val roundtrip = RuleStorageHelper.RuleData.fromMatchRule(rule).toMatchRule()

        assertEquals(rule, roundtrip.copy(sqlPassword = rule.sqlPassword), "除 sqlPassword 外所有字段应往返一致")
        assertEquals(rule.copy(sqlPassword = ""), roundtrip, "唯一允许的差异是 sqlPassword 被丢弃")
    }

    @Test
    fun `sqlPassword never persists`() {
        val data = RuleStorageHelper.RuleData.fromMatchRule(fullRule())

        assertTrue(
            data.javaClass.declaredFields.none { it.name.contains("password", ignoreCase = true) },
            "序列化 bean 不应有任何密码字段（明文不落盘）"
        )
        assertEquals("", data.toMatchRule().sqlPassword, "往返后 sqlPassword 应恒为空串")
    }

    // ==================== 脏数据保守回退 ====================

    @Test
    fun `unknown type falls back to REGEX`() {
        val data = RuleStorageHelper.RuleData(id = "d1", type = "NOT_A_TYPE")
        assertEquals(RuleType.REGEX, data.toMatchRule().type, "未知规则类型应保守回退 REGEX")
    }

    @Test
    fun `unknown regexTarget falls back to ALL`() {
        val data = RuleStorageHelper.RuleData(id = "d1", regexTarget = "SIDEWAYS")
        assertEquals(RegexTarget.ALL, data.toMatchRule().regexTarget, "未知匹配目标应保守回退 ALL")
    }

    @Test
    fun `known enum names map exactly`() {
        RuleType.entries.forEach { type ->
            assertEquals(type, RuleStorageHelper.RuleData(id = "d", type = type.name).toMatchRule().type, "已知 type 应精确映射")
        }
        RegexTarget.entries.forEach { target ->
            assertEquals(
                target,
                RuleStorageHelper.RuleData(id = "d", regexTarget = target.name).toMatchRule().regexTarget,
                "已知 regexTarget 应精确映射"
            )
        }
    }

    // ==================== State 与存取 ====================

    @Test
    fun `state defaults to empty rule list`() {
        assertTrue(RuleStorageHelper.State().rules.isEmpty(), "State 默认应为空规则列表")
        assertEquals(0, RuleStorageHelper.State().rules.size)
    }

    @Test
    fun `saveRules then getRules roundtrip`() {
        val helper = RuleStorageHelper()
        val rule = fullRule()

        helper.saveRules(listOf(rule))
        val loaded = helper.getRules()

        assertEquals(1, loaded.size, "保存一条应读到一条")
        assertEquals(rule.copy(sqlPassword = ""), loaded.single(), "读回应与保存一致（sqlPassword 不落盘）")
    }

    @Test
    fun `getState returns snapshot containing saved rules`() {
        val helper = RuleStorageHelper()
        helper.saveRules(listOf(fullRule()))

        assertEquals(1, helper.getState().rules.size, "getState 应反映已保存规则")
        assertEquals("rule-1", helper.getState().rules.single().id)
    }
}
