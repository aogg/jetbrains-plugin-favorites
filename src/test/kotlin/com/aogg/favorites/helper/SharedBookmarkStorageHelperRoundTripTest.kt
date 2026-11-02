package com.aogg.favorites.helper

import com.aogg.favorites.model.Bookmark
import com.aogg.favorites.model.BookmarkType
import com.aogg.favorites.model.ListInfo
import com.aogg.favorites.model.SortType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * SharedBookmarkStorageHelper（应用级共享收藏存储）State 写入读出往返单元测试（1.7.0 新增）
 *
 * 说明：直接实例化存储助手（无参构造，脱离 IDE 服务），验证 loadState/getState 往返
 * 多集合数据一致，以及集合切片 content（复用项目级 BookmarkData/ListData 结构，含
 * id/parentId/sortType 兼容字段）的写入读出往返一致。
 */
class SharedBookmarkStorageHelperRoundTripTest {

    /**
     * 构造一个含嵌套收藏与多层级分组列表的集合切片 content（两集合复用同一构造，数据可区分）
     */
    private fun buildContent(bookmarkId: String, listIdPrefix: String): BookmarkStorageHelper.State {
        val content = BookmarkStorageHelper.State()
        content.anonymousBookmarks.add(
            BookmarkStorageHelper.BookmarkData(
                id = bookmarkId,
                type = BookmarkType.LINE.name,
                filePath = "/projA/src/A.php",
                lineNumber = 3,
                mnemonic = null,
                description = "匿名收藏描述",
                createdTime = 111L,
                listName = Bookmark.DEFAULT_LIST_NAME
            )
        )
        content.mnemonicBookmarks[bookmarkId] = BookmarkStorageHelper.BookmarkData(
            id = "${bookmarkId}-M",
            type = BookmarkType.FILE.name,
            filePath = "/projA/src/B.php",
            lineNumber = -1,
            mnemonic = "A",
            description = "助记符收藏描述",
            createdTime = 222L,
            listName = "工作"
        )
        content.lists.add(BookmarkStorageHelper.ListData(name = Bookmark.DEFAULT_LIST_NAME))
        // 多层级分组：父子按 id 关联（1.5.0 起的兼容字段）
        content.lists.add(BookmarkStorageHelper.ListData(name = "工作", sortType = SortType.LOCATION.name, id = "${listIdPrefix}-1", parentId = ""))
        content.lists.add(BookmarkStorageHelper.ListData(name = "子分组", sortType = SortType.ADDED.name, id = "${listIdPrefix}-2", parentId = "${listIdPrefix}-1"))
        return content
    }

    @Test
    fun `state load get roundtrip should keep collections and nested content`() {
        // ////////////////  准备数据：多集合 State（含嵌套 BookmarkData/ListData）  start  //
        val state = SharedBookmarkStorageHelper.State()
        state.collections["c1"] = SharedBookmarkStorageHelper.SharedCollectionData(
            id = "c1",
            ownerProjectName = "ProjA",
            ownerProjectPath = "/projA",
            updatedTime = 111L,
            content = buildContent("B1", "l1")
        )
        state.collections["c2"] = SharedBookmarkStorageHelper.SharedCollectionData(
            id = "c2",
            ownerProjectName = "ProjB",
            ownerProjectPath = "/projB",
            updatedTime = 222L,
            content = buildContent("B2", "l2")
        )
        // ////////////////  准备数据  end  /////////////////////////////////////////////

        // ////////////////  核心逻辑：loadState → getState 往返  start  /////////////////
        val helper = SharedBookmarkStorageHelper()
        helper.loadState(state)
        val restored = helper.getState()
        // ////////////////  核心逻辑  end  /////////////////////////////////////////////

        // ////////////////  返回：逐集合逐字段比对  start  //////////////////////////////
        assertEquals(setOf("c1", "c2"), restored.collections.keys, "往返后集合 id 集合应一致")

        val restoredC1 = restored.collections["c1"]!!
        assertEquals("c1", restoredC1.id, "集合 id 应往返一致")
        assertEquals("ProjA", restoredC1.ownerProjectName, "来源项目名应往返一致")
        assertEquals("/projA", restoredC1.ownerProjectPath, "来源项目路径应往返一致")
        assertEquals(111L, restoredC1.updatedTime, "更新时间应往返一致")

        // content 往返：匿名收藏（含描述/行号/列表归属）
        val anonymous = restoredC1.content.anonymousBookmarks.single()
        assertEquals("B1", anonymous.id, "匿名收藏 id 应往返一致")
        assertEquals(BookmarkType.LINE.name, anonymous.type, "收藏类型应往返一致")
        assertEquals("/projA/src/A.php", anonymous.filePath, "收藏路径应往返一致")
        assertEquals(3, anonymous.lineNumber, "行号应往返一致")
        assertEquals("匿名收藏描述", anonymous.description, "描述应往返一致")
        assertEquals(111L, anonymous.createdTime, "创建时间应往返一致")
        assertEquals(Bookmark.DEFAULT_LIST_NAME, anonymous.listName, "列表归属应往返一致")

        // content 往返：助记符收藏（含 mnemonic 兼容字段，字符串单字符存储）
        val mnemonicData = restoredC1.content.mnemonicBookmarks["B1"]!!
        assertEquals("B1-M", mnemonicData.id, "助记符收藏 id 应往返一致")
        assertEquals("A", mnemonicData.mnemonic, "助记符应以单字符字符串往返一致")
        assertEquals("工作", mnemonicData.listName, "助记符收藏列表归属应往返一致")

        // content 往返：多层级分组（id/parentId/sortType 兼容字段）
        val lists = restoredC1.content.lists
        assertEquals(3, lists.size, "分组数量应往返一致")
        assertEquals("收藏", lists[0].name, "默认列表名应往返一致")
        assertEquals("l1-1", lists[1].id, "分组 id 应往返一致")
        assertEquals("LOCATION", lists[1].sortType, "分组排序应往返一致")
        assertEquals("l1-2", lists[2].id, "子分组 id 应往返一致")
        assertEquals("l1-1", lists[2].parentId, "子分组父 id 应往返一致（父子关系保留）")

        // 两集合数据互不干扰
        assertEquals("B2", restored.collections["c2"]!!.content.anonymousBookmarks.single().id, "不同集合切片应互不干扰")
        // ////////////////  返回  end  /////////////////////////////////////////////////
    }

