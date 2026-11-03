package com.aogg.favorites.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * SourceProjectStore 磁盘解析链路单元测试（1.7.1 配置来源项目模型）
 *
 * 说明：SourceProjectStore 是应用级服务，单元测试不起 IDE 容器，直接 new 实例走
 * 公开的 [SourceProjectStore.collectionsFor] / [SourceProjectStore.countBookmarks]
 * （两者均完整经过私有的「JDOM load → 定位 FavoritesBookmarks 组件 → XmlSerializer 反序列化
 * State → readFromState → 相对路径换算绝对路径」解析链，无需开放产品可见性）：
 * - 未安装 mock Application 时 ProjectManager.getInstanceIfCreated() 返回 null，
 *   自然落入「未打开 → 磁盘解析」分支，磁盘夹具由 SharedStoreTestSupport.writeFavoritesXml
 *   按平台持久化格式生成，保证夹具真实可信；
 * - 覆盖：列表/收藏/mnemonicBookmarks 写盘→解析往返、相对路径→来源根下绝对路径换算、
 *   countBookmarks 统计、文件缺失/损坏/无目标组件的安全回退（不抛异常）、
 *   切片写入绝不回写来源项目磁盘文件（saveAll no-op）。
 * - 「来源项目已打开→实时读」分支依赖真实 ProjectManager，属 IDE 集成行为，不在单测范围。
 */
class SourceProjectStoreParseTest {

    /** 每个测试独立的临时目录（JUnit5 实例级 @TempDir） */
    @TempDir
    lateinit var tempDir: File

    /**
     * 在临时目录下造一个来源项目根目录
     */
    private fun newSourceRoot(name: String): File = File(tempDir, name).apply { mkdirs() }

    // ==================== 往返与路径换算 ====================

    @Test
    fun `favorites xml should roundtrip lists bookmarks and mnemonics through disk parse`() {
        // ////////////////  准备数据：来源项目磁盘夹具（分组/行收藏/文件收藏/助记符）  start  //
        val root = newSourceRoot("sourceA")
        SharedStoreTestSupport.writeFavoritesXml(
            root,
            listOf(
                Bookmark("SA", BookmarkType.LINE, "src/Rel.php", 5, mnemonic = 'A', description = "来源行收藏", listName = "工作"),
                Bookmark("SA2", BookmarkType.FILE, "lib/Rel2.php", listName = "工作")
            ),
            listOf(ListInfo("工作", SortType.LOCATION))
        )
        // ////////////////  准备数据  end  /////////////////////////////////////////////

        // ////////////////  核心逻辑：磁盘解析构建只读切片  start  ///////////////////////
        val collection = SourceProjectStore().collectionsFor(listOf(root.absolutePath)).single()
        // ////////////////  核心逻辑  end  /////////////////////////////////////////////

        // ////////////////  返回：集合元信息/收藏/分组/助记符往返一致  start  ////////////
        assertEquals(SourceProjectStore.sourceCollectionId(root.absolutePath), collection.id, "切片 id 应按来源根路径生成")
        assertEquals("sourceA", collection.ownerProjectName, "owner 名应取来源根目录名")
        assertEquals(root.absolutePath, collection.ownerProjectPath, "owner 路径应为规范化的来源根路径")

        val all = collection.core.getAllBookmarks()
        assertEquals(setOf("SA", "SA2"), all.map { it.id }.toSet(), "匿名与助记符收藏都应解析回来")

        val sa = collection.core.getBookmark("SA")!!
        assertEquals('A', sa.mnemonic, "助记符应往返一致")
        assertEquals("来源行收藏", sa.description, "描述应往返一致")
        assertEquals(5, sa.lineNumber, "行号应往返一致")
        assertEquals("工作", sa.listName, "分组归属应往返一致")
        assertEquals(
            File(root, "src/Rel.php").absolutePath.replace('\\', '/'),
            sa.filePath,
            "相对路径应换算为来源项目根下绝对路径"
        )
        assertEquals(
            File(root, "lib/Rel2.php").absolutePath.replace('\\', '/'),
            collection.core.getBookmark("SA2")!!.filePath,
            "文件收藏相对路径同样应换算为绝对路径"
        )

        assertTrue(collection.core.hasList("工作"), "分组元信息应解析回来")
        assertEquals(SortType.LOCATION, collection.core.getSortType("工作"), "分组排序方式应往返一致")
        assertEquals(
            setOf("SA", "SA2"),
            collection.core.getBookmarksByList("工作").map { it.id }.toSet(),
            "组内收藏应通过解析链读回"
        )
        assertEquals("SA", collection.core.getBookmarkByMnemonic('A')!!.id, "助记符单点查找应命中切片")
        assertEquals(2, collection.totalCount(), "切片总数应为解析出的收藏条数")
        // ////////////////  返回  end  /////////////////////////////////////////////////
    }

