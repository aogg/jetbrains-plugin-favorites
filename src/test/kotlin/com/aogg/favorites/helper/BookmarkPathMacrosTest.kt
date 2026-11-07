package com.aogg.favorites.helper

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.ThrowingSupplier

/**
 * 收藏路径宏展开工具（BookmarkPathMacros）单元测试（纯 JVM，不依赖 IDE 运行时）
 *
 * 覆盖：
 * - 空输入：null / 空串 / 全空白 → 空候选
 * - 无宏路径：只产出 1 条归一化候选（file:// 前缀剥离、`\`→`/`、首尾空白裁剪）
 * - `$PROJECT_DIR$`：有项目根时展开成绝对路径 + 保留原样宏；项目根为空时宏原样保留
 * - 已知宏大小写不敏感、尾随 `$` 可省略（`$project_dir/src/x.php`）
 * - 多宏并存：一次替换全部已知宏（`$PROJECT_DIR$` + `$USER_HOME$`）
 * - 宏不在开头：额外产出「从第一个宏位置截断再展开」的候选（历史坏数据典型形态）
 * - 未知宏：平台兜底不可用时原样保留，绝不抛异常
 * - 平台宏 `$APPLICATION_CONFIG_DIR$` / `$APPLICATION_SYSTEM_DIR$` / `$APPLICATION_HOME_DIR$`：只断言结构不变量
 *   （不抛异常 / 不拼项目根 / 不半残），不依赖 IDE 平台初始化
 * - 输出：保序去重、不含空串
 */
class BookmarkPathMacrosTest {

    /** Unix 风格项目根（测试用固定值，避免依赖本机真实路径） */
    private val basePath = "/work/demo"

    /** Windows 风格项目根（本次修复的用户场景） */
    private val winBase = "D:/proj"

    /** 用户主目录（与实现取值一致：System.getProperty("user.home")；统一正斜杠以便跨平台断言） */
    private val userHome = requireNotNull(System.getProperty("user.home")).trim().replace('\\', '/').trimEnd('/')

    /** 数据库控制台收藏的 uuid（取自真实历史坏数据） */
    private val consoleUuid = "34598bcc-3cf7-4eb6-a40c-af84cc1b20f6"

    /** Windows 盘符前缀（断言宏展开结果已是绝对路径） */
    private val drivePrefix = Regex("^[A-Za-z]:/")

    // ==================== 空输入 ====================

    @Test
    fun `blank input should produce no candidates`() {
        assertEquals(emptyList<String>(), BookmarkPathMacros.candidates(null, basePath))
        assertEquals(emptyList<String>(), BookmarkPathMacros.candidates("", basePath))
        assertEquals(emptyList<String>(), BookmarkPathMacros.candidates("   ", basePath))
    }

    // ==================== 无宏路径 ====================

    @Test
    fun `path without macro should keep single normalized candidate`() {
        // 相对路径原样
        assertEquals(listOf("src/a.php"), BookmarkPathMacros.candidates("src/a.php", basePath))
        // Windows 绝对路径原样
        assertEquals(listOf("D:/proj/src/x.php"), BookmarkPathMacros.candidates("D:/proj/src/x.php", winBase))
        // file:/// 前缀剥离后是 Unix 绝对路径（不拼项目根）
        assertEquals(listOf("/abs/a.php"), BookmarkPathMacros.candidates("file:///abs/a.php", basePath))
        // 首尾空白裁剪 + 反斜杠归一
        assertEquals(listOf("src/a.php"), BookmarkPathMacros.candidates("  src\\a.php  ", basePath))
        assertEquals(
            listOf("D:/proj/src/x.php"),
            BookmarkPathMacros.candidates("file://D:\\proj\\src\\x.php", winBase)
        )
    }

    // ==================== $PROJECT_DIR$ ====================

