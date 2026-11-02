package com.aogg.favorites.model

import com.aogg.favorites.helper.SharedRefStorageHelper
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

/**
 * 共享收藏管理器（项目级门面，1.7.0 新增）
 *
 * 「共享收藏」作用域对 UI/动作层的统一入口，实现 [ScopedBookmarkManager]：
 * - 读操作聚合「自己 + 已订阅」集合（[SharedBookmarkStore.visibleCollections]）；同名列表自己集合优先；
 * - 写操作全部落到自己集合（[SharedBookmarkStore.collectionOf]），外来集合只读——
 *   目标实为外来集合的数据时一律返回 false/0/null；
 * - hasList/isMnemonicAvailable 只按自己集合（写只发生在自己集合，输入校验语义）；
 * - getSortType 自己集合优先，否则订阅集合读值，否则默认 [SortType.ADDED]。
 *
 * 路径语义：filePath 存绝对路径（同 GLOBAL），跨项目定位同一位置。
 */
@Service(Service.Level.PROJECT)
class SharedBookmarkManager(private val project: Project) : ScopedBookmarkManager {

    companion object {
        /**
         * 获取项目实例（首次获取时确保共享仓库已加载）
         */
        @JvmStatic
        fun getInstance(project: Project): SharedBookmarkManager {
            val manager = project.getService(SharedBookmarkManager::class.java)
            manager.ensureLoaded()
            return manager
        }
    }

    // 测试注入的共享仓库（优先于真实应用级仓库，供单元测试脱离 IDE 环境使用）
    @Volatile
    private var testStore: SharedBookmarkStore? = null

    // 测试注入的引用存储助手（优先于真实项目级服务）
    @Volatile
    private var testRefsHelper: SharedRefStorageHelper? = null

    /**
     * 解析实际使用的共享仓库（测试注入优先）
     */
    private fun store(): SharedBookmarkStore = testStore ?: SharedBookmarkStore.getInstance()

    /**
     * 解析实际使用的引用存储助手（测试注入优先）
     */
    private fun refsHelper(): SharedRefStorageHelper = testRefsHelper ?: SharedRefStorageHelper.getInstance(project)

    /**
     * 确保共享仓库已加载（getInstance 与内核内部调用）
     */
    fun ensureLoaded() = store().ensureLoaded()

    /**
     * 自己的集合（写操作唯一目标）
     */
    private fun ownCollection(): SharedBookmarkStore.SharedCollection = store().collectionOf(project)

    /**
     * 已订阅的外来集合（按订阅顺序，不含自己的集合）
     */
    private fun subscribedCollections(): List<SharedBookmarkStore.SharedCollection> {
        val own = ownCollection()
        return store().visibleCollections(project).filter { it.id != own.id }
    }

    /**
     * 自己集合的管理器视图（移动到列表目标分组/添加分组选择弹窗使用：SHARED 的目标分组只取自己集合）
     */
    fun ownCollectionManager(): ScopedBookmarkManager = SharedCollectionManagerView(ownCollection())

    /**
     * 收藏是否属于外来集合（存在于可见共享集合但不在自己集合中；转移语义用）
     *
     * @param bookmarkId 收藏 id
     * @return true=外来集合（只读，跨作用域转移为复制）
     */
    fun isForeignBookmark(bookmarkId: String): Boolean {
        // ////////////////  准备数据：收藏归属的集合  start  /////////////////////////////
        val owner = store().collectionOfBookmark(project, bookmarkId) ?: return false
        // ////////////////  准备数据  end  /////////////////////////////////////////////

        // ////////////////  返回：非自己集合即外来  start  ///////////////////////////////
        return owner.id != ownCollection().id
        // ////////////////  返回  end  /////////////////////////////////////////////////
    }

    // ==================== 收藏基础操作（读=自己+订阅聚合；写=只落自己集合） ====================

    /**
     * 添加收藏到自己集合（filePath 须为绝对路径）
     *
     * @param bookmark 收藏项
     * @return 是否添加成功（助记符在自己集合已存在时返回 false）
     */
    override fun addBookmark(bookmark: Bookmark): Boolean = ownCollection().core.addBookmark(bookmark)

