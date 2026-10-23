package com.aogg.favorites.rules

import com.aogg.favorites.model.RegexTarget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * RegexRuleMatcher 纯逻辑单元测试（不依赖 IntelliJ 平台）
 *
 * 覆盖：正则编译（正常/非法/忽略大小写）、相对路径规范化、隐藏目录跳过判定、
 * 三种 RegexTarget × 文件/目录 × find()（部分匹配即命中）语义
 */
@DisplayName("RegexRuleMatcher 正则匹配纯逻辑")
class RegexRuleMatcherTest {

    // ==================== compile：正则编译 ====================

    @Test
    fun `compile normal pattern returns regex`() {
        val regex = RegexRuleMatcher.compile("Test\\.php$", caseInsensitive = false)
        assertNotNull(regex, "正常正则应编译成功")
        assertTrue(regex!!.containsMatchIn("src/main/Test.php"), "编译结果应可匹配")
    }

    @Test
    fun `compile invalid pattern returns null`() {
        assertNull(RegexRuleMatcher.compile("[", caseInsensitive = false), "非法正则（未闭合字符类）应返回 null")
        assertNull(RegexRuleMatcher.compile("(unclosed", caseInsensitive = false), "非法正则（未闭合分组）应返回 null")
        assertNull(RegexRuleMatcher.compile("*", caseInsensitive = false), "非法正则（悬空量词）应返回 null")
    }

    @Test
    fun `compile empty pattern returns null`() {
        assertNull(RegexRuleMatcher.compile("", caseInsensitive = false), "空串按约定视为非法，返回 null")
    }

    @Test
    fun `compile caseInsensitive matches different case`() {
        val sensitive = RegexRuleMatcher.compile("TEST\\.PHP", caseInsensitive = false)
        val insensitive = RegexRuleMatcher.compile("TEST\\.PHP", caseInsensitive = true)

        assertFalse(sensitive!!.containsMatchIn("src/test.php"), "大小写敏感时不应命中小写路径")
        assertTrue(insensitive!!.containsMatchIn("src/test.php"), "忽略大小写时应命中小写路径")
    }

    // ==================== normalizeRelativePath：路径规范化 ====================

    @Test
    fun `normalize backslashes to slashes`() {
        assertEquals("a/b/c.kt", RegexRuleMatcher.normalizeRelativePath("a\\b\\c.kt"), "反斜杠应统一为正斜杠")
    }

    @Test
    fun `normalize strips leading slashes and whitespace`() {
        assertEquals("src/A.php", RegexRuleMatcher.normalizeRelativePath("/src/A.php"), "前导斜杠应去除")
        assertEquals("src/A.php", RegexRuleMatcher.normalizeRelativePath("  /src/A.php  "), "首尾空白与前导斜杠都应去除")
        assertEquals("server/share", RegexRuleMatcher.normalizeRelativePath("\\\\server\\share"), "UNC 前缀反斜杠规范化后应去除")
    }

    // ==================== isSkippedDirectoryName：隐藏目录跳过 ====================

    @Test
    fun `dot directories are skipped`() {
        assertTrue(RegexRuleMatcher.isSkippedDirectoryName(".git"), ".git 应跳过")
        assertTrue(RegexRuleMatcher.isSkippedDirectoryName(".idea"), ".idea 应跳过")
        assertTrue(RegexRuleMatcher.isSkippedDirectoryName(".svn"), ".svn 应跳过")
    }

    @Test
    fun `normal directories are not skipped`() {
        assertFalse(RegexRuleMatcher.isSkippedDirectoryName("src"), "普通目录不应跳过")
        assertFalse(RegexRuleMatcher.isSkippedDirectoryName("git"), "不含点前缀不应跳过")
        assertFalse(RegexRuleMatcher.isSkippedDirectoryName(""), "空串不应跳过")
    }

    // ==================== matches：目标过滤 + find() 语义 ====================

    @Test
    fun `matches target FILE only hits files`() {
        val regex = RegexRuleMatcher.compile("Test\\.php", caseInsensitive = false)!!

        assertTrue(
            RegexRuleMatcher.matches(regex, "src/main/Test.php", isFolder = false, target = RegexTarget.FILE),
            "FILE 目标 + 文件 + 部分匹配应命中（find 语义）"
        )
        assertFalse(
            RegexRuleMatcher.matches(regex, "src/main", isFolder = true, target = RegexTarget.FILE),
            "FILE 目标遇目录应直接不命中"
        )
        assertFalse(
            RegexRuleMatcher.matches(regex, "src/main/Other.php", isFolder = false, target = RegexTarget.FILE),
            "模式未命中时应不命中"
        )
    }

    @Test
    fun `matches target FOLDER only hits folders`() {
        val regex = RegexRuleMatcher.compile("main", caseInsensitive = false)!!

        assertTrue(
            RegexRuleMatcher.matches(regex, "src/main", isFolder = true, target = RegexTarget.FOLDER),
            "FOLDER 目标 + 目录 + 部分匹配应命中"
        )
        assertFalse(
            RegexRuleMatcher.matches(regex, "src/main/X.php", isFolder = false, target = RegexTarget.FOLDER),
            "FOLDER 目标遇文件应直接不命中"
        )
    }

    @Test
    fun `matches target ALL hits both files and folders`() {
        val regex = RegexRuleMatcher.compile("api", caseInsensitive = false)!!

        assertTrue(
            RegexRuleMatcher.matches(regex, "src/api", isFolder = true, target = RegexTarget.ALL),
            "ALL 目标 + 目录应命中"
        )
        assertTrue(
            RegexRuleMatcher.matches(regex, "src/api/Client.php", isFolder = false, target = RegexTarget.ALL),
            "ALL 目标 + 文件应命中"
        )
        assertFalse(
            RegexRuleMatcher.matches(regex, "src/web", isFolder = true, target = RegexTarget.ALL),
            "ALL 目标 + 模式未命中应不命中"
        )
    }

    @Test
    fun `matches uses find semantics not full match`() {
        val regex = RegexRuleMatcher.compile("Service", caseInsensitive = false)!!
        assertTrue(
            RegexRuleMatcher.matches(regex, "app/UserService.php", isFolder = false, target = RegexTarget.ALL),
            "路径中间命中即算命中（containsMatchIn 语义）"
        )
        assertEquals(false, regex.matches("app/UserService.php"), "对照：全匹配语义下不应相等，确认实现用的是 find 而非 matches")
    }
}