    @Test
    fun `collectionsFor should normalize source root path style`() {
        // ////////////////  准备数据：来源项目夹具  start  ///////////////////////////////
        val root = newSourceRoot("sourceNorm")
        SharedStoreTestSupport.writeFavoritesXml(
            root,
            listOf(Bookmark("SN", BookmarkType.LINE, "src/N.php", 1, mnemonic = 'N'))
        )
        // ////////////////  准备数据  end  /////////////////////////////////////////////

        // ////////////////  返回：带尾斜杠的路径规范化后指向同一来源切片  start  ///////////
        val straight = SourceProjectStore().collectionsFor(listOf(root.absolutePath)).single()
        val trailing = SourceProjectStore().collectionsFor(listOf("${root.absolutePath}/")).single()
        assertEquals(straight.id, trailing.id, "路径风格差异不应影响切片 id")
        assertEquals("SN", trailing.core.getBookmark("SN")!!.id, "规范化后应解析同一来源项目")
        // ////////////////  返回  end  /////////////////////////////////////////////////
    }

    // ==================== countBookmarks ====================

    @Test
    fun `countBookmarks should count parsed disk bookmarks`() {
        // ////////////////  准备数据：两条收藏的来源项目  start  /////////////////////////
        val root = newSourceRoot("sourceCount")
        SharedStoreTestSupport.writeFavoritesXml(
            root,
            listOf(
                Bookmark("SC1", BookmarkType.LINE, "src/A.php", 1),
                Bookmark("SC2", BookmarkType.LINE, "src/B.php", 2, mnemonic = 'B')
            )
        )
        // ////////////////  准备数据  end  /////////////////////////////////////////////

        // ////////////////  返回：统计=解析链读出的收藏条数，路径风格不敏感  start  ////////
        assertEquals(2, SourceProjectStore().countBookmarks(root.absolutePath), "统计应为磁盘收藏条数")
        assertEquals(2, SourceProjectStore().countBookmarks("${root.absolutePath}/"), "尾斜杠规范化后统计应一致")
        // ////////////////  返回  end  /////////////////////////////////////////////////
    }

    // ==================== 安全回退（不抛异常） ====================

    @Test
    fun `missing favorites xml should degrade safely`() {
        // ////////////////  准备数据：无 .idea/favorites.xml 的来源项目根  start  /////////
        val root = newSourceRoot("emptySource")
        // ////////////////  准备数据  end  /////////////////////////////////////////////

        // ////////////////  返回：统计返回 null、切片为空，均不抛异常  start  ////////////
        assertNull(SourceProjectStore().countBookmarks(root.absolutePath), "文件缺失时统计应返回 null")
        val collection = SourceProjectStore().collectionsFor(listOf(root.absolutePath)).single()
        assertTrue(collection.core.getAllBookmarks().isEmpty(), "文件缺失时应得到空切片")
        assertEquals(0, collection.totalCount(), "空切片总数应为 0")
        // ////////////////  返回  end  /////////////////////////////////////////////////
    }

