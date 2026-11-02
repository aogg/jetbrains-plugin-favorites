package com.aogg.favorites.model

import com.aogg.favorites.helper.BookmarkStorageHelper
import com.aogg.favorites.helper.GlobalBookmarkStorageHelper
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
import org.mockito.Mockito

/**
 * FavoritesTransferService（跨作用域转移）单元测试（1.2.0 新增，1.7.0 起覆盖三作用域）
 *
 * 说明：
 * - Mockito mock Project 提供 basePath（/proj）做路径换算
 * - 三个作用域管理器均注入假持久化（空数据起步），脱离真实 IDE 环境；
 *   共享作用域使用真实 SharedBookmarkStore（注入假应用级存储）+ SharedRefStorageHelper（真实例），
 *   经 SharedStoreTestSupport 安装 mock Application 后构造，绕开 IDE 容器依赖
 * - 覆盖：PROJECT→GLOBAL 转绝对路径、GLOBAL→PROJECT 项目内转相对、项目外失败、
 *   助记符冲突降级匿名、字段保留、移动而非复制、同作用域退化为列表移动、目标列表缺失兜底；
 *   三作用域：PROJECT↔SHARED 相对/绝对换算、SHARED↔GLOBAL 绝对原样、
 *   外来共享转移为复制（源保留）、自己集合转移为移动（源删除）、助记符冲突降级、目标列表兜底
 */
class FavoritesTransferServiceTest {

    private lateinit var project: Project
    private lateinit var projectManager: BookmarkManager
    private lateinit var globalManager: GlobalBookmarkManager
    private lateinit var sharedManager: SharedBookmarkManager
    private lateinit var sharedStore: SharedBookmarkStore
    private lateinit var service: FavoritesTransferService

    /**
     * mock Project 的项目根路径（目录边界对齐测试会用到 /projX）
     */
    private val basePath = "/proj"

    @BeforeEach
    fun setUp() {
        // ////////////////  准备数据：mock 项目根 + 三作用域假持久化  start  //////////////
        project = Mockito.mock(Project::class.java)
        Mockito.`when`(project.basePath).thenReturn(basePath)
        Mockito.`when`(project.name).thenReturn("Proj")

        val projectStorage = Mockito.mock(BookmarkStorageHelper::class.java)
        Mockito.`when`(projectStorage.loadAll())
            .thenReturn(emptyList<Bookmark>() to emptyList<ListInfo>())
        Mockito.doNothing().`when`(projectStorage).saveAll(Mockito.anyList(), Mockito.anyList())
        projectManager = BookmarkManager(project)
        projectManager.setStorageHelperForTest(projectStorage)

        val globalStorage = Mockito.mock(GlobalBookmarkStorageHelper::class.java)
        Mockito.`when`(globalStorage.loadAll())
            .thenReturn(emptyList<Bookmark>() to emptyList<ListInfo>())
        Mockito.doNothing().`when`(globalStorage).saveAll(Mockito.anyList(), Mockito.anyList())
        globalManager = GlobalBookmarkManager()
        globalManager.setStorageHelperForTest(globalStorage)

        // 共享作用域：真实仓库 + 假应用级存储 + 真实例订阅引用存储（项目级服务解析直通真实例）
        val sharedRefHelper = SharedRefStorageHelper()
        sharedStore = SharedStoreTestSupport.newSharedStore(SharedBookmarkStorageHelper())
        sharedManager = SharedBookmarkManager(project)
        sharedManager.setStoreForTest(sharedStore)
        sharedManager.setRefsHelperForTest(sharedRefHelper)
        Mockito.`when`(project.getService(SharedRefStorageHelper::class.java)).thenReturn(sharedRefHelper)

        service = FavoritesTransferService(project, projectManager, globalManager, sharedManager)
        // ////////////////  准备数据  end  ////////////////////////////////////////////
    }

    @AfterEach
    fun tearDown() {
        projectManager.clearAll()
        globalManager.clearAll()
        sharedStore.dispose()
        SharedStoreTestSupport.uninstallMockApplication()
    }

