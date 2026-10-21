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
 * - 覆盖收藏列表（List）模型：创建/重命名/删除迁移、跨列表移动、排序、助记符跨列表唯一
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
        Mockito.`when`(storageHelper.loadAll())
            .thenReturn(emptyList<Bookmark>() to emptyList<ListInfo>())
        Mockito.doNothing().`when`(storageHelper)
            .saveAll(Mockito.anyList(), Mockito.anyList())

        manager = BookmarkManager(project)
        manager.setStorageHelperForTest(storageHelper)
    }

    @AfterEach
    fun tearDown() {
        manager.clearAll()
    }

    private fun addLineBookmark(id: String, line: Int, listName: String? = null, mnemonic: Char? = null): Bookmark {
        val bookmark = Bookmark(
            id = id,
            type = BookmarkType.LINE,
            filePath = "src/Test.php",
            lineNumber = line,
            mnemonic = mnemonic,
            listName = listName ?: Bookmark.DEFAULT_LIST_NAME
        )
        assertTrue(manager.addBookmark(bookmark), "添加收藏 $id 应成功")
        return bookmark
    }

    // ==================== 基础功能 ====================

    @Test
    fun `add and get anonymous bookmark`() {
        addLineBookmark("1", 10)

        assertEquals(1, manager.getTotalCount(), "总数应为 1")
        assertEquals(1, manager.getAnonymousCount(), "匿名收藏数量应为 1")
        assertEquals(0, manager.getMnemonicCount(), "助记符收藏数量应为 0")

        val fetched = manager.getBookmark("1")
        assertNotNull(fetched, "应能通过 ID 获取收藏")
        assertEquals(bookmarkDefaultListName(), fetched!!.listName, "默认应归入默认列表")
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
    fun `mnemonic should be unique across lists`() {
        // 列表A中的助记符
        addLineBookmark("M1", 1, listName = Bookmark.DEFAULT_LIST_NAME, mnemonic = 'A')
        manager.createList("工作")

        // 另一个列表中使用同一助记符：应被拒绝（项目内全局唯一）
        val conflict = Bookmark(
            id = "M2",
            type = BookmarkType.LINE,
            filePath = "src/Other.php",
            lineNumber = 9,
            mnemonic = 'A',
            listName = "工作"
        )
        assertFalse(manager.addBookmark(conflict), "跨列表重复使用助记符 A 应失败")
        assertNull(manager.getBookmark("M2"), "冲突收藏不应被加入")

        // 跳转快捷键按助记符跨列表查找
        assertEquals("M1", manager.getBookmarkByMnemonic('A')!!.id)
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
        addLineBookmark("D1", 3)

        val updated = manager.updateBookmarkDescription("D1", "新的描述")
        assertTrue(updated, "更新描述应成功")

        val fetched = manager.getBookmark("D1")
        assertNotNull(fetched)
        assertEquals("新的描述", fetched!!.description, "描述应更新")
    }

    @Test
    fun `update bookmark details should swap mnemonic`() {
        addLineBookmark("M1", 1, mnemonic = 'A')

        val updated = manager.updateBookmarkDetails("M1", 'B', "新描述")
        assertTrue(updated, "更新助记符应成功")
        assertEquals('B', manager.getBookmark("M1")!!.mnemonic, "助记符应更新为 B")
        assertTrue(manager.isMnemonicAvailable('A'), "旧助记符 A 应释放")
        assertFalse(manager.isMnemonicAvailable('B'), "新助记符 B 应占用")

        // 已被占用的助记符应拒绝
        addLineBookmark("M2", 2)
        assertFalse(manager.updateBookmarkDetails("M2", 'B', null), "占用他人助记符应失败")
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
        addLineBookmark("V", 5)
        addLineBookmark("I", 100)

        val removed = manager.cleanupInvalidLineBookmarks("src/Test.php", lineCount = 10)
        assertEquals(1, removed, "应删除 1 个无效行收藏")
        assertNull(manager.getBookmark("I"), "无效收藏应被删除")
        assertNotNull(manager.getBookmark("V"), "有效收藏应保留")
    }

    @Test
    fun `available mnemonics should exclude used ones`() {
        addLineBookmark("M1", 1, mnemonic = '9')

        val available = manager.getAvailableMnemonics()
        assertFalse(available.contains('9'), "已使用的助记符不应出现在可用列表中")
        assertTrue(available.contains('0'), "未使用的助记符应在可用列表中")
    }

    // ==================== 收藏列表（List） ====================

    @Test
    fun `default list should always exist and be named correctly`() {
        val lists = manager.getLists()
        assertEquals(1, lists.size, "初始应只有默认列表")
        assertEquals(Bookmark.DEFAULT_LIST_NAME, lists.first().name)
        assertEquals("收藏", Bookmark.DEFAULT_LIST_NAME, "默认列表名固定为 收藏")
    }

    @Test
    fun `create list should reject blank and duplicate names`() {
        assertTrue(manager.createList("工作"), "新建列表应成功")
        assertFalse(manager.createList("工作"), "重名列表应创建失败")
        assertFalse(manager.createList("   "), "空白名称应创建失败")
        assertFalse(manager.createList(""), "空名称应创建失败")

        assertTrue(manager.hasList("工作"))
        assertEquals(2, manager.getLists().size, "应有默认列表 + 工作列表")
    }

    @Test
    fun `rename list should rename meta and move its bookmarks`() {
        manager.createList("工作")
        addLineBookmark("W1", 1, listName = "工作")

        assertTrue(manager.renameList("工作", "重要"), "重命名应成功")
        assertFalse(manager.hasList("工作"), "旧列表名应不存在")
        assertTrue(manager.hasList("重要"), "新列表名应存在")
        assertEquals("重要", manager.getBookmark("W1")!!.listName, "收藏应迁移到新列表名")
        assertEquals(1, manager.getBookmarksByList("重要").size)
        assertEquals(0, manager.getBookmarksByList("工作").size)
    }

    @Test
    fun `rename default list should be rejected`() {
        assertFalse(manager.renameList(Bookmark.DEFAULT_LIST_NAME, "x"), "默认列表不可重命名")
        assertFalse(manager.deleteList(Bookmark.DEFAULT_LIST_NAME), "默认列表不可删除")
    }

    @Test
    fun `delete list should merge bookmarks into default list`() {
        manager.createList("临时")
        addLineBookmark("T1", 1, listName = "临时")
        addLineBookmark("T2", 2, listName = "临时")
        addLineBookmark("D1", 3, listName = Bookmark.DEFAULT_LIST_NAME)

        assertTrue(manager.deleteList("临时"), "删除列表应成功")
        assertFalse(manager.hasList("临时"), "列表应已删除")

        // 内容并入默认列表，不丢数据
        val defaultBookmarks = manager.getBookmarksByList(Bookmark.DEFAULT_LIST_NAME)
        assertEquals(3, defaultBookmarks.size, "默认列表应包含并入的收藏")
        assertEquals("收藏", manager.getBookmark("T1")!!.listName)
        assertEquals("收藏", manager.getBookmark("T2")!!.listName)
    }

    @Test
    fun `move to list should move bookmark between lists`() {
        manager.createList("重要")
        addLineBookmark("A1", 1, listName = Bookmark.DEFAULT_LIST_NAME)
        addLineBookmark("A2", 2, listName = Bookmark.DEFAULT_LIST_NAME)

        assertTrue(manager.moveToList("A1", "重要"), "移动应成功")
        assertEquals(1, manager.getBookmarksByList("重要").size)
        assertEquals(1, manager.getBookmarksByList(Bookmark.DEFAULT_LIST_NAME).size)
        assertEquals("重要", manager.getBookmark("A1")!!.listName)

        // 目标列表不存在时应失败
        assertFalse(manager.moveToList("A2", "不存在的列表"), "移动到不存在列表应失败")

        // 助记符/索引在移动后保持不变
        assertEquals(2, manager.getTotalCount(), "移动不改变收藏总数")
    }

    @Test
    fun `sort type should be remembered per list and applied`() {
        manager.createList("位置排序")
        val b1 = Bookmark(id = "S1", type = BookmarkType.FILE, filePath = "b/B.php", listName = "位置排序", createdTime = 50)
        val b2 = Bookmark(id = "S2", type = BookmarkType.FILE, filePath = "a/A.php", listName = "位置排序", createdTime = 100)
        val b3 = Bookmark(id = "S3", type = BookmarkType.LINE, filePath = "a/A.php", lineNumber = 5, listName = "位置排序", createdTime = 200)
        manager.addBookmark(b1)
        manager.addBookmark(b2)
        manager.addBookmark(b3)

        // 默认按添加时间（S1:50 → S2:100 → S3:200）
        assertEquals(listOf("S1", "S2", "S3"), manager.getBookmarksByList("位置排序").map { it.id })

        // 按位置（a/A.php(-1) → a/A.php:5 → b/B.php）
        assertTrue(manager.setSortType("位置排序", SortType.LOCATION))
        assertEquals(listOf("S2", "S3", "S1"), manager.getBookmarksByList("位置排序").map { it.id })
        assertEquals(SortType.LOCATION, manager.getSortType("位置排序"))

        // 按列表记忆：默认列表的排序互不影响
        assertTrue(manager.setSortType(Bookmark.DEFAULT_LIST_NAME, SortType.DESCRIPTION))
        assertEquals(SortType.LOCATION, manager.getSortType("位置排序"), "列表间排序应互相独立")

        // 不存在的列表
        assertFalse(manager.setSortType("不存在", SortType.ADDED))
    }

    @Test
    fun `load should merge legacy data into default list`() {
        // 重新配置持久化层：模拟旧格式数据（listName 已在 BookmarkData.toBookmark 中兜底为默认列表）
        val legacy = Bookmark(id = "OLD", type = BookmarkType.LINE, filePath = "src/Old.php", lineNumber = 1)
        Mockito.reset(storageHelper)
        Mockito.`when`(storageHelper.loadAll()).thenReturn(listOf(legacy) to listOf(ListInfo("老列表")))
        Mockito.doNothing().`when`(storageHelper).saveAll(Mockito.anyList(), Mockito.anyList())

        val newManager = BookmarkManager(project)
        newManager.setStorageHelperForTest(storageHelper)

        // 无 listName 的旧数据归入默认列表
        assertEquals(Bookmark.DEFAULT_LIST_NAME, newManager.getBookmark("OLD")!!.listName)
        // 存储里的列表元信息被恢复
        assertTrue(newManager.hasList("老列表"))
        assertTrue(newManager.hasList(Bookmark.DEFAULT_LIST_NAME))
    }

    private fun bookmarkDefaultListName(): String = Bookmark.DEFAULT_LIST_NAME
}
