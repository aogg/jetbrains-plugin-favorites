package com.aogg.favorites.model

import com.aogg.favorites.helper.SharedBookmarkStorageHelper
import com.aogg.favorites.helper.SharedRefStorageHelper
import com.intellij.openapi.project.Project
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * SharedBookmarkStore（应用级共享收藏仓库）核心逻辑单元测试（1.7.0 新增，1.7.1 适配配置来源项目模型）
 *
 * 说明：
 * - 通过 SharedStoreTestSupport 注入 mock Application 后构造仓库，再注入真实例假存储
 *   （SharedBookmarkStorageHelper 无参构造），State 可直接检查「写落盘」内容
 * - 项目级订阅引用走注入的真实例 SharedRefStorageHelper（state.refs/sourceProjects 可直接断言）
 * - 1.7.0 遗留行为：collectionIdFor 稳定/唯一/归一、集合创建幂等、可见集合=自己+旧订阅且自己在前、
 *   subscribe/unsubscribe 往返、removeCollection 只删自己集合、写操作落盘（owner 信息/更新时间）
 * - 1.7.1 配置来源项目模型：setConfiguredSourceProjects 规范化/去重/排除自身/覆盖写并清空旧 refs、
 *   旧订阅 refs 惰性迁移（映射回 ownerProjectPath、丢弃映射不到的、排除自身、清空 refs）
 */
class SharedBookmarkStoreTest {

    private lateinit var refHelper: SharedRefStorageHelper
    private lateinit var sharedHelper: SharedBookmarkStorageHelper
    private lateinit var store: SharedBookmarkStore
    private lateinit var project: Project

    /**
     * 第二个 mock 项目（充当「来源项目」，创建外来集合）
     */
    private lateinit var foreignProject: Project

    @BeforeEach
    fun setUp() {
        // ////////////////  准备数据：假存储 + 注入式仓库 + mock 项目  start  //////////////
        refHelper = SharedRefStorageHelper()
        sharedHelper = SharedBookmarkStorageHelper()
        store = SharedStoreTestSupport.newSharedStore(sharedHelper)
        project = SharedStoreTestSupport.mockProject("/projA", "ProjA", refHelper)
        foreignProject = SharedStoreTestSupport.mockProject("/projB", "ProjB", refHelper)
        // ////////////////  准备数据  end  ////////////////////////////////////////////
    }

    @AfterEach
    fun tearDown() {
        store.dispose()
        SharedStoreTestSupport.uninstallMockApplication()
    }

    // ==================== collectionIdFor ====================

    @Test
    fun `collection id should be stable for same path and differ for different paths`() {
        // 同一路径多次计算：结果稳定（同一项目多窗口/重开项目得到同一集合）
        val first = SharedBookmarkStore.collectionIdFor("/projA")
        val second = SharedBookmarkStore.collectionIdFor("/projA")
        assertEquals(first, second, "同一路径应得到稳定集合 id")
        assertEquals(16, first.length, "集合 id 应为 16 位 hex 字符串")

        // 不同路径：集合 id 不同（各项目数据分片隔离）
        assertNotEquals(first, SharedBookmarkStore.collectionIdFor("/projB"), "不同路径应得到不同集合 id")
    }

    @Test
    fun `collection id should normalize separators and trailing slashes`() {
        // Windows 反斜杠与尾部斜杠都应归一为正斜杠无尾斜杠
        assertEquals(
            SharedBookmarkStore.collectionIdFor("C:/proj/sub"),
            SharedBookmarkStore.collectionIdFor("C:\\proj\\sub\\"),
            "反斜杠与尾斜杠应归一后计算"
        )
        assertEquals(
            SharedBookmarkStore.collectionIdFor("/projA"),
            SharedBookmarkStore.collectionIdFor("/projA/"),
            "尾部斜杠应归一后计算"
        )
        assertEquals(
            SharedBookmarkStore.collectionIdFor("/projA"),
            SharedBookmarkStore.collectionIdFor("\\projA\\"),
            "纯反斜杠风格应与正斜杠风格等价"
        )
    }

    // ==================== 集合创建与可见性 ====================

    @Test
    fun `collection of should be idempotent for same project`() {
        // 同一项目两次调用：返回同一集合实例（幂等，不重复创建）
        val first = store.collectionOf(project)
        val second = store.collectionOf(project)
        assertSame(first, second, "同项目二次调用应返回同一集合实例")
        assertEquals(SharedBookmarkStore.collectionIdFor("/projA"), first.id, "集合 id 应按项目路径计算")
        assertEquals("ProjA", first.ownerProjectName, "owner 名应取项目名")
        assertEquals("/projA", first.ownerProjectPath, "owner 路径应取项目根路径")
    }