    // ==================== PROJECT → GLOBAL ====================

    @Test
    fun `project to global should convert path to absolute and preserve fields`() {
        // 项目内相对路径收藏（带助记符/描述/行号）
        val source = Bookmark(
            id = "P1",
            type = BookmarkType.LINE,
            filePath = "src/A.php",
            lineNumber = 9,
            mnemonic = 'M',
            description = "重点行",
            createdTime = 12345L
        )
        assertTrue(projectManager.addBookmark(source))

        val result = service.transferBookmark("P1", FavoriteScope.PROJECT, FavoriteScope.GLOBAL, Bookmark.DEFAULT_LIST_NAME)

        // 转移成功：路径转绝对、字段保留、移动而非复制
        assertTrue(result.success, "转移应成功：${result.errorMessage}")
        val transferred = result.bookmark!!
        assertEquals("$basePath/src/A.php", transferred.filePath, "相对路径应转为绝对路径")
        assertEquals("P1", transferred.id, "id 应保留")
        assertEquals(12345L, transferred.createdTime, "createdTime 应保留")
        assertEquals("重点行", transferred.description, "description 应保留")
        assertEquals(BookmarkType.LINE, transferred.type, "type 应保留")
        assertEquals(9, transferred.lineNumber, "lineNumber 应保留")
        assertEquals('M', transferred.mnemonic, "无冲突时助记符应保留")

        // 移动而非复制：原作用域删除、目标作用域存在
        assertNull(projectManager.getBookmark("P1"), "原作用域应已删除")
        assertNotNull(globalManager.getBookmark("P1"), "目标作用域应存在")
        assertEquals(0, projectManager.getTotalCount())
        assertEquals(1, globalManager.getTotalCount())
    }

    @Test
    fun `project to global should downgrade mnemonic when occupied in global`() {
        // 两个作用域各自占用助记符 A（互相独立）
        assertTrue(projectManager.addBookmark(Bookmark("P1", BookmarkType.LINE, "src/A.php", 1, mnemonic = 'A')))
        assertTrue(globalManager.addBookmark(Bookmark("G1", BookmarkType.LINE, "/other/B.php", 2, mnemonic = 'A')))

        val result = service.transferBookmark("P1", FavoriteScope.PROJECT, FavoriteScope.GLOBAL, Bookmark.DEFAULT_LIST_NAME)

        // 目标作用域助记符被占用 → 降级为匿名收藏（与导入原生书签策略一致）
        assertTrue(result.success)
        val transferred = globalManager.getBookmark("P1")!!
        assertNull(transferred.mnemonic, "冲突助记符应降级为匿名")
        assertNull(globalManager.getBookmark("P1")!!.mnemonic)
        // 原占用者不受影响
        assertEquals("G1", globalManager.getBookmarkByMnemonic('A')!!.id, "原占用者应保留助记符")
    }

    @Test
    fun `project to global should fallback to default list when target list missing`() {
        // 项目与全局都有列表"工作"，但删除全局侧的，模拟目标列表缺失的防御场景
        assertTrue(projectManager.createList("工作"))
        assertTrue(projectManager.addBookmark(Bookmark("P1", BookmarkType.FILE, "src/A.php", -1, listName = "工作")))

        val result = service.transferBookmark("P1", FavoriteScope.PROJECT, FavoriteScope.GLOBAL, "工作")

        assertTrue(result.success)
        assertEquals(Bookmark.DEFAULT_LIST_NAME, globalManager.getBookmark("P1")!!.listName, "目标列表缺失应兜底并入默认列表")
    }

    // ==================== GLOBAL → PROJECT ====================

    @Test
    fun `global to project should convert to relative when under base path`() {
        assertTrue(globalManager.addBookmark(Bookmark("G1", BookmarkType.FILE, "$basePath/src/A.php", -1)))

        val result = service.transferBookmark("G1", FavoriteScope.GLOBAL, FavoriteScope.PROJECT, Bookmark.DEFAULT_LIST_NAME)

        assertTrue(result.success, "项目内路径应转移成功：${result.errorMessage}")
        assertEquals("src/A.php", projectManager.getBookmark("G1")!!.filePath, "绝对路径应转为相对路径")
        assertNull(globalManager.getBookmark("G1"), "原作用域应已删除")
    }

