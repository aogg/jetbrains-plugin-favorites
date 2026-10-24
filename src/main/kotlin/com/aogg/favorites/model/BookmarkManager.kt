package com.aogg.favorites.model

import com.aogg.favorites.helper.BookmarkStorageHelper
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * 收藏管理器（项目作用域，单例）
 * 负责项目收藏的增删改查、收藏列表（List）管理、持久化存储和助记符冲突检测
 *
 * 1.2.0 起核心逻辑抽取到共享内核 [BookmarkStoreCore]（与全局作用域 [GlobalBookmarkManager] 复用同一份逻辑），
 * 本类退化为项目作用域门面：对外 API 与 1.1.x 完全一致，行为不变。
 *
 * 列表模型约束：
 * - 助记符在项目内全局唯一（跨列表），跳转快捷键按助记符跨列表查找
 * - 默认列表 [Bookmark.DEFAULT_LIST_NAME] 不可删除/重命名
 * - 删除列表时其收藏全部并入默认列表
 */
@Service(Service.Level.PROJECT)
class BookmarkManager(private val project: Project) : ScopedBookmarkManager {

    companion object {
        /**
         * 获取项目实例
         */
        @JvmStatic
        fun getInstance(project: Project): BookmarkManager {
            val manager = project.service<BookmarkManager>()
            // 初始化时加载持久化数据（只加载一次，避免频繁I/O与全量重建索引）
            manager.ensureLoaded()
            return manager
        }
    }

    // 测试注入的假持久化助手（优先于真实存储，供单元测试脱离 IDE 环境使用）
    private var testHelper: BookmarkStorageHelper? = null

    // 共享存储内核：懒解析持久化（首次加载/每次落盘时取 testHelper，否则取项目级真实存储）
    private val core = BookmarkStoreCore { testHelper ?: BookmarkStorageHelper.getInstance(project) }

    /**
     * 确保持久化数据已加载（getInstance 与内核内部调用）
     */
    fun ensureLoaded() = core.ensureLoaded()

    // ==================== 收藏基础操作（委托共享内核） ====================

    /**
     * 添加收藏
     *
     * @param bookmark 收藏项
     * @return 是否添加成功（如果助记符已存在则返回false）
     */
    override fun addBookmark(bookmark: Bookmark): Boolean = core.addBookmark(bookmark)

    /**
     * 删除收藏
     *
     * @param bookmarkId 收藏ID
     * @return 是否删除成功
     */
    override fun removeBookmark(bookmarkId: String): Boolean = core.removeBookmark(bookmarkId)

    /**
     * 获取收藏
     *
     * @param bookmarkId 收藏ID
     * @return 收藏项，如果不存在返回null
     */
    override fun getBookmark(bookmarkId: String): Bookmark? = core.getBookmark(bookmarkId)

    /**
     * 通过助记符获取收藏（跨列表查找）
     *
     * @param mnemonic 助记符
     * @return 收藏项，如果不存在返回null
     */
    override fun getBookmarkByMnemonic(mnemonic: Char): Bookmark? = core.getBookmarkByMnemonic(mnemonic)

    /**
     * 获取所有收藏
     *
     * @return 所有收藏的列表
     */
    override fun getAllBookmarks(): List<Bookmark> = core.getAllBookmarks()

    /**
     * 获取所有匿名收藏
     *
     * @return 匿名收藏列表
     */
    fun getAnonymousBookmarks(): List<Bookmark> = core.getAnonymousBookmarks()

    /**
     * 获取所有助记符收藏
     *
     * @return 助记符收藏列表
     */
    fun getMnemonicBookmarks(): List<Bookmark> = core.getMnemonicBookmarks()

    /**
     * 获取文件的所有收藏
     *
     * @param filePath 文件路径（项目作用域为相对路径）
     * @return 该文件的收藏列表
     */
    fun getBookmarksByFile(filePath: String): List<Bookmark> = core.getBookmarksByFile(filePath)

    /**
     * 获取文件夹的所有收藏
     *
     * @param folderPath 文件夹路径（项目作用域为相对路径）
     * @return 该文件夹的收藏列表
     */
    fun getBookmarksByFolder(folderPath: String): List<Bookmark> = core.getBookmarksByFolder(folderPath)

    /**
     * 获取某个文件某一行的收藏（用于 gutter 查询，避免全量扫描）
     */
    override fun getLineBookmarks(filePath: String, lineNumber: Int): List<Bookmark> = core.getLineBookmarks(filePath, lineNumber)

    /**
     * 检查助记符是否可用
     *
     * @param mnemonic 助记符
     * @return 是否可用
     */
    override fun isMnemonicAvailable(mnemonic: Char): Boolean = core.isMnemonicAvailable(mnemonic)

    /**
     * 获取所有已使用的助记符
     *
     * @return 已使用的助记符集合
     */
    override fun getUsedMnemonics(): Set<Char> = core.getUsedMnemonics()

    /**
     * 获取所有可用的助记符
     *
     * @return 可用的助记符列表（0-9, A-Z）
     */
    fun getAvailableMnemonics(): List<Char> = core.getAvailableMnemonics()

    /**
     * 更新收藏描述
     *
     * @param bookmarkId 收藏ID
     * @param description 新描述
     * @return 是否更新成功
     */
    override fun updateBookmarkDescription(bookmarkId: String, description: String?): Boolean =
        core.updateBookmarkDescription(bookmarkId, description)