    @Test
    fun `project dir macro should expand with project base`() {
        // file:// + $PROJECT_DIR$：展开路径在前、原样宏在后
        assertEquals(
            listOf("D:/proj/src/x.php", "\$PROJECT_DIR\$/src/x.php"),
            BookmarkPathMacros.candidates("file://\$PROJECT_DIR\$/src/x.php", winBase)
        )
        // 项目根带尾随分隔符时展开结果不出现重复分隔符
        assertEquals(
            listOf("D:/proj/src/x.php", "\$PROJECT_DIR\$/src/x.php"),
            BookmarkPathMacros.candidates("\$PROJECT_DIR\$/src/x.php", "$winBase/")
        )
        // 尾随 $ 可省略 + 大小写不敏感：展开成功，原样候选保留用户原始写法
        assertEquals(
            listOf("D:/proj/src/x.php", "\$project_dir/src/x.php"),
            BookmarkPathMacros.candidates("\$project_dir\\src\\x.php", winBase)
        )
    }

    @Test
    fun `project dir macro should stay when project base missing`() {
        // 项目根缺失/空白时该宏无法展开，保持原样（不抛异常）
        assertEquals(
            listOf("\$PROJECT_DIR\$/src/x.php"),
            BookmarkPathMacros.candidates("\$PROJECT_DIR\$/src/x.php", null)
        )
        assertEquals(
            listOf("\$PROJECT_DIR\$/src/x.php"),
            BookmarkPathMacros.candidates("\$PROJECT_DIR\$/src/x.php", "   ")
        )
    }

    // ==================== 未知宏 / 多宏 ====================

    @Test
    fun `unknown macro should stay as is without throwing`() {
        // 纯 JVM 下平台 PathMacroManager 兜底不可用：已知宏之外的宏原样保留，且绝不抛异常
        val actual = assertDoesNotThrow(ThrowingSupplier {
            BookmarkPathMacros.candidates("\$FOO_BAR\$/a.sql", basePath)
        })
        assertEquals(listOf("\$FOO_BAR\$/a.sql"), actual)
    }

    @Test
    fun `multiple macros should expand all at once`() {
        // 一次替换全部已知宏（宏在开头，因此没有截断候选，只有展开 + 原样两条）
        assertEquals(
            listOf("$winBase/$userHome/src/a.php", "\$PROJECT_DIR\$/\$USER_HOME\$/src/a.php"),
            BookmarkPathMacros.candidates("\$PROJECT_DIR\$/\$USER_HOME\$/src/a.php", winBase)
        )
    }

    // ==================== 宏不在开头（历史坏数据） ====================

    @Test
    fun `macro not at start should add truncated candidate`() {
        // 项目根 + 未展开宏混拼：① 完整展开（含重复分隔符，交解析侧过滤）② 从宏位置截断展开 ③ 原样
        assertEquals(
            listOf(
                "$winBase/$userHome/consoles/u/console.sql",
                "$userHome/consoles/u/console.sql",
                "$winBase/\$USER_HOME\$/consoles/u/console.sql"
            ),
            BookmarkPathMacros.candidates("$winBase/\$USER_HOME\$/consoles/u/console.sql", winBase)
        )
    }

    // ==================== 已知宏集合：大小写 / 尾随 $ ====================

    @Test
    fun `known macros should be case insensitive and tolerate missing trailing dollar`() {
        assertEquals(
            listOf("$userHome/consoles/a.sql", "\$USER_HOME\$/consoles/a.sql"),
            BookmarkPathMacros.candidates("\$USER_HOME\$/consoles/a.sql", basePath)
        )
        // 不带尾随 $
        assertEquals(
            listOf("$userHome/consoles/a.sql", "\$user_home/consoles/a.sql"),
            BookmarkPathMacros.candidates("\$user_home/consoles/a.sql", basePath)
        )
        // 混合大小写
        assertEquals(
            listOf("$userHome/consoles/a.sql", "\$User_Home/consoles/a.sql"),
            BookmarkPathMacros.candidates("\$User_Home/consoles/a.sql", basePath)
        )
        assertEquals(
            listOf("D:/p/a.sql", "\$Project_Dir\$/a.sql"),
            BookmarkPathMacros.candidates("\$Project_Dir\$/a.sql", "D:/p")
        )
    }

    // ==================== 平台宏（只断言结构不变量） ====================