    @Test
    fun `visible collections should be own first then subscribed in order`() {
        val foreign = store.collectionOf(foreignProject)

        // 未订阅：可见集合只有自己的
        assertEquals(listOf(store.collectionOf(project)), store.visibleCollections(project), "未订阅时只应看到自己的集合")

        // 订阅外来集合后：自己在前、订阅随后
        store.subscribe(project, foreign.id)
        assertEquals(
            listOf(store.collectionOf(project), foreign),
            store.visibleCollections(project),
            "可见集合应为自己在前、订阅集合随后"
        )

        // 订阅自己集合 id：应被去重（不重复出现）
        store.subscribe(project, store.collectionOf(project).id)
        assertEquals(2, store.visibleCollections(project).size, "订阅自己集合不应重复出现")

        // 订阅不存在的集合 id：静默跳过（来源项目取消共享后自然消失）
        store.subscribe(project, "不存在的集合id")
        assertEquals(2, store.visibleCollections(project).size, "不存在的订阅集合应被静默跳过")
    }

    @Test
    fun `subscribe and unsubscribe should roundtrip refs storage`() {
        val foreign = store.collectionOf(foreignProject)

        // 订阅：写入项目级引用存储
        store.subscribe(project, foreign.id)
        assertEquals(listOf(foreign.id), refHelper.state.refs, "订阅应写入引用存储")

        // 重复订阅：幂等（不重复追加）
        store.subscribe(project, foreign.id)
        assertEquals(listOf(foreign.id), refHelper.state.refs, "重复订阅应保持幂等")

        // 订阅顺序保持首次订阅位置
        val another = SharedBookmarkStore.collectionIdFor("/projC")
        store.subscribe(project, another)
        assertEquals(listOf(foreign.id, another), refHelper.state.refs, "订阅顺序应保持追加顺序")

        // 取消订阅：从引用存储移除
        store.unsubscribe(project, foreign.id)
        assertEquals(listOf(another), refHelper.state.refs, "取消订阅应移除引用")

        // 取消未订阅的集合：无副作用
        store.unsubscribe(project, "未订阅的id")
        assertEquals(listOf(another), refHelper.state.refs, "取消未订阅集合应无副作用")
    }

    @Test
    fun `remove collection should only remove own collection`() {
        val own = store.collectionOf(project)
        val foreign = store.collectionOf(foreignProject)

        // 删别人的集合：拒绝
        assertFalse(store.removeCollection(project, foreign.id), "删除外来集合应返回 false")
        assertNotNull(store.getCollection(foreign.id), "外来集合应保留")
        assertTrue(sharedHelper.state.collections.containsKey(foreign.id), "外来集合切片应保留")

        // 删自己的集合：成功，内存与存储切片同时移除
        assertTrue(store.removeCollection(project, own.id), "删除自己的集合应成功")
        assertNull(store.getCollection(own.id), "内存中的集合应已移除")
        assertFalse(sharedHelper.state.collections.containsKey(own.id), "存储切片应已移除")
    }

    // ==================== 写操作落盘 ====================

    @Test
    fun `write operations should persist into injected storage with owner info`() {
        val own = store.collectionOf(project)

        // 写操作：向自己集合添加助记符收藏
        assertTrue(own.core.addBookmark(Bookmark("B1", BookmarkType.LINE, "/projA/src/A.php", 3, mnemonic = 'A')), "写入应成功")

        // ////////////////  返回：检查注入存储中的集合切片  start  /////////////////////////
        val data = sharedHelper.state.collections[own.id]
        assertNotNull(data, "写入后存储应出现该集合切片")
        assertEquals(own.id, data!!.id, "切片 id 应与集合 id 一致")
        assertEquals("ProjA", data.ownerProjectName, "切片应记录来源项目名")
        assertEquals("/projA", data.ownerProjectPath, "切片应记录来源项目路径")
        assertTrue(data.updatedTime > 0, "切片应记录内容更新时间")

        // content 复用项目级 State 结构：助记符收藏应可读回
        val (bookmarks, lists) = com.aogg.favorites.helper.BookmarkStorageHelper.readFromState(data.content)
        assertEquals(listOf("B1"), bookmarks.map { it.id }, "收藏应写进切片 content")
        assertEquals('A', bookmarks.first().mnemonic, "助记符应写进切片 content")
        assertTrue(lists.any { it.name == Bookmark.DEFAULT_LIST_NAME }, "默认列表元信息应写进切片 content")
        // ////////////////  返回  end  /////////////////////////////////////////////////
    }

