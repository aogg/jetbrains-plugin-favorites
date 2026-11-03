package com.aogg.favorites.model

import com.aogg.favorites.helper.BookmarkPersistence
import com.aogg.favorites.helper.BookmarkStorageHelper
import com.intellij.openapi.components.Service
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import org.jdom.Element
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 来源项目收藏仓库（应用级，1.7.1 新增，配置来源项目模型）
 *
 * 为「共享收藏 → 共享自：来源项目」节点提供数据：每个已配置的来源项目一个只读数据切片，
 * 内容 = 该来源项目的「项目收藏」（PROJECT 作用域）：
 * - 来源项目已在本 IDE 打开 → 每次构建切片都实时读其 [BookmarkManager]（实时数据，不走缓存）；
 * - 未打开 → 从磁盘解析其项目根下 .idea/favorites.xml（与 [BookmarkStorageHelper] 的 State 结构一致，
 *   [XmlSerializer] 反序列化，state name=FavoritesBookmarks），按文件 mtime 缓存，
 *   文件变更后（手动刷新树）自动重新解析；
 * - 来源项目收藏 filePath 为相对「来源项目根」的路径，装入切片内核前统一换算为绝对路径
 *   （与 SHARED 作用域绝对路径语义一致，树渲染/跳转/骨架复用既有链路，无需新写解析逻辑）；
 * - 单向只读：切片内核的持久化适配器 saveAll 为显式 no-op，绝不写来源项目的任何文件；
 *   被共享项目不需要任何开启/配置动作。
 *
 * 线程安全：缓存用 ConcurrentHashMap；切片内核懒加载各自独立（同 SharedBookmarkStore.SharedCollection）。
 * 解析时机：树重建/对话框统计时在调用线程读取（收藏文件小，同步解析可接受，不额外引入后台时序）。
 */
@Service(Service.Level.APP)
class SourceProjectStore {

    companion object {
        private val log = Logger.getInstance(SourceProjectStore::class.java)

        /**
         * 获取应用级实例
         */
        @JvmStatic
        fun getInstance(): SourceProjectStore {
            return ApplicationManager.getApplication().getService(SourceProjectStore::class.java)
        }

        /**
         * 来源项目切片 id 生成规则："source-" 前缀 + 来源项目路径哈希（[SharedBookmarkStore.collectionIdFor]）。
         * 前缀保证与共享仓库 favorites.shared.xml 的集合 id 空间隔离（不会与「我的共享」等集合撞 id）
         *
         * @param sourceRootPath 来源项目根路径（原始风格即可，内部统一规范化）
         */
        @JvmStatic
        fun sourceCollectionId(sourceRootPath: String): String =
            "source-" + SharedBookmarkStore.collectionIdFor(sourceRootPath)
    }

    /**
     * 未打开来源项目的解析缓存条目
     *
     * @param mtime 解析时 .idea/favorites.xml 的 mtime（-1=文件不存在）
     * @param collection 按该时刻数据构建的只读切片（内核懒加载持有解析结果）
     */
    private class CachedSource(val mtime: Long, val collection: SharedBookmarkStore.SharedCollection)

    // 未打开来源项目的切片缓存（key=规范化来源项目根路径；已打开项目不缓存，保证实时）
    private val cache = ConcurrentHashMap<String, CachedSource>()

    /**
     * 取一批来源项目的只读切片（1.7.1 树渲染与共享管理器判定共用）
     *
     * @param sourceRootPaths 已配置的来源项目根路径列表（原始风格即可）
     * @return 逐来源项目的切片（顺序与入参一致；单项目解析失败不阻断其余项目）
     */
    fun collectionsFor(sourceRootPaths: List<String>): List<SharedBookmarkStore.SharedCollection> {
        // ////////////////  核心逻辑：逐来源项目构建只读切片  start  ///////////////////////
        val projectManager = ProjectManager.getInstanceIfCreated()
        return sourceRootPaths.map { path ->
            val normalized = SharedBookmarkStore.normalizeProjectPath(path)
            collectionFor(normalized, projectManager)
        }
        // ////////////////  核心逻辑  end  ///////////////////////////////////////////////
    }

