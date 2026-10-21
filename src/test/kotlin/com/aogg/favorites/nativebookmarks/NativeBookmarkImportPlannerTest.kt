package com.aogg.favorites.nativebookmarks

import com.aogg.favorites.model.Bookmark
import com.aogg.favorites.model.BookmarkType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 原生书签导入规划器单元测试（纯逻辑，不依赖 IDE 运行时）
 *
 * 覆盖：
 * - 映射规则：行号>=0→LINE；目录→FOLDER；文件→FILE
 * - 目标列表：原生列表同名创建；无列表→"原生书签"兜底；已存在列表不重复创建
 * - 去重：与现有收藏按 (相对路径+行号)/路径 比对；同批次内去重；重复执行不产生重复
 * - 助记符：原生有则带过来；被占用/同批次冲突时降级为匿名收藏
 */
class NativeBookmarkImportPlannerTest {

    // ==================== 映射规则 ====================

    @Test
    fun `map line folder and file bookmarks`() {
        val plan = NativeBookmarkImportPlanner.plan(
            items = listOf(
                NativeBookmarkItem(relativePath = "src/a.php", line = 10),
                NativeBookmarkItem(relativePath = "assets", isDirectory = true),
                NativeBookmarkItem(relativePath = "README.md")
            ),
            existingKeys = emptySet(),
            usedMnemonics = emptySet(),
            existingListNames = setOf(Bookmark.DEFAULT_LIST_NAME)
        )

        assertEquals(3, plan.bookmarks.size)
        assertEquals(BookmarkType.LINE, plan.bookmarks[0].type)
        assertEquals(10, plan.bookmarks[0].lineNumber)
        assertEquals(BookmarkType.FOLDER, plan.bookmarks[1].type)
        assertEquals(-1, plan.bookmarks[1].lineNumber)
        assertEquals(BookmarkType.FILE, plan.bookmarks[2].type)
        assertEquals(0, plan.skippedDuplicates)
    }

    // ==================== 目标列表 ====================

    @Test
    fun `use native list name or fallback list`() {
        val plan = NativeBookmarkImportPlanner.plan(
            items = listOf(
                NativeBookmarkItem(relativePath = "src/a.php", line = 1, listName = "核心代码"),
                NativeBookmarkItem(relativePath = "src/b.php", line = 2, listName = "  ")
            ),
            existingKeys = emptySet(),
            usedMnemonics = emptySet(),
            existingListNames = setOf(Bookmark.DEFAULT_LIST_NAME)
        )

        assertEquals(listOf("核心代码", NativeBookmarkImportPlanner.FALLBACK_LIST_NAME), plan.listsToCreate)
        assertEquals("核心代码", plan.bookmarks[0].listName)
        assertEquals(NativeBookmarkImportPlanner.FALLBACK_LIST_NAME, plan.bookmarks[1].listName)
    }

    @Test
    fun `do not recreate existing lists`() {
        val plan = NativeBookmarkImportPlanner.plan(
            items = listOf(NativeBookmarkItem(relativePath = "src/a.php", listName = "已有列表")),
            existingKeys = emptySet(),
            usedMnemonics = emptySet(),
            existingListNames = setOf(Bookmark.DEFAULT_LIST_NAME, "已有列表")
        )

        assertTrue(plan.listsToCreate.isEmpty(), "已存在的列表不应重复创建")
        assertEquals("已有列表", plan.bookmarks[0].listName)
    }

    // ==================== 去重 ====================

    @Test
    fun `skip duplicates against existing bookmarks`() {
        val existing = listOf(
            Bookmark(id = "1", type = BookmarkType.LINE, filePath = "src/a.php", lineNumber = 10),
            Bookmark(id = "2", type = BookmarkType.FILE, filePath = "README.md")
        )
        val plan = NativeBookmarkImportPlanner.plan(
            items = listOf(
                NativeBookmarkItem(relativePath = "src/a.php", line = 10), // 与现有行收藏重复
                NativeBookmarkItem(relativePath = "src/a.php", line = 11), // 同文件不同行 → 保留
                NativeBookmarkItem(relativePath = "README.md"),            // 与现有文件收藏重复
                NativeBookmarkItem(relativePath = "new.php")               // 全新 → 保留
            ),
            existingKeys = NativeBookmarkImportPlanner.existingKeysOf(existing),
            usedMnemonics = emptySet(),
            existingListNames = setOf(Bookmark.DEFAULT_LIST_NAME)
        )

        assertEquals(2, plan.bookmarks.size, "重复项应跳过，仅保留 2 条")
        assertEquals(2, plan.skippedDuplicates)
        assertEquals("src/a.php", plan.bookmarks[0].filePath)
        assertEquals(11, plan.bookmarks[0].lineNumber)
    }

    @Test
    fun `deduplicate within batch`() {
        val plan = NativeBookmarkImportPlanner.plan(
            items = listOf(
                NativeBookmarkItem(relativePath = "src/a.php", line = 1),
                NativeBookmarkItem(relativePath = "src/a.php", line = 1) // 批内重复
            ),
            existingKeys = emptySet(),
            usedMnemonics = emptySet(),
            existingListNames = setOf(Bookmark.DEFAULT_LIST_NAME)
        )

        assertEquals(1, plan.bookmarks.size)
        assertEquals(1, plan.skippedDuplicates)
    }

