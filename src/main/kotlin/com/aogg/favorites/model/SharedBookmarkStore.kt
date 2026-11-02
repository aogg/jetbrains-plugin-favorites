package com.aogg.favorites.model

import com.aogg.favorites.helper.BookmarkPersistence
import com.aogg.favorites.helper.BookmarkStorageHelper
import com.aogg.favorites.helper.SharedBookmarkStorageHelper
import com.aogg.favorites.helper.SharedRefStorageHelper
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 共享收藏变化监听器（应用级消息总线契约，1.7.0 新增）
 *
 * 共享仓库每次写入/重载后广播；项目级监听方（如收藏工具窗口所属项目）收到后刷新树与 gutter，
 * 实现「项目 a 改共享收藏、项目 b 立即可见」。
 */
interface SharedFavoritesListener {

    /**
     * 共享收藏数据发生变化（任意集合内容/集合增删）
     */
    fun onSharedChanged()
}

/**
 * 应用级共享收藏仓库（1.7.0 新增）
 *
 * 管理「共享收藏」作用的全部集合（key=collectionId，由来源项目路径哈希得到），
 * 每个集合内持有一个共享内核 [BookmarkStoreCore]（持久化提供器适配该集合的数据切片），
 * 完全复用内核能力（增删改查/多层级分组 CRUD/助记符唯一/排序记忆/级联删除）。
 *
 * 可见性模型（1.7.1 改版：配置来源项目）：
 * - 每个项目「自己的集合」（按 basePath 算 id）永远可见且可写（「我的共享」，仍存于 favorites.shared.xml）；
 * - 项目通过 [configuredSourceProjects] 配置若干来源项目根路径后，「共享收藏」根下逐来源项目
 *   只读展示其「项目收藏」（数据由 [SourceProjectStore] 读打开项目的 BookmarkManager 或解析其
 *   .idea/favorites.xml 得到，绝不写来源项目的任何文件，被共享项目无需任何开启动作）；
 * - 1.7.0 旧订阅模型（refs 存集合 id）不再写入，加载后首次读取配置时自动迁移为来源项目路径并清空 refs；
 * - 同一 IDE 进程内多项目窗口共享同一实例（内存即一致）；跨 IDE 进程由后台线程
 *   按 2 秒间隔 stat favorites.shared.xml 的 mtime 兜底重载并广播（重载在后台线程做，UI 刷新由监听方 invokeLater）。
 *
 * 线程安全：集合 map 用 ConcurrentHashMap + 全局写锁（写落盘串行化）；重载与写入互斥。
 */
@Service(Service.Level.APP)
class SharedBookmarkStore : Disposable {

