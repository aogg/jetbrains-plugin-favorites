package com.aogg.favorites.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

/**
 * SshConfigReader 纯函数 parse() 单元测试（不依赖 IntelliJ 平台）
 *
 * 覆盖：Host 多别名一行、HostName/Port 默认 22/User、# 注释、大小写不敏感 key、
 * 通配条目跳过、Include 忽略、同别名后值覆盖前值、块间状态重置、空串/垃圾文本不抛异常
 */
@DisplayName("SshConfigReader.parse() ssh config 文本解析")
class SshConfigReaderTest {

    private fun parse(content: String): List<SshHostEntry> = SshConfigReader.parse(content)

    // ==================== 基础字段解析 ====================

    @Test
    fun `host with multiple aliases creates one entry per alias`() {
        val entries = parse(
            """
            Host web1 web2
              HostName 10.0.0.5
              User deploy
              Port 2200
            """.trimIndent()
        )

        assertEquals(2, entries.size, "一行两个别名应产出两条条目")
        assertEquals(listOf("web1", "web2"), entries.map { it.alias }, "别名应按出现顺序保留")
        entries.forEach {
            assertEquals("10.0.0.5", it.hostName, "多别名共用同一 HostName")
            assertEquals("deploy", it.user, "多别名共用同一 User")
            assertEquals(2200, it.port, "多别名共用同一 Port")
        }
    }

    @Test
    fun `defaults when HostName and Port omitted`() {
        val entries = parse("Host db1\n  User root\n")

        assertEquals(1, entries.size)
        val entry = entries.single()
        assertEquals("db1", entry.hostName, "未写 HostName 的条目应以别名充当主机地址")
        assertEquals(SshConfigReader.DEFAULT_PORT, entry.port, "未写 Port 应默认 22")
        assertEquals("root", entry.user)
    }

    @Test
    fun `explicit HostName Port User and quoted values`() {
        val entries = parse(
            """
            Host db2
              HostName "db.example.com"
              Port 2222
              User "ubuntu"
            """.trimIndent()
        )

        val entry = entries.single()
        assertEquals("db.example.com", entry.hostName, "带引号的值应去引号")
        assertEquals(2222, entry.port)
        assertEquals("ubuntu", entry.user)
    }

    @Test
    fun `illegal port value is ignored and default kept`() {
        val entries = parse("Host p1\n  Port abc\n")

        assertEquals(SshConfigReader.DEFAULT_PORT, entries.single().port, "非法端口应被忽略并保留默认 22")
    }

    // ==================== 注释与大小写 ====================

    @Test
    fun `whole line comments and inline comments are ignored`() {
        val entries = parse(
            """
            # Host ghost
            #   HostName 9.9.9.9
            Host real1 # 行内注释
              Port 2202 # inline comment
            """.trimIndent()
        )

        assertEquals(listOf("real1"), entries.map { it.alias }, "# 起整行应视为注释")
        assertEquals(2202, entries.single().port, "行内 # 后内容应截断")
    }

    @Test
    fun `keys are case insensitive`() {
        val entries = parse(
            """
            HOST c1
              hostname C.example.com
              PORT 2203
              USER ubuntu
            """.trimIndent()
        )

        val entry = entries.single()
        assertEquals("c1", entry.alias)
        assertEquals("C.example.com", entry.hostName, "key 大小写不敏感，值大小写保留")
        assertEquals(2203, entry.port)
        assertEquals("ubuntu", entry.user)
    }

    // ==================== 通配与未知 key ====================

    @Test
    fun `wildcard host entries are skipped`() {
        val entries = parse(
            """
            Host *
              User nobody
              HostName wildcard.invalid

            Host web?
              User probe

            Host web3
            """.trimIndent()
        )

        assertEquals(listOf("web3"), entries.map { it.alias }, "含 * / ? 通配的条目应整块跳过")
        assertEquals("", entries.single().user, "通配块里的 User 不应泄漏给后续条目")
        assertEquals(SshConfigReader.DEFAULT_PORT, entries.single().port, "通配块里的设置不应泄漏给后续条目")
    }

    @Test
    fun `include and unknown keys are ignored`() {
        val entries = parse(
            """
            Include ~/.ssh/config.d/*
            IdentityFile ~/.ssh/id_rsa
            IgnoreUnknown UseKeychain

            Host inc1
            """.trimIndent()
        )

        assertEquals(listOf("inc1"), entries.map { it.alias }, "Include 及未知 key 应忽略，不影响后续 Host 解析")
    }

    // ==================== 覆盖与状态重置 ====================

    @Test
    fun `same alias later block wins`() {
        val entries = parse(
            """
            Host dup
              HostName 1.1.1.1
              Port 1111

            Host dup
              HostName 2.2.2.2
            """.trimIndent()
        )

        assertEquals(1, entries.size, "同别名应去重为一条")
        assertEquals("2.2.2.2", entries.single().hostName, "同别名后值应覆盖前值")
        assertEquals(SshConfigReader.DEFAULT_PORT, entries.single().port, "后块未写 Port 应回到默认，而非继承前块")
    }

    @Test
    fun `block state resets between hosts`() {
        val entries = parse(
            """
            Host a1
              Port 2222
              User u1

            Host a2
            """.trimIndent()
        )

        val second = entries.last()
        assertEquals("a2", second.hostName)
        assertEquals(SshConfigReader.DEFAULT_PORT, second.port, "块间 Port 应重置")
        assertEquals("", second.user, "块间 User 应重置")
    }

    // ==================== 容错 ====================

    @Test
    fun `empty content returns empty list without throwing`() {
        assertTrue(parse("").isEmpty(), "空串应返回空列表")
        assertTrue(parse("   \n\n \t \n").isEmpty(), "纯空白内容应返回空列表")
    }

    @Test
    fun `garbage content returns empty list without throwing`() {
        val entries = assertDoesNotThrow("垃圾文本不应抛异常") { parse("this is not\na: valid :: config\n@#$%^&\n") }
        assertTrue(entries.isEmpty(), "无 Host 块的垃圾文本应返回空列表")
    }

    @Test
    fun `entries keep file order`() {
        val entries = parse("Host z9\n\nHost a1\n\nHost m5\n")

        assertEquals(listOf("z9", "a1", "m5"), entries.map { it.alias }, "条目应按配置文件出现顺序返回")
    }
}