    @Test
    fun `global to project should fail when path outside base path`() {
        // 项目外路径（注意 /projX 与 /proj 前缀相似但不是子目录，验证目录边界对齐）
        assertTrue(globalManager.addBookmark(Bookmark("G1", BookmarkType.FILE, "/projX/src/A.php", -1)))

        val result = service.transferBookmark("G1", FavoriteScope.GLOBAL, FavoriteScope.PROJECT, Bookmark.DEFAULT_LIST_NAME)

        // 失败：返回错误信息且收藏保留在全局作用域（不丢数据）
        assertFalse(result.success, "项目外路径转移应失败")
        assertNotNull(result.errorMessage, "失败应带用户可读错误信息")
        assertNotNull(globalManager.getBookmark("G1"), "失败时原收藏应保留在全局作用域")
        assertNull(projectManager.getBookmark("G1"), "失败时不应写入项目作用域")
    }

    @Test
    fun `global to project should fail when project base path is null`() {
        // 重新 mock：项目无 basePath（如默认项目）
        val nullBaseProject = Mockito.mock(Project::class.java)
        Mockito.`when`(nullBaseProject.basePath).thenReturn(null)
        val nullBaseService = FavoritesTransferService(nullBaseProject, projectManager, globalManager, sharedManager)

        assertTrue(globalManager.addBookmark(Bookmark("G1", BookmarkType.FILE, "$basePath/src/A.php", -1)))

        val result = nullBaseService.transferBookmark("G1", FavoriteScope.GLOBAL, FavoriteScope.PROJECT, Bookmark.DEFAULT_LIST_NAME)
        assertFalse(result.success, "无项目根时应失败")
        assertNotNull(globalManager.getBookmark("G1"), "失败时收藏应保留")
    }

    // ==================== PROJECT → SHARED ====================

    @Test
    fun `project to shared should convert path to absolute and move into own collection`() {
        // 项目内相对路径收藏：转移到共享作用域应转为绝对路径
        assertTrue(projectManager.addBookmark(Bookmark("P1", BookmarkType.LINE, "src/A.php", 9)))

        val result = service.transferBookmark("P1", FavoriteScope.PROJECT, FavoriteScope.SHARED, Bookmark.DEFAULT_LIST_NAME)

        // 转移成功：相对→绝对、移动而非复制（落自己集合）
        assertTrue(result.success, "转移应成功：${result.errorMessage}")
        assertEquals("$basePath/src/A.php", result.bookmark!!.filePath, "相对路径应转为绝对路径")
        assertNull(projectManager.getBookmark("P1"), "项目作用域应已删除")
        assertEquals("P1", sharedManager.getBookmark("P1")!!.id, "共享作用域应存在（自己集合）")
        assertEquals("$basePath/src/A.php", sharedManager.getBookmark("P1")!!.filePath, "共享作用域应保存绝对路径")
    }

    @Test
    fun `project to shared should downgrade mnemonic when occupied in shared own collection`() {
        // 自己共享集合占用 A；项目收藏也用 A → 目标冲突降级匿名
        assertTrue(sharedManager.addBookmark(Bookmark("S0", BookmarkType.LINE, "$basePath/src/B.php", 1, mnemonic = 'A')))
        assertTrue(projectManager.addBookmark(Bookmark("P1", BookmarkType.LINE, "src/A.php", 2, mnemonic = 'A')))

        val result = service.transferBookmark("P1", FavoriteScope.PROJECT, FavoriteScope.SHARED, Bookmark.DEFAULT_LIST_NAME)

        assertTrue(result.success, "转移应成功：${result.errorMessage}")
        assertNull(sharedManager.getBookmark("P1")!!.mnemonic, "冲突助记符应降级为匿名")
        assertEquals("S0", sharedManager.getBookmarkByMnemonic('A')!!.id, "原占用者应保留助记符")
    }