    /**
     * 统计来源项目收藏条数（配置对话框「项目收藏条数」列用）
     *
     * @param sourceRootPath 来源项目根路径
     * @return 收藏条数；来源项目已打开=其实时总数，未打开=解析其 .idea/favorites.xml 统计，
     *         文件缺失/解析失败返回 null（对话框显示 "-"）
     */
    fun countBookmarks(sourceRootPath: String): Int? {
        // ////////////////  准备数据：来源项目是否已打开  start  //////////////////////////
        val normalized = SharedBookmarkStore.normalizeProjectPath(sourceRootPath)
        val openProject = findOpenProject(normalized)
        // ////////////////  准备数据  end  /////////////////////////////////////////////

        // ////////////////  分支一：已打开 → 实时总数  start  /////////////////////////////
        if (openProject != null) {
            return BookmarkManager.getInstance(openProject).getTotalCount()
        }
        // ////////////////  分支一  end  ///////////////////////////////////////////////

        // ////////////////  分支二：未打开 → 磁盘解析统计（失败返回 null）  start  //////////
        val file = favoritesFile(normalized)
        if (file == null) return null
        val data = readFromDisk(file) ?: return null
        return data.first.size
        // ////////////////  分支二  end  ///////////////////////////////////////////////
    }

    /**
     * 构建单个来源项目的只读切片：
     * 已打开 → 每次新建内核并实时读其 BookmarkManager；未打开 → 按 mtime 命中缓存，
     * 未命中/文件变更时重新解析磁盘文件（内核懒加载持有解析结果，重复访问不重复读盘）
     */
    private fun collectionFor(
        normalizedPath: String,
        projectManager: ProjectManager?
    ): SharedBookmarkStore.SharedCollection {
        // ////////////////  分支一：来源项目已打开 → 实时读取  start  /////////////////////
        val openProject = projectManager?.openProjects?.firstOrNull {
            !it.isDisposed && SharedBookmarkStore.normalizeProjectPath(it.basePath ?: "") == normalizedPath
        }
        if (openProject != null) {
            // 实时数据：每次构建新内核（懒加载闭包内读打开项目的管理器），不走缓存
            return buildCollection(normalizedPath, openProject.name, 0L) {
                readFromOpenProject(openProject)
            }
        }
        // ////////////////  分支一  end  ///////////////////////////////////////////////

        // ////////////////  分支二：未打开 → 磁盘解析 + mtime 缓存  start  ////////////////
        val file = favoritesFile(normalizedPath)
        val mtime = if (file != null && file.isFile) file.lastModified() else -1L
        val cached = cache[normalizedPath]
        if (cached != null && cached.mtime == mtime) {
            return cached.collection
        }
        val collection = buildCollection(
            normalizedPath,
            normalizedPath.substringAfterLast('/').ifBlank { normalizedPath },
            mtime
        ) {
            if (file == null) Pair(emptyList(), emptyList()) else readFromDisk(file) ?: Pair(emptyList(), emptyList())
        }
        cache[normalizedPath] = CachedSource(mtime, collection)
        return collection
        // ////////////////  分支二  end  ///////////////////////////////////////////////
    }

    /**
     * 构建只读切片集合（owner 信息 + 懒加载内核；内核持久化适配器为只读镜像）
     */
    private fun buildCollection(
        sourceRootPath: String,
        ownerProjectName: String,
        updatedTime: Long,
        dataProvider: () -> Pair<List<Bookmark>, List<ListInfo>>
    ): SharedBookmarkStore.SharedCollection {
        return SharedBookmarkStore.SharedCollection(
            id = sourceCollectionId(sourceRootPath),
            ownerProjectName = ownerProjectName,
            ownerProjectPath = sourceRootPath,
            updatedTime = updatedTime,
            core = BookmarkStoreCore {
                readOnlyPersistence(dataProvider(), sourceRootPath)
            }
        )
    }

    /**
     * 只读持久化适配器：loadAll 返回快照（相对路径已换算为来源项目根下的绝对路径），
     * saveAll 显式 no-op——来源项目收藏是单向只读镜像，任何写入口都不得回写来源项目
     */
    private fun readOnlyPersistence(
        data: Pair<List<Bookmark>, List<ListInfo>>,
        sourceRootPath: String
    ): BookmarkPersistence = object : BookmarkPersistence {

        /**
         * 读取来源项目的全部收藏与分组元信息（相对路径已在快照准备阶段换算为绝对路径）
         */
        override fun loadAll(): Pair<List<Bookmark>, List<ListInfo>> {
            return convertToAbsolute(data, sourceRootPath)
        }

        /**
         * 只读镜像：禁止回写（外来共享只读约束的存储层兜底）
         */
        override fun saveAll(bookmarks: List<Bookmark>, lists: List<ListInfo>) {
            // 单向只读约束：绝不写来源项目的任何文件（1.7.1 配置来源项目模型）
            log.warn("来源项目共享切片禁止写入（来源项目=$sourceRootPath），已忽略写请求")
        }
    }

