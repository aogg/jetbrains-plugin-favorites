package com.aogg.favorites.model

import com.aogg.favorites.helper.BookmarkPersistence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * BookmarkStoreCore 分组树（多层级列表）核心逻辑单元测试（1.5.0 新增）
 *
 * 说明：
 * - 直接构造共享内核 [BookmarkStoreCore]，持久化用内存假实现 [FakePersistence]（构造参数即持久化提供器）
 * - 只测试纯业务逻辑，不依赖真实 IntelliJ 环境
 * - 覆盖：createSubGroup/childrenOf/fullPath 分组树 CRUD、previewCascadeDelete/deleteListCascade 级联语义、
 *   旧存储迁移归一（缺 id/坏 parentId/成环/收藏归属补建）
 */
class BookmarkStoreCoreGroupTreeTest {

    /**
     * 内存假持久化：记录加载源数据与最近一次落盘快照（供迁移/落盘断言）
     */
    private class FakePersistence : BookmarkPersistence {
        var loadBookmarks: List<Bookmark> = emptyList()
        var loadLists: List<ListInfo> = emptyList()
        var savedBookmarks: List<Bookmark> = emptyList()
        var savedLists: List<ListInfo> = emptyList()
        var saveCount: Int = 0

        override fun loadAll(): Pair<List<Bookmark>, List<ListInfo>> = loadBookmarks to loadLists

        override fun saveAll(bookmarks: List<Bookmark>, lists: List<ListInfo>) {
            savedBookmarks = bookmarks
            savedLists = lists
            saveCount++
        }
    }

    private lateinit var persistence: FakePersistence
    private lateinit var core: BookmarkStoreCore

    @BeforeEach
    fun setUp() {
        persistence = FakePersistence()
        core = BookmarkStoreCore { persistence }
    }

    private fun addLineBookmark(id: String, listName: String): Bookmark {
        val bookmark = Bookmark(
            id = id,
            type = BookmarkType.LINE,
            filePath = "src/Test.php",
            lineNumber = id.hashCode().mod(100),
            listName = listName
        )
        assertTrue(core.addBookmark(bookmark), "添加收藏 $id 应成功")
        return bookmark
    }

    /** 搭建三层级分组树：收藏（默认）/ 父 → 子 → 孙，以及旁支顶级「父2」 */
    private fun buildThreeLevelTree() {
        assertTrue(core.createList("父"))
        assertTrue(core.createSubGroup("父", "子"))
        assertTrue(core.createSubGroup("子", "孙"))
        assertTrue(core.createList("父2"))
    }

    // ==================== createSubGroup ====================

    @Test
    fun `create sub group should link parent by id`() {
        assertTrue(core.createList("父"))
        assertTrue(core.createSubGroup("父", "子"), "创建子分组应成功")

        val parent = core.getLists().first { it.name == "父" }
        val child = core.getLists().first { it.name == "子" }
        assertEquals(parent.id, child.parentId, "子分组 parentId 应指向父分组 id")
        assertTrue(core.hasList("子"))
    }

    @Test
    fun `create sub group should reject missing parent`() {
        assertFalse(core.createSubGroup("不存在的父", "子"), "父分组不存在应失败")
        assertFalse(core.hasList("子"), "失败时不应残留新分组")
    }

    @Test
    fun `create sub group should reject blank name`() {
        assertTrue(core.createList("父"))
        assertFalse(core.createSubGroup("父", ""), "空名应失败")
        assertFalse(core.createSubGroup("父", "   "), "空白名应失败")
    }

    @Test
    fun `create sub group should reject duplicate name across tree`() {
        assertTrue(core.createList("父"))
        assertTrue(core.createList("旁支"))
        assertTrue(core.createSubGroup("父", "同名"))
        // 名字全作用域唯一：与其它层级下的兄弟重名同样拒绝
        assertFalse(core.createSubGroup("旁支", "同名"), "与既有分组重名应失败")
        assertFalse(core.createList("同名"), "与子分组重名的顶级列表也应失败")
    }

    // ==================== childrenOf ====================

    @Test
    fun `children of root should contain default list first and keep storage order`() {
        buildThreeLevelTree()
        val topLevel = core.childrenOf(ListInfo.ROOT_PARENT_ID)
        assertEquals(
            listOf("收藏", "父", "父2"),
            topLevel.map { it.name },
            "顶级子分组应含默认列表（置首）且保持存储顺序"
        )
    }

    @Test
    fun `children of parent should only return direct children`() {
        buildThreeLevelTree()
        val parent = core.getLists().first { it.name == "父" }
        val child = core.getLists().first { it.name == "子" }

        assertEquals(listOf("子"), core.childrenOf(parent.id).map { it.name }, "只应返回直接子分组")
        assertEquals(listOf("孙"), core.childrenOf(child.id).map { it.name }, "孙分组挂在子分组下")
        assertTrue(core.childrenOf("missing-id").isEmpty(), "不存在的父 id 应返回空")
    }

