package com.aogg.favorites.model

import com.aogg.favorites.helper.SharedBookmarkStorageHelper
import com.aogg.favorites.helper.SharedRefStorageHelper
import com.intellij.openapi.project.Project
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * SharedBookmarkManager（项目级共享收藏门面）单元测试（1.7.0 新增，1.7.1 适配配置来源项目模型）
 *
 * 说明：
 * - 真实 SharedBookmarkStore（注入假存储）+ 真实 SharedRefStorageHelper 注入门面，
 *   脱离 IDE 环境验证「自己集合可写、外来数据只读」的门面语义
 * - 1.7.1 起注册真实 SourceProjectStore 实例（磁盘解析分支：mock Application 下
 *   ProjectManager.getInstanceIfCreated 返回 null），来源项目夹具为临时目录里的
 *   .idea/favorites.xml（SharedStoreTestSupport.writeFavoritesXml 平台格式生成）
 * - 1.7.0 遗留行为（旧订阅 legacyForeignCollections）：读聚合含订阅集合、同名列表自己优先、
 *   写落自己集合、外来写操作返回 false/0/null、isMnemonicAvailable 只看自己集合、
 *   getBookmarkByMnemonic 自己优先
 * - 1.7.1 配置来源项目模型：单点查找含来源切片回退、聚合读不含来源切片、
 *   isForeignBookmark 覆盖来源切片、来源切片写操作全部拒绝且不回写来源项目
 */
class SharedBookmarkManagerTest {

    /** 每个测试独立的临时目录（来源项目根的宿主） */
    @TempDir
    lateinit var tempDir: File

    private lateinit var refHelper: SharedRefStorageHelper
    private lateinit var sharedHelper: SharedBookmarkStorageHelper
    private lateinit var store: SharedBookmarkStore
    private lateinit var project: Project
    private lateinit var foreignProject: Project
    private lateinit var manager: SharedBookmarkManager

    @BeforeEach
    fun setUp() {
        // ////////////////  准备数据：真实仓库 + 注入门面 + 真实来源项目存储  start  ///////
        refHelper = SharedRefStorageHelper()
        sharedHelper = SharedBookmarkStorageHelper()
        store = SharedStoreTestSupport.newSharedStore(sharedHelper)
        project = SharedStoreTestSupport.mockProject("/projA", "ProjA", refHelper)
        foreignProject = SharedStoreTestSupport.mockProject("/projB", "ProjB", refHelper)
        manager = SharedBookmarkManager(project)
        manager.setStoreForTest(store)
        manager.setRefsHelperForTest(refHelper)
        // 注册应用级 SourceProjectStore（未打开来源项目 → 磁盘解析分支）
        SharedStoreTestSupport.registerSourceProjectStore(SourceProjectStore())
        // ////////////////  准备数据  end  ////////////////////////////////////////////
    }

    @AfterEach
    fun tearDown() {
        store.dispose()
        SharedStoreTestSupport.uninstallMockApplication()
    }

    /**
     * 订阅外来集合（来源项目 /projB）的辅助方法
     */
    private fun subscribeForeign(): SharedBookmarkStore.SharedCollection {
        val foreign = store.collectionOf(foreignProject)
        store.subscribe(project, foreign.id)
        return foreign
    }

    /**
     * 在临时目录造一个来源项目（写 .idea/favorites.xml 夹具）并返回其根目录
     */
    private fun newSourceProject(
        name: String,
        bookmarks: List<Bookmark>,
        lists: List<ListInfo> = emptyList()
    ): File {
        val root = File(tempDir, name).apply { mkdirs() }
        SharedStoreTestSupport.writeFavoritesXml(root, bookmarks, lists)
        return root
    }

    // ==================== 读聚合（自己 + 订阅） ====================