    /**
     * 删除收藏（先查自己集合，命中才删；外来集合只读返回 false）
     *
     * @param bookmarkId 收藏ID
     * @return 是否删除成功
     */
    override fun removeBookmark(bookmarkId: String): Boolean {
        // ////////////////  校验参数：收藏必须在自己集合  start  /////////////////////////
        val own = ownCollection()
        if (own.core.getBookmark(bookmarkId) == null) return false
        // ////////////////  校验参数  end  /////////////////////////////////////////////

        // ////////////////  返回：删除自己集合内的收藏  start  //////////////////////////
        return own.core.removeBookmark(bookmarkId)
        // ////////////////  返回  end  /////////////////////////////////////////////////
    }

    /**
     * 获取收藏（自己集合优先，未命中查订阅集合）
     *
     * @param bookmarkId 收藏ID
     * @return 收藏项，不存在返回 null
     */
    override fun getBookmark(bookmarkId: String): Bookmark? {
        // ////////////////  核心逻辑：自己集合优先，订阅集合随后  start  ////////////////
        ownCollection().core.getBookmark(bookmarkId)?.let { return it }
        for (collection in subscribedCollections()) {
            collection.core.getBookmark(bookmarkId)?.let { return it }
        }
        return null
        // ////////////////  核心逻辑  end  /////////////////////////////////////////////
    }

    /**
     * 通过助记符获取收藏（自己集合优先，未命中查订阅集合）
     *
     * @param mnemonic 助记符
     * @return 收藏项，不存在返回 null
     */
    override fun getBookmarkByMnemonic(mnemonic: Char): Bookmark? {
        // ////////////////  核心逻辑：自己集合优先，订阅集合随后  start  ////////////////
        ownCollection().core.getBookmarkByMnemonic(mnemonic)?.let { return it }
        for (collection in subscribedCollections()) {
            collection.core.getBookmarkByMnemonic(mnemonic)?.let { return it }
        }
        return null
        // ////////////////  核心逻辑  end  /////////////////////////////////////////////
    }

    /**
     * 获取可见范围（自己+订阅）全部收藏（自己集合在前）
     */
    override fun getAllBookmarks(): List<Bookmark> {
        // ////////////////  核心逻辑：逐集合聚合  start  ///////////////////////////////
        val result = mutableListOf<Bookmark>()
        for (collection in store().visibleCollections(project)) {
            result.addAll(collection.core.getAllBookmarks())
        }
        return result
        // ////////////////  核心逻辑  end  /////////////////////////////////////////////
    }

    /**
     * 获取某个文件某一行的收藏（自己集合在前，订阅集合随后；绝对路径匹配）
     */
    override fun getLineBookmarks(filePath: String, lineNumber: Int): List<Bookmark> {
        // ////////////////  核心逻辑：逐集合聚合  start  ///////////////////////////////
        val result = mutableListOf<Bookmark>()
        for (collection in store().visibleCollections(project)) {
            result.addAll(collection.core.getLineBookmarks(filePath, lineNumber))
        }
        return result
        // ////////////////  核心逻辑  end  /////////////////////////////////////////////
    }

    /**
     * 检查助记符在自己集合是否可用（写只发生在自己集合）
     */
    override fun isMnemonicAvailable(mnemonic: Char): Boolean = ownCollection().core.isMnemonicAvailable(mnemonic)

    /**
     * 获取可见范围全部已使用的助记符（自己+订阅并集）
     */
    override fun getUsedMnemonics(): Set<Char> {
        // ////////////////  核心逻辑：逐集合并集  start  ///////////////////////////////
        val result = mutableSetOf<Char>()
        for (collection in store().visibleCollections(project)) {
            result.addAll(collection.core.getUsedMnemonics())
        }
        return result
        // ////////////////  核心逻辑  end  /////////////////////////////////////////////
    }

    /**
     * 更新收藏描述（仅自己集合；外来集合只读返回 false）
     */
    override fun updateBookmarkDescription(bookmarkId: String, description: String?): Boolean {
        // ////////////////  校验参数：收藏必须在自己集合  start  /////////////////////////
        val own = ownCollection()
        if (own.core.getBookmark(bookmarkId) == null) return false
        // ////////////////  校验参数  end  /////////////////////////////////////////////
        return own.core.updateBookmarkDescription(bookmarkId, description)
    }

