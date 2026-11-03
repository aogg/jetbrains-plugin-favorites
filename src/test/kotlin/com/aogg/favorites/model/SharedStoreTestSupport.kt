package com.aogg.favorites.model

import com.aogg.favorites.helper.BookmarkStorageHelper
import com.aogg.favorites.helper.SharedBookmarkStorageHelper
import com.aogg.favorites.helper.SharedRefStorageHelper
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializer
import org.jdom.Document
import org.jdom.Element
import org.jdom.output.Format
import org.jdom.output.XMLOutputter
import org.mockito.Mockito
import java.io.File
import java.io.FileOutputStream

/**
 * 共享收藏（SHARED 作用域）单元测试支撑工具
 *
 * 解决三个脱离 IDE 环境的问题：
 * - [SharedBookmarkStore] 的 init 块即触发 ensureLoaded（从应用级存储读全量），
 *   纯单元测试没有 Application 实例，会因 ApplicationManager.getApplication() 为 null 而 NPE；
 *   这里在构造前用反射把 ApplicationManager 的静态字段换成 mock Application（isDisposed=false、
 *   invokeLater 什么都不做），构造完成并注入测试假存储后即可脱离 IDE 运行；
 * - store 内部的项目级订阅引用走 SharedRefStorageHelper.getInstance(project) → project.getService，
 *   mock Project 需要统一 stub 该方法返回真实例（无状态的轻量对象，可直接 new）；
 * - 1.7.1 配置来源项目模型：SharedBookmarkManager.sourceCollections 经
 *   SourceProjectStore.getInstance()（应用级服务）取只读切片，
 *   mock Project 需再 stub SourceProjectStore 服务返回真实例（未注册时 Mockito 返回 null 会 NPE）；
 *   未打开来源项目走磁盘解析（ProjectManager.getInstanceIfCreated 对 mock Application 返回 null，
 *   自然落入磁盘分支），磁盘夹具由 [writeFavoritesXml] 按平台持久化格式生成。
 */
object SharedStoreTestSupport {

    /**
     * ApplicationManager 内部持有应用实例的静态字段名（2023.3 SDK 稳定存在）
     */
    private const val APP_FIELD_NAME = "ourApplication"

    /**
     * 是否已安装 mock Application（独立标志位：原始应用实例可能本身就是 null，不能以原值判空）
     */
    private var installed = false

    /**
     * 安装前保存的原应用实例（一般为 null，测试结束后恢复现场）
     */
    private var originalApp: Any? = null

    /**
     * 已安装的 mock Application（供按需 stub 应用级服务解析）
     */
    private var mockApp: com.intellij.openapi.application.Application? = null

    /**
     * 安装 mock Application（幂等）：让 SharedBookmarkStore 的构造与写后广播不再依赖真实 IDE 容器
     */
    fun installMockApplication() {
        if (installed) return
        // ////////////////  准备数据：反射定位静态字段并保存原值  start  //////////////////
        val field = ApplicationManager::class.java.getDeclaredField(APP_FIELD_NAME)
        field.isAccessible = true
        originalApp = field.get(null)
        // ////////////////  准备数据  end  /////////////////////////////////////////////

        // ////////////////  核心逻辑：替换为 mock Application  start  ////////////////////
        val app = Mockito.mock(com.intellij.openapi.application.Application::class.java)
        Mockito.`when`(app.isDisposed).thenReturn(false)
        field.set(null, app)
        mockApp = app
        installed = true
        // ////////////////  核心逻辑  end  /////////////////////////////////////////////
    }

    /**
     * 卸载 mock Application，恢复安装前的现场（未安装时无副作用）
     */
    fun uninstallMockApplication() {
        if (!installed) return
        val field = ApplicationManager::class.java.getDeclaredField(APP_FIELD_NAME)
        field.isAccessible = true
        field.set(null, originalApp)
        originalApp = null
        mockApp = null
        installed = false
    }

    /**
     * 构造注入假应用级存储的共享仓库：
     * - mock Application 的 getService(SharedBookmarkStorageHelper) 指向测试假存储，
     *   使构造期 init 的首次加载就读假存储（而不是 NPE 或误触真实服务）；
     * - 构造完成立即注入 setStorageHelperForTest（同一实例），后续读写全部落在假存储上。
     *
     * @param helper 测试用 SharedBookmarkStorageHelper（真实例，State 可直接检查落盘内容）
     */
    fun newSharedStore(helper: SharedBookmarkStorageHelper): SharedBookmarkStore {
        installMockApplication()
        val app = mockApp!!
        Mockito.`when`(app.getService(SharedBookmarkStorageHelper::class.java)).thenReturn(helper)
        return SharedBookmarkStore().also { it.setStorageHelperForTest(helper) }
    }

