package com.aogg.favorites.helper

import com.aogg.favorites.model.Bookmark
import com.aogg.favorites.model.BookmarkType
import com.aogg.favorites.model.ListInfo
import com.aogg.favorites.model.SortType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * BookmarkStorageHelper ListData 新字段（id/parentId）与旧格式兼容单元测试（1.5.0 多层级分组新增）
 *
 * 说明：只测试纯数据转换逻辑（ListData 往返、writeToState/readFromState 共享静态实现），
 * 不依赖真实 IDE 存储服务。
 * 旧版本 favorites.xml 中 ListData 无 id/parentId 字段，XML 反序列化时字段保持构造默认值（""），
 * 因此这里用无参构造 + 属性赋值模拟"缺字段"的旧行为；缺 id 的归一在 ListInfo.normalizeTree（另测）。
 */
class BookmarkStorageHelperListDataTest {

    // ==================== ListData 新字段往返 ====================

    @Test
    fun `list data roundtrip should keep id and parent id`() {
        val info = ListInfo(name = "子分组", sortType = SortType.DESCRIPTION, id = "gid-1", parentId = "pid-1")
        val data = BookmarkStorageHelper.ListData.fromListInfo(info)

        assertEquals("gid-1", data.id, "分组 id 应写入序列化数据")
        assertEquals("pid-1", data.parentId, "父分组 id 应写入序列化数据")

        val restored = data.toListInfo()
        assertEquals(info, restored, "往返后分组元信息（含层级）应完全一致")
    }

    @Test
    fun `list data should roundtrip nested tree`() {
        val lists = listOf(
            ListInfo(name = "父", sortType = SortType.LOCATION, id = "p1", parentId = ListInfo.ROOT_PARENT_ID),
            ListInfo(name = "子", sortType = SortType.ADDED, id = "c1", parentId = "p1")
        )
        val state = BookmarkStorageHelper.State()
        BookmarkStorageHelper.writeToState(state, emptyList(), lists)

        val restored = BookmarkStorageHelper.readFromState(state).second
        assertEquals(lists, restored, "分组树经 State 写读后应保持父子关系与排序记忆")
    }

    // ==================== 旧 XML 缺字段兼容 ====================

    @Test
    fun `legacy list data without id fields should keep blank and fall back to added sort`() {
        // 模拟旧格式：反序列化后 id/parentId 为空串（未写入）
        val legacy = BookmarkStorageHelper.ListData()
        legacy.name = "老分组"
        legacy.sortType = ""

        val info = legacy.toListInfo()
        assertEquals("老分组", info.name)
        assertEquals("", info.id, "旧数据缺 id 透传空串，由加载侧 normalizeTree 补 UUID")
        assertEquals("", info.parentId, "旧数据缺 parentId 透传空串（顶级）")
        assertEquals(SortType.ADDED, info.sortType, "未知/缺失排序值保守降级 ADDED")

        // 加载侧归一：缺 id 补齐后即可正常参与分组树
        val normalized = ListInfo.normalizeTree(listOf(info))
        assertTrue(normalized.single().id.isNotBlank(), "归一后旧数据应有 id")
    }

    @Test
    fun `legacy list data mixed with new data should normalize to valid tree`() {
        // 旧数据（无 id）+ 新数据（有 id/parentId）：同库混合存储应能归一成合法树
        val legacy = BookmarkStorageHelper.ListData()
        legacy.name = "旧顶级"
        val modern = BookmarkStorageHelper.ListData(name = "新子", sortType = "ADDED", id = "child-1", parentId = "")
        val normalized = ListInfo.normalizeTree(listOf(legacy.toListInfo(), modern.toListInfo()))

        assertTrue(normalized.all { it.id.isNotBlank() }, "归一后所有分组都有 id")
        assertEquals(listOf("旧顶级", "新子"), normalized.map { it.name }, "归一不丢任何分组")
    }

    // ==================== State 全量写读往返 ====================

    @Test
    fun `write and read state should split and merge anonymous and mnemonic bookmarks`() {
        val anonymous = Bookmark(id = "A1", type = BookmarkType.LINE, filePath = "src/A.php", lineNumber = 3)
        val mnemonic = Bookmark(id = "M1", type = BookmarkType.LINE, filePath = "src/B.php", lineNumber = 5, mnemonic = '5')
        val invalid = Bookmark(id = "X1", type = BookmarkType.FILE, filePath = "src/C.php", mnemonic = '$')

        val state = BookmarkStorageHelper.State()
        BookmarkStorageHelper.writeToState(state, listOf(anonymous, mnemonic, invalid), emptyList())

        // 写入侧分桶：匿名入列表、合法助记符入映射（键=助记符字符）、非法助记符过滤
        assertEquals(listOf("A1"), state.anonymousBookmarks.map { it.id })
        assertEquals(listOf("5"), state.mnemonicBookmarks.keys.toList())

        // 读取侧合并恢复
        val bookmarks = BookmarkStorageHelper.readFromState(state).first
        assertEquals(2, bookmarks.size, "非法助记符收藏不恢复（写入侧已过滤）")
        assertTrue(bookmarks.any { it.id == "A1" })
        assertTrue(bookmarks.any { it.id == "M1" && it.mnemonic == '5' })
    }

    @Test
    fun `write and read state on empty input should produce empty data`() {
        val state = BookmarkStorageHelper.State()
        BookmarkStorageHelper.writeToState(state, emptyList(), emptyList())

        val (bookmarks, lists) = BookmarkStorageHelper.readFromState(state)
        assertTrue(bookmarks.isEmpty(), "空数据写入读出后收藏为空")
        assertTrue(lists.isEmpty(), "空数据写入读出后分组为空")
    }
}