    /**
     * 更新收藏的助记符与描述（仅自己集合；外来集合只读返回 false）
     */
    override fun updateBookmarkDetails(bookmarkId: String, mnemonic: Char?, description: String?): Boolean {
        // ////////////////  校验参数：收藏必须在自己集合  start  /////////////////////////
        val own = ownCollection()
        if (own.core.getBookmark(bookmarkId) == null) return false
        // ////////////////  校验参数  end  /////////////////////////////////////////////
        return own.core.updateBookmarkDetails(bookmarkId, mnemonic, description)
    }

    /**
     * 删除指定路径的收藏（仅自己集合，绝对路径匹配）
     *
     * @param path 绝对路径
     * @param includeChildren 是否包含子路径（用于文件夹删除/移出项目）
     * @return 删除数量
     */
    override fun removeBookmarksByPath(path: String, includeChildren: Boolean): Int =
        ownCollection().core.removeBookmarksByPath(path, includeChildren)

    /**
     * 更新自己集合内收藏的路径（文件/文件夹重命名、移动时同步；外来集合不动）
     *
     * @param oldPath 旧绝对路径
     * @param newPath 新绝对路径
     * @param includeChildren 是否包含子路径（用于文件夹）
     * @return 更新数量
     */
    fun updateBookmarksPath(oldPath: String, newPath: String, includeChildren: Boolean): Int =
        ownCollection().core.updateBookmarksPath(oldPath, newPath, includeChildren)

    /**
     * 获取可见范围收藏总数（自己+订阅）
     */
    override fun getTotalCount(): Int {
        // ////////////////  核心逻辑：逐集合累加  start  ///////////////////////////////
        var total = 0
        for (collection in store().visibleCollections(project)) {
            total += collection.core.getTotalCount()
        }
        return total
        // ////////////////  核心逻辑  end  /////////////////////////////////////////////
    }

    // ==================== 分组（List）管理（读=自己+订阅同名去重；写=只落自己集合） ====================

    /**
     * 获取可见范围的分组元信息（自己集合在前；订阅集合中与自己同名的分组被自己遮蔽，不重复出现）
     */
    override fun getLists(): List<ListInfo> {
        // ////////////////  准备数据：自己集合分组（含默认列表，排在首位）  start  ////////
        val own = ownCollection()
        val merged = own.core.getLists().toMutableList()
        val ownNames = merged.map { it.name }.toHashSet()
        // ////////////////  准备数据  end  /////////////////////////////////////////////

        // ////////////////  核心逻辑：订阅集合分组（同名被自己遮蔽）  start  //////////////
        for (collection in subscribedCollections()) {
            for (info in collection.core.getLists()) {
                if (info.name !in ownNames) {
                    merged.add(info)
                }
            }
        }
        return merged
        // ////////////////  核心逻辑  end  /////////////////////////////////////////////
    }

    /**
     * 判断分组是否在自己集合存在（输入校验语义：写入目标只看自己集合）
     */
    override fun hasList(name: String): Boolean = ownCollection().core.hasList(name)

    /**
     * 新建分组（自己集合）
     */
    override fun createList(name: String): Boolean = ownCollection().core.createList(name)

    /**
     * 在指定分组下新建子分组（自己集合；父子按 id 关联）
     */
    override fun createSubGroup(parentName: String, newName: String): Boolean =
        ownCollection().core.createSubGroup(parentName, newName)

    /**
     * 获取指定父分组的直接子分组（自己集合在前；订阅集合与自己同名的子分组被遮蔽）
     *
     * @param parentId 父分组 id（""=顶级）
     */
    override fun childrenOf(parentId: String): List<ListInfo> {
        // ////////////////  准备数据：自己集合子分组与自己全部分组名  start  //////////////
        val own = ownCollection()
        val ownChildren = own.core.childrenOf(parentId)
        val merged = ownChildren.toMutableList()
        val ownNames = own.core.getLists().map { it.name }.toHashSet()
        // ////////////////  准备数据  end  /////////////////////////////////////////////

        // ////////////////  核心逻辑：订阅集合子分组（同名被自己遮蔽）  start  //////////////
        for (collection in subscribedCollections()) {
            for (info in collection.core.childrenOf(parentId)) {
                if (info.name !in ownNames) {
                    merged.add(info)
                }
            }
        }
        return merged
        // ////////////////  核心逻辑  end  /////////////////////////////////////////////
    }