    /**
     * 更新收藏的助记符与描述（用于工具窗口"设置助记符"）
     *
     * @param bookmarkId 收藏ID
     * @param mnemonic 新助记符（null 表示移除助记符）
     * @param description 新描述
     * @return 是否更新成功（目标助记符已被其它收藏占用时返回false）
     */
    override fun updateBookmarkDetails(bookmarkId: String, mnemonic: Char?, description: String?): Boolean =
        core.updateBookmarkDetails(bookmarkId, mnemonic, description)

    /**
     * 删除指定路径的收藏
     *
     * @param path 相对路径
     * @param includeChildren 是否包含子路径（用于文件夹删除/移出项目）
     * @return 删除数量
     */
    override fun removeBookmarksByPath(path: String, includeChildren: Boolean): Int =
        core.removeBookmarksByPath(path, includeChildren)

    /**
     * 更新收藏路径（用于文件/文件夹重命名、移动）
     *
     * @param oldPath 旧相对路径
     * @param newPath 新相对路径
     * @param includeChildren 是否包含子路径（用于文件夹）
     * @return 更新数量
     */
    fun updateBookmarksPath(oldPath: String, newPath: String, includeChildren: Boolean): Int =
        core.updateBookmarksPath(oldPath, newPath, includeChildren)

    /**
     * 清理某文件的无效行收藏（行号超出范围）
     *
     * @param filePath 相对文件路径
     * @param lineCount 文件总行数
     * @return 删除数量
     */
    fun cleanupInvalidLineBookmarks(filePath: String, lineCount: Int): Int =
        core.cleanupInvalidLineBookmarks(filePath, lineCount)

    /**
     * 清空所有收藏
     */
    fun clearAll() = core.clearAll()

    /**
     * 获取收藏总数
     */
    override fun getTotalCount(): Int = core.getTotalCount()

    /**
     * 获取匿名收藏数量
     */
    fun getAnonymousCount(): Int = core.getAnonymousCount()

    /**
     * 获取助记符收藏数量
     */
    fun getMnemonicCount(): Int = core.getMnemonicCount()

    // ==================== 收藏列表（List）管理（委托共享内核） ====================

    /**
     * 获取所有列表元信息（始终包含默认列表，默认列表排在首位）
     */
    override fun getLists(): List<ListInfo> = core.getLists()

    /**
     * 判断列表是否存在
     */
    override fun hasList(name: String): Boolean = core.hasList(name)

    /**
     * 新建列表（顶级分组）
     *
     * @param name 列表名称（去空格后不能为空，且不能与现有列表重名）
     * @return 是否创建成功
     */
    override fun createList(name: String): Boolean = core.createList(name)

    /**
     * 在指定分组下新建子分组（名字全作用域唯一；父子按 id 关联）
     *
     * @param parentName 父分组名（须已存在）
     * @param newName 子分组名（去空格后不能为空，且不能与现有列表重名）
     * @return 是否创建成功
     */
    override fun createSubGroup(parentName: String, newName: String): Boolean = core.createSubGroup(parentName, newName)

    /**
     * 获取指定父分组的直接子分组（parentId="" 返回顶级分组，含默认列表）
     */
    override fun childrenOf(parentId: String): List<ListInfo> = core.childrenOf(parentId)

    /**
     * 获取分组的完整层级路径（如「父/子」；分组不存在时原样返回名称）
     */
    override fun fullPath(name: String): String = core.fullPath(name)

    /**
     * 重命名列表（默认列表不可重命名；其下所有收藏同步迁移；改名不影响层级）
     *
     * @return 是否重命名成功
     */
    override fun renameList(oldName: String, newName: String): Boolean = core.renameList(oldName, newName)

    /**
     * 删除列表（级联删除全部后代分组，其收藏并入默认列表）
     *
     * @return 是否删除成功
     */
    override fun deleteList(name: String): Boolean = core.deleteList(name)

    /**
     * 列表级联删除预览（确认框展示用；分组不存在或为默认列表返回 null）
     */
    override fun previewCascadeDelete(name: String): CascadeDeletePreview? = core.previewCascadeDelete(name)

    /**
     * 删除列表并返回级联删除详情（删除分组数/并入收藏数）
     */
    override fun deleteListCascade(name: String): ListDeleteResult = core.deleteListCascade(name)

    /**
     * 将收藏移动到指定列表
     *
     * @param bookmarkId 收藏ID
     * @param listName 目标列表名（必须已存在）
     * @return 是否移动成功
     */
    override fun moveToList(bookmarkId: String, listName: String): Boolean = core.moveToList(bookmarkId, listName)

    /**
     * 设置列表的排序方式（按列表记忆并持久化）
     *
     * @return 是否发生变化
     */
    override fun setSortType(listName: String, sortType: SortType): Boolean = core.setSortType(listName, sortType)

    /**
     * 获取指定列表的排序方式（列表不存在时返回默认值 ADDED）
     */
    override fun getSortType(listName: String): SortType = core.getSortType(listName)

    /**
     * 获取指定列表下的所有收藏（按该列表的排序方式排序）
     */
    override fun getBookmarksByList(listName: String): List<Bookmark> = core.getBookmarksByList(listName)

    /**
     * 仅用于测试：注入自定义的持久化助手，避免依赖真实 IDE 服务
     */
    internal fun setStorageHelperForTest(helper: BookmarkStorageHelper) {
        this.testHelper = helper
    }
}
