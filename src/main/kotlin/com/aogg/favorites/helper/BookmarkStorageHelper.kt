package com.aogg.favorites.helper

import com.aogg.favorites.model.Bookmark
import com.aogg.favorites.model.BookmarkType
import com.intellij.openapi.components.*
import com.intellij.util.xmlb.XmlSerializerUtil
import java.util.*

/**
 * 收藏持久化存储助手
 * 使用 ComponentState 实现持久化存储
 */
@State(
    name = "FavoritesBookmarks",
    storages = [Storage(value = "favorites.xml", roamingType = RoamingType.DISABLED)]
)
@Service(Service.Level.PROJECT)
class BookmarkStorageHelper : PersistentStateComponent<BookmarkStorageHelper.State> {

    data class State(
        var anonymousBookmarks: MutableList<BookmarkData> = mutableListOf(),
        var mnemonicBookmarks: MutableMap<String, BookmarkData> = mutableMapOf()
    ) {
        // 空构造函数用于序列化
        constructor() : this(mutableListOf(), mutableMapOf())
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
        var createdTime: Long = System.currentTimeMillis()
    ) {
        constructor() : this("", "", "", -1, null, null, System.currentTimeMillis())

        fun toBookmark(): Bookmark {
            return Bookmark(
                id = id,
                type = BookmarkType.valueOf(type),
                filePath = filePath,
                lineNumber = lineNumber,
                mnemonic = mnemonic?.singleOrNull(),
                description = description,
                createdTime = createdTime
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
                    createdTime = bookmark.createdTime
                )
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
     * 保存所有收藏
     */
    fun saveBookmarks(anonymousBookmarks: List<Bookmark>, mnemonicBookmarks: Map<Char, Bookmark>) {
        state.anonymousBookmarks = anonymousBookmarks.map { BookmarkData.fromBookmark(it) }.toMutableList()
        state.mnemonicBookmarks = mnemonicBookmarks.mapKeys { it.key.toString() }
            .mapValues { BookmarkData.fromBookmark(it.value) }.toMutableMap()
    }

    /**
     * 加载所有收藏
     */
    fun loadBookmarks(): Pair<List<Bookmark>, Map<Char, Bookmark>> {
        val anonymous = state.anonymousBookmarks.map { it.toBookmark() }
        val mnemonic = state.mnemonicBookmarks.mapKeys { it.key.singleOrNull() ?: '0' }
            .mapValues { it.value.toBookmark() }
            .filterKeys { it in '0'..'9' || it in 'A'..'Z' }
        return Pair(anonymous, mnemonic)
    }

    /**
     * 清空所有收藏
     */
    fun clearAll() {
        state.anonymousBookmarks.clear()
        state.mnemonicBookmarks.clear()
    }

    companion object {
        /**
         * 获取项目实例
         */
        @JvmStatic
        fun getInstance(project: com.intellij.openapi.project.Project): BookmarkStorageHelper {
            return project.service()
        }
    }
}
