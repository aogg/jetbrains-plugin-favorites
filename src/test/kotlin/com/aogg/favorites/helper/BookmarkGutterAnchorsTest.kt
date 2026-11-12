package com.aogg.favorites.helper

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 行号区图标「行锚点」判定（BookmarkGutterAnchors）单元测试（纯 JVM，不依赖 IDE 运行时）
 *
 * 覆盖（对应 1.8.1 修复「缩进行不显示图标」）：
 * - 行首叶子（无缩进行，含行首空白 token）→ [BookmarkGutterAnchors.isLineAnchor] 命中
 * - 缩进行（行首无叶子，第一个叶子前是跨行空白 token）→ [BookmarkGutterAnchors.isIndentedLineAnchor] 命中
 * - 行中间叶子（前一个叶子已在同一行）→ 不命中（保证每行至多一个锚点，图标不重复）
 * - 零长叶子 → 不命中（平台要求跳过）
 * - 非叶子（复合元素）→ 不命中
 * - 跨行空白 token 自身 → 不命中（它属于上一行，不是本行锚点）
 * - 首行（lineStartOffset=0）、无前叶子（prevLeafOffset=-1）等边界
 * - [BookmarkGutterAnchors.isAnchorOfItsLineForElement] 组合判定与叶子判定一致
 */
class BookmarkGutterAnchorsTest {

    // ==================== 行首叶子（isLineAnchor） ====================

    @Test
    fun `leaf at line start should be anchor`() {
        // 无缩进行：叶子起点 == 行首，命中（无论有没有前一个叶子）
        assertTrue(BookmarkGutterAnchors.isLineAnchor(isLeaf = true, textLength = 5, elementOffset = 0, lineStartOffset = 0))
        assertTrue(BookmarkGutterAnchors.isLineAnchor(isLeaf = true, textLength = 1, elementOffset = 12, lineStartOffset = 12))
    }

    @Test
    fun `whitespace leaf starting at line start should also be anchor`() {
        // 行首空白 token（独立成 token，起点即行首）也是锚点——不允许排除 PsiWhiteSpace
        assertTrue(BookmarkGutterAnchors.isLineAnchor(isLeaf = true, textLength = 4, elementOffset = 12, lineStartOffset = 12))
    }

    @Test
    fun `leaf not at line start should not hit isLineAnchor`() {
        // 缩进行第一个叶子：起点在行首之后，isLineAnchor 不命中（由 isIndentedLineAnchor 兜底）
        assertFalse(BookmarkGutterAnchors.isLineAnchor(isLeaf = true, textLength = 2, elementOffset = 10, lineStartOffset = 6))
        // 行中间叶子不命中
        assertFalse(BookmarkGutterAnchors.isLineAnchor(isLeaf = true, textLength = 1, elementOffset = 14, lineStartOffset = 12))
    }

    // ==================== 缩进行兜底（isIndentedLineAnchor） ====================

    @Test
    fun `first leaf of indented line should be anchor`() {
        // 实测 PSI 形态：`<?php\n    $a = 1;` → 空白 token [5,10)="\n    "，第 2 行行首偏移 6 上无叶子；
        // 该行第一个叶子 `$a`[10,12)：起点 10 > 行首 6，前一个叶子（空白）起点 5 < 6 → 命中
        assertTrue(
            BookmarkGutterAnchors.isIndentedLineAnchor(
                isLeaf = true, textLength = 2, elementOffset = 10, lineStartOffset = 6, prevLeafOffset = 5
            )
        )
    }

    @Test
    fun `cross line whitespace token itself should not be anchor of the next line`() {
        // 跨行空白 token（"\n    " 起点 5 落在上一行）按它自己的行（第 0 行，行首 0）判定：
        // 5 > 0 但前一个叶子 `<?php` 起点 0 不小于 0 → 不命中（不重复产生图标）
        assertFalse(
            BookmarkGutterAnchors.isIndentedLineAnchor(
                isLeaf = true, textLength = 5, elementOffset = 5, lineStartOffset = 0, prevLeafOffset = 0
            )
        )
    }

    @Test
    fun `later leaf of the same line should not be anchor`() {
        // 同一行第二个叶子：前一个叶子起点 10 >= 行首 6 → 不命中（每行至多一个锚点）
        assertFalse(
            BookmarkGutterAnchors.isIndentedLineAnchor(
                isLeaf = true, textLength = 1, elementOffset = 13, lineStartOffset = 6, prevLeafOffset = 10
            )
        )
    }