    @Test
    fun `read should aggregate own and subscribed collections with own first`() {
        // 自己集合与外来集合各有一条收藏
        assertTrue(manager.addBookmark(Bookmark("B1", BookmarkType.LINE, "/projA/src/A.php", 3, mnemonic = 'A')))
        val foreign = store.collectionOf(foreignProject)
        foreign.core.addBookmark(Bookmark("F1", BookmarkType.LINE, "/projB/src/X.php", 7, mnemonic = 'B'))

        // 未订阅：读聚合只见自己集合
        assertEquals(listOf("B1"), manager.getAllBookmarks().map { it.id }, "未订阅时读聚合只见自己集合")
        assertNull(manager.getBookmark("F1"), "未订阅时外来收藏不可见")

        // 订阅后：聚合可见，自己集合在前
        store.subscribe(project, foreign.id)
        assertEquals(listOf("B1", "F1"), manager.getAllBookmarks().map { it.id }, "读聚合应自己在前、订阅随后")
        assertEquals("F1", manager.getBookmark("F1")!!.id, "订阅后外来收藏应可读")
        assertEquals(
            listOf("F1"),
            manager.getLineBookmarks("/projB/src/X.php", 7).map { it.id },
            "行收藏查询应覆盖订阅集合数据"
        )
        assertEquals(2, manager.getTotalCount(), "总数应聚合自己与订阅集合")
        assertEquals(setOf('A', 'B'), manager.getUsedMnemonics(), "已用助记符应为两集合并集")
    }

    @Test
    fun `same name list should prefer own collection`() {
        // 自己集合与外来集合都建了同名列表「工作」，各放一条收藏
        assertTrue(manager.createList("工作"))
        manager.addBookmark(Bookmark("B2", BookmarkType.LINE, "/projA/src/B.php", 1, listName = "工作"))

        val foreign = store.collectionOf(foreignProject)
        foreign.core.createList("工作")
        foreign.core.addBookmark(Bookmark("F2", BookmarkType.LINE, "/projB/src/Y.php", 2, listName = "工作"))
        foreign.core.createList("外来列表")
        foreign.core.addBookmark(Bookmark("F3", BookmarkType.LINE, "/projB/src/Z.php", 3, listName = "外来列表"))
        store.subscribe(project, foreign.id)

        // getLists：同名分组被自己遮蔽不重复出现；外来独有分组正常出现且排在后面
        val lists = manager.getLists()
        val workLists = lists.filter { it.name == "工作" }
        assertEquals(1, workLists.size, "同名分组应只出现一次（自己优先）")
        val ownWork = manager.ownCollectionManager().getLists().first { it.name == "工作" }
        assertEquals(ownWork.id, workLists.first().id, "「工作」应取自己集合的分组")
        assertEquals("外来列表", lists.last().name, "外来独有分组应出现在聚合末尾")

        // getBookmarksByList：同名分组按自己集合返回，外来数据不混入
        assertEquals(listOf("B2"), manager.getBookmarksByList("工作").map { it.id }, "同名分组应只返回自己集合的收藏")
        assertEquals(listOf("F3"), manager.getBookmarksByList("外来列表").map { it.id }, "外来独有分组应返回外来收藏")
    }

    // ==================== 写落自己集合 ====================

    @Test
    fun `write should land in own collection only`() {
        val foreign = subscribeForeign()
        foreign.core.addBookmark(Bookmark("F1", BookmarkType.LINE, "/projB/src/X.php", 1))

        // 门面写操作：落自己集合
        assertTrue(manager.addBookmark(Bookmark("B3", BookmarkType.FILE, "/projA/src/C.php", -1)), "写操作应成功")
        assertTrue(manager.createList("新建列表"), "新建分组应成功")

        // 自己集合有数据、外来集合不受影响
        val own = store.collectionOf(project)
        assertNotNull(own.core.getBookmark("B3"), "写操作应落自己集合")
        assertTrue(own.core.hasList("新建列表"), "新建分组应落自己集合")
        assertEquals(listOf("F1"), foreign.core.getAllBookmarks().map { it.id }, "外来集合数据不应被写操作影响")
        assertFalse(foreign.core.hasList("新建列表"), "新建分组不应落到外来集合")
    }