    @Test
    fun `new store instance should read persisted slices from same storage`() {
        // 旧仓库写入数据（模拟上一个 IDE 进程的落盘）
        val oldStore = SharedStoreTestSupport.newSharedStore(sharedHelper)
        val own = oldStore.collectionOf(project)
        own.core.addBookmark(Bookmark("B1", BookmarkType.LINE, "/projA/src/A.php", 3))
        oldStore.dispose()

        // 新仓库实例（同一存储）：懒加载内核在首次访问时读取最新切片，数据不丢
        val newStore = SharedStoreTestSupport.newSharedStore(sharedHelper)
        try {
            val collection = newStore.collectionOf(project)
            assertNotNull(collection.core.getBookmark("B1"), "新仓库应从存储恢复已有收藏")
            assertEquals("ProjA", collection.ownerProjectName, "owner 信息应从存储恢复")
            assertEquals("/projA", collection.ownerProjectPath, "owner 路径应从存储恢复")
        } finally {
            newStore.dispose()
        }
    }

    @Test
    fun `collection of bookmark should search own first then subscribed`() {
        val own = store.collectionOf(project)
        val foreign = store.collectionOf(foreignProject)
        own.core.addBookmark(Bookmark("B1", BookmarkType.LINE, "/projA/src/A.php", 1))
        foreign.core.addBookmark(Bookmark("F1", BookmarkType.LINE, "/projB/src/X.php", 2))

        // 自己集合命中：返回自己的集合
        assertEquals(own, store.collectionOfBookmark(project, "B1"), "自己集合的收藏应归属自己的集合")

        // 外来集合：需订阅后才可查到（转移语义判定用）
        assertNull(store.collectionOfBookmark(project, "F1"), "未订阅时外来收藏不应可见")
        store.subscribe(project, foreign.id)
        assertEquals(foreign, store.collectionOfBookmark(project, "F1"), "订阅后外来收藏应归属外来集合")

        // 都未命中：返回 null
        assertNull(store.collectionOfBookmark(project, "不存在"), "未命中的收藏应返回 null")
    }

    // ==================== 来源项目配置（1.7.1） ====================

    @Test
    fun `set configured source projects should normalize dedupe exclude self and drop blank`() {
        // ////////////////  核心逻辑：规范化+去重+排除自身+去空白  start  /////////////////
        store.setConfiguredSourceProjects(
            project,
            listOf("/src/one/", "\\src\\two\\", "/src/one", "", "/projA", "/projA/")
        )
        // ////////////////  核心逻辑  end  /////////////////////////////////////////////

        // ////////////////  返回：规范化正斜杠、去尾斜杠、首现保序去重、空串与自身剔除  //
        assertEquals(
            listOf("/src/one", "/src/two"),
            store.configuredSourceProjects(project),
            "应只保留规范化去重后且非自身的来源项目"
        )
        // ////////////////  返回  end  /////////////////////////////////////////////////
    }

    @Test
    fun `set configured source projects with empty list should clear all config`() {
        // ////////////////  准备数据：先配置一个来源项目  start  ////////////////////////
        store.setConfiguredSourceProjects(project, listOf("/src/one"))
        assertEquals(listOf("/src/one"), store.configuredSourceProjects(project))
        // ////////////////  准备数据  end  /////////////////////////////////////////////

        // ////////////////  返回：空列表整体覆盖=清空全部配置  start  ///////////////////
        store.setConfiguredSourceProjects(project, emptyList())
        assertTrue(store.configuredSourceProjects(project).isEmpty(), "空配置应清空全部来源项目")
        // ////////////////  返回  end  /////////////////////////////////////////////////
    }

    @Test
    fun `set configured source projects should clear legacy refs and overwrite previous config`() {
        // ////////////////  准备数据：旧订阅模型先订阅外来集合  start  ///////////////////
        val foreign = store.collectionOf(foreignProject)
        store.subscribe(project, foreign.id)
        assertEquals(listOf(foreign.id), refHelper.state.refs, "前置条件：旧订阅已在 refs 中")
        // ////////////////  准备数据  end  /////////////////////////////////////////////

        // ////////////////  核心逻辑：保存配置（顺带清空旧 refs）→ 再次覆盖写  start  ///
        store.setConfiguredSourceProjects(project, listOf("/src/one"))
        assertTrue(refHelper.state.refs.isEmpty(), "保存配置应顺带清空旧订阅 refs")
        assertEquals(listOf("/src/one"), store.configuredSourceProjects(project))

        store.setConfiguredSourceProjects(project, listOf("/src/two"))
        // ////////////////  核心逻辑  end  /////////////////////////////////////////////

        // ////////////////  返回：整体覆盖语义  start  /////////////////////////////////
        assertEquals(listOf("/src/two"), store.configuredSourceProjects(project), "再次保存应为整体覆盖而非追加")
        // ////////////////  返回  end  /////////////////////////////////////////////////
    }

    // ==================== 旧订阅 refs 惰性迁移（1.7.0 → 1.7.1） ====================

