package com.aogg.favorites.model

import com.aogg.favorites.helper.GlobalBookmarkStorageHelper
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
 * GlobalBookmarkManager（全局作用域）核心逻辑单元测试（1.2.0 新增）
 *
 * 说明：
 * - 这里使用 Mockito 模拟 GlobalBookmarkStorageHelper，直接构造管理器实例
 * - 只测试纯业务逻辑，不依赖真实 IntelliJ 环境
 * - 覆盖：增删改查、列表 CRUD、助记符全局作用域内唯一、moveToList、按列表排序记忆、getBookmarksByList
 */
class GlobalBookmarkManagerTest {

    private lateinit var storageHelper: GlobalBookmarkStorageHelper
    private lateinit var manager: GlobalBookmarkManager

    @BeforeEach
    fun setUp() {
        storageHelper = Mockito.mock(GlobalBookmarkStorageHelper::class.java)

        // 模拟持久化层：初始时返回空数据，save 不做任何事
        Mockito.`when`(storageHelper.loadAll())
            .thenReturn(emptyList<Bookmark>() to emptyList<ListInfo>())
        Mockito.doNothing().`when`(storageHelper)
            .saveAll(Mockito.anyList(), Mockito.anyList())

        manager = GlobalBookmarkManager()
        manager.setStorageHelperForTest(storageHelper)
    }

    @AfterEach
    fun tearDown() {
        manager.clearAll()
    }

    /**
     * 构造全局收藏（filePath 为绝对路径）并断言添加成功
     */
    private fun addBookmark(
        id: String,
        filePath: String,
        type: BookmarkType = BookmarkType.LINE,
        line: Int = 1,
        mnemonic: Char? = null,
        listName: String = Bookmark.DEFAULT_LIST_NAME
    ): Bookmark {
        val bookmark = Bookmark(
            id = id,
            type = type,
            filePath = filePath,
            lineNumber = line,
            mnemonic = mnemonic,
            listName = listName
        )
        assertTrue(manager.addBookmark(bookmark), "添加收藏 $id 应成功")
        return bookmark
    }

    // ==================== 基础功能 ====================

    @Test
    fun `add and get bookmark with absolute path`() {
        addBookmark("G1", "/other/project/src/A.php")

        assertEquals(1, manager.getTotalCount(), "总数应为 1")
        assertNotNull(manager.getBookmark("G1"), "应能通过 ID 获取收藏")
        assertEquals("/other/project/src/A.php", manager.getBookmark("G1")!!.filePath, "全局收藏应保留绝对路径")
    }

    @Test
    fun `remove bookmark should decrease total count`() {
        addBookmark("G1", "/p/src/A.php")
        addBookmark("G2", "/p/src/B.php")

        assertTrue(manager.removeBookmark("G1"), "删除应成功")
        assertNull(manager.getBookmark("G1"), "删除后应查不到")
        assertEquals(1, manager.getTotalCount(), "总数应为 1")
        assertFalse(manager.removeBookmark("不存在"), "删除不存在的收藏应失败")
    }

    @Test
    fun `mnemonic should be unique within global scope`() {
        addBookmark("M1", "/p/src/A.php", mnemonic = 'A')

        // 同一助记符第二次使用：应失败
        val conflict = Bookmark(
            id = "M2",
            type = BookmarkType.LINE,
            filePath = "/p/src/B.php",
            lineNumber = 2,
            mnemonic = 'A'
        )
        assertFalse(manager.addBookmark(conflict), "全局作用域内重复使用助记符 A 应失败")
        assertNull(manager.getBookmark("M2"), "冲突收藏不应被加入")

        // 助记符查询按全局作用域
        assertEquals("M1", manager.getBookmarkByMnemonic('A')!!.id)
        assertFalse(manager.isMnemonicAvailable('A'), "助记符 A 不应可用")
        assertTrue(manager.isMnemonicAvailable('B'), "助记符 B 应可用")
        assertFalse(manager.getAvailableMnemonics().contains('A'), "可用列表不应包含 A")
    }