    // ==================== 外来集合只读（写操作返回 false/0/null） ====================

    @Test
    fun `foreign bookmark write operations should be rejected`() {
        val foreign = subscribeForeign()
        foreign.core.createList("外来列表")
        foreign.core.addBookmark(Bookmark("F1", BookmarkType.LINE, "/projB/src/X.php", 1, mnemonic = 'F'))
        foreign.core.createList("工作")
        foreign.core.addBookmark(Bookmark("F2", BookmarkType.LINE, "/projB/src/Y.php", 2, listName = "工作"))

        // 读可见（聚合），写全部拒绝
        assertNotNull(manager.getBookmark("F1"), "外来收藏应可读")

        assertFalse(manager.removeBookmark("F1"), "删除外来收藏应返回 false")
        assertNotNull(foreign.core.getBookmark("F1"), "外来收藏应保留不被删除")

        assertFalse(manager.updateBookmarkDescription("F1", "改描述"), "改外来收藏描述应返回 false")
        assertFalse(manager.updateBookmarkDetails("F1", 'Z', null), "改外来收藏助记符应返回 false")
        assertEquals('F', foreign.core.getBookmark("F1")!!.mnemonic, "外来收藏助记符不应被改动")

        assertFalse(manager.moveToList("F1", "工作"), "移动外来收藏应返回 false")
        assertEquals("收藏", foreign.core.getBookmark("F1")!!.listName, "外来收藏列表归属不应被改动")

        assertFalse(manager.setSortType("外来列表", SortType.LOCATION), "设置外来分组排序应返回 false")
        assertEquals(SortType.ADDED, foreign.core.getSortType("外来列表"), "外来分组排序不应被改动")

        assertFalse(manager.deleteList("外来列表"), "删除外来分组应返回 false")
        assertTrue(foreign.core.hasList("外来列表"), "外来分组应保留")

        // 按路径删除只作用于自己集合：外来路径返回 0
        assertEquals(0, manager.removeBookmarksByPath("/projB/src", includeChildren = true), "外来路径删除应返回 0")
        assertEquals(2, foreign.core.getAllBookmarks().size, "外来收藏应全部保留")
    }

    // ==================== 助记符语义 ====================

    @Test
    fun `isMnemonicAvailable should only consider own collection`() {
        // 外来集合占用 A：不参与写入校验
        val foreign = subscribeForeign()
        foreign.core.addBookmark(Bookmark("F1", BookmarkType.LINE, "/projB/src/X.php", 1, mnemonic = 'A'))

        // 写入校验只看自己集合：外来占用的 A 视为可用
        assertTrue(manager.isMnemonicAvailable('A'), "外来占用的助记符不应影响自己集合的可用性判断")
        assertTrue(manager.addBookmark(Bookmark("B1", BookmarkType.LINE, "/projA/src/A.php", 1, mnemonic = 'A')), "自己集合使用 A 应成功")

        // 自己集合内冲突照常拒绝
        assertFalse(manager.addBookmark(Bookmark("B2", BookmarkType.LINE, "/projA/src/B.php", 2, mnemonic = 'A')), "自己集合内重复使用 A 应失败")
    }