    /**
     * 获取分组的完整层级路径（自己集合优先，未命中查订阅集合，分组不存在时原样返回名称）
     */
    override fun fullPath(name: String): String {
        // ////////////////  核心逻辑：自己集合优先，订阅集合随后  start  ////////////////
        val own = ownCollection()
        if (own.core.hasList(name)) return own.core.fullPath(name)
        for (collection in subscribedCollections()) {
            if (collection.core.hasList(name)) return collection.core.fullPath(name)
        }
        return name
        // ////////////////  核心逻辑  end  /////////////////////////////////////////////
    }

    /**
     * 重命名分组（仅自己集合；外来集合只读返回 false）
     */
    override fun renameList(oldName: String, newName: String): Boolean = ownCollection().core.renameList(oldName, newName)

    /**
     * 删除分组（级联，仅自己集合）
     */
    override fun deleteList(name: String): Boolean = ownCollection().core.deleteList(name)

    /**
     * 分组级联删除预览（仅自己集合；分组不存在或为默认列表返回 null）
     */
    override fun previewCascadeDelete(name: String): CascadeDeletePreview? =
        ownCollection().core.previewCascadeDelete(name)

    /**
     * 删除分组并返回级联删除详情（仅自己集合）
     */
    override fun deleteListCascade(name: String): ListDeleteResult = ownCollection().core.deleteListCascade(name)

    /**
     * 将收藏移动到指定分组（仅自己集合内的收藏可移动；外来集合只读返回 false）
     */
    override fun moveToList(bookmarkId: String, listName: String): Boolean =
        ownCollection().core.moveToList(bookmarkId, listName)

    /**
     * 设置分组排序方式（仅自己集合）
     */
    override fun setSortType(listName: String, sortType: SortType): Boolean =
        ownCollection().core.setSortType(listName, sortType)

    /**
     * 获取分组排序方式（自己集合优先，否则订阅集合读值，否则默认 [SortType.ADDED]）
     */
    override fun getSortType(listName: String): SortType {
        // ////////////////  核心逻辑：自己集合优先，订阅集合随后，兜底 ADDED  start  ////////
        val own = ownCollection()
        if (own.core.hasList(listName)) return own.core.getSortType(listName)
        for (collection in subscribedCollections()) {
            if (collection.core.hasList(listName)) return collection.core.getSortType(listName)
        }
        return SortType.ADDED
        // ////////////////  核心逻辑  end  /////////////////////////////////////////////
    }

    /**
     * 获取指定分组下的收藏（同名分组自己集合优先：先在自己集合找，未命中查订阅集合）
     */
    override fun getBookmarksByList(listName: String): List<Bookmark> {
        // ////////////////  核心逻辑：同名分组自己集合优先  start  //////////////////////
        val own = ownCollection()
        if (own.core.hasList(listName)) return own.core.getBookmarksByList(listName)
        for (collection in subscribedCollections()) {
            if (collection.core.hasList(listName)) return collection.core.getBookmarksByList(listName)
        }
        return emptyList()
        // ////////////////  核心逻辑  end  /////////////////////////////////////////////
    }

    // ==================== 测试注入口 ====================

    /**
     * 仅用于测试：注入自定义共享仓库，避免依赖真实 IDE 应用级服务
     */
    internal fun setStoreForTest(store: SharedBookmarkStore) {
        this.testStore = store
    }

    /**
     * 仅用于测试：注入自定义引用存储助手，避免依赖真实 IDE 项目级服务
     */
    internal fun setRefsHelperForTest(helper: SharedRefStorageHelper) {
        this.testRefsHelper = helper
    }
}

/**
 * 单个共享集合的管理器视图（1.7.0 内部适配器）：
 * 把集合内 [BookmarkStoreCore] 适配成 [ScopedBookmarkManager]，供收藏树按集合维度
 * 构建分组子树（SHARED 作用域下集合节点 → 分组 → 文件树），以及移动到列表/添加收藏
 * 选择弹窗以「自己集合」为目标分组来源。
 *
 * 写操作直接委托集合内核：本视图只应以「自己的集合」或「只读展示」场景使用，
 * 外来集合的写入口一律走 [SharedBookmarkManager]（其写操作只落自己集合）。
 */