    // ==================== fullPath ====================

    @Test
    fun `full path should join ancestor names with slash`() {
        buildThreeLevelTree()
        assertEquals("父/子/孙", core.fullPath("孙"), "完整层级路径应按「父/子/孙」拼接")
        assertEquals("父/子", core.fullPath("子"))
        assertEquals("父", core.fullPath("父"), "顶级分组路径就是自身名")
        assertEquals("收藏", core.fullPath(Bookmark.DEFAULT_LIST_NAME), "默认列表路径就是自身名")
    }

    @Test
    fun `full path should return name as is for missing group`() {
        assertEquals("不存在", core.fullPath("不存在"), "分组不存在时原样返回名称")
    }

    @Test
    fun `rename parent should keep hierarchy and update full path`() {
        buildThreeLevelTree()
        assertTrue(core.renameList("父", "新父"), "重命名应成功")

        // 父子按 id 关联：改名不影响层级位置
        val child = core.getLists().first { it.name == "子" }
        val parent = core.getLists().first { it.name == "新父" }
        assertEquals(parent.id, child.parentId, "改名后子分组仍挂在原父分组（按 id）")
        assertEquals("新父/子/孙", core.fullPath("孙"), "完整路径应跟随新名")
    }

    // ==================== previewCascadeDelete ====================

    @Test
    fun `preview cascade delete should count descendant groups and bookmarks`() {
        buildThreeLevelTree()
        addLineBookmark("B1", "父")
        addLineBookmark("B2", "子")
        addLineBookmark("B3", "子")
        addLineBookmark("B4", "孙")
        addLineBookmark("B5", "孙")
        addLineBookmark("B6", "孙")
        addLineBookmark("B7", Bookmark.DEFAULT_LIST_NAME) // 默认列表收藏不计入

        val preview = core.previewCascadeDelete("父")
        assertNotNull(preview)
        assertEquals(listOf("父", "子", "孙"), preview!!.groupNames, "被删分组名单（含自身，父先子后）")
        assertEquals(2, preview.subGroupCount, "连删子分组数不含自身")
        assertEquals(6, preview.movedBookmarkCount, "被删分组内收藏总数（不含默认列表收藏）")

        val middle = core.previewCascadeDelete("子")
        assertEquals(listOf("子", "孙"), middle!!.groupNames, "从中间层删应只含其后代")
        assertEquals(5, middle.movedBookmarkCount)

        val leaf = core.previewCascadeDelete("孙")
        assertEquals(listOf("孙"), leaf!!.groupNames)
        assertEquals(0, leaf.subGroupCount, "叶子分组无连删子分组")
        assertEquals(3, leaf.movedBookmarkCount)
    }

    @Test
    fun `preview cascade delete should return null for default and missing list`() {
        buildThreeLevelTree()
        assertNull(core.previewCascadeDelete(Bookmark.DEFAULT_LIST_NAME), "默认列表不可删，预览为 null")
        assertNull(core.previewCascadeDelete("不存在"), "分组不存在预览为 null")
    }

    // ==================== deleteListCascade ====================

    @Test
    fun `delete list cascade should remove subtree and merge bookmarks into default list`() {
        buildThreeLevelTree()
        addLineBookmark("B1", "父")
        addLineBookmark("B2", "子")
        addLineBookmark("B3", "孙")
        addLineBookmark("B4", Bookmark.DEFAULT_LIST_NAME)

        val result = core.deleteListCascade("父")
        assertTrue(result.success, "级联删除应成功")
        assertEquals(listOf("父", "子", "孙"), result.deletedGroupNames, "实际删除的分组名单")
        assertEquals(3, result.movedBookmarkCount, "并入默认列表的收藏数")

        // 只删目标子树：默认列表与旁支顶级分组保留
        assertEquals(listOf(Bookmark.DEFAULT_LIST_NAME, "父2"), core.getLists().map { it.name })
        // 收藏不丢：全部并入默认列表
        assertEquals("收藏", core.getBookmark("B1")!!.listName)
        assertEquals("收藏", core.getBookmark("B2")!!.listName)
        assertEquals("收藏", core.getBookmark("B3")!!.listName)
        assertEquals(4, core.getBookmarksByList(Bookmark.DEFAULT_LIST_NAME).size)
        // 落盘快照与内存一致
        assertEquals(listOf(Bookmark.DEFAULT_LIST_NAME, "父2"), persistence.savedLists.map { it.name })
    }

    @Test
    fun `delete list cascade on middle group should keep ancestors`() {
        buildThreeLevelTree()
        addLineBookmark("B1", "父")

        val result = core.deleteListCascade("子")
        assertTrue(result.success)
        assertEquals(listOf("子", "孙"), result.deletedGroupNames, "中间层删除只级联其后代")

        val remaining = core.getLists().map { it.name }
        assertTrue("父" in remaining, "祖先分组应保留")
        assertFalse("子" in remaining)
        assertFalse("孙" in remaining)
        assertEquals("父", core.getBookmark("B1")!!.listName, "祖先分组收藏不受影响")
    }