    companion object {
        private val log = Logger.getInstance(SharedBookmarkStore::class.java)

        /**
         * 共享收藏变化的应用级消息总线 topic
         */
        @JvmStatic
        val SHARED_FAVORITES_TOPIC: Topic<SharedFavoritesListener> =
            Topic.create("SharedFavoritesTopic", SharedFavoritesListener::class.java)

        /**
         * 获取应用级实例
         */
        @JvmStatic
        fun getInstance(): SharedBookmarkStore {
            return ApplicationManager.getApplication().getService(SharedBookmarkStore::class.java)
        }

        /**
         * 项目路径规范化（1.7.1 新增）：反斜杠→正斜杠、去尾部斜杠。
         * 来源项目配置/对话框项目列表/自身排除比对统一使用，与 [collectionIdFor] 内部规则一致
         *
         * @param projectPath 项目根路径（原始风格即可）
         * @return 规范化路径
         */
        @JvmStatic
        fun normalizeProjectPath(projectPath: String): String {
            return projectPath.replace('\\', '/').trimEnd('/')
        }

        /**
         * 集合 id 生成规则：ownerProjectPath 规范化（反斜杠→正斜杠、去尾部斜杠）后
         * 取 SHA-256 hex 前 16 位（同一项目多窗口/重开项目得到同一集合）
         *
         * @param projectPath 项目根路径（原始风格即可，内部统一规范化）
         * @return 集合 id（16 位 hex 字符串）
         */
        @JvmStatic
        fun collectionIdFor(projectPath: String): String {
            // ////////////////  准备数据：路径规范化  start  //////////////////////////////////
            val normalized = normalizeProjectPath(projectPath)
            // ////////////////  准备数据  end  ///////////////////////////////////////////////

            // ////////////////  核心逻辑：SHA-256 hex 前 16 位  start  ///////////////////////
            val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }.take(16)
            // ////////////////  核心逻辑  end  ///////////////////////////////////////////////
        }
    }

    /**
     * 单个共享集合（来源项目维度）：数据切片在应用级存储中的载体 + 对应内核
     *
     * @param id 集合 id（[collectionIdFor] 生成）
     * @param ownerProjectName 来源项目显示名
     * @param ownerProjectPath 来源项目根路径（规范化正斜杠）
     * @param updatedTime 集合内容最近一次写入时间（管理对话框「更新时间」列展示用）
     * @param core 该集合的收藏内核（懒加载，持久化适配该集合数据切片）
     */
    class SharedCollection(
        val id: String,
        val ownerProjectName: String,
        val ownerProjectPath: String,
        val updatedTime: Long,
        val core: BookmarkStoreCore
    ) {
        /**
         * 集合收藏总数（管理对话框/树徽标展示用）
         */
        fun totalCount(): Int = core.getTotalCount()
    }

    // 测试注入的应用级存储助手（优先于真实存储，供单元测试脱离 IDE 环境使用）
    @Volatile
    private var testStorageHelper: SharedBookmarkStorageHelper? = null

    // 全部共享集合（key=集合 id）；重载时整体替换内容
    private val collections = ConcurrentHashMap<String, SharedCollection>()

    // 懒加载标记与锁（首次访问从存储读全量并逐集合构建内核）
    @Volatile
    private var loaded = false
    private val loadLock = Any()

    // 全局写锁：写落盘（切片写回）/重载 串行化
    private val writeLock = Any()

    // 跨 IDE 进程兜底：单线程守护调度器，2 秒间隔 stat favorites.shared.xml 的 mtime
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "FavoritesSharedBookmarks-Poller").apply { isDaemon = true }
    }

    // 最近一次 stat 到的存储文件 mtime（-1=文件不存在；变化即重载并广播）
    @Volatile
    private var lastMtime: Long

    init {
        // 初始化即加载（首窗口打开前 store 也可能被 gutter/跳转链触达）
        ensureLoaded()
        // 以当前文件 mtime 作为基线，避免启动时把「无变化」误判为变更
        lastMtime = readFileMtime()
        scheduler.scheduleWithFixedDelay({ pollStorageFile() }, POLL_INTERVAL_SECONDS, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS)
    }

    /**
     * 确保共享仓库已从存储加载（只加载一次；后续外部变更走轮询重载）
     */
    fun ensureLoaded() {
        if (loaded) return
        synchronized(loadLock) {
            if (loaded) return
            reloadAll()
            loaded = true
        }
    }

    // ==================== 集合查询 ====================

    /**
     * 取/自动创建当前项目自己的共享集合（id 按 basePath 计算，owner 信息取项目名与 basePath）
     *
     * @param project 当前项目
     * @return 自己的集合（永不返回 null；basePath 缺失时以空路径计算 id 兜底）
     */
    fun collectionOf(project: Project): SharedCollection {
        ensureLoaded()
        // ////////////////  准备数据：集合 id 与 owner 信息  start  ///////////////////////
        val basePath = (project.basePath ?: "").replace('\\', '/').trimEnd('/')
        val id = collectionIdFor(basePath)
        // ////////////////  准备数据  end  ///////////////////////////////////////////////

        // ////////////////  核心逻辑：命中直接返回；缺失时在写锁内创建（存储 State + 内核）  //
        collections[id]?.let { return it }
        synchronized(writeLock) {
            collections[id]?.let { return it }
            val name = project.name
            val helper = storageHelper()
            val data = helper.state.collections.getOrPut(id) {
                SharedBookmarkStorageHelper.SharedCollectionData(
                    id = id,
                    ownerProjectName = name,
                    ownerProjectPath = basePath,
                    updatedTime = System.currentTimeMillis()
                )
            }
            val collection = SharedCollection(
                id = id,
                ownerProjectName = data.ownerProjectName.ifBlank { name },
                ownerProjectPath = data.ownerProjectPath.ifBlank { basePath },
                updatedTime = data.updatedTime,
                core = BookmarkStoreCore { slicePersistence(id, name, basePath) }
            )
            collections[id] = collection
            // ////////////////  核心逻辑  end  ///////////////////////////////////////////

            // ////////////////  逻辑后：新集合广播（其它项目管理对话框可见）  start  ///////
            broadcastChanged()
            // ////////////////  逻辑后  end  /////////////////////////////////////////////
            return collection
        }
    }

    /**
     * 获取当前项目可见的全部共享集合（1.7.1 起仅自己的集合；
     * 旧订阅 refs 在迁移前若仍有残留集合则继续可见，迁移后恒为 [own] 单元素）
     * （订阅 id 已不在仓库中时静默跳过）
     *
     * @param project 当前项目
     */
    fun visibleCollections(project: Project): List<SharedCollection> {
        ensureLoaded()
        // ////////////////  准备数据：自己的集合恒在首位  start  //////////////////////////
        val own = collectionOf(project)
        val result = mutableListOf(own)
        // ////////////////  准备数据  end  ///////////////////////////////////////////////

        // ////////////////  核心逻辑：按旧订阅顺序追加可见集合（迁移后为空）  start  ////////
        for (id in refsHelper(project).state.refs) {
            if (id == own.id) continue
            collections[id]?.let { result.add(it) }
        }
        // ////////////////  核心逻辑  end  ///////////////////////////////////////////////
        return result
    }

    /**
     * 仓库中全部集合（旧订阅迁移/调试用；按存储出现顺序）：
     * 1.7.1 起配置对话框改列全部已知项目（不再列共享仓库集合）
     */
    fun listAllCollections(): List<SharedCollection> {
        ensureLoaded()
        synchronized(writeLock) {
            return storageHelper().state.collections.keys.mapNotNull { collections[it] }
        }
    }

    /**
     * 按集合 id 取集合（不存在返回 null）
     */
    fun getCollection(collectionId: String): SharedCollection? {
        ensureLoaded()
        return collections[collectionId]
    }

    /**
     * 判断某收藏属于哪个共享集合：先查自己的集合，再按订阅顺序查外来集合
     * （转移语义用：命中自己集合=移动；命中外来集合=复制；都未命中返回 null）
     *
     * @param project 当前项目（决定「自己」与订阅范围）
     * @param bookmarkId 收藏 id
     */
    fun collectionOfBookmark(project: Project, bookmarkId: String): SharedCollection? {
        ensureLoaded()
        // ////////////////  核心逻辑：自己集合优先，订阅集合随后  start  //////////////////
        val own = collectionOf(project)
        own.core.getBookmark(bookmarkId)?.let { return own }
        for (id in refsHelper(project).state.refs) {
            if (id == own.id) continue
            val collection = collections[id] ?: continue
            collection.core.getBookmark(bookmarkId)?.let { return collection }
        }
        // ////////////////  核心逻辑  end  ///////////////////////////////////////////////
        return null
    }

    // ==================== 订阅管理（1.7.0 遗留）与来源项目配置（1.7.1） ====================

    /**
     * 订阅共享集合（1.7.0 旧订阅模型遗留入口，写项目级引用存储，幂等）。
     * 1.7.1 起 UI 已不再调用（改为 [setConfiguredSourceProjects] 来源项目配置），
     * 方法保留仅为旧数据兼容与既有单元测试过渡。
     *
     * @param project 当前项目
     * @param collectionId 目标集合 id
     */
    fun subscribe(project: Project, collectionId: String) {
        // ////////////////  核心逻辑：去重追加订阅  start  ///////////////////////////////
        val refs = refsHelper(project)
        if (!refs.state.refs.contains(collectionId)) {
            refs.state.refs.add(collectionId)
        }
        // ////////////////  核心逻辑  end  ///////////////////////////////////////////////
    }

    /**
     * 取消订阅共享集合（1.7.0 旧订阅模型遗留入口；未订阅时无副作用）。
     * 保留原因同 [subscribe]。
     *
     * @param project 当前项目
     * @param collectionId 目标集合 id
     */
    fun unsubscribe(project: Project, collectionId: String) {
        refsHelper(project).state.refs.remove(collectionId)
    }

    /**
     * 取当前项目已配置的来源项目根路径列表（1.7.1 新增，规范化正斜杠、按配置顺序）。
     * 首次读取时顺带完成 1.7.0 旧订阅数据迁移（见 [migrateLegacyRefsIfNeeded]）
     *
     * @param project 当前项目
     * @return 来源项目路径列表（可能为空）
     */
    fun configuredSourceProjects(project: Project): List<String> {
        val helper = refsHelper(project)
        migrateLegacyRefsIfNeeded(project, helper)
        return helper.state.sourceProjects.toList()
    }

    /**
     * 保存来源项目配置（1.7.1 新增，整体覆盖写）：
     * 规范化路径、去重保序，并强制排除当前项目自身（单向选择，不能选自己）。
     * 仅写本项目 favorites.shared.refs.xml，绝不触碰来源项目的任何文件
     *
     * @param project 当前项目
     * @param sourcePaths 来源项目根路径列表（原始风格即可，内部统一规范化）
     */
    fun setConfiguredSourceProjects(project: Project, sourcePaths: List<String>) {
        // ////////////////  准备数据：规范化 + 去重 + 排除自身  start  ////////////////////
        val own = normalizeProjectPath(project.basePath ?: "")
        val normalized = sourcePaths.map { normalizeProjectPath(it) }
            .filter { it.isNotEmpty() && it != own }
            .distinct()
        // ////////////////  准备数据  end  ///////////////////////////////////////////////

        // ////////////////  核心逻辑：整体覆盖写项目级配置  start  ////////////////////////
        val helper = refsHelper(project)
        helper.state.sourceProjects.clear()
        helper.state.sourceProjects.addAll(normalized)
        helper.state.refs.clear()
        // ////////////////  核心逻辑  end  ///////////////////////////////////////////////
    }

    /**
     * 旧订阅数据迁移（1.7.0 → 1.7.1，尽力而为）：
     * refs 非空时，把每个旧集合 id 经 [listAllCollections] 映射回 ownerProjectPath 转入
     * sourceProjects（去重、排除自身），映射不到的 id 直接丢弃，最后清空 refs。
     * 迁移只发生一次（refs 清空后不再进入）
     */
    private fun migrateLegacyRefsIfNeeded(project: Project, helper: SharedRefStorageHelper) {
        // ////////////////  校验参数：无旧订阅数据则无需迁移  start  //////////////////////
        if (helper.state.refs.isEmpty()) return
        // ////////////////  校验参数  end  /////////////////////////////////////////////

        // ////////////////  准备数据：旧集合 id → 来源项目路径映射  start  ///////////////
        val idToPath = listAllCollections().associate { it.id to normalizeProjectPath(it.ownerProjectPath) }
        val own = normalizeProjectPath(project.basePath ?: "")
        val converted = helper.state.refs.mapNotNull { idToPath[it] }
            .filter { it.isNotEmpty() && it != own }
            .filter { it !in helper.state.sourceProjects }
        // ////////////////  准备数据  end  /////////////////////////////////////////////

        // ////////////////  核心逻辑：转入 sourceProjects 并清空旧 refs  start  ///////////
        helper.state.sourceProjects.addAll(converted)
        helper.state.refs.clear()
        log.info("共享收藏旧订阅数据已迁移为来源项目配置：转换 ${converted.size} 项（项目=${project.name}）")
        // ////////////////  核心逻辑  end  ///////////////////////////////////////////////
    }

    /**
     * 取消共享（删除自己的集合及其全部数据；仅允许删自己的集合，UI 确认后调用）
     * 外来订阅方的引用 id 将不再命中仓库，其界面自然不再展示该集合。
     *
     * @param project 当前项目（只能删自己的集合）
     * @param collectionId 目标集合 id
     * @return 是否删除成功（目标不是自己的集合返回 false）
     */
    fun removeCollection(project: Project, collectionId: String): Boolean {
        ensureLoaded()
        // ////////////////  校验参数：仅允许删自己的集合  start  /////////////////////////
        val own = collectionOf(project)
        if (own.id != collectionId) return false
        // ////////////////  校验参数  end  /////////////////////////////////////////////

        // ////////////////  核心逻辑：存储与内存同时移除  start  /////////////////////////
        synchronized(writeLock) {
            storageHelper().state.collections.remove(collectionId)
            collections.remove(collectionId)
        }
        // ////////////////  核心逻辑  end  /////////////////////////////////////////////

        // ////////////////  逻辑后：广播（订阅方界面同步消失）  start  ///////////////////
        broadcastChanged()
        return true
        // ////////////////  逻辑后  end  ///////////////////////////////////////////////
    }

    // ==================== 重载与广播 ====================

    /**
     * 从存储重载全部集合（后台轮询/测试注入后调用）：整表重建内核，
     * 懒加载内核在首次访问时读取最新切片数据；项目级订阅引用不受影响
     */
    private fun reloadAll() {
        // ////////////////  核心逻辑：按存储 State 整表重建集合与内核  start  ////////////
        synchronized(writeLock) {
            val helper = storageHelper()
            val rebuilt = LinkedHashMap<String, SharedCollection>()
            for ((id, data) in helper.state.collections) {
                rebuilt[id] = SharedCollection(
                    id = id,
                    ownerProjectName = data.ownerProjectName,
                    ownerProjectPath = data.ownerProjectPath,
                    updatedTime = data.updatedTime,
                    core = BookmarkStoreCore { slicePersistence(id, data.ownerProjectName, data.ownerProjectPath) }
                )
            }
            collections.clear()
            collections.putAll(rebuilt)
        }
        // ////////////////  核心逻辑  end  ///////////////////////////////////////////////
    }

    /**
     * 广播共享收藏变化（异步：invokeLater 到 EDT 发布，监听方各自再刷新界面）
     */
    private fun broadcastChanged() {
        // ////////////////  校验参数：应用已退出时不再广播  start  ///////////////////////
        val application = ApplicationManager.getApplication() ?: return
        if (application.isDisposed) return
        // ////////////////  校验参数  end  /////////////////////////////////////////////

        // ////////////////  核心逻辑：异步发布 topic  start  /////////////////////////////
        application.invokeLater {
            if (application.isDisposed) return@invokeLater
            application.messageBus.syncPublisher(SHARED_FAVORITES_TOPIC).onSharedChanged()
        }
        // ////////////////  核心逻辑  end  ///////////////////////////////////////////////
    }

    /**
     * 跨 IDE 进程兜底轮询：stat favorites.shared.xml 的 mtime，变化即重载并广播
     * （同进程多项目窗口共享同一实例，内存即一致；重载在调度线程完成，UI 刷新由监听方处理）
     */
    private fun pollStorageFile() {
        // ////////////////  校验参数：应用已退出时停止工作  start  ///////////////////////
        val application = ApplicationManager.getApplication() ?: return
        if (application.isDisposed) return
        // ////////////////  校验参数  end  /////////////////////////////////////////////

        // ////////////////  准备数据：读取当前 mtime  start  /////////////////////////////
        val mtime = readFileMtime()
        // ////////////////  准备数据  end  ///////////////////////////////////////////////

        // ////////////////  核心逻辑：mtime 变化才重载+广播  start  /////////////////////
        if (mtime == lastMtime) return
        lastMtime = mtime
        reloadAll()
        broadcastChanged()
        // ////////////////  核心逻辑  end  ///////////////////////////////////////////////
    }

    /**
     * 读取存储文件 mtime（文件不存在返回 -1；异常按不可用处理记日志）
     */
    private fun readFileMtime(): Long = runCatching {
        val file = storageFile() ?: return -1L
        if (file.exists()) file.lastModified() else -1L
    }.onFailure { log.warn("stat 共享收藏存储文件失败", it) }.getOrDefault(-1L)

    /**
     * 共享存储文件（IDE 配置 options 目录下 favorites.shared.xml；跨进程兜底轮询目标）
     */
    private fun storageFile(): File? {
        val optionsPath = com.intellij.openapi.application.PathManager.getOptionsPath() ?: return null
        return File(optionsPath, "favorites.shared.xml")
    }

    /**
     * 集合数据切片持久化适配器：loadAll 从该集合的 content 读、saveAll 写回 content 并刷新更新时间，
     * 全部落到应用级存储 State（平台按其持久化机制落盘 favorites.shared.xml），写串行于全局写锁
     *
     * @param collectionId 集合 id
     * @param ownerProjectName 来源项目名（切片缺失时重建数据用）
     * @param ownerProjectPath 来源项目路径（切片缺失时重建数据用）
     */
    private fun slicePersistence(
        collectionId: String,
        ownerProjectName: String,
        ownerProjectPath: String
    ): BookmarkPersistence = object : BookmarkPersistence {

        /**
         * 读取该集合的全部收藏与列表元信息（集合切片缺失时按空数据处理）
         */
        override fun loadAll(): Pair<List<Bookmark>, List<ListInfo>> {
            // ////////////////  准备数据：按 id 取集合切片  start  ///////////////////////
            val data = synchronized(writeLock) { storageHelper().state.collections[collectionId] }
            val content = data?.content ?: BookmarkStorageHelper.State()
            // ////////////////  准备数据  end  ///////////////////////////////////////////

            // ////////////////  返回：复用共享读取实现  start  ///////////////////////////
            return BookmarkStorageHelper.readFromState(content)
            // ////////////////  返回  end  ///////////////////////////////////////////////
        }

        /**
         * 保存该集合的全部收藏与列表元信息（写回切片并刷新 updatedTime，随后广播全项目可见）
         */
        override fun saveAll(bookmarks: List<Bookmark>, lists: List<ListInfo>) {
            // ////////////////  核心逻辑：写回切片 + 刷新更新时间  start  ///////////////
            synchronized(writeLock) {
                val helper = storageHelper()
                val data = helper.state.collections.getOrPut(collectionId) {
                    SharedBookmarkStorageHelper.SharedCollectionData(
                        id = collectionId,
                        ownerProjectName = ownerProjectName,
                        ownerProjectPath = ownerProjectPath,
                        updatedTime = System.currentTimeMillis()
                    )
                }
                BookmarkStorageHelper.writeToState(data.content, bookmarks, lists)
                data.updatedTime = System.currentTimeMillis()
            }
            // ////////////////  核心逻辑  end  ///////////////////////////////////////////

            // ////////////////  逻辑后：写操作统一出口广播（跨项目窗口可见）  start  ///////
            broadcastChanged()
            // ////////////////  逻辑后  end  /////////////////////////////////////////////
        }
    }

    // ==================== 测试注入口 ====================

    /**
     * 仅用于测试：注入自定义应用级存储助手（优先于真实存储），脱离 IDE 服务运行
     */
    internal fun setStorageHelperForTest(helper: SharedBookmarkStorageHelper?) {
        this.testStorageHelper = helper
    }

    /**
     * 仅用于测试：清空实例状态（集合表与加载标记复位，下次访问重新加载）
     */
    internal fun resetForTest() {
        synchronized(writeLock) {
            collections.clear()
        }
        loaded = false
    }

    /**
     * 解析实际使用的存储助手（测试注入优先）
     */
    private fun storageHelper(): SharedBookmarkStorageHelper = testStorageHelper ?: SharedBookmarkStorageHelper.getInstance()

    /**
     * 解析实际使用的项目引用存储助手（测试注入由 [SharedBookmarkManager] 承担，这里取真实服务）
     */
    private fun refsHelper(project: Project): SharedRefStorageHelper = SharedRefStorageHelper.getInstance(project)

    /**
     * 应用退出时停掉 mtime 轮询调度（项目卸载无需处理，本服务为应用级）
     */
    override fun dispose() {
        scheduler.shutdownNow()
    }
}

/** 存储文件 mtime 轮询间隔（秒） */
private const val POLL_INTERVAL_SECONDS = 2L