    /**
     * mock 项目：提供 basePath/name（集合 id 与 owner 信息来源），
     * 并 stub 项目级服务解析，使订阅引用读写落到传入的真实例
     */
    fun mockProject(basePath: String, name: String, refHelper: SharedRefStorageHelper): Project {
        val project = Mockito.mock(Project::class.java)
        Mockito.`when`(project.basePath).thenReturn(basePath)
        Mockito.`when`(project.name).thenReturn(name)
        Mockito.`when`(project.getService(SharedRefStorageHelper::class.java)).thenReturn(refHelper)
        return project
    }

    /**
     * 当前已安装的 mock Application（未安装时为 null）：
     * 供个别测试按需 stub 其它应用级服务（如 ProjectManager.getInstanceIfCreated 的解析链）
     */
    fun mockApplication(): com.intellij.openapi.application.Application? = mockApp

    /**
     * 注册测试用 [SourceProjectStore] 实例（1.7.1 配置来源项目模型）：
     * stub mock Application 的 SourceProjectStore 应用级服务解析指向真实例，
     * 使 SharedBookmarkManager.sourceCollections / SourceProjectStore.getInstance() 脱离 IDE 容器可用
     *
     * @param store 测试用 SourceProjectStore（真实例，内部缓存私有、实例间互不影响）
     */
    fun registerSourceProjectStore(store: SourceProjectStore) {
        installMockApplication()
        Mockito.`when`(mockApp!!.getService(SourceProjectStore::class.java)).thenReturn(store)
    }

    /**
     * 向来源项目根写入 .idea/favorites.xml 磁盘夹具（1.7.1 来源项目磁盘解析链路用）：
     * 平台项目级持久化格式为 <project version="4"><component name="FavoritesBookmarks">…</component></project>，
     * 组件内容由 [XmlSerializer.serialize] 从 [BookmarkStorageHelper.State] 生成——
     * 与 IDE 平台对 PersistentStateComponent 的实际写盘格式一致，保证夹具真实可信
     *
     * @param sourceRoot 来源项目根目录（不存在时自动创建 .idea）
     * @param state 要落盘的收藏状态（收藏 + 分组元信息）
     */
    fun writeFavoritesXml(sourceRoot: File, state: BookmarkStorageHelper.State) {
        // ////////////////  准备数据：State 序列化为 FavoritesBookmarks 组件元素  start  ///////
        val component = XmlSerializer.serialize(state)
        component.name = "component"
        component.setAttribute("name", "FavoritesBookmarks")
        // ////////////////  准备数据  end  /////////////////////////////////////////////

        // ////////////////  核心逻辑：包 project 根元素后写 .idea/favorites.xml  start  ///////
        val root = Element("project").setAttribute("version", "4")
        root.addContent(component)
        val ideaDir = File(sourceRoot, ".idea")
        ideaDir.mkdirs()
        FileOutputStream(File(ideaDir, "favorites.xml")).use {
            XMLOutputter(Format.getPrettyFormat()).output(Document(root), it)
        }
        // ////////////////  核心逻辑  end  /////////////////////////////////////////////
    }

    /**
     * 向来源项目根写入 .idea/favorites.xml（收藏模型便捷重载）：
     * 复用 [BookmarkStorageHelper.writeToState] 完成 Bookmark/ListInfo → State 的正式转换
     * （匿名/助记符分流、分组元信息），再委托 [writeFavoritesXml] 的 State 重载落盘
     *
     * @param sourceRoot 来源项目根目录（不存在时自动创建 .idea）
     * @param bookmarks 要落盘的收藏（相对/绝对路径均可，落盘原样）
     * @param lists 要落盘的分组元信息（缺省为空，加载侧内核会兜底默认列表）
     */
    fun writeFavoritesXml(
        sourceRoot: File,
        bookmarks: List<com.aogg.favorites.model.Bookmark>,
        lists: List<com.aogg.favorites.model.ListInfo> = emptyList()
    ) {
        // ////////////////  准备数据：收藏模型转为持久化 State  start  ///////////////////
        val state = BookmarkStorageHelper.State()
        BookmarkStorageHelper.writeToState(state, bookmarks, lists)
        // ////////////////  准备数据  end  /////////////////////////////////////////////

        // ////////////////  返回：委托 State 重载写盘  start  ///////////////////////////
        writeFavoritesXml(sourceRoot, state)
        // ////////////////  返回  end  /////////////////////////////////////////////////
    }
}