    @Test
    fun `corrupted favorites xml should degrade safely without throwing`() {
        // ////////////////  准备数据：截断损坏的 XML  start  /////////////////////////////
        val root = newSourceRoot("brokenSource")
        val ideaDir = File(root, ".idea")
        ideaDir.mkdirs()
        File(ideaDir, "favorites.xml").writeText(
            "<project version=\"4\"><component name=\"FavoritesBookmarks\"><option name=\"mnemonicBook"
        )
        // ////////////////  准备数据  end  /////////////////////////////////////////////

        // ////////////////  返回：解析失败按空数据处理，统计返回 null  start  ////////////
        assertNull(SourceProjectStore().countBookmarks(root.absolutePath), "损坏文件统计应返回 null 而非抛异常")
        val collection = SourceProjectStore().collectionsFor(listOf(root.absolutePath)).single()
        assertTrue(collection.core.getAllBookmarks().isEmpty(), "损坏文件应解析为空切片")
        // ////////////////  返回  end  /////////////////////////////////////////////////
    }

    @Test
    fun `xml without FavoritesBookmarks component should degrade safely`() {
        // ////////////////  准备数据：合法 XML 但没有目标组件（旧版本/异构项目）  start  //
        val root = newSourceRoot("otherComponentSource")
        val ideaDir = File(root, ".idea")
        ideaDir.mkdirs()
        File(ideaDir, "favorites.xml").writeText(
            "<project version=\"4\"><component name=\"Other\"><option name=\"x\" value=\"1\"/></component></project>"
        )
        // ////////////////  准备数据  end  /////////////////////////////////////////////

        // ////////////////  返回：无目标组件按无数据处理  start  ////////////////////////
        assertNull(SourceProjectStore().countBookmarks(root.absolutePath), "无目标组件时统计应返回 null")
        val collection = SourceProjectStore().collectionsFor(listOf(root.absolutePath)).single()
        assertTrue(collection.core.getAllBookmarks().isEmpty(), "无目标组件应解析为空切片")
        // ////////////////  返回  end  /////////////////////////////////////////////////
    }

    // ==================== 单向只读（saveAll no-op） ====================

    @Test
    fun `slice writes should never touch source project disk file`() {
        // ////////////////  准备数据：来源项目夹具并记录原始字节  start  ////////////////
        val root = newSourceRoot("readonlySource")
        SharedStoreTestSupport.writeFavoritesXml(
            root,
            listOf(Bookmark("SR", BookmarkType.LINE, "src/R.php", 1, mnemonic = 'R'))
        )
        val xmlFile = File(File(root, ".idea"), "favorites.xml")
        val before = xmlFile.readBytes()
        // ////////////////  准备数据  end  /////////////////////////////////////////////

        // ////////////////  核心逻辑：对切片内核发起写请求（saveAll 走 no-op 适配器）  //
        val collection = SourceProjectStore().collectionsFor(listOf(root.absolutePath)).single()
        assertTrue(collection.core.addBookmark(Bookmark("SNEW", BookmarkType.LINE, "src/New.php", 9)), "切片内存写应成功（持久化被丢弃）")
        // ////////////////  核心逻辑  end  /////////////////////////////////////////////

        // ////////////////  返回：磁盘文件字节不变、重解析读不到新收藏  start  ///////////
        assertTrue(
            before.contentEquals(xmlFile.readBytes()),
            "切片写请求不得改写来源项目磁盘文件（单向只读）"
        )
        val reparsed = SourceProjectStore().collectionsFor(listOf(root.absolutePath)).single()
        assertNull(reparsed.core.getBookmark("SNEW"), "绕过内存缓存重解析后不应读到切片内存中的新收藏")
        // ////////////////  返回  end  /////////////////////////////////////////////////
    }
}