    @Test
    fun `getBookmarkByMnemonic should prefer own collection`() {
        val foreign = subscribeForeign()
        foreign.core.addBookmark(Bookmark("F1", BookmarkType.LINE, "/projB/src/X.php", 1, mnemonic = 'A'))
        manager.addBookmark(Bookmark("B1", BookmarkType.LINE, "/projA/src/A.php", 1, mnemonic = 'A'))

        // 两个集合各有一个 A：自己集合优先
        assertEquals("B1", manager.getBookmarkByMnemonic('A')!!.id, "助记符查找应自己集合优先")

        // 自己集合删除后：降级到订阅集合
        assertTrue(manager.removeBookmark("B1"), "删除自己集合收藏应成功")
        assertEquals("F1", manager.getBookmarkByMnemonic('A')!!.id, "自己集合删除后应聚合到订阅集合")

        // 取消订阅后：不可见
        store.unsubscribe(project, foreign.id)
        assertNull(manager.getBookmarkByMnemonic('A'), "取消订阅后外来收藏不可见")
    }

    // ==================== 配置来源项目（1.7.1：单点查找含来源切片，聚合读不含） ====================

    @Test
    fun `configured source slice should be visible via single lookup but excluded from aggregate reads`() {
        // ////////////////  准备数据：来源项目夹具（相对路径 + 助记符）并配置  start  ///////
        val sourceRoot = newSourceProject(
            "srcB",
            listOf(
                Bookmark("SB", BookmarkType.LINE, "src/Rel.php", 5, mnemonic = 'B', description = "来源收藏", listName = "工作"),
                Bookmark("SB2", BookmarkType.FILE, "lib/Rel2.php", listName = "工作")
            ),
            listOf(ListInfo("工作", SortType.LOCATION))
        )
        store.setConfiguredSourceProjects(project, listOf(sourceRoot.absolutePath))
        manager.addBookmark(Bookmark("B1", BookmarkType.LINE, "/projA/src/A.php", 3, mnemonic = 'A'))
        // ////////////////  准备数据  end  /////////////////////////////////////////////

        // ////////////////  返回：单点查找命中来源切片（含路径换算/助记符回退）  start  ////
        val sb = manager.getBookmark("SB")
        assertNotNull(sb, "单点查找应回退到来源切片")
        assertEquals(
            File(sourceRoot, "src/Rel.php").absolutePath.replace('\\', '/'),
            sb!!.filePath,
            "来源切片返回的收藏应为来源根下绝对路径"
        )
        assertEquals("SB", manager.getBookmarkByMnemonic('B')!!.id, "助记符跳转应回退到来源切片")
        // ////////////////  返回  end  /////////////////////////////////////////////////

        // ////////////////  返回：聚合读不含来源切片  start  ////////////////////////////
        assertEquals(listOf("B1"), manager.getAllBookmarks().map { it.id }, "聚合读应不含来源切片")
        assertEquals(1, manager.getTotalCount(), "总数统计应不含来源切片")
        assertTrue(
            manager.getLineBookmarks(sb.filePath, 5).isEmpty(),
            "行收藏查询（gutter 匹配）应不含来源切片"
        )
        assertEquals(setOf('A'), manager.getUsedMnemonics(), "已用助记符统计应不含来源切片")
        assertEquals(
            listOf(Bookmark.DEFAULT_LIST_NAME),
            manager.getLists().map { it.name },
            "分组聚合应不含来源切片的分组"
        )
        assertTrue(manager.getBookmarksByList("工作").isEmpty(), "按组取收藏不应取到来源切片数据")
        // ////////////////  返回  end  /////////////////////////////////////////////////

        // ////////////////  返回：isForeignBookmark 覆盖来源切片  start  ////////////////
        assertTrue(manager.isForeignBookmark("SB"), "来源切片收藏应判定为外来只读")
        assertFalse(manager.isForeignBookmark("B1"), "自己集合收藏不应判定为外来")
        // ////////////////  返回  end  /////////////////////////////////////////////////
    }