    @Test
    fun `project to shared should fallback to default list when target list missing`() {
        // 目标列表"工作"只在项目作用域存在，共享作用域缺失 → 兜底并入默认列表
        assertTrue(projectManager.createList("工作"))
        assertTrue(projectManager.addBookmark(Bookmark("P1", BookmarkType.FILE, "src/A.php", -1, listName = "工作")))

        val result = service.transferBookmark("P1", FavoriteScope.PROJECT, FavoriteScope.SHARED, "工作")

        assertTrue(result.success)
        assertEquals(Bookmark.DEFAULT_LIST_NAME, sharedManager.getBookmark("P1")!!.listName, "共享作用域缺失目标列表应兜底并入默认列表")
    }

    // ==================== SHARED → PROJECT ====================

    @Test
    fun `shared to project should convert in-project absolute path to relative`() {
        // 自己集合内的收藏（绝对路径在项目内）：转移到项目应转为相对路径，且为移动语义
        assertTrue(sharedManager.addBookmark(Bookmark("S1", BookmarkType.LINE, "$basePath/src/A.php", 3)))

        val result = service.transferBookmark("S1", FavoriteScope.SHARED, FavoriteScope.PROJECT, Bookmark.DEFAULT_LIST_NAME)

        assertTrue(result.success, "项目内路径应转移成功：${result.errorMessage}")
        assertEquals("src/A.php", projectManager.getBookmark("S1")!!.filePath, "项目内绝对路径应转为相对路径")
        assertNull(sharedManager.getBookmark("S1"), "自己集合转移应删除源（移动语义）")
    }

    @Test
    fun `shared to project should fail when path outside project`() {
        // 项目外路径：转移到项目收藏失败（项目收藏只支持项目内路径）
        assertTrue(sharedManager.addBookmark(Bookmark("S2", BookmarkType.FILE, "/other/B.php", -1)))

        val result = service.transferBookmark("S2", FavoriteScope.SHARED, FavoriteScope.PROJECT, Bookmark.DEFAULT_LIST_NAME)

        assertFalse(result.success, "项目外路径转移应失败")
        assertNotNull(result.errorMessage, "失败应带用户可读错误信息")
        assertNotNull(sharedManager.getBookmark("S2"), "失败时收藏应保留在共享作用域")
        assertNull(projectManager.getBookmark("S2"), "失败时不应写入项目作用域")
    }

    // ==================== SHARED → GLOBAL ====================

    @Test
    fun `shared own collection to global should keep absolute path and move`() {
        // 自己集合 → 全局：绝对路径原样（统一正斜杠），移动而非复制（源删除）
        assertTrue(sharedManager.addBookmark(Bookmark("S1", BookmarkType.LINE, "$basePath/src/A.php", 3)))

        val result = service.transferBookmark("S1", FavoriteScope.SHARED, FavoriteScope.GLOBAL, Bookmark.DEFAULT_LIST_NAME)

        assertTrue(result.success, "转移应成功：${result.errorMessage}")
        assertEquals("$basePath/src/A.php", globalManager.getBookmark("S1")!!.filePath, "绝对路径应原样保留")
        assertNull(sharedManager.getBookmark("S1"), "自己集合的转移应删除源（移动语义）")
        assertNotNull(globalManager.getBookmark("S1"), "目标作用域应存在")
    }

    // ==================== GLOBAL → SHARED ====================

    @Test
    fun `global to shared should keep absolute path`() {
        // 全局收藏（绝对路径）：转移到共享作用域路径原样，写入自己集合
        assertTrue(globalManager.addBookmark(Bookmark("G1", BookmarkType.FILE, "/other/C.php", -1)))

        val result = service.transferBookmark("G1", FavoriteScope.GLOBAL, FavoriteScope.SHARED, Bookmark.DEFAULT_LIST_NAME)

        assertTrue(result.success, "转移应成功：${result.errorMessage}")
        assertEquals("/other/C.php", sharedManager.getBookmark("G1")!!.filePath, "绝对路径应原样保留")
        assertNull(globalManager.getBookmark("G1"), "全局作用域应已删除")
    }