    @Test
    fun `legacy refs should migrate to source projects on first configured read`() {
        // ////////////////  准备数据：仓库里有两个可映射集合，refs 存旧订阅 id  start  ////
        val foreign = store.collectionOf(foreignProject)
        val third = store.collectionOf(SharedStoreTestSupport.mockProject("/projC", "ProjC", refHelper))
        refHelper.state.refs.addAll(listOf(foreign.id, third.id))
        // ////////////////  准备数据  end  /////////////////////////////////////////////

        // ////////////////  核心逻辑：首次读取配置触发迁移  start  //////////////////////
        val sources = store.configuredSourceProjects(project)
        // ////////////////  核心逻辑  end  /////////////////////////////////////////////

        // ////////////////  返回：id 映射回路径转入、refs 清空、二次读取稳定  start  ////
        assertEquals(listOf("/projB", "/projC"), sources, "旧订阅 id 应按订阅顺序映射为来源项目路径")
        assertTrue(refHelper.state.refs.isEmpty(), "迁移后旧订阅 refs 应清空")
        assertEquals(listOf("/projB", "/projC"), store.configuredSourceProjects(project), "refs 清空后再次读取应稳定不重复迁移")
        // ////////////////  返回  end  /////////////////////////////////////////////////
    }

    @Test
    fun `unmappable legacy refs should be discarded during migration`() {
        // ////////////////  准备数据：一个可映射 id + 一个仓库中不存在的 id  start  ///////
        val foreign = store.collectionOf(foreignProject)
        refHelper.state.refs.addAll(listOf("0123456789abcdef", foreign.id))
        // ////////////////  准备数据  end  /////////////////////////////////////////////

        // ////////////////  返回：映射不到的丢弃，可映射的转入，refs 仍清空  start  ////
        assertEquals(listOf("/projB"), store.configuredSourceProjects(project), "映射不到的旧 id 应直接丢弃")
        assertTrue(refHelper.state.refs.isEmpty(), "丢弃后旧订阅 refs 同样应清空（只迁移一次）")
        // ////////////////  返回  end  /////////////////////////////////////////////////
    }

    @Test
    fun `migration should map owner path to normalized form`() {
        // ////////////////  准备数据：直接在存储层造一个 ownerProjectPath 带尾斜杠的集合，
        //                    并 reset 让仓库重新加载（模拟旧版本落盘的未规范化路径）  start  //
        val idB = SharedBookmarkStore.collectionIdFor("/projB")
        sharedHelper.state.collections[idB] = SharedBookmarkStorageHelper.SharedCollectionData(
            id = idB,
            ownerProjectName = "ProjB",
            ownerProjectPath = "/projB/",
            updatedTime = 1L
        )
        refHelper.state.refs.addAll(listOf(idB))
        store.resetForTest()
        // ////////////////  准备数据  end  /////////////////////////////////////////////

        // ////////////////  返回：迁移映射应做路径规范化  start  ////////////////////////
        assertEquals(listOf("/projB"), store.configuredSourceProjects(project), "迁移后的来源路径应为规范化形式")
        assertTrue(refHelper.state.refs.isEmpty(), "迁移后 refs 应清空")
        // ////////////////  返回  end  /////////////////////////////////////////////////
    }

    @Test
    fun `migration should exclude own collection path and keep existing config`() {
        // ////////////////  准备数据：自己的集合已在仓库 + 已配置一个来源项目，
        //                    refs 里含自己的集合 id 与已配置项目的 id  start  ///////////
        val own = store.collectionOf(project)
        val foreign = store.collectionOf(foreignProject)
        refHelper.state.sourceProjects.addAll(listOf("/projB"))
        refHelper.state.refs.addAll(listOf(own.id, foreign.id))
        // ////////////////  迁移前置  end  ////////////////////////////////////////////

        // ////////////////  返回：自身路径被排除、已配置路径不重复追加、refs 清空  start  //
        assertEquals(listOf("/projB"), store.configuredSourceProjects(project), "迁移应排除自身且不重复追加已配置路径")
        assertTrue(refHelper.state.refs.isEmpty(), "迁移后 refs 应清空")
        // ////////////////  返回  end  /////////////////////////////////////////////////
    }

    @Test
    fun `configured read should not migrate when refs already empty`() {
        // ////////////////  准备数据：仅配置来源项目（迁移后典型状态）  start  ///////////
        store.setConfiguredSourceProjects(project, listOf("/src/one"))
        // ////////////////  准备数据  end  /////////////////////////////////////////////

        // ////////////////  返回：refs 为空时读取配置零副作用  start  ///////////////////
        assertEquals(listOf("/src/one"), store.configuredSourceProjects(project), "无旧数据时读取应原样返回配置")
        assertTrue(refHelper.state.refs.isEmpty(), "refs 应保持为空")
        assertTrue(refHelper.state.sourceProjects.size == 1, "既有配置不应被读取动作改动")
        // ////////////////  返回  end  /////////////////////////////////////////////////
    }
}
