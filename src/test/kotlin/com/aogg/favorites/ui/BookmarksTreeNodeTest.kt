package com.aogg.favorites.ui

import com.aogg.favorites.model.Bookmark
import com.aogg.favorites.model.BookmarkType
import com.aogg.favorites.model.FavoriteScope
import com.aogg.favorites.model.ListInfo
import com.aogg.favorites.rules.RuleItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 收藏树节点用户对象展示语义单元测试（树视图修复 bc657bb/37f7bf4 相关纯逻辑）
 *
 * 说明：BookmarksTree 组件本身构造即依赖平台服务（TreeUIHelper/ActionManager/VFS 等），
 * 这里只测可独立构造的节点用户对象（Public）：
 * - LineEntryNode：行内容灰文本回退链（内容→描述→空）与 toString 格式（行内容实时更新原位改写 contentText）
 * - FileNode/DirNode/ScopeNode/BookmarkListNode：主文本与展开能力标记
 * - RuleItemNode：可跳转判定与超长条目文本截断（…）
 */
class BookmarksTreeNodeTest {

    private fun lineBookmark(lineNumber: Int = 4, mnemonic: Char? = null, description: String? = null) = Bookmark(
        id = "L1",
        type = BookmarkType.LINE,
        filePath = "src/A.php",
        lineNumber = lineNumber,
        mnemonic = mnemonic,
        description = description
    )

    // ==================== LineEntryNode 灰文本回退 ====================

    @Test
    fun `line entry gray text should prefer content text`() {
        val node = LineEntryNode(FavoriteScope.PROJECT, "收藏", lineBookmark(), "val x = 1")
        assertEquals("val x = 1", node.grayText(), "行内容优先")
    }

    @Test
    fun `line entry gray text should fall back to description when content missing`() {
        val node = LineEntryNode(FavoriteScope.PROJECT, "收藏", lineBookmark(description = "备注"), null)
        assertEquals("备注", node.grayText(), "内容读取失败（null）回退收藏描述")
    }

    @Test
    fun `line entry gray text should be empty when both missing`() {
        val blank = LineEntryNode(FavoriteScope.PROJECT, "收藏", lineBookmark(description = "   "), null)
        assertEquals("", blank.grayText(), "空白描述视为无描述")
    }

    @Test
    fun `line entry pending placeholder should be shown as is`() {
        // 异步加载中：contentText 为占位「…」，原位更新前展示占位
        val node = LineEntryNode(FavoriteScope.PROJECT, "收藏", lineBookmark(), "…")
        assertEquals("…", node.grayText())
    }

    @Test
    fun `line entry content text can be updated in place`() {
        // 防抖刷新完成后原位更新 contentText（不重建树，保持展开态）
        val node = LineEntryNode(FavoriteScope.PROJECT, "收藏", lineBookmark(), "…")
        node.contentText = "刷新后的内容"
        assertEquals("刷新后的内容", node.grayText())
    }

    @Test
    fun `line entry to string should be line number plus gray text with mnemonic prefix`() {
        val anonymous = LineEntryNode(FavoriteScope.PROJECT, "收藏", lineBookmark(lineNumber = 4), "code")
        assertEquals("5: code", anonymous.toString(), "展示行号为 1 基")

        val mnemonic = LineEntryNode(FavoriteScope.PROJECT, "收藏", lineBookmark(lineNumber = 0, mnemonic = 'A'), "code")
        assertEquals("[A] 1: code", mnemonic.toString(), "助记符前缀 + 1 基行号")
    }

    // ==================== FileNode / DirNode ====================

    @Test
    fun `file node to string should prefix mnemonic when present`() {
        val plain = FileNode(FavoriteScope.PROJECT, "收藏", "src/A.php", "A.php", null, emptyList())
        assertEquals("A.php", plain.toString())

        val marked = FileNode(
            FavoriteScope.PROJECT,
            "收藏",
            "src/A.php",
            "A.php",
            Bookmark(id = "F1", type = BookmarkType.FILE, filePath = "src/A.php", mnemonic = 'B'),
            emptyList()
        )
        assertEquals("[B] A.php", marked.toString())
    }

