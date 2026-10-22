package com.aogg.favorites.helper

import com.aogg.favorites.model.Bookmark
import com.aogg.favorites.model.ListInfo

/**
 * 收藏持久化契约（单作用域视角）
 *
 * 项目级（[BookmarkStorageHelper]）与应用级（[GlobalBookmarkStorageHelper]）两个存储助手
 * 实现同一契约，使共享内核 [com.aogg.favorites.model.BookmarkStoreCore] 无需感知作用域差异。
 */
interface BookmarkPersistence {

    /**
     * 加载该作用域的全部收藏与列表元信息
     *
     * @return Pair(全部收藏, 列表元信息)
     */
    fun loadAll(): Pair<List<Bookmark>, List<ListInfo>>

    /**
     * 保存该作用域的全部收藏与列表元信息（全量覆盖）
     *
     * @param bookmarks 全部收藏
     * @param lists 列表元信息
     */
    fun saveAll(bookmarks: List<Bookmark>, lists: List<ListInfo>)
}