    @Test
    fun `platform macros should expand or stay whole without throwing`() {
        // $APPLICATION_*$ 三个平台宏依赖 IDE 平台初始化：不断言具体平台路径，只断言结构不变量
        assertPlatformMacroCandidates("APPLICATION_CONFIG_DIR")
        assertPlatformMacroCandidates("APPLICATION_SYSTEM_DIR")
        assertPlatformMacroCandidates("APPLICATION_HOME_DIR")
    }

    /**
     * 平台宏候选的结构不变量断言
     *
     * 平台可用 → 首个候选是已展开的绝对路径；平台不可用 → 整段宏原样保留（不允许半残展开）；
     * 两种情况都不得拼上项目根（旧 bug 的成因）、不得残留 file:// 协议前缀、不得半角反斜杠。
     */
    private fun assertPlatformMacroCandidates(macro: String) {
        val raw = "file://\$$macro\$/consoles/$consoleUuid/console.sql"
        val candidates = assertDoesNotThrow(ThrowingSupplier { BookmarkPathMacros.candidates(raw, basePath) })

        assertTrue(candidates.isNotEmpty(), "$macro 候选不应为空")
        // 可复现（同一输入多次调用结果一致）
        assertEquals(candidates, BookmarkPathMacros.candidates(raw, basePath))
        for (candidate in candidates) {
            assertFalse(candidate.contains("file:"), "$macro 候选不应保留 file:// 协议前缀：$candidate")
            assertFalse(candidate.contains('\\'), "$macro 候选应为正斜杠风格：$candidate")
            assertTrue(
                candidate.endsWith("consoles/$consoleUuid/console.sql"),
                "$macro 候选应保留原始尾部路径：$candidate"
            )
            assertFalse(candidate.startsWith("$basePath/"), "$macro 路径不得拼成项目相对路径：$candidate")
        }

        val first = candidates.first()
        if (first.contains('$')) {
            assertEquals(
                "\$$macro\$/consoles/$consoleUuid/console.sql",
                first,
                "$macro 未展开时应整段保留宏（不得半残）"
            )
        } else {
            assertTrue(
                first.startsWith("/") || drivePrefix.containsMatchIn(first),
                "$macro 展开后应为绝对路径：$first"
            )
        }
    }

    @Test
    fun `backslash application config dir url should normalize separators`() {
        // 历史坏数据里宏名后缺少尾随 $ 且分隔符是反斜杠：file://$APPLICATION_CONFIG_DIR\consoles/<uuid>\console.sql
        val raw = "file://\$APPLICATION_CONFIG_DIR\\consoles/$consoleUuid\\console.sql"
        val candidates = assertDoesNotThrow(ThrowingSupplier { BookmarkPathMacros.candidates(raw, basePath) })

        assertTrue(candidates.isNotEmpty())
        for (candidate in candidates) {
            assertFalse(candidate.contains('\\'), "反斜杠应归一为正斜杠：$candidate")
            assertTrue(candidate.endsWith("consoles/$consoleUuid/console.sql"), "应保留尾部路径：$candidate")
        }
    }

    // ==================== 输出契约：保序去重、无空串 ====================

    @Test
    fun `candidates should be deduplicated in order and never blank`() {
        val samples = listOf(
            "src/a.php",
            "file:///abs/a.php",
            "\$PROJECT_DIR\$/src/x.php",
            "\$PROJECT_DIR\$/src/x.php",
            "\$FOO_BAR\$/a.sql",
            "\$USER_HOME\$/consoles/a.sql",
            "$winBase/\$USER_HOME\$/consoles/u/console.sql",
            "file://\$APPLICATION_CONFIG_DIR\$/consoles/$consoleUuid/console.sql"
        )
        for (sample in samples) {
            for (base in listOf(basePath, winBase, null)) {
                val candidates = BookmarkPathMacros.candidates(sample, base)
                assertEquals(candidates.distinct(), candidates, "候选必须保序去重：$sample / $base")
                assertTrue(candidates.none { it.isBlank() }, "候选不得包含空串：$sample / $base")
            }
        }
        // 不可展开的宏（原样候选与展开候选相同）只保留一条，证明去重生效
        assertEquals(1, BookmarkPathMacros.candidates("\$FOO_BAR\$/a.sql", basePath).size)
    }
}
