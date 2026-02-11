package com.aogg.favorites.model

import com.aogg.favorites.helper.BookmarkStorageHelper
import com.intellij.openapi.project.Project
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito

/**
 * BookmarkManager 核心逻辑单元测试
 *
 * 说明：
 * - 这里使用 Mockito 模拟 Project 和 BookmarkStorageHelper
 * - 只测试纯业务逻辑，不依赖真实 IntelliJ 环境
 */
class BookmarkManagerTest {

    private lateinit var project: Project
    private lateinit var storageHelper: BookmarkStorageHelper
    private lateinit var manager: BookmarkManager

    @BeforeEach
    fun setUp() {
        project = Mockito.mock(Project::class.java)
        storageHelper = Mockito.mock(BookmarkStorageHelper::class.java)

        // 模拟持久化层：初始时返回空数据，save 不做任何事
        Mockito.`when`(storageHelper.loadBookmarks())
            .thenReturn(emptyList<Bookmark>() to emptyMap())
        Mockito.doNothing().`when`(storageHelper)
            .saveBookmarks(Mockito.anyList(), Mockito.anyMap())

        manager = BookmarkManager(project)
        manager.setStorageHelperForTest(storageHelper)
    }

    @AfterEach
    fun tearDown() {
        manager.clearAll()
    }

    @Test
    fun `add and get anonymous bookmark`() {
        val bookmark = Bookmark(
            id = "1",
            type = BookmarkType.LINE,
            filePath = "src/Test.php",
            lineNumber = 10
        )

        val added = manager.addBookmark(bookmark)
        assertTrue(added, "首次添加匿名收藏应成功")
        assertEquals(1, manager.getTotalCount(), "总数应为 1")
        assertEquals(1, manager.getAnonymousCount(), "匿名收藏数量应为 1")
        assertEquals(0, manager.getMnemonicCount(), "助记符收藏数量应为 0")

        val fetched = manager.getBookmark("1")
        assertNotNull(fetched, "应能通过 ID 获取收藏")
        assertEquals(bookmark.id, fetched!!.id)
    }

    @Test
    fun `add mnemonic bookmark and prevent duplicate mnemonic`() {
        val bookmarkA = Bookmark(
            id = "A",
            type = BookmarkType.LINE,
            filePath = "src/Test.php",
            lineNumber = 1,
            mnemonic = 'A'
        )
        val bookmarkA2 = Bookmark(
            id = "A2",
            type = BookmarkType.LINE,
            filePath = "src/Test.php",
            lineNumber = 2,
            mnemonic = 'A'
        )

        assertTrue(manager.addBookmark(bookmarkA), "首次使用助记符 A 应成功")
        assertFalse(manager.addBookmark(bookmarkA2), "重复使用助记符 A 应失败")

        assertEquals(1, manager.getMnemonicCount(), "助记符收藏数量应为 1")
        assertFalse(manager.isMnemonicAvailable('A'), "助记符 A 不应可用")
        assertTrue(manager.isMnemonicAvailable('B'), "助记符 B 应可用")

        val byMnemonic = manager.getBookmarkByMnemonic('A')
        assertNotNull(byMnemonic, "应能通过助记符获取收藏")
        assertEquals(bookmarkA.id, byMnemonic!!.id)
    }

    @Test
    fun `line file and folder indexes should work`() {
        val lineBookmark = Bookmark(
            id = "L1",
            type = BookmarkType.LINE,
            filePath = "src/Line.php",
            lineNumber = 5
        )
        val fileBookmark = Bookmark(
            id = "F1",
            type = BookmarkType.FILE,
            filePath = "src/File.php"
        )
        val folderBookmark = Bookmark(
            id = "D1",
            type = BookmarkType.FOLDER,
            filePath = "src/folder"
        )

        manager.addBookmark(lineBookmark)
        manager.addBookmark(fileBookmark)
        manager.addBookmark(folderBookmark)

        val lineList = manager.getLineBookmarks("src/Line.php", 5)
        assertEquals(1, lineList.size, "指定行应有 1 个收藏")
        assertEquals("L1", lineList.first().id)

        val fileList = manager.getBookmarksByFile("src/File.php")
        assertEquals(1, fileList.size, "文件应有 1 个收藏")
        assertEquals("F1", fileList.first().id)

        val folderList = manager.getBookmarksByFolder("src/folder")
        assertEquals(1, folderList.size, "文件夹应有 1 个收藏")
        assertEquals("D1", folderList.first().id)
    }

