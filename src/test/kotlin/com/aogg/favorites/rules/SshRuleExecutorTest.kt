package com.aogg.favorites.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * SshRuleExecutor 纯函数单元测试（不依赖 IntelliJ 平台、不真正发起 ssh 进程）
 *
 * 只覆盖 companion 里的输出整形/错误信息拼接纯函数：
 * stdout 拆行、去尾部空行、limit 截断、stderr 尾部截断（tailForMessage）。
 * execute() 依赖平台 ExecUtil 与真实 ssh 进程，不属纯 JVM 单测范围（见任务报告说明）。
 */
@DisplayName("SshRuleExecutor 输出整形与错误信息纯函数")
class SshRuleExecutorTest {

    // ==================== splitOutputLines：拆行 + 去尾部空行 ====================

    @Test
    fun `split lines and drop trailing blank lines`() {
        assertEquals(listOf("a", "b"), SshRuleExecutor.splitOutputLines("a\nb\n"), "结尾换行产生的尾部空串应去掉")
        assertEquals(listOf("a", "b"), SshRuleExecutor.splitOutputLines("a\r\nb\r\n"), "CRLF 应先归一为 LF 再拆行")
        assertEquals(listOf("a"), SshRuleExecutor.splitOutputLines("a\n\n\n"), "尾部连续空行应全部去掉")
        assertEquals(emptyList<String>(), SshRuleExecutor.splitOutputLines(""), "空输出应得空列表")
    }

    @Test
    fun `inner blank lines are preserved`() {
        assertEquals(listOf("a", "", "b"), SshRuleExecutor.splitOutputLines("a\n\nb"), "中间空行是内容，应保留")
        assertEquals(listOf("  indented"), SshRuleExecutor.splitOutputLines("  indented\n"), "行内空白应原样保留")
    }

    // ==================== processOutput：limit 截断 ====================

    @Test
    fun `limit truncates output lines`() {
        assertEquals(listOf("1", "2"), SshRuleExecutor.processOutput("1\n2\n3", limit = 2), "超出 limit 的行应截断")
        assertEquals(listOf("1", "2", "3"), SshRuleExecutor.processOutput("1\n2\n3", limit = 10), "limit 大于行数时应全保留")
    }

    @Test
    fun `non positive limit returns empty list`() {
        assertEquals(emptyList<String>(), SshRuleExecutor.processOutput("a\nb", limit = 0), "limit<=0 应返回空列表")
        assertEquals(emptyList<String>(), SshRuleExecutor.processOutput("a\nb", limit = -1), "limit<=0 应返回空列表")
    }

    @Test
    fun `truncation happens after dropping trailing blanks`() {
        assertEquals(listOf("1"), SshRuleExecutor.processOutput("1\n2\n\n\n", limit = 1), "应先去尾部空行再按 limit 截断")
    }

    // ==================== tailForMessage：错误信息尾部截断 ====================

    @Test
    fun `blank text gets placeholder`() {
        assertEquals("（无错误输出）", SshRuleExecutor.tailForMessage(""), "空文本应返回占位说明")
        assertEquals("（无错误输出）", SshRuleExecutor.tailForMessage("   \n\t"), "纯空白文本应返回占位说明")
    }

    @Test
    fun `short text kept as is`() {
        val stderr = "ssh: connect to host 1.2.3.4 port 22: Connection refused"
        assertEquals(stderr, SshRuleExecutor.tailForMessage(stderr), "未超长的文本应原样返回（无省略号）")
    }

    @Test
    fun `multi line text keeps the tail within maxChars`() {
        // "line2\nline3" 恰好 11 字符：line1 装不下被舍弃，尾部两行保留并加省略号
        assertEquals(
            "…line2\nline3",
            SshRuleExecutor.tailForMessage("line1\nline2\nline3", maxChars = 11),
            "多行应从尾部向前收集，累计不超 maxChars"
        )

        val kept = SshRuleExecutor.tailForMessage("e1\ne2\ne3\ne4\ne5", maxChars = 6)
        assertEquals("…e4\ne5", kept, "从尾行向前收集，装不下的头部行舍弃")
    }

    @Test
    fun `single overlong line truncated from tail`() {
        val longLine = "x".repeat(400)
        val result = SshRuleExecutor.tailForMessage(longLine, maxChars = 300)

        assertTrue(result.startsWith("…"), "单行超长应加省略号前缀")
        assertEquals(301, result.length, "省略号 + 尾部 maxChars 字符")
        assertEquals("x".repeat(300), result.removePrefix("…"), "应保留尾部 300 字符")
    }

    @Test
    fun `result never exceeds maxChars plus ellipsis`() {
        val stderr = "err\n".repeat(200)
        val result = SshRuleExecutor.tailForMessage(stderr, maxChars = 300)
        assertTrue(result.length <= 301, "返回长度不应超过 maxChars+省略号")
    }
}