    @Test
    fun `repeatable import produces no duplicates`() {
        val items = listOf(
            NativeBookmarkItem(relativePath = "src/a.php", line = 1, mnemonic = 'A', listName = "核心"),
            NativeBookmarkItem(relativePath = "src/b.php", line = 2),
            NativeBookmarkItem(relativePath = "docs", isDirectory = true)
        )

        // 第一次导入
        val firstPlan = NativeBookmarkImportPlanner.plan(
            items, emptySet(), emptySet(), setOf(Bookmark.DEFAULT_LIST_NAME)
        )
        assertEquals(3, firstPlan.bookmarks.size)
        assertEquals(0, firstPlan.skippedDuplicates)

        // 模拟导入后的收藏库状态
        val imported = firstPlan.bookmarks
        val importedKeys = NativeBookmarkImportPlanner.existingKeysOf(imported)
        val importedMnemonics = imported.mapNotNull { it.mnemonic }.toSet()
        val importedLists = setOf(Bookmark.DEFAULT_LIST_NAME) + firstPlan.listsToCreate

        // 第二次导入相同内容 → 全部判重跳过（增量合并幂等）
        val secondPlan = NativeBookmarkImportPlanner.plan(
            items, importedKeys, importedMnemonics, importedLists
        )
        assertEquals(0, secondPlan.bookmarks.size, "重复导入不应产生新收藏")
        assertEquals(3, secondPlan.skippedDuplicates)
        assertTrue(secondPlan.listsToCreate.isEmpty(), "重复导入不应重复建列表")
    }

    @Test
    fun `skip non importable items`() {
        val plan = NativeBookmarkImportPlanner.plan(
            items = listOf(NativeBookmarkItem(relativePath = "")),
            existingKeys = emptySet(),
            usedMnemonics = emptySet(),
            existingListNames = emptySet()
        )

        assertEquals(0, plan.bookmarks.size)
        assertEquals(1, plan.skippedDuplicates)
    }

    // ==================== 助记符降级 ====================

    @Test
    fun `keep native mnemonic when free`() {
        val plan = NativeBookmarkImportPlanner.plan(
            items = listOf(NativeBookmarkItem(relativePath = "src/a.php", line = 1, mnemonic = 'A')),
            existingKeys = emptySet(),
            usedMnemonics = emptySet(),
            existingListNames = setOf(Bookmark.DEFAULT_LIST_NAME)
        )

        assertEquals('A', plan.bookmarks[0].mnemonic)
        assertTrue(plan.bookmarks[0].isMnemonic())
    }

    @Test
    fun `degrade to anonymous when mnemonic occupied by existing bookmark`() {
        val plan = NativeBookmarkImportPlanner.plan(
            items = listOf(NativeBookmarkItem(relativePath = "src/a.php", line = 1, mnemonic = 'A')),
            existingKeys = emptySet(),
            usedMnemonics = setOf('A'),
            existingListNames = setOf(Bookmark.DEFAULT_LIST_NAME)
        )

        assertNull(plan.bookmarks[0].mnemonic, "助记符已被占用时应降级为匿名收藏")
        assertTrue(plan.bookmarks[0].isAnonymous())
    }

    @Test
    fun `degrade second conflicting mnemonic within batch`() {
        val plan = NativeBookmarkImportPlanner.plan(
            items = listOf(
                NativeBookmarkItem(relativePath = "src/a.php", line = 1, mnemonic = 'B'),
                NativeBookmarkItem(relativePath = "src/b.php", line = 2, mnemonic = 'B') // 批内冲突
            ),
            existingKeys = emptySet(),
            usedMnemonics = emptySet(),
            existingListNames = setOf(Bookmark.DEFAULT_LIST_NAME)
        )

        assertEquals('B', plan.bookmarks[0].mnemonic)
        assertNull(plan.bookmarks[1].mnemonic, "批内助记符冲突时后者应降级为匿名收藏")
    }

    @Test
    fun `drop invalid mnemonic chars`() {
        val plan = NativeBookmarkImportPlanner.plan(
            items = listOf(NativeBookmarkItem(relativePath = "src/a.php", line = 1, mnemonic = '!')),
            existingKeys = emptySet(),
            usedMnemonics = emptySet(),
            existingListNames = setOf(Bookmark.DEFAULT_LIST_NAME)
        )

        assertNull(plan.bookmarks[0].mnemonic, "非法助记符应忽略")
    }

    // ==================== 描述透传 ====================

    @Test
    fun `carry over description and trim blank`() {
        val plan = NativeBookmarkImportPlanner.plan(
            items = listOf(
                NativeBookmarkItem(relativePath = "src/a.php", line = 1, description = "  入口  "),
                NativeBookmarkItem(relativePath = "src/b.php", line = 2, description = "   ")
            ),
            existingKeys = emptySet(),
            usedMnemonics = emptySet(),
            existingListNames = setOf(Bookmark.DEFAULT_LIST_NAME)
        )

        assertEquals("入口", plan.bookmarks[0].description)
        assertNull(plan.bookmarks[1].description, "空白描述应归一为 null")
    }
}
