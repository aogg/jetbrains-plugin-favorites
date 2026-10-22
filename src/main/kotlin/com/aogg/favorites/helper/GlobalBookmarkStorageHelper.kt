package com.aogg.favorites.helper

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * 全局收藏持久化存储助手（应用级，1.2.0 新增）
 *
 * State 结构与项目级 [BookmarkStorageHelper] 完全一致（直接复用其 State/BookmarkData/ListData，
 * 不复制第二份结构），仅持久化位置不同：
 * - 项目级：.idea/favorites.xml（随项目走，路径存相对路径）
 * - 全局级：IDE 配置目录 favorites.global.xml（随应用走，路径存绝对路径，跨项目共享）
 */
@State(
    name = "FavoritesGlobalBookmarks",
    storages = [Storage(value = "favorites.global.xml")]
)
@Service(Service.Level.APP)
class GlobalBookmarkStorageHelper :
    PersistentStateComponent<BookmarkStorageHelper.State>, BookmarkPersistence {

    // 直接复用项目级存储助手的 State 结构（字段与序列化格式一致）
    private var state = BookmarkStorageHelper.State()

    override fun getState(): BookmarkStorageHelper.State {
        return state
    }

    override fun loadState(state: BookmarkStorageHelper.State) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    /**
     * 保存所有收藏与列表元信息（委托共享静态实现，与项目级存储同一份逻辑）
     */
    override fun saveAll(bookmarks: List<com.aogg.favorites.model.Bookmark>, lists: List<com.aogg.favorites.model.ListInfo>) {
        BookmarkStorageHelper.writeToState(state, bookmarks, lists)
    }

    /**
     * 加载所有收藏与列表元信息（委托共享静态实现）
     *
     * @return Pair(全部收藏, 列表元信息)
     */
    override fun loadAll(): Pair<List<com.aogg.favorites.model.Bookmark>, List<com.aogg.favorites.model.ListInfo>> =
        BookmarkStorageHelper.readFromState(state)

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
         * 获取应用级实例
         */
        @JvmStatic
        fun getInstance(): GlobalBookmarkStorageHelper {
            return ApplicationManager.getApplication().getService(GlobalBookmarkStorageHelper::class.java)
        }
    }
}
