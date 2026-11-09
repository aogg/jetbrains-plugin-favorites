package com.aogg.favorites.helper

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.ThrowingSupplier

/**
 * 菜单动作显示名工具（FavoritesActionTexts）单元测试（纯 JVM，不依赖 IDE 运行时）
 *
 * 背景：1.8.0 起「收藏 >」二级菜单组标题、各菜单动作文案、「跳转到收藏 [X]」等显示名统一走
 * `FavoritesActionTexts.of(base)`：开启「隐藏 PhpStorm 原生书签界面」（默认）时用短名；
 * 关闭时追加常量 PLUGIN_SUFFIX（中文全角括号包裹的插件名），以便与同屏出现的原生「书签」项区分。
 *
 * 覆盖：
 * - PLUGIN_SUFFIX 常量值 = `（Favorites）`（中文全角括号，不是 ASCII 括号）
 * - `of(base, true)`：原样返回 base，结果不含后缀
 * - `of(base, false)`：等于 `base + PLUGIN_SUFFIX`，且后缀只出现一次（不重复叠加）
 * - base 用 1.8.0 各入口的实际文案：添加收藏 / 添加助记收藏 / 收藏 / 收藏文件夹 / 收藏文件 /
 *   跳转到收藏 [X]（X 覆盖 0、9、A、Z）/ 点击跳转（行号图标提示短名，纯透传）
 * - 边界：空串（true→空串；false→仅后缀：实现是裸字符串拼接，不做空串特判）、
 *   全空白串（不 trim）、已含后缀的 base（不去重，false 时再叠一层 → 见该用例注释，属潜在叠加点）
 * - `of(base)`（读设置的重载）：裸 JVM 下 FavoritesSettings 实例不可用，实现用 runCatching 兜底 true，
 *   断言「不抛异常且返回 base 短名」
 */
class FavoritesActionTextsTest {

    /**
     * 1.8.0 各菜单入口实际使用的 base 文案（短名，是否加后缀由开关决定）
     *
     * 取值来源：AddAnonymousBookmarkAction / AddMnemonicBookmarkAction / AddFileFolderFavoriteAction /
     * FavoritesActionGroup 的 BASE_TEXT，AddFileFolderFavoriteAction 的选中类型分支文案，
     * JumpToMnemonicBookmarkAction 的 `跳转到收藏 [X]`（X = 助记符 0-9 / A-Z），以及行号图标的「点击跳转」短名。
     */
    private val baseTexts = listOf(
        "添加收藏", // AddAnonymousBookmarkAction / 原生 F11 接管转发项
        "添加助记收藏", // AddMnemonicBookmarkAction / 原生 Ctrl+F11 接管转发项
        "收藏", // AddFileFolderFavoriteAction（混合多选）/ FavoritesActionGroup 组标题
        "收藏文件夹", // AddFileFolderFavoriteAction（全选文件夹）
        "收藏文件", // AddFileFolderFavoriteAction（全选文件）
        "跳转到收藏 [1]", // JumpToMnemonicBookmarkAction（数字助记符）
        "跳转到收藏 [A]", // JumpToMnemonicBookmarkAction（字母助记符）
        "点击跳转" // 行号图标点击提示的短名（通用透传用例）
    )

    /** 断言后缀常量本身：中文全角括号 + 插件名（IDE 中文菜单风格） */
    @Test
    fun `plugin suffix should be full width chinese parens`() {
        assertEquals("（Favorites）", FavoritesActionTexts.PLUGIN_SUFFIX)
        assertTrue(
            FavoritesActionTexts.PLUGIN_SUFFIX.startsWith("（"),
            "后缀应以中文全角左括号「（」开头"
        )
        assertTrue(
            FavoritesActionTexts.PLUGIN_SUFFIX.endsWith("）"),
            "后缀应以中文全角右括号「）」结尾"
        )
        // 防止有人把全角括号改成 ASCII 括号：(Favorites) 与中文 IDE 菜单风格不一致
        assertFalse(
            FavoritesActionTexts.PLUGIN_SUFFIX.contains("(Favorites)"),
            "后缀不应使用 ASCII 括号版本"
        )
    }

    /** 原生书签界面已隐藏（默认）：所有 base 原样返回，不加后缀 */
    @Test
    fun `hidden native bookmarks should keep base text without suffix`() {
        for (base in baseTexts) {
            val actual = FavoritesActionTexts.of(base, true)
            assertEquals(base, actual, "隐藏原生书签界面时 base=「$base」应原样返回")
            assertFalse(
                actual.contains(FavoritesActionTexts.PLUGIN_SUFFIX),
                "隐藏原生书签界面时 base=「$base」不应含后缀，实际「$actual」"
            )
        }
    }