    @Test
    fun `first leaf of first line without previous leaf should not double hit`() {
        // 文件第一个叶子（无前叶子 prevLeafOffset=-1）：若起点不在行首（理论边界），由行首规则或兜底判定；
        // 起点==行首时走 isLineAnchor，兜底条件 elementOffset > lineStartOffset 不成立 → 不会双命中
        assertFalse(
            BookmarkGutterAnchors.isIndentedLineAnchor(
                isLeaf = true, textLength = 5, elementOffset = 0, lineStartOffset = 0, prevLeafOffset = -1
            )
        )
    }

    // ==================== 组合判定（isAnchorOfItsLine / isAnchorOfItsLineForElement） ====================

    @Test
    fun `combined rule should accept exactly one anchor per line`() {
        // 模拟 `<?php\n    $a = 1;` 三个叶子按各自所在行判定，只应有一个锚点（$a）
        // 叶子1 `<?php`[0,5)：第 0 行行首 → 锚点
        assertTrue(
            BookmarkGutterAnchors.isAnchorOfItsLine(
                isLeaf = true, textLength = 5, elementOffset = 0, lineStartOffset = 0, prevLeafOffset = -1
            )
        )
        // 叶子2 空白"\n    "[5,10)：按第 0 行判定 → 不是任何行锚点
        assertFalse(
            BookmarkGutterAnchors.isAnchorOfItsLine(
                isLeaf = true, textLength = 5, elementOffset = 5, lineStartOffset = 0, prevLeafOffset = 0
            )
        )
        // 叶子3 `$a`[10,12)：第 1 行（行首 6）唯一锚点
        assertTrue(
            BookmarkGutterAnchors.isAnchorOfItsLine(
                isLeaf = true, textLength = 2, elementOffset = 10, lineStartOffset = 6, prevLeafOffset = 5
            )
        )
        // 叶子4 ` =`（同行后续叶子，前叶子起点 12 >= 6）→ 不命中
        assertFalse(
            BookmarkGutterAnchors.isAnchorOfItsLine(
                isLeaf = true, textLength = 2, elementOffset = 12, lineStartOffset = 6, prevLeafOffset = 10
            )
        )
    }

    @Test
    fun `zero length leaf should never be anchor`() {
        // 零长叶子（如 Python 缩进标记）：两条规则都不命中
        assertFalse(
            BookmarkGutterAnchors.isAnchorOfItsLine(
                isLeaf = true, textLength = 0, elementOffset = 6, lineStartOffset = 6, prevLeafOffset = 5
            )
        )
        assertFalse(
            BookmarkGutterAnchors.isAnchorOfItsLine(
                isLeaf = true, textLength = 0, elementOffset = 10, lineStartOffset = 6, prevLeafOffset = 5
            )
        )
    }

    @Test
    fun `composite element should never be anchor`() {
        // 平台要求只为叶子元素建 marker：复合元素（hasChild=true）两条规则都不命中
        assertFalse(
            BookmarkGutterAnchors.isAnchorOfItsLineForElement(
                hasChild = true, textLength = 5, elementOffset = 0, lineStartOffset = 0, prevLeafOffset = -1
            )
        )
        // 叶子（hasChild=false）行为与 isAnchorOfItsLine 一致
        assertTrue(
            BookmarkGutterAnchors.isAnchorOfItsLineForElement(
                hasChild = false, textLength = 5, elementOffset = 0, lineStartOffset = 0, prevLeafOffset = -1
            )
        )
    }

    @Test
    fun `line with anchor exactly at line start should not allow duplicate fallback anchor`() {
        // 行首叶子存在时，行内后续叶子绝不允许再命中兜底规则（唯一性核心断言）：
        // 行首叶子 offset==L；其后任一叶子 prevLeafOffset >= L → isIndentedLineAnchor false
        val lineStart = 20
        // 行首叶子
        assertTrue(
            BookmarkGutterAnchors.isAnchorOfItsLine(
                isLeaf = true, textLength = 1, elementOffset = lineStart, lineStartOffset = lineStart, prevLeafOffset = lineStart - 8
            )
        )
        // 同行第二个叶子（prevLeaf 即行首叶子）
        assertFalse(
            BookmarkGutterAnchors.isAnchorOfItsLine(
                isLeaf = true, textLength = 3, elementOffset = lineStart + 1, lineStartOffset = lineStart, prevLeafOffset = lineStart
            )
        )
    }
}
