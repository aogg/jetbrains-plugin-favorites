package com.aogg.favorites.ui

import com.aogg.favorites.model.Bookmark
import com.aogg.favorites.model.BookmarkType
import com.aogg.favorites.model.FavoriteScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * FavoritesListSpine（列表文件系统骨架）与 FavoritesTreePaths（路径工具）单元测试（1.3.0/1.5.0 树视图）
 *
 * 说明：被测类为 internal，测试源集与主源集同模块（friend）可直接访问。
 * - 回归 bc657bb「同路径去重」：同一路径 FILE+LINE 收藏在骨架中只产生一个文件条目（FILE 与 LINE 合并挂载）
 * - 虚拟目录链/公共祖先目录/文件夹收藏挂载
 * - FavoritesTreePaths：反斜杠规范化、盘符路径、前导斜杠、路径段拆拼、公共前缀
 */
class FavoritesListSpineTest {

    private fun line(id: String, filePath: String, lineNumber: Int, listName: String = "收藏") = Bookmark(
        id = id,
        type = BookmarkType.LINE,
        filePath = filePath,
        lineNumber = lineNumber,
        listName = listName
    )

    private fun file(id: String, filePath: String, listName: String = "收藏") = Bookmark(
        id = id,
        type = BookmarkType.FILE,
        filePath = filePath,
        listName = listName
    )

    private fun folder(id: String, filePath: String, listName: String = "收藏") = Bookmark(
        id = id,
        type = BookmarkType.FOLDER,
        filePath = filePath,
        listName = listName
    )

    private fun fileEntries(spine: FavoritesListSpine, dirPath: String) =
        spine.childrenOf(dirPath).filterIsInstance<SpineFileEntry>()

    private fun dirEntries(spine: FavoritesListSpine, dirPath: String) =
        spine.childrenOf(dirPath).filterIsInstance<SpineDirEntry>()

    // ==================== 同路径唯一文件条目（回归 bc657bb） ====================

    @Test
    fun `same path file and line bookmarks should merge into single file entry`() {
        // 回归：同一路径 FILE+LINE 只产生一个骨架文件节点（树侧另一半去重在 appendSpineChildren 幂等，平台耦合不测）
        val fileBookmark = file("F1", "src/A.php")
        val lineBookmark = line("L1", "src/A.php", 4)
        val spine = FavoritesListSpine.build(FavoriteScope.PROJECT, "收藏", listOf(fileBookmark, lineBookmark))

        // 文件挂靠父目录 "src"，即公共祖先目录
        val files = fileEntries(spine, "src")
        assertEquals(1, files.size, "同一路径 FILE+LINE 应合并为一个文件条目")
        assertEquals("A.php", files.single().name)
        assertEquals("src/A.php", files.single().path)
        assertEquals(fileBookmark, files.single().fileBookmark, "文件收藏挂同一文件条目")
        assertEquals(listOf(lineBookmark), files.single().lineBookmarks, "行收藏挂同一文件条目")
    }

    @Test
    fun `multiple line bookmarks on same file should share one entry in order`() {
        val l1 = line("L1", "src/A.php", 1)
        val l2 = line("L2", "src/A.php", 9)
        val spine = FavoritesListSpine.build(FavoriteScope.PROJECT, "收藏", listOf(l1, l2))

        val files = fileEntries(spine, "src")
        assertEquals(1, files.size, "同一路径多行收藏仍只有一个文件条目")
        assertEquals(listOf(l1, l2), files.single().lineBookmarks, "行收藏保持传入排序顺序")
        assertNull(files.single().fileBookmark, "仅行收藏时无文件收藏")
    }

    @Test
    fun `build should ignore blank file path dirty data`() {
        val dirty = line("L0", "  ", 1)
        val normal = line("L1", "src/A.php", 2)
        val spine = FavoritesListSpine.build(FavoriteScope.PROJECT, "收藏", listOf(dirty, normal))

        assertEquals(1, fileEntries(spine, "src").size, "路径为空的脏数据应被忽略")
    }

    // ==================== 公共祖先与虚拟目录链 ====================

    @Test
    fun `common ancestor should be computed from bookmark directories`() {
        val spine = FavoritesListSpine.build(
            FavoriteScope.PROJECT,
            "收藏",
            listOf(line("L1", "a/b/x.php", 1), line("L2", "a/b/y.php", 2))
        )
        assertEquals("a/b", spine.commonAncestorPath, "公共祖先目录取收藏挂靠目录的公共前缀")
        assertEquals(0, spine.childrenOf("").size, "公共祖先以上不再生成目录链")
        assertEquals(listOf("x.php", "y.php"), fileEntries(spine, "a/b").map { it.name })
    }

    @Test
    fun `root level bookmarks should produce base dir ancestor`() {
        val spine = FavoritesListSpine.build(
            FavoriteScope.PROJECT,
            "收藏",
            listOf(line("L1", "top.php", 1), line("L2", "a/x.php", 2))
        )
        assertEquals("", spine.commonAncestorPath, "公共祖先为作用域基准目录时路径为空串")
        val children = spine.childrenOf("")
        assertEquals(listOf("a", "top.php"), children.map { it.name }, "目录条目在前、文件条目在后")
        assertTrue(dirEntries(spine, "").single().path == "a", "虚拟目录路径按基准目录拼接")
    }