    /**
     * 快照准备：相对路径换算为来源项目根下的绝对路径并统一正斜杠
     * （SHARED 作用域为绝对路径语义，换算后可直接复用既有树渲染/跳转链路）
     */
    private fun convertToAbsolute(
        data: Pair<List<Bookmark>, List<ListInfo>>,
        sourceRootPath: String
    ): Pair<List<Bookmark>, List<ListInfo>> {
        // ////////////////  核心逻辑：收藏路径相对→绝对（绝对路径原样归一）  start  //////////
        val bookmarks = data.first.map { bookmark ->
            bookmark.copy(filePath = toAbsoluteForSource(bookmark.filePath, sourceRootPath))
        }
        // ////////////////  核心逻辑  end  /////////////////////////////////////////////

        // ////////////////  返回：分组元信息原样透传（分组无路径概念）  start  //////////////
        return Pair(bookmarks, data.second)
        // ////////////////  返回  end  /////////////////////////////////////////////////
    }

    /**
     * 单条路径换算：相对路径拼来源项目根；已是绝对路径仅统一反斜杠→正斜杠
     */
    private fun toAbsoluteForSource(path: String, sourceRootPath: String): String {
        // ////////////////  校验参数：空路径原样返回  start  ///////////////////////////////
        if (path.isBlank()) return path
        // ////////////////  校验参数  end  /////////////////////////////////////////////

        // ////////////////  返回：绝对路径归一 / 相对路径拼来源根  start  //////////////////
        return if (File(path).isAbsolute) {
            path.replace('\\', '/')
        } else {
            File(sourceRootPath, path).absolutePath.replace('\\', '/')
        }
        // ////////////////  返回  end  /////////////////////////////////////////////////
    }

    /**
     * 实时读取已打开来源项目的项目收藏（BookmarkManager 即 PROJECT 作用域门面）
     */
    private fun readFromOpenProject(openProject: Project): Pair<List<Bookmark>, List<ListInfo>> {
        val manager = BookmarkManager.getInstance(openProject)
        return Pair(manager.getAllBookmarks(), manager.getLists())
    }

    /**
     * 从磁盘解析来源项目的 .idea/favorites.xml：
     * 平台 State 持久化格式为 <project><component name="FavoritesBookmarks">…字段…</component></project>，
     * 取出组件元素后用 [XmlSerializer] 反序列化为 [BookmarkStorageHelper.State]，
     * 再复用 [BookmarkStorageHelper.readFromState] 读成收藏+分组（路径仍为相对来源项目根）
     *
     * @return 解析结果；文件缺失/解析失败返回 null（调用方按空数据处理/显示 "-"）
     */
    private fun readFromDisk(file: File): Pair<List<Bookmark>, List<ListInfo>>? {
        // ////////////////  校验参数：文件不存在按空数据处理  start  //////////////////////
        if (!file.isFile) return null
        // ////////////////  校验参数  end  /////////////////////////////////////////////

        // ////////////////  核心逻辑：定位组件元素并反序列化 State  start  ////////////////
        return try {
            // 2023.3 的 JDOMUtil.load(File) 返回根 Element，此处需 Document，故用 loadDocument
            val document = JDOMUtil.loadDocument(file)
            val component: Element = document.rootElement.getChildren("component")
                .firstOrNull { it.getAttributeValue("name") == "FavoritesBookmarks" } ?: return null
            val state = XmlSerializer.deserialize(component, BookmarkStorageHelper.State::class.java)
            BookmarkStorageHelper.readFromState(state)
        } catch (e: Exception) {
            // 解析失败按空数据处理并记录日志（旧版本文件/损坏文件不影响树其余部分）
            log.warn("解析来源项目收藏文件失败：${file.absolutePath}", e)
            null
        }
        // ////////////////  核心逻辑  end  /////////////////////////////////////////////
    }

    /**
     * 来源项目根下的收藏存储文件（<来源项目根>/.idea/favorites.xml）
     */
    private fun favoritesFile(sourceRootPath: String): File? {
        // ////////////////  校验参数：空路径无法定位文件  start  ///////////////////////////
        if (sourceRootPath.isBlank()) return null
        // ////////////////  校验参数  end  /////////////////////////////////////////////

        // ////////////////  返回  start  //////////////////////////////////////////////
        return File(sourceRootPath, ".idea/favorites.xml")
        // ////////////////  返回  end  ////////////////////////////////////////////////
    }

    /**
     * 按规范化根路径查找本 IDE 已打开的来源项目（多开同路径项目取首个未关闭的）
     */
    private fun findOpenProject(normalizedPath: String): Project? {
        val projectManager = ProjectManager.getInstanceIfCreated() ?: return null
        return projectManager.openProjects.firstOrNull {
            !it.isDisposed && SharedBookmarkStore.normalizeProjectPath(it.basePath ?: "") == normalizedPath
        }
    }
}