    @Test
    fun `own collection should take precedence over source slice on lookups`() {
        // ////////////////  准备数据：自己与来源切片各有一条助记符 A 收藏  start  //////////
        val sourceRoot = newSourceProject(
            "srcDup",
            listOf(Bookmark("SB", BookmarkType.LINE, "src/S.php", 2, mnemonic = 'A'))
        )
        store.setConfiguredSourceProjects(project, listOf(sourceRoot.absolutePath))
        manager.addBookmark(Bookmark("B1", BookmarkType.LINE, "/projA/src/A.php", 1, mnemonic = 'A'))
        // ////////////////  准备数据  end  /////////////////////////////////////////////

        // ////////////////  返回：单点查找自己集合优先  start  //////////////////////////
        assertEquals("B1", manager.getBookmarkByMnemonic('A')!!.id, "助记符查找应自己集合优先")
        assertEquals("B1", manager.getBookmark("B1")!!.id, "自己集合收藏应优先命中")
        assertFalse(manager.isForeignBookmark("B1"), "自己集合收藏不是外来数据")
        assertTrue(manager.isForeignBookmark("SB"), "同助记符的来源切片收藏仍是外来数据")
        // ////////////////  返回  end  /////////////////////////////////////////////////
    }

    @Test
    fun `source slice should be readonly and never write back to source project`() {
        // ////////////////  准备数据：来源项目夹具并记录磁盘原始字节  start  //////////////
        val sourceRoot = newSourceProject(
            "srcRo",
            listOf(Bookmark("SB", BookmarkType.LINE, "src/S.php", 1, mnemonic = 'B'))
        )
        val xmlFile = File(File(sourceRoot, ".idea"), "favorites.xml")
        val before = xmlFile.readBytes()
        store.setConfiguredSourceProjects(project, listOf(sourceRoot.absolutePath))
        manager.createList("工作")
        assertNotNull(manager.getBookmark("SB"), "前置条件：来源切片收藏可读")
        // ////////////////  准备数据  end  /////////////////////////////////////////////

        // ////////////////  核心逻辑：对来源切片收藏发起各种写请求  start  ////////////////
        assertFalse(manager.removeBookmark("SB"), "删除来源收藏应返回 false")
        assertFalse(manager.updateBookmarkDescription("SB", "改描述"), "改来源收藏描述应返回 false")
        assertFalse(manager.updateBookmarkDetails("SB", 'Z', null), "改来源收藏助记符应返回 false")
        assertFalse(manager.moveToList("SB", "工作"), "移动来源收藏应返回 false")
        assertEquals(
            0,
            manager.removeBookmarksByPath(File(sourceRoot, "src").absolutePath.replace('\\', '/'), includeChildren = true),
            "按来源路径删除应返回 0"
        )
        // ////////////////  核心逻辑  end  /////////////////////////////////////////////

        // ////////////////  返回：来源项目磁盘文件字节级不变  start  /////////////////////
        assertTrue(
            before.contentEquals(xmlFile.readBytes()),
            "任何写入口都不得回写来源项目的 .idea/favorites.xml"
        )
        // ////////////////  返回  end  /////////////////////////////////////////////////
    }