    /** 原生书签界面可见：所有 base 追加一次后缀（不重复叠加） */
    @Test
    fun `visible native bookmarks should append suffix exactly once`() {
        for (base in baseTexts) {
            val actual = FavoritesActionTexts.of(base, false)
            assertEquals(
                base + FavoritesActionTexts.PLUGIN_SUFFIX,
                actual,
                "未隐藏原生书签界面时 base=「$base」应等于 base+后缀"
            )
            assertEquals(
                1,
                countOccurrences(actual, FavoritesActionTexts.PLUGIN_SUFFIX),
                "base=「$base」的后缀只应出现一次，实际「$actual」"
            )
        }
    }

    /** 助记符跳转文案：数字 0/9 与字母 A/Z 在两种开关下都遵循同一规则（[X] 原样保留） */
    @Test
    fun `mnemonic jump base should keep mnemonic bracket under both switch values`() {
        for (mnemonic in listOf("0", "9", "A", "Z")) {
            val base = "跳转到收藏 [$mnemonic]"
            assertEquals(base, FavoritesActionTexts.of(base, true), "助记符 [$mnemonic] 隐藏时应原样返回")
            assertEquals(
                base + FavoritesActionTexts.PLUGIN_SUFFIX,
                FavoritesActionTexts.of(base, false),
                "助记符 [$mnemonic] 未隐藏时应追加一次后缀"
            )
        }
    }

    /**
     * 空串边界（按源码实际语义断言）
     *
     * 实现为 `if (hidden) base else base + PLUGIN_SUFFIX`，没有空串特判：
     * hidden=true 得到空串；hidden=false 得到「只有后缀」。
     */
    @Test
    fun `empty base should stay empty when hidden and become suffix only when visible`() {
        assertEquals("", FavoritesActionTexts.of("", true), "空串 + 隐藏 → 空串")
        assertEquals(
            FavoritesActionTexts.PLUGIN_SUFFIX,
            FavoritesActionTexts.of("", false),
            "空串 + 未隐藏 → 仅后缀（实现不做空串特判）"
        )
    }

    /** 全空白串边界：实现不 trim，空白原样保留（后缀追加在末尾） */
    @Test
    fun `blank base should not be trimmed`() {
        assertEquals("   ", FavoritesActionTexts.of("   ", true), "全空白串隐藏时应原样返回（不 trim）")
        assertEquals(
            "   " + FavoritesActionTexts.PLUGIN_SUFFIX,
            FavoritesActionTexts.of("   ", false),
            "全空白串未隐藏时应保留空白再追加后缀"
        )
    }

    /**
     * 已含后缀的 base：实现只做字符串拼接，不去重也不剥离，未隐藏时会再叠一层
     *
     * 现状如实断言（不改 src/main）：当前各入口传入的 base 都是短名常量，正常链路不会走到这里；
     * 但若以后有调用方把「已加过后缀」的文案（如 presentation.text）再次传进来，就会出现双层后缀。
     * 这一点在测试报告里作为潜在问题上报。
     */
    @Test
    fun `already suffixed base should be appended again because there is no dedup guard`() {
        val base = "添加收藏" + FavoritesActionTexts.PLUGIN_SUFFIX
        assertEquals(base, FavoritesActionTexts.of(base, true), "隐藏时应原样返回（不剥离既有后缀）")
        val visible = FavoritesActionTexts.of(base, false)
        assertEquals(
            base + FavoritesActionTexts.PLUGIN_SUFFIX,
            visible,
            "未隐藏时无条件再追加一次后缀（实现无去重）"
        )
        assertEquals(
            2,
            countOccurrences(visible, FavoritesActionTexts.PLUGIN_SUFFIX),
            "已含后缀的 base 会叠加成两层，实际「$visible」"
        )
    }

    /** `of(base)` 读设置重载：裸 JVM 下读设置失败 → 兜底 true（短名）且不抛异常 */
    @Test
    fun `settings overload should fall back to hidden default on bare jvm`() {
        // 裸 JVM（无 IDE Application）下 FavoritesSettings 实例不可用，
        // 实现用 runCatching 兜底 true，因此这里应返回 base 短名而不是抛异常。
        val actual = assertDoesNotThrow(ThrowingSupplier { FavoritesActionTexts.of("添加收藏") })
        assertEquals("添加收藏", actual, "读设置失败应兜底「隐藏」语义，返回 base 短名")
        assertFalse(
            actual.contains(FavoritesActionTexts.PLUGIN_SUFFIX),
            "兜底路径不应追加后缀，实际「$actual」"
        )
        // 空串走同一兜底路径
        assertEquals("", FavoritesActionTexts.of(""))
    }

    /**
     * 统计 target 在 source 中出现的次数（断言后缀不重复叠加用）
     *
     * @param source 被搜索的字符串
     * @param target 要统计的子串（空串直接返回 0，避免死循环）
     * @return 出现次数
     */
    private fun countOccurrences(source: String, target: String): Int {
        if (target.isEmpty()) return 0
        var count = 0
        var index = source.indexOf(target)
        while (index >= 0) {
            count++
            index = source.indexOf(target, index + target.length)
        }
        return count
    }
}
