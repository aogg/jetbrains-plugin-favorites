package com.aogg.favorites.model

import java.io.Serializable

/**
 * 收藏列表排序方式
 */
enum class SortType {
    /**
     * 按添加时间（默认）
     */
    ADDED,

    /**
     * 按描述
     */
    DESCRIPTION,

    /**
     * 按位置（文件路径 + 行号）
     */
    LOCATION
}

/**
 * 收藏列表元信息
 *
 * @param name 列表名称（项目内唯一；[Bookmark.DEFAULT_LIST_NAME] 为默认列表，不可删除/重命名）
 * @param sortType 列表内收藏的排序方式（按列表记忆并持久化）
 */
data class ListInfo(
    val name: String,
    val sortType: SortType = SortType.ADDED
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