    // ==================== SHARED（外来）→ PROJECT：复制语义 ====================

    @Test
    fun `shared foreign bookmark to project should copy and keep source`() {
        // 外来集合（来源项目 /projB）有一条项目内绝对路径收藏；本项目订阅后可见
        // （外来集合创建只读 basePath/name，不触发项目级服务解析，订阅引用走已 stub 的主 project）
        val foreignProject = SharedStoreTestSupport.mockProject("/projB", "ProjB", SharedRefStorageHelper())
        val foreignCollection = sharedStore.collectionOf(foreignProject)
        foreignCollection.core.addBookmark(Bookmark("F1", BookmarkType.LINE, "$basePath/src/A.php", 5, mnemonic = 'F'))
        sharedStore.subscribe(project, foreignCollection.id)

        val result = service.transferBookmark("F1", FavoriteScope.SHARED, FavoriteScope.PROJECT, Bookmark.DEFAULT_LIST_NAME)

        // 复制语义：目标新增（路径转相对），外来集合的源保留不删
        assertTrue(result.success, "转移应成功：${result.errorMessage}")
        assertEquals("src/A.php", projectManager.getBookmark("F1")!!.filePath, "项目内绝对路径应转为相对路径")
        assertNotNull(foreignCollection.core.getBookmark("F1"), "外来集合的源应保留（复制而非移动）")
        assertEquals('F', foreignCollection.core.getBookmark("F1")!!.mnemonic, "外来集合源数据不应被改动")
    }

    // ==================== 通用语义 ====================

    @Test
    fun `transfer should respect target list in target scope`() {
        // 目标作用域已有列表"重要"：转移后进入该列表
        assertTrue(globalManager.createList("重要"))
        assertTrue(projectManager.addBookmark(Bookmark("P1", BookmarkType.FOLDER, "src/folder", -1)))

        val result = service.transferBookmark("P1", FavoriteScope.PROJECT, FavoriteScope.GLOBAL, "重要")

        assertTrue(result.success)
        assertEquals("重要", globalManager.getBookmark("P1")!!.listName, "应进入目标列表")
    }

    @Test
    fun `same scope transfer should degrade to move to list`() {
        assertTrue(projectManager.createList("重要"))
        assertTrue(projectManager.addBookmark(Bookmark("P1", BookmarkType.FILE, "src/A.php", -1)))

        val result = service.transferBookmark("P1", FavoriteScope.PROJECT, FavoriteScope.PROJECT, "重要")

        // 同作用域：退化为列表移动（不删不建，id 不变）
        assertTrue(result.success)
        assertEquals("重要", projectManager.getBookmark("P1")!!.listName)
        assertEquals(1, projectManager.getTotalCount(), "同作用域移动不改变总数")
    }

    @Test
    fun `transfer non-existent bookmark should fail`() {
        val result = service.transferBookmark("不存在", FavoriteScope.PROJECT, FavoriteScope.GLOBAL, Bookmark.DEFAULT_LIST_NAME)
        assertFalse(result.success, "收藏不存在应失败")
        assertNotNull(result.errorMessage)
    }

    @Test
    fun `mnemonic stays unique per scope after transfer`() {
        // 项目内 A 与全局空：转移后全局占用 A；此后全局内不能再添加 A，但项目内可以
        assertTrue(projectManager.addBookmark(Bookmark("P1", BookmarkType.LINE, "src/A.php", 1, mnemonic = 'A')))

        val result = service.transferBookmark("P1", FavoriteScope.PROJECT, FavoriteScope.GLOBAL, Bookmark.DEFAULT_LIST_NAME)
        assertTrue(result.success)
        assertEquals('A', globalManager.getBookmark("P1")!!.mnemonic, "转移后助记符保留在全局作用域")

        // 项目作用域释放 A，可再次使用
        assertTrue(projectManager.isMnemonicAvailable('A'), "项目作用域应释放助记符")
        assertFalse(globalManager.isMnemonicAvailable('A'), "全局作用域应占用助记符")
    }
}