    @Test
    fun `delete list cascade should reject default and missing list`() {
        buildThreeLevelTree()
        val before = core.getLists().map { it.name }

        assertFalse(core.deleteListCascade(Bookmark.DEFAULT_LIST_NAME).success, "默认列表不可删除")
        assertFalse(core.deleteListCascade("不存在").success, "分组不存在删除失败")
        assertFalse(core.deleteList(Bookmark.DEFAULT_LIST_NAME), "布尔入口同样拒绝默认列表")
        assertEquals(before, core.getLists().map { it.name }, "失败路径不应改动分组树")
    }

    @Test
    fun `delete list boolean entry should reuse cascade semantics`() {
        buildThreeLevelTree()
        assertTrue(core.deleteList("父2"), "布尔入口删除成功")
        assertFalse(core.hasList("父2"))
    }

    // ==================== 存储迁移（旧数据归一） ====================

    @Test
    fun `load should normalize legacy tree data and persist on next save`() {
        // 模拟旧 favorites.xml：全部缺 id（""），默认列表还带坏 parentId，混合一条新数据
        persistence.loadLists = listOf(
            ListInfo(name = "收藏", id = "", parentId = "missing"),
            ListInfo(name = "老分组", id = "", parentId = ""),
            ListInfo(name = "新分组", id = "fixed-id", parentId = "")
        )
        val lists = core.getLists() // 触发加载 + 归一

        assertTrue(lists.all { it.id.isNotBlank() }, "旧数据缺 id 应补 UUID")
        assertEquals("", lists.first { it.name == "收藏" }.parentId, "默认列表强制顶级")
        assertEquals("", lists.first { it.name == "老分组" }.parentId)
        assertEquals("fixed-id", lists.first { it.name == "新分组" }.id, "已有 id 保留")
        assertEquals(listOf("收藏", "老分组", "新分组"), lists.map { it.name }, "归一不丢列表、不变顺序")

        // 归一结果随下一次保存自然落盘
        assertTrue(core.createList("新"))
        val saved = persistence.savedLists
        assertEquals(listOf("收藏", "老分组", "新分组", "新"), saved.map { it.name }, "落盘应包含归一后全量分组")
        assertTrue(saved.all { it.id.isNotBlank() }, "落盘数据 id 已补全")
        assertEquals("", saved.first { it.name == "收藏" }.parentId, "落盘数据默认列表已归顶级")
    }

    @Test
    fun `load should cut cycle in legacy tree data`() {
        persistence.loadLists = listOf(
            ListInfo(name = "收藏", id = "d"),
            ListInfo(name = "A", id = "a", parentId = "b"),
            ListInfo(name = "B", id = "b", parentId = "a")
        )
        val lists = core.getLists()
        assertTrue(lists.filter { it.name != Bookmark.DEFAULT_LIST_NAME }.all { it.parentId == "" }, "环数据加载时应断环归顶级")
        assertEquals(3, lists.size, "断环不丢列表")
    }

    @Test
    fun `load should migrate blank list name and rebuild missing list meta`() {
        // 旧收藏数据：listName 为空 → 归默认列表；归属不存在列表 → 自动补建元信息，绝不丢数据
        persistence.loadBookmarks = listOf(
            Bookmark(id = "OLD", type = BookmarkType.LINE, filePath = "src/Old.php", lineNumber = 1, listName = ""),
            Bookmark(id = "GHOST", type = BookmarkType.FILE, filePath = "src/Ghost.php", listName = "幽灵分组")
        )
        persistence.loadLists = emptyList()

        assertEquals(Bookmark.DEFAULT_LIST_NAME, core.getBookmark("OLD")!!.listName, "空白列表名应归默认列表")
        assertEquals("幽灵分组", core.getBookmark("GHOST")!!.listName, "归属保留不丢数据")
        assertTrue(core.hasList("幽灵分组"), "归属不存在列表时应自动补建元信息")
        assertTrue(core.hasList(Bookmark.DEFAULT_LIST_NAME), "默认列表始终存在")
        // 补建/迁移后的元信息 id 已归一
        assertTrue(core.getLists().all { it.id.isNotBlank() })
    }

    // ==================== 默认列表保护 ====================

    @Test
    fun `default list should stay top level with empty parent id`() {
        buildThreeLevelTree()
        val defaultInfo = core.getLists().first { it.name == Bookmark.DEFAULT_LIST_NAME }
        assertEquals("", defaultInfo.parentId, "默认列表 parentId 恒为顶级")
        assertEquals(Bookmark.DEFAULT_LIST_NAME, core.childrenOf(ListInfo.ROOT_PARENT_ID).first().name, "顶级子分组中默认列表置首")
    }
}