    @Test
    fun `line and path indexes should work for absolute paths`() {
        addBookmark("L1", "/p/src/A.php", line = 5)
        addBookmark("F1", "/p/src/B.php", type = BookmarkType.FILE, line = -1)
        addBookmark("D1", "/p/src/folder", type = BookmarkType.FOLDER, line = -1)

        assertEquals(1, manager.getLineBookmarks("/p/src/A.php", 5).size, "指定行应有 1 个收藏")
        assertEquals(1, manager.getBookmarksByFile("/p/src/B.php").size, "文件应有 1 个收藏")
        assertEquals(1, manager.getBookmarksByFolder("/p/src/folder").size, "文件夹应有 1 个收藏")
    }

    // ==================== 收藏列表（List） ====================

    @Test
    fun `list crud should work in global scope`() {
        // 初始仅默认列表
        assertEquals(1, manager.getLists().size, "初始应只有默认列表")

        // 新建：空白/重名拒绝
        assertTrue(manager.createList("全局工作"))
        assertFalse(manager.createList("全局工作"), "重名列表应创建失败")
        assertFalse(manager.createList("  "), "空白名称应创建失败")
        assertTrue(manager.hasList("全局工作"))

        // 收藏入列表 + 重命名迁移
        addBookmark("W1", "/p/src/A.php", listName = "全局工作")
        assertTrue(manager.renameList("全局工作", "全局重要"))
        assertEquals("全局重要", manager.getBookmark("W1")!!.listName, "收藏应迁移到新列表名")

        // 删除列表并入默认列表；默认列表不可删/改名
        addBookmark("W2", "/p/src/C.php", listName = "全局重要")
        assertTrue(manager.deleteList("全局重要"))
        assertEquals(Bookmark.DEFAULT_LIST_NAME, manager.getBookmark("W2")!!.listName, "删除列表后收藏应并入默认列表")
        assertFalse(manager.deleteList(Bookmark.DEFAULT_LIST_NAME), "默认列表不可删除")
        assertFalse(manager.renameList(Bookmark.DEFAULT_LIST_NAME, "x"), "默认列表不可重命名")
    }

    @Test
    fun `move to list should work within global scope`() {
        manager.createList("全局重要")
        addBookmark("A1", "/p/src/A.php")

        assertTrue(manager.moveToList("A1", "全局重要"), "移动应成功")
        assertEquals("全局重要", manager.getBookmark("A1")!!.listName)
        assertFalse(manager.moveToList("A1", "不存在的列表"), "移动到不存在列表应失败")
        assertEquals(1, manager.getTotalCount(), "移动不改变总数")
    }

    @Test
    fun `sort type should be remembered per list`() {
        manager.createList("排序")
        manager.addBookmark(Bookmark(id = "S1", type = BookmarkType.FILE, filePath = "/p/b.php", listName = "排序", createdTime = 50))
        manager.addBookmark(Bookmark(id = "S2", type = BookmarkType.FILE, filePath = "/p/a.php", listName = "排序", createdTime = 100))

        // 默认按添加时间
        assertEquals(listOf("S1", "S2"), manager.getBookmarksByList("排序").map { it.id })

        // 按位置（a 在 b 前）
        assertTrue(manager.setSortType("排序", SortType.LOCATION))
        assertEquals(SortType.LOCATION, manager.getSortType("排序"))
        assertEquals(listOf("S2", "S1"), manager.getBookmarksByList("排序").map { it.id })
    }

    @Test
    fun `load should restore persisted state via helper`() {
        // 重新配置持久化层：模拟已保存的全局数据
        Mockito.reset(storageHelper)
        val saved = Bookmark(id = "OLD", type = BookmarkType.FILE, filePath = "/p/old.php", mnemonic = 'Z')
        Mockito.`when`(storageHelper.loadAll()).thenReturn(listOf(saved) to listOf(ListInfo("旧列表")))
        Mockito.doNothing().`when`(storageHelper).saveAll(Mockito.anyList(), Mockito.anyList())

        val newManager = GlobalBookmarkManager()
        newManager.setStorageHelperForTest(storageHelper)

        assertEquals("OLD", newManager.getBookmark("OLD")!!.id, "应恢复已保存的收藏")
        assertTrue(newManager.hasList("旧列表"), "应恢复列表元信息")
        assertEquals('Z', newManager.getBookmarkByMnemonic('Z')!!.mnemonic, "助记符映射应恢复")
    }
}
