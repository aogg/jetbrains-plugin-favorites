package com.aogg.favorites.helper

import com.aogg.favorites.model.Bookmark
import com.aogg.favorites.model.BookmarkType
import com.aogg.favorites.model.ListInfo
import com.aogg.favorites.model.SortType
import com.intellij.openapi.components.*
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * 收藏持久化存储助手
 * 使用 ComponentState 实现持久化存储
 *
 * 存储格式：
 * - anonymousBookmarks/mnemonicBookmarks：收藏数据（1.1.0 起 BookmarkData 增加 listName 字段）
 * - lists：收藏列表元信息（1.1.0 新增；1.5.0 起 ListData 增加 id/parentId 支持多层级分组树）
 * 兼容迁移：旧格式无 listName/lists/id/parentId，加载时缺失字段自动取默认值并归一，不丢数据。
 */
@State(
    name = "FavoritesBookmarks",
    storages = [Storage(value = "favorites.xml", roamingType = RoamingType.DISABLED)]
)
@Service(Service.Level.PROJECT)
class BookmarkStorageHelper : PersistentStateComponent<BookmarkStorageHelper.State>, BookmarkPersistence {

    data class State(
        var anonymousBookmarks: MutableList<BookmarkData> = mutableListOf(),
        var mnemonicBookmarks: MutableMap<String, BookmarkData> = mutableMapOf(),
        var lists: MutableList<ListData> = mutableListOf()
    ) {
        // 空构造函数用于序列化
        constructor() : this(mutableListOf(), mutableMapOf(), mutableListOf())
    }

    /**
     * 收藏数据（用于序列化）
     */
    data class BookmarkData(
        var id: String = "",
        var type: String = "",
        var filePath: String = "",
        var lineNumber: Int = -1,
        var mnemonic: String? = null,
        var description: String? = null,
        var createdTime: Long = System.currentTimeMillis(),
        var listName: String = Bookmark.DEFAULT_LIST_NAME
    ) {
        constructor() : this("", "", "", -1, null, null, System.currentTimeMillis(), Bookmark.DEFAULT_LIST_NAME)

        fun toBookmark(): Bookmark {
            // 旧数据无 listName 时使用默认值，自动归入默认列表
            val name = if (listName.isBlank()) Bookmark.DEFAULT_LIST_NAME else listName
            return Bookmark(
                id = id,
                type = BookmarkType.valueOf(type),
                filePath = filePath,
                lineNumber = lineNumber,
                mnemonic = mnemonic?.singleOrNull(),
                description = description,
                createdTime = createdTime,
                listName = name
            )
        }

        companion object {
            fun fromBookmark(bookmark: Bookmark): BookmarkData {
                return BookmarkData(
                    id = bookmark.id,
                    type = bookmark.type.name,
                    filePath = bookmark.filePath,
                    lineNumber = bookmark.lineNumber,
                    mnemonic = bookmark.mnemonic?.toString(),
                    description = bookmark.description,
                    createdTime = bookmark.createdTime,
                    listName = bookmark.listName
                )
            }
        }
    }

    /**
     * 收藏列表元数据（用于序列化；1.5.0 起增加 id/parentId 支持多层级分组树）
     * 兼容迁移：旧文件缺 id/parentId 字段时取默认值（""），加载侧由内核归一补 id/归顶级，不丢数据。
     */
    data class ListData(
        var name: String = "",
        var sortType: String = SortType.ADDED.name,
        var id: String = "",
        var parentId: String = ""
    ) {
        constructor() : this("", SortType.ADDED.name, "", "")

        fun toListInfo(): ListInfo {
            // 未知排序值（异常数据/跨版本）时保守降级为 ADDED，不抛异常；
            // 空 id 透传（""），加载侧由 ListInfo.normalizeTree 统一补 UUID
            val type = SortType.entries.firstOrNull { it.name == sortType } ?: SortType.ADDED
            return ListInfo(name = name, sortType = type, id = id, parentId = parentId)
        }

        companion object {
            fun fromListInfo(info: ListInfo): ListData {
                return ListData(name = info.name, sortType = info.sortType.name, id = info.id, parentId = info.parentId)
            }
        }
    }

    private var state = State()

    override fun getState(): State {
        return state
    }

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    /**
     * 保存所有收藏与列表元信息（委托共享静态实现，全局存储助手复用同一份逻辑）
     */
    override fun saveAll(bookmarks: List<Bookmark>, lists: List<ListInfo>) {
        writeToState(state, bookmarks, lists)
    }

    /**
     * 加载所有收藏与列表元信息（委托共享静态实现）
     *
     * @return Pair(全部收藏, 列表元信息)
     */
    override fun loadAll(): Pair<List<Bookmark>, List<ListInfo>> = readFromState(state)

    /**
     * 清空所有收藏
     */
    fun clearAll() {
        state.anonymousBookmarks.clear()
        state.mnemonicBookmarks.clear()
        state.lists.clear()
    }

    companion object {
        /**
         * 获取项目实例
         */
        @JvmStatic
        fun getInstance(project: com.intellij.openapi.project.Project): BookmarkStorageHelper {
            return project.service()
        }

        /**
         * 将收藏与列表元信息全量写入指定 State（项目/全局两个存储助手共用，避免逻辑复制）
         *
         * @param state 目标 State（原地覆盖）
         * @param bookmarks 全部收藏（匿名入列表、合法助记符入映射）
         * @param lists 列表元信息
         */
        @JvmStatic
        fun writeToState(state: State, bookmarks: List<Bookmark>, lists: List<ListInfo>) {
            state.anonymousBookmarks = bookmarks.filter { it.isAnonymous() }
                .map { BookmarkData.fromBookmark(it) }
                .toMutableList()
            state.mnemonicBookmarks = bookmarks.filter { it.isMnemonic() && it.mnemonic != null && Bookmark.isValidMnemonic(it.mnemonic!!) }
                .associateBy({ it.mnemonic!!.toString() }, { BookmarkData.fromBookmark(it) })
                .toMutableMap()
            state.lists = lists.map { ListData.fromListInfo(it) }.toMutableList()
        }

        /**
         * 从指定 State 读取全部收藏与列表元信息（项目/全局两个存储助手共用）
         *
         * @return Pair(全部收藏, 列表元信息)
         */
        @JvmStatic
        fun readFromState(state: State): Pair<List<Bookmark>, List<ListInfo>> {
            val anonymous = state.anonymousBookmarks.map { it.toBookmark() }
            val mnemonic = state.mnemonicBookmarks.values
                .filter { (it.mnemonic?.singleOrNull())?.let { c -> Bookmark.isValidMnemonic(c) } == true }
                .map { it.toBookmark() }
            val lists = state.lists.map { it.toListInfo() }
            return Pair(anonymous + mnemonic, lists)
        }
    }
}