    @Test
    fun `slice content write read roundtrip should keep bookmark and list fields`() {
        // ////////////////  准备数据：写入集合切片 content  start  ///////////////////////
        val data = SharedBookmarkStorageHelper.SharedCollectionData(
            id = "ck",
            ownerProjectName = "ProjK",
            ownerProjectPath = "/projK",
            updatedTime = 0L
        )
        val bookmarks = listOf(
            Bookmark("K1", BookmarkType.LINE, "/projK/src/A.php", 7, mnemonic = 'Z', description = "行收藏", createdTime = 333L, listName = "工作"),
            Bookmark("K2", BookmarkType.FOLDER, "/projK/folder", -1)
        )
        val lists = listOf(
            ListInfo(name = Bookmark.DEFAULT_LIST_NAME),
            ListInfo(name = "工作", sortType = SortType.LOCATION, id = "lk-1"),
            ListInfo(name = "子分组", sortType = SortType.ADDED, id = "lk-2", parentId = "lk-1")
        )
        BookmarkStorageHelper.writeToState(data.content, bookmarks, lists)
        data.updatedTime = 444L
        // ////////////////  准备数据  end  /////////////////////////////////////////////

        // ////////////////  核心逻辑：切片嵌入 State 往返后读回  start  ///////////////////
        val state = SharedBookmarkStorageHelper.State()
        state.collections["ck"] = data
        val helper = SharedBookmarkStorageHelper()
        helper.loadState(state)

        val restored = helper.getState().collections["ck"]!!
        val (readBackBookmarks, readBackLists) = BookmarkStorageHelper.readFromState(restored.content)
        // ////////////////  核心逻辑  end  /////////////////////////////////////////////

        // ////////////////  返回：读回字段比对  start  ///////////////////////////////////
        assertEquals(setOf("K1", "K2"), readBackBookmarks.map { it.id }.toSet(), "收藏应全部读回")
        val k1 = readBackBookmarks.first { it.id == "K1" }
        assertEquals('Z', k1.mnemonic, "助记符应往返一致")
        assertEquals("行收藏", k1.description, "描述应往返一致")
        assertEquals(333L, k1.createdTime, "创建时间应往返一致")
        assertEquals("工作", k1.listName, "列表归属应往返一致")
        assertEquals("/projK/folder", readBackBookmarks.first { it.id == "K2" }.filePath, "文件夹收藏路径应往返一致")

        assertEquals(setOf("工作", "子分组", Bookmark.DEFAULT_LIST_NAME), readBackLists.map { it.name }.toSet(), "分组应全部读回")
        val work = readBackLists.first { it.name == "工作" }
        assertEquals(SortType.LOCATION, work.sortType, "排序方式应往返一致")
        assertEquals("lk-1", work.id, "分组 id 应往返一致")
        assertEquals("lk-1", readBackLists.first { it.name == "子分组" }.parentId, "父子关系应往返一致")
        assertTrue(restored.updatedTime == 444L, "更新时间应往返一致")
        // ////////////////  返回  end  /////////////////////////////////////////////////
    }

    @Test
    fun `clearAll should empty collections`() {
        // 清空辅助方法：清空后 State 回到空集合表（重置场景用）
        val helper = SharedBookmarkStorageHelper()
        val state = SharedBookmarkStorageHelper.State()
        state.collections["c1"] = SharedBookmarkStorageHelper.SharedCollectionData(id = "c1")
        helper.loadState(state)
        helper.clearAll()
        assertTrue(helper.getState().collections.isEmpty(), "清空后集合表应为空")
    }
}