    @Test
    fun `legacy foreign collections should aggregate until source config replaces them`() {
        // ////////////////  准备数据：1.7.0 旧订阅遗留集合 + 自己收藏  start  //////////////
        val foreign = store.collectionOf(foreignProject)
        foreign.core.addBookmark(Bookmark("F1", BookmarkType.LINE, "/projB/src/X.php", 1, mnemonic = 'C'))
        store.subscribe(project, foreign.id)
        manager.addBookmark(Bookmark("B1", BookmarkType.LINE, "/projA/src/A.php", 3, mnemonic = 'A'))
        // ////////////////  准备数据  end  /////////////////////////////////////////////

        // ////////////////  返回：迁移前聚合读含自己与旧订阅遗留、覆盖外来判定  start  ///////
        // 注意：此阶段不能触发任何来源项目读取（configuredSourceProjects 首读即把 refs 迁移清空），
        // getAllBookmarks/isForeignBookmark 均只走 visibleCollections/collectionOfBookmark，安全
        assertEquals(
            listOf("B1", "F1"),
            manager.getAllBookmarks().map { it.id },
            "迁移前聚合读应含自己与旧订阅遗留集合"
        )
        assertTrue(manager.isForeignBookmark("F1"), "迁移前旧订阅遗留收藏应判定为外来")
        // ////////////////  返回  end  /////////////////////////////////////////////////

        // ////////////////  核心逻辑：保存来源项目配置（1.7.1 模型整体覆盖）  start  ///////
        // setConfiguredSourceProjects 会清空旧订阅 refs（保存新配置即替代旧订阅模型）
        val sourceRoot = newSourceProject(
            "srcMix",
            listOf(Bookmark("SB", BookmarkType.LINE, "src/S.php", 2, mnemonic = 'B'))
        )
        store.setConfiguredSourceProjects(project, listOf(sourceRoot.absolutePath))
        // ////////////////  核心逻辑  end  /////////////////////////////////////////////

        // ////////////////  返回：聚合读只含自己；来源切片仅单点查找/外来判定可见  start  ///
        assertEquals(
            listOf("B1"),
            manager.getAllBookmarks().map { it.id },
            "配置来源项目后聚合读只含自己（旧订阅 refs 已清空、来源切片不参与聚合）"
        )
        assertNotNull(manager.getBookmark("SB"), "来源切片单点查找应可见")
        assertTrue(manager.isForeignBookmark("SB"), "来源切片收藏应判定为外来")
        assertFalse(manager.isForeignBookmark("F1"), "被来源模型替代后的旧订阅收藏不再判定为外来")
        // ////////////////  返回  end  /////////////////////////////////////////////////
    }

    @Test
    fun `source slice should disappear after unconfiguring`() {
        // ////////////////  准备数据：配置来源项目并确认可见  start  /////////////////////
        val sourceRoot = newSourceProject(
            "srcGone",
            listOf(Bookmark("SB", BookmarkType.LINE, "src/S.php", 1, mnemonic = 'B'))
        )
        store.setConfiguredSourceProjects(project, listOf(sourceRoot.absolutePath))
        assertNotNull(manager.getBookmark("SB"), "配置后来源切片应可单点查找")
        // ////////////////  准备数据  end  /////////////////////////////////////////////

        // ////////////////  核心逻辑：清空来源项目配置  start  //////////////////////////
        store.setConfiguredSourceProjects(project, emptyList())
        // ////////////////  核心逻辑  end  /////////////////////////////////////////////

        // ////////////////  返回：来源切片全面不可见  start  ////////////////////////////
        assertNull(manager.getBookmark("SB"), "取消配置后来源收藏应不可查")
        assertNull(manager.getBookmarkByMnemonic('B'), "取消配置后助记符不应回退到来源切片")
        assertFalse(manager.isForeignBookmark("SB"), "取消配置后来源收藏不应再判定为外来")
        // ////////////////  返回  end  /////////////////////////////////////////////////
    }

    @Test
    fun `unconfigured source project should not be visible at all`() {
        // ////////////////  准备数据：来源项目夹具存在但未配置  start  ///////////////////
        val sourceRoot = newSourceProject(
            "srcQuiet",
            listOf(Bookmark("SB", BookmarkType.LINE, "src/S.php", 1, mnemonic = 'B'))
        )
        assertNotNull(File(File(sourceRoot, ".idea"), "favorites.xml"), "前置条件：磁盘夹具已存在")
        // ////////////////  准备数据  end  /////////////////////////////////////////////

        // ////////////////  返回：未配置的来源项目不参与任何可见性  start  ///////////////
        assertNull(manager.getBookmark("SB"), "未配置的来源收藏不应可查")
        assertNull(manager.getBookmarkByMnemonic('B'), "未配置的来源助记符不应可跳转")
        assertFalse(manager.isForeignBookmark("SB"), "未配置的来源收藏不应判定为外来")
        // ////////////////  返回  end  /////////////////////////////////////////////////
    }
}