    @Test
    fun `update description should be persisted in manager`() {
        val bookmark = Bookmark(
            id = "D1",
            type = BookmarkType.LINE,
            filePath = "src/Test.php",
            lineNumber = 3
        )
        manager.addBookmark(bookmark)

        val updated = manager.updateBookmarkDescription("D1", "新的描述")
        assertTrue(updated, "更新描述应成功")

        val fetched = manager.getBookmark("D1")
        assertNotNull(fetched)
        assertEquals("新的描述", fetched!!.description, "描述应更新")
    }

    @Test
    fun `remove by path should remove children when includeChildren is true`() {
        val parent = Bookmark(
            id = "P",
            type = BookmarkType.FILE,
            filePath = "src/parent/Parent.php"
        )
        val child = Bookmark(
            id = "C",
            type = BookmarkType.FILE,
            filePath = "src/parent/child/Child.php"
        )
        manager.addBookmark(parent)
        manager.addBookmark(child)

        val removed = manager.removeBookmarksByPath("src/parent", includeChildren = true)
        assertEquals(2, removed, "应删除父路径下的所有收藏")
        assertEquals(0, manager.getTotalCount(), "总数应为 0")
    }

    @Test
    fun `remove by path should only remove exact path when includeChildren is false`() {
        val parent = Bookmark(
            id = "P",
            type = BookmarkType.FILE,
            filePath = "src/parent/Parent.php"
        )
        val child = Bookmark(
            id = "C",
            type = BookmarkType.FILE,
            filePath = "src/parent/child/Child.php"
        )
        manager.addBookmark(parent)
        manager.addBookmark(child)

        val removed = manager.removeBookmarksByPath("src/parent/Parent.php", includeChildren = false)
        assertEquals(1, removed, "仅应删除精确路径的收藏")
        assertEquals(1, manager.getTotalCount(), "总数应为 1")
        assertNotNull(manager.getBookmark("C"), "子路径收藏应保留")
    }

    @Test
    fun `update path should move bookmarks correctly`() {
        val bookmark = Bookmark(
            id = "M1",
            type = BookmarkType.FILE,
            filePath = "src/old/Path.php"
        )
        manager.addBookmark(bookmark)

        val updatedCount = manager.updateBookmarksPath(
            oldPath = "src/old",
            newPath = "src/new",
            includeChildren = true
        )
        assertEquals(1, updatedCount, "应更新一个路径")

        val all = manager.getAllBookmarks()
        assertEquals(1, all.size)
        assertEquals("src/new/Path.php", all.first().filePath, "路径应已更新")
    }

    @Test
    fun `cleanup invalid line bookmarks should remove out of range`() {
        val valid = Bookmark(
            id = "V",
            type = BookmarkType.LINE,
            filePath = "src/Test.php",
            lineNumber = 5
        )
        val invalid = Bookmark(
            id = "I",
            type = BookmarkType.LINE,
            filePath = "src/Test.php",
            lineNumber = 100
        )
        manager.addBookmark(valid)
        manager.addBookmark(invalid)

        val removed = manager.cleanupInvalidLineBookmarks("src/Test.php", lineCount = 10)
        assertEquals(1, removed, "应删除 1 个无效行收藏")
        assertNull(manager.getBookmark("I"), "无效收藏应被删除")
        assertNotNull(manager.getBookmark("V"), "有效收藏应保留")
    }

    @Test
    fun `available mnemonics should exclude used ones`() {
        val bookmark = Bookmark(
            id = "M1",
            type = BookmarkType.LINE,
            filePath = "src/Test.php",
            lineNumber = 1,
            mnemonic = '9'
        )
        manager.addBookmark(bookmark)

        val available = manager.getAvailableMnemonics()
        assertFalse(available.contains('9'), "已使用的助记符不应出现在可用列表中")
        assertTrue(available.contains('0'), "未使用的助记符应在可用列表中")
    }
}