    @Test
    fun `dir node fs backed should be true for bookmark or fs source`() {
        val virtual = DirNode(FavoriteScope.PROJECT, "收藏", "src", "src")
        assertFalse(virtual.fsBacked, "纯虚拟目录不读文件系统")

        val withBookmark = DirNode(FavoriteScope.PROJECT, "收藏", "src", "src", bookmark = Bookmark(
            id = "D1", type = BookmarkType.FOLDER, filePath = "src"
        ))
        assertTrue(withBookmark.fsBacked, "文件夹收藏目录可展开读文件系统")

        val fromFs = DirNode(FavoriteScope.PROJECT, "收藏", "src", "src", fromFs = true)
        assertTrue(fromFs.fsBacked, "真实目录继续逐级懒加载")
    }

    // ==================== ScopeNode / BookmarkListNode 徽标 ====================

    @Test
    fun `scope and list node labels should carry total badge`() {
        assertEquals("项目收藏 (3)", ScopeNode(FavoriteScope.PROJECT, 3).toString())
        assertEquals("全局收藏 (0)", ScopeNode(FavoriteScope.GLOBAL, 0).toString())
        assertEquals("工作 (7)", BookmarkListNode(FavoriteScope.PROJECT, ListInfo(name = "工作"), 7).toString())
    }

    // ==================== RuleItemNode 可跳转与截断 ====================

    @Test
    fun `rule item jumpable should follow item type and path presence`() {
        assertTrue(RuleItemNode("r1", RuleItem.FileItem("a/b.php", false)).jumpable, "文件条目可跳转")
        assertFalse(RuleItemNode("r1", RuleItem.FileItem("", false)).jumpable, "空路径文件条目不可跳转")
        assertTrue(RuleItemNode("r1", RuleItem.RowItem(listOf("a"), "p/x.sql")).jumpable, "带路径行条目可跳转")
        assertFalse(RuleItemNode("r1", RuleItem.RowItem(listOf("a"), null)).jumpable, "无路径行条目不可跳转")
        assertFalse(RuleItemNode("r1", RuleItem.LineItem("text")).jumpable, "SSH 行条目仅展示")
    }

    @Test
    fun `rule item to string should show file name and joined cells`() {
        assertEquals("b.php", RuleItemNode("r1", RuleItem.FileItem("a/b.php", false)).toString())
        assertEquals("a  b", RuleItemNode("r1", RuleItem.RowItem(listOf("a", "b"), null)).toString(), "多列两空格拼接")
        assertEquals("text", RuleItemNode("r1", RuleItem.LineItem("text")).toString())
    }

    @Test
    fun `rule item long text should truncate with ellipsis`() {
        val longText = "x".repeat(130)
        val truncated = RuleItemNode("r1", RuleItem.LineItem(longText)).toString()
        assertEquals(121, truncated.length, "截断为 120 字符 + 省略号")
        assertTrue(truncated.endsWith("…"))

        val short = RuleItemNode("r1", RuleItem.LineItem("short")).toString()
        assertEquals("short", short, "短文本不截断")

        val rowTruncated = RuleItemNode("r1", RuleItem.RowItem(listOf("y".repeat(130)), null)).toString()
        assertTrue(rowTruncated.endsWith("…"), "行条目多列拼接超长同样截断")
    }

    @Test
    fun `rule item detail text should keep full content`() {
        assertEquals("a/b.php", RuleItemNode("r1", RuleItem.FileItem("a/b.php", false)).detailText())
        assertEquals("text", RuleItemNode("r1", RuleItem.LineItem("text")).detailText())
        assertEquals(
            "a\tb\n路径: p/x.sql",
            RuleItemNode("r1", RuleItem.RowItem(listOf("a", "b"), "p/x.sql")).detailText(),
            "行条目详情含各列与路径"
        )
    }
}
