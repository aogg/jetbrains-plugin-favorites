package com.aogg.favorites.helper

import com.aogg.favorites.model.Bookmark
import com.aogg.favorites.model.BookmarkType
import com.aogg.favorites.model.ListInfo
import com.aogg.favorites.model.SortType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * BookmarkStorageHelper 序列化数据与旧格式迁移语义单元测试
 *
 * 说明：只测试纯数据转换逻辑（toBookmark/fromBookmark 等），不依赖真实 IDE 存储服务。
 * 旧版本 favorites.xml 中 BookmarkData 无 listName 字段，XML 反序列化时字段保持
 * 构造默认值，因此这里用无参构造 + 属性赋值模拟"缺字段"的旧行为。
 */
class BookmarkStorageHelperTest {

    @Test
    fun `bookmark data without list name should fall back to default list`() {
        // 模拟旧格式：反序列化后 listName 为空串（未写入）
        val legacy = BookmarkStorageHelper.BookmarkData()
        legacy.id = "1"
        legacy.type = BookmarkType.LINE.name
        legacy.filePath = "src/Test.php"
        legacy.lineNumber = 10
        legacy.listName = ""

        val bookmark = legacy.toBookmark()
        assertEquals(Bookmark.DEFAULT_LIST_NAME, bookmark.listName, "旧数据应归入默认列表「收藏」")
        assertEquals("收藏", bookmark.listName)
    }

    @Test
    fun `bookmark data roundtrip should keep list name`() {
        val bookmark = Bookmark(
            id = "2",
            type = BookmarkType.FILE,
            filePath = "src/A.php",
            listName = "工作"
        )
        val data = BookmarkStorageHelper.BookmarkData.fromBookmark(bookmark)
        assertEquals("工作", data.listName)
        assertEquals("工作", data.toBookmark().listName)
    }

    @Test
    fun `list data should roundtrip sort type`() {
        val info = ListInfo(name = "重要", sortType = SortType.LOCATION)
        val data = BookmarkStorageHelper.ListData.fromListInfo(info)
        assertEquals("LOCATION", data.sortType)
        val restored = data.toListInfo()
        assertEquals("重要", restored.name)
        assertEquals(SortType.LOCATION, restored.sortType)
    }

    @Test
    fun `list data with unknown sort type should fall back to ADDED`() {
        val data = BookmarkStorageHelper.ListData(name = "x", sortType = "BOGUS")
        assertEquals(SortType.ADDED, data.toListInfo().sortType, "未知排序值应保守降级为 ADDED")
    }

    @Test
    fun `default list info should use ADDED sort`() {
        val info = ListInfo(name = Bookmark.DEFAULT_LIST_NAME)
        assertEquals(SortType.ADDED, info.sortType)
        assertTrue(Bookmark.isValidMnemonic('0'))
    }
}
