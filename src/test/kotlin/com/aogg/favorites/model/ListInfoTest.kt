package com.aogg.favorites.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * ListInfo 分组树纯函数单元测试（1.5.0 多层级分组新增）
 *
 * 说明：只测试纯数据逻辑（normalizeTree/collectDescendantIds/formsCycle）与级联删除预览模型，
 * 不依赖任何 IntelliJ 平台环境。
 * - normalizeTree：旧数据补 id、默认列表强制顶级、坏 parentId（缺目标/指自己/成环）断环归顶级
 * - collectDescendantIds：后代收集（含环数据自动截断不死循环）
 * - formsCycle：沿父链环校验
 */
class ListInfoTest {

    // ==================== normalizeTree ====================

    @Test
    fun `normalize tree should keep empty list as is`() {
        val lists = emptyList<ListInfo>()
        assertTrue(normalized(lists).isEmpty(), "空列表归一后仍为空")
    }

    @Test
    fun `normalize tree should fill missing ids and keep order`() {
        val lists = listOf(
            ListInfo(name = "收藏", id = ""),          // 旧数据：无 id
            ListInfo(name = "老分组", id = ""),        // 旧数据：无 id
            ListInfo(name = "新分组", id = "fixed")    // 新数据：已有 id
        )
        val result = normalized(lists)

        assertEquals(lists.size, result.size, "归一前后长度一致")
        assertEquals(listOf("收藏", "老分组", "新分组"), result.map { it.name }, "归一前后顺序一致")
        assertTrue(result.all { it.id.isNotBlank() }, "缺 id 的旧数据应补 UUID")
        assertEquals("fixed", result[2].id, "已有 id 应保留")
        assertNotSame(lists[0], result[0], "归一应返回 copy 出的新实例")
    }

    @Test
    fun `normalize tree should force default list to root`() {
        val lists = listOf(ListInfo(name = "收藏", id = "d1", parentId = "p1"))
        assertEquals("", normalized(lists).single().parentId, "默认列表 parentId 应强制归顶级")
    }

    @Test
    fun `normalize tree should keep valid parent child relation`() {
        val lists = listOf(
            ListInfo(name = "收藏", id = "d1"),
            ListInfo(name = "父", id = "p1", parentId = ListInfo.ROOT_PARENT_ID),
            ListInfo(name = "子", id = "c1", parentId = "p1"),
            ListInfo(name = "孙", id = "g1", parentId = "c1")
        )
        val result = normalized(lists)
        assertEquals("p1", result.first { it.name == "子" }.parentId, "合法父子关系应原样保留")
        assertEquals("c1", result.first { it.name == "孙" }.parentId, "合法祖孙关系应原样保留")
    }

    @Test
    fun `normalize tree should reset self referencing parent to root`() {
        val lists = listOf(ListInfo(name = "自指", id = "a1", parentId = "a1"))
        assertEquals("", normalized(lists).single().parentId, "parentId 指向自己应归顶级")
    }

    @Test
    fun `normalize tree should reset dangling parent to root`() {
        val lists = listOf(ListInfo(name = "孤儿", id = "a1", parentId = "ghost"))
        assertEquals("", normalized(lists).single().parentId, "parentId 指向不存在的分组应归顶级")
    }

    @Test
    fun `normalize tree should cut cycle and keep all lists`() {
        // 回归：A→B→A 成环时归一必须断环且绝不丢列表
        val lists = listOf(
            ListInfo(name = "A", id = "a", parentId = "b"),
            ListInfo(name = "B", id = "b", parentId = "a")
        )
        val result = normalized(lists)
        assertEquals(2, result.size, "断环不丢列表")
        assertTrue(result.all { it.parentId == "" }, "环上分组应全部归顶级")
    }

    @Test
    fun `normalize tree should reset tail hanging into cycle to root`() {
        // C 挂进 A↔B 环：C 本身不构成环入口，但沿父链上行会回到已访问节点 → 同样归顶级
        val lists = listOf(
            ListInfo(name = "A", id = "a", parentId = "b"),
            ListInfo(name = "B", id = "b", parentId = "a"),
            ListInfo(name = "C", id = "c", parentId = "a")
        )
        val result = normalized(lists)
        assertTrue(result.all { it.parentId == "" }, "挂进环的尾部分组也应归顶级")
    }

