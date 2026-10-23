package com.aogg.favorites.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * SqlDriverLoader 纯函数单元测试（不依赖 IntelliJ 平台、不真正加载驱动 jar）
 *
 * 覆盖：JDBC URL → 候选驱动类名推导（各数据库 + 大小写/空白容忍 + 未知前缀）、
 * 全局兜底目录驱动 jar 文件名识别、URL 字符串 → 本地 jar 路径解析。
 * connect() 涉及磁盘扫描与真实建连，不属纯 JVM 单测范围。
 */
@DisplayName("SqlDriverLoader 驱动类名推导/驱动 jar 识别/URL 转路径")
class SqlDriverLoaderTest {

    // ==================== driverClassCandidates：URL → 候选驱动类名 ====================

    @Test
    fun `mysql url yields two mysql driver candidates`() {
        assertEquals(
            listOf("com.mysql.cj.jdbc.Driver", "com.mysql.jdbc.Driver"),
            SqlDriverLoader.driverClassCandidates("jdbc:mysql://localhost:3306/db"),
            "mysql 应给出新旧两个驱动候选，新版在前"
        )
    }

    @Test
    fun `each database yields its own driver candidate`() {
        assertEquals(
            listOf("org.postgresql.Driver"),
            SqlDriverLoader.driverClassCandidates("jdbc:postgresql://localhost:5432/db"),
            "postgresql 候选应为 org.postgresql.Driver"
        )
        assertEquals(
            listOf("org.mariadb.jdbc.Driver"),
            SqlDriverLoader.driverClassCandidates("jdbc:mariadb://localhost:3306/db"),
            "mariadb 候选应为 org.mariadb.jdbc.Driver"
        )
        assertEquals(
            listOf("org.sqlite.JDBC"),
            SqlDriverLoader.driverClassCandidates("jdbc:sqlite:/tmp/data.db"),
            "sqlite 候选应为 org.sqlite.JDBC"
        )
        assertEquals(
            listOf("org.h2.Driver"),
            SqlDriverLoader.driverClassCandidates("jdbc:h2:mem:test"),
            "h2 候选应为 org.h2.Driver"
        )
        assertEquals(
            listOf("com.microsoft.sqlserver.jdbc.SQLServerDriver"),
            SqlDriverLoader.driverClassCandidates("jdbc:sqlserver://localhost:1433;databaseName=db"),
            "sqlserver 候选应为 SQLServerDriver"
        )
        assertEquals(
            listOf("oracle.jdbc.OracleDriver", "oracle.jdbc.driver.OracleDriver"),
            SqlDriverLoader.driverClassCandidates("jdbc:oracle:thin:@localhost:1521:orcl"),
            "oracle 应给出新旧两个候选"
        )
    }

    @Test
    fun `unknown url prefix yields no candidates`() {
        assertEquals(emptyList<String>(), SqlDriverLoader.driverClassCandidates("jdbc:foobar://host/db"), "未知前缀应返回空候选")
        assertEquals(emptyList<String>(), SqlDriverLoader.driverClassCandidates("not-a-jdbc-url"), "非 JDBC URL 应返回空候选")
    }

    @Test
    fun `url matching tolerates case and surrounding whitespace`() {
        assertEquals(
            listOf("com.mysql.cj.jdbc.Driver", "com.mysql.jdbc.Driver"),
            SqlDriverLoader.driverClassCandidates("JDBC:MYSQL://host/db"),
            "前缀匹配应忽略大小写"
        )
        assertEquals(
            listOf("org.h2.Driver"),
            SqlDriverLoader.driverClassCandidates("  jdbc:h2:mem:test  "),
            "前缀匹配应容忍首尾空白"
        )
    }

    // ==================== isDriverJarCandidate：驱动 jar 文件名识别 ====================

    @Test
    fun `known driver jar names are recognized`() {
        assertTrue(SqlDriverLoader.isDriverJarCandidate("mysql-connector-j-8.0.33.jar"), "mysql 驱动 jar 应识别")
        assertTrue(SqlDriverLoader.isDriverJarCandidate("postgresql-42.7.1.jar"), "postgresql 驱动 jar 应识别")
        assertTrue(SqlDriverLoader.isDriverJarCandidate("mariadb-java-client-3.3.3.jar"), "mariadb 驱动 jar 应识别")
        assertTrue(SqlDriverLoader.isDriverJarCandidate("sqlite-jdbc-3.44.1.0.jar"), "sqlite 驱动 jar 应识别")
        assertTrue(SqlDriverLoader.isDriverJarCandidate("h2-2.2.224.jar"), "h2 驱动 jar 应识别")
        assertTrue(SqlDriverLoader.isDriverJarCandidate("mssql-jdbc-12.4.2.jre11.jar"), "sqlserver 驱动 jar 应识别")
        assertTrue(SqlDriverLoader.isDriverJarCandidate("ojdbc11.jar"), "oracle 驱动 jar 应识别")
        assertTrue(SqlDriverLoader.isDriverJarCandidate("MySQL-Connector-J-8.0.33.JAR"), "识别应不区分大小写")
    }

    @Test
    fun `non driver names are rejected`() {
        assertFalse(SqlDriverLoader.isDriverJarCandidate("README.txt"), "非 jar 不应识别")
        assertFalse(SqlDriverLoader.isDriverJarCandidate("kotlin-stdlib.jar"), "无关键词的普通 jar 不应识别")
        assertFalse(SqlDriverLoader.isDriverJarCandidate("mysql.jar"), "仅含 mysql 但非 connector 关键词不应识别")
        assertFalse(SqlDriverLoader.isDriverJarCandidate(null), "空文件名不应识别")
    }

    // ==================== urlStringToPath：URL 字符串 → 本地 jar 路径 ====================

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `null or non file url yields null`() {
        assertNull(SqlDriverLoader.urlStringToPath(null), "空输入应返回 null")
        assertNull(SqlDriverLoader.urlStringToPath("http://example.com/mysql.jar"), "非 file 协议应返回 null")
        assertNull(SqlDriverLoader.urlStringToPath("file:/no/such/mysql-connector.jar"), "不存在的文件应返回 null")
    }

    @Test
    fun `existing jar file url resolves to path`() {
        val jar = Files.createFile(tempDir.resolve("mysql-connector-j-8.0.33.jar"))

        assertEquals(
            jar,
            SqlDriverLoader.urlStringToPath("file:$jar"),
            "存在的 jar file URL 应解析为其路径"
        )
        assertEquals(
            jar,
            SqlDriverLoader.urlStringToPath("jar:file:$jar!/lib/inner"),
            "jar:file:...!/ 嵌套 URL 应取 !/ 前的 jar 本体"
        )
    }

    @Test
    fun `existing non jar file yields null`() {
        val txt = Files.createFile(tempDir.resolve("notes.txt"))
        assertNull(SqlDriverLoader.urlStringToPath("file:$txt"), "存在但非 .jar 的文件应返回 null")
    }

    @Test
    fun `url with unescaped space resolves via decoder fallback`() {
        val jar = Files.createFile(tempDir.resolve("my driver.jar"))

        assertEquals(
            jar,
            SqlDriverLoader.urlStringToPath("file:$jar"),
            "未转义空格应经 URLDecoder 兜底解析成功"
        )
    }
}
