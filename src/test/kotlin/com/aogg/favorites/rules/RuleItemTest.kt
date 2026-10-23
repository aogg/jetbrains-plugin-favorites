package com.aogg.favorites.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * RuleItem 三种条目的 searchText() 单元测试（树过滤数据来源）
 *
 * 覆盖：FileItem 取 path、RowItem 取 cells 制表符拼接（path 非空才追加）、LineItem 取 text。
 */
@DisplayName("RuleItem.searchText() 三种条目的过滤文本")
class RuleItemTest {

    @Test
    fun `file item search text is path`() {
        val item = RuleItem.FileItem(path = "src/main/Service.php", isFolder = false)

        assertEquals("src/main/Service.php", item.searchText(), "文件条目过滤文本应为相对路径")
    }

    @Test
    fun `row item search text joins cells and optional path`() {
        assertEquals(
            "a\tb\tc",
            RuleItem.RowItem(cells = listOf("a", "b", "c"), path = null).searchText(),
            "无 path 时应为各列制表符拼接"
        )
        assertEquals(
            "a\tb\tsrc/x.php",
            RuleItem.RowItem(cells = listOf("a", "b"), path = "src/x.php").searchText(),
            "有 path 时应在末尾追加制表符 + path"
        )
    }

    @Test
    fun `row item blank path is not appended`() {
        assertEquals(
            "a\tb",
            RuleItem.RowItem(cells = listOf("a", "b"), path = "   ").searchText(),
            "path 为纯空白时不应追加"
        )
        assertEquals(
            "a\tb",
            RuleItem.RowItem(cells = listOf("a", "b"), path = "").searchText(),
            "path 为空串时不应追加"
        )
    }

    @Test
    fun `line item search text is raw text`() {
        val item = RuleItem.LineItem(text = "  keep inner spaces  ")

        assertEquals("  keep inner spaces  ", item.searchText(), "SSH 行条目过滤文本应为原文（不去空白）")
    }
}