    @Test
    fun `deep dir chain should be created level by level below common ancestor`() {
        // 一深一浅两个收藏：公共祖先 "a"，其下逐级建 b/deep 链
        val spine = FavoritesListSpine.build(
            FavoriteScope.PROJECT,
            "收藏",
            listOf(line("L1", "a/b/deep/x.php", 1), line("L2", "a/y.php", 2))
        )
        assertEquals("a", spine.commonAncestorPath)
        assertTrue(spine.childrenOf("").isEmpty(), "公共祖先目录本身不作为骨架条目（由树侧渲染为顶层目录节点）")
        assertEquals(listOf("b", "y.php"), spine.childrenOf("a").map { it.name }, "目录条目在前、文件条目在后")
        assertEquals(listOf("deep"), dirEntries(spine, "a/b").map { it.name })
        assertEquals(listOf("x.php"), fileEntries(spine, "a/b/deep").map { it.name })
    }

    @Test
    fun `folder bookmark should attach to its own dir entry`() {
        val folderBookmark = folder("D1", "src/lib")
        val spine = FavoritesListSpine.build(
            FavoriteScope.PROJECT,
            "收藏",
            listOf(folderBookmark, file("F1", "src/lib/A.php"))
        )
        // 文件夹收藏挂自身路径目录，文件挂父目录 → 公共祖先即 "src/lib"
        assertEquals("src/lib", spine.commonAncestorPath)
        assertEquals(folderBookmark, spine.dirBookmarkAt("src/lib"), "文件夹收藏应挂在其自身目录路径上")
    }

    // ==================== 全局作用域绝对路径语义 ====================

    @Test
    fun `global scope should use absolute path semantics with leading slash`() {
        val spine = FavoritesListSpine.build(
            FavoriteScope.GLOBAL,
            "收藏",
            listOf(line("L1", "/var/www/a.php", 1), line("L2", "/var/www/b.php", 2))
        )
        assertEquals("/var/www", spine.commonAncestorPath, "全局作用域公共祖先补前导斜杠")
        assertEquals(listOf("/var/www/a.php", "/var/www/b.php"), fileEntries(spine, "/var/www").map { it.path })
    }

    @Test
    fun `global scope with divergent roots should build child chains from fs root`() {
        val spine = FavoritesListSpine.build(
            FavoriteScope.GLOBAL,
            "收藏",
            listOf(line("L1", "/a/x.php", 1), line("L2", "/b/y.php", 2))
        )
        assertEquals("", spine.commonAncestorPath, "仅文件系统根为公共祖先")
        assertEquals(listOf("/a", "/b"), dirEntries(spine, "").map { it.path }, "基准目录下的目录路径补前导斜杠")
    }

    // ==================== FavoritesTreePaths 路径工具 ====================

    @Test
    fun `is absolute path should detect unix windows drive and relative`() {
        assertTrue(FavoritesTreePaths.isAbsolutePath("/var/www/a.php"), "Unix 绝对路径")
        assertTrue(FavoritesTreePaths.isAbsolutePath("C:/x/a.php"), "Windows 盘符正斜杠")
        assertTrue(FavoritesTreePaths.isAbsolutePath("C:\\x\\a.php"), "反斜杠规范化后按盘符判定")
        assertTrue(!FavoritesTreePaths.isAbsolutePath("src/A.php"), "项目相对路径")
        assertTrue(!FavoritesTreePaths.isAbsolutePath("a.php"), "单段相对路径")
    }

    @Test
    fun `segments should split on slash ignore empty and normalize backslash`() {
        assertEquals(listOf("var", "www", "a.php"), FavoritesTreePaths.segments("/var/www/a.php"))
        assertEquals(listOf("src", "A.php"), FavoritesTreePaths.segments("src\\A.php"), "反斜杠按分隔符处理")
        assertEquals(emptyList<String>(), FavoritesTreePaths.segments("/"), "纯分隔符无路径段")
        assertEquals(emptyList<String>(), FavoritesTreePaths.segments(""), "空路径无路径段")
    }

    @Test
    fun `join should prepend slash for absolute scope except drive paths`() {
        assertEquals("/var/www", FavoritesTreePaths.join(listOf("var", "www"), absolute = true))
        assertEquals("src/A.php", FavoritesTreePaths.join(listOf("src", "A.php"), absolute = false))
        assertEquals("C:/x", FavoritesTreePaths.join(listOf("C:", "x"), absolute = true), "盘符开头不重复加前导斜杠")
        assertEquals("", FavoritesTreePaths.join(emptyList(), absolute = true), "空段=基准目录返回空串")
    }

    @Test
    fun `child path should handle base dir and fs root`() {
        assertEquals("/x", FavoritesTreePaths.childPath("", "x", absolute = true), "基准目录+绝对语义补前导斜杠")
        assertEquals("x", FavoritesTreePaths.childPath("", "x", absolute = false))
        assertEquals("/x", FavoritesTreePaths.childPath("/", "x", absolute = false), "文件系统根不叠加斜杠")
        assertEquals("a/x", FavoritesTreePaths.childPath("a", "x", absolute = false))
    }

    @Test
    fun `parent path should return base dir for top level entries`() {
        assertEquals("a/b", FavoritesTreePaths.parentPath("a/b/c.php"))
        assertEquals("", FavoritesTreePaths.parentPath("a.php"), "顶层文件父级为基准目录")
        assertEquals("", FavoritesTreePaths.parentPath("/a.php"), "根下文件父级同为基准目录")
        assertEquals("C:/x", FavoritesTreePaths.parentPath("C:/x/a.php"))
    }

    @Test
    fun `common prefix should shrink to longest shared directory chain`() {
        assertEquals(
            listOf("a", "b"),
            FavoritesTreePaths.commonPrefix(listOf(listOf("a", "b", "c"), listOf("a", "b", "d"), listOf("a", "b")))
        )
        assertEquals(emptyList<List<String>>(), FavoritesTreePaths.commonPrefix(emptyList()), "空入参返回基准目录")
        assertEquals(emptyList<List<String>>(), FavoritesTreePaths.commonPrefix(listOf(listOf("a"), listOf("b"))), "无公共前缀返回基准目录")
    }
}