internal class SharedCollectionManagerView(
    private val collection: SharedBookmarkStore.SharedCollection
) : ScopedBookmarkManager {

    /**
     * 集合收藏内核（委托目标）
     */
    private val core: BookmarkStoreCore = collection.core

    /**
     * 添加收藏
     */
    override fun addBookmark(bookmark: Bookmark): Boolean = core.addBookmark(bookmark)

    /**
     * 删除收藏
     */
    override fun removeBookmark(bookmarkId: String): Boolean = core.removeBookmark(bookmarkId)

    /**
     * 获取收藏
     */
    override fun getBookmark(bookmarkId: String): Bookmark? = core.getBookmark(bookmarkId)

    /**
     * 通过助记符获取收藏
     */
    override fun getBookmarkByMnemonic(mnemonic: Char): Bookmark? = core.getBookmarkByMnemonic(mnemonic)

    /**
     * 获取全部收藏
     */
    override fun getAllBookmarks(): List<Bookmark> = core.getAllBookmarks()

    /**
     * 获取某个文件某一行的收藏
     */
    override fun getLineBookmarks(filePath: String, lineNumber: Int): List<Bookmark> =
        core.getLineBookmarks(filePath, lineNumber)

    /**
     * 检查助记符是否可用
     */
    override fun isMnemonicAvailable(mnemonic: Char): Boolean = core.isMnemonicAvailable(mnemonic)

    /**
     * 获取已使用的助记符
     */
    override fun getUsedMnemonics(): Set<Char> = core.getUsedMnemonics()

    /**
     * 更新收藏描述
     */
    override fun updateBookmarkDescription(bookmarkId: String, description: String?): Boolean =
        core.updateBookmarkDescription(bookmarkId, description)

    /**
     * 更新收藏的助记符与描述
     */
    override fun updateBookmarkDetails(bookmarkId: String, mnemonic: Char?, description: String?): Boolean =
        core.updateBookmarkDetails(bookmarkId, mnemonic, description)

    /**
     * 删除指定路径的收藏
     */
    override fun removeBookmarksByPath(path: String, includeChildren: Boolean): Int =
        core.removeBookmarksByPath(path, includeChildren)

    /**
     * 获取收藏总数
     */
    override fun getTotalCount(): Int = core.getTotalCount()

    /**
     * 获取全部分组元信息
     */
    override fun getLists(): List<ListInfo> = core.getLists()

    /**
     * 判断分组是否存在
     */
    override fun hasList(name: String): Boolean = core.hasList(name)

    /**
     * 新建分组
     */
    override fun createList(name: String): Boolean = core.createList(name)

    /**
     * 新建子分组
     */
    override fun createSubGroup(parentName: String, newName: String): Boolean = core.createSubGroup(parentName, newName)

    /**
     * 获取父分组的直接子分组
     */
    override fun childrenOf(parentId: String): List<ListInfo> = core.childrenOf(parentId)

    /**
     * 获取分组完整层级路径
     */
    override fun fullPath(name: String): String = core.fullPath(name)

    /**
     * 重命名分组
     */
    override fun renameList(oldName: String, newName: String): Boolean = core.renameList(oldName, newName)

    /**
     * 删除分组
     */
    override fun deleteList(name: String): Boolean = core.deleteList(name)

    /**
     * 分组级联删除预览
     */
    override fun previewCascadeDelete(name: String): CascadeDeletePreview? = core.previewCascadeDelete(name)

    /**
     * 删除分组并返回级联详情
     */
    override fun deleteListCascade(name: String): ListDeleteResult = core.deleteListCascade(name)

    /**
     * 移动收藏到分组
     */
    override fun moveToList(bookmarkId: String, listName: String): Boolean = core.moveToList(bookmarkId, listName)

    /**
     * 设置分组排序方式
     */
    override fun setSortType(listName: String, sortType: SortType): Boolean = core.setSortType(listName, sortType)

    /**
     * 获取分组排序方式
     */
    override fun getSortType(listName: String): SortType = core.getSortType(listName)

    /**
     * 获取分组下的收藏
     */
    override fun getBookmarksByList(listName: String): List<Bookmark> = core.getBookmarksByList(listName)
}