    private fun normalized(lists: List<ListInfo>): List<ListInfo> = ListInfo.normalizeTree(lists)

    // ==================== formsCycle ====================

    @Test
    fun `forms cycle should detect self reference`() {
        val byId = mapOf("a" to ListInfo(name = "A", id = "a", parentId = "a"))
        assertTrue(ListInfo.formsCycle(byId, "a"), "直接指向自己应判定为环")
    }

    @Test
    fun `forms cycle should detect two node cycle from both sides`() {
        val byId = mapOf(
            "a" to ListInfo(name = "A", id = "a", parentId = "b"),
            "b" to ListInfo(name = "B", id = "b", parentId = "a")
        )
        assertTrue(ListInfo.formsCycle(byId, "a"), "从环上任一点起判均应为环")
        assertTrue(ListInfo.formsCycle(byId, "b"), "从环上任一点起判均应为环")
    }

    @Test
    fun `forms cycle should accept valid chain`() {
        val byId = mapOf(
            "a" to ListInfo(name = "A", id = "a", parentId = ListInfo.ROOT_PARENT_ID),
            "b" to ListInfo(name = "B", id = "b", parentId = "a"),
            "c" to ListInfo(name = "C", id = "c", parentId = "b")
        )
        assertFalse(ListInfo.formsCycle(byId, "c"), "正常父链不应判定为环")
        assertFalse(ListInfo.formsCycle(byId, "a"), "顶级分组不应判定为环")
    }

    @Test
    fun `forms cycle should accept dangling and blank parent`() {
        val dangling = mapOf("a" to ListInfo(name = "A", id = "a", parentId = "ghost"))
        assertFalse(ListInfo.formsCycle(dangling, "a"), "父分组不存在不应判定为环")

        val blank = mapOf("a" to ListInfo(name = "A", id = "a", parentId = ""))
        assertFalse(ListInfo.formsCycle(blank, "a"), "空 parentId 不应判定为环")
    }

    // ==================== collectDescendantIds ====================

    @Test
    fun `collect descendants should return all levels in list order`() {
        val lists = listOf(
            ListInfo(name = "收藏", id = "d0"),
            ListInfo(name = "父", id = "p", parentId = ListInfo.ROOT_PARENT_ID),
            ListInfo(name = "子", id = "c", parentId = "p"),
            ListInfo(name = "孙", id = "g", parentId = "c"),
            ListInfo(name = "旁支", id = "s", parentId = ListInfo.ROOT_PARENT_ID)
        )
        val descendants = ListInfo.collectDescendantIds(lists, "p")
        assertEquals(setOf("c", "g"), descendants, "应收集全部层级后代且不含自身/旁支")
    }

    @Test
    fun `collect descendants should return empty set for leaf`() {
        val lists = listOf(ListInfo(name = "父", id = "p"), ListInfo(name = "收藏", id = "d0"))
        assertTrue(ListInfo.collectDescendantIds(lists, "p").isEmpty(), "叶子分组无后代应返回空集")
    }

    @Test
    fun `collect descendants should terminate on cycle data`() {
        // 环数据（加载归一前可能出现）：收集必须自动截断不死循环
        val lists = listOf(
            ListInfo(name = "A", id = "a", parentId = "b"),
            ListInfo(name = "B", id = "b", parentId = "c"),
            ListInfo(name = "C", id = "c", parentId = "a")
        )
        val descendants = ListInfo.collectDescendantIds(lists, "a")
        assertEquals(setOf("c", "b", "a"), descendants, "环数据应收集全部分组并终止")
    }

    // ==================== CascadeDeletePreview 模型 ====================

    @Test
    fun `cascade delete preview should compute sub group count`() {
        val withChildren = CascadeDeletePreview(groupNames = listOf("父", "子", "孙"), movedBookmarkCount = 6)
        assertEquals(2, withChildren.subGroupCount, "连删子分组数 = 总数 - 1")
        val leaf = CascadeDeletePreview(groupNames = listOf("叶子"), movedBookmarkCount = 1)
        assertEquals(0, leaf.subGroupCount, "叶子分组无子分组")
        val empty = CascadeDeletePreview(groupNames = emptyList(), movedBookmarkCount = 0)
        assertEquals(0, empty.subGroupCount, "空名单不产生负数")
    }
}
