package com.aogg.favorites.model

import com.intellij.openapi.project.Project

/**
 * 作用域收藏管理器通用接口（1.2.0 新增）
 *
 * [BookmarkManager]（项目作用域）与 [GlobalBookmarkManager]（全局作用域）实现同一接口，
 * 供 UI / 动作层按 [FavoriteScope] 统一路由，避免到处写 when(scope) 分支。
 * 接口只声明两个作用域语义完全一致的方法子集。
 */
interface ScopedBookmarkManager {

    /**
     * 添加收藏（助记符在本作用域内冲突时返回 false）
     */
    fun addBookmark(bookmark: Bookmark): Boolean

    /**
     * 删除收藏
     */
    fun removeBookmark(bookmarkId: String): Boolean

    /**
     * 获取收藏（不存在返回 null）
     */
    fun getBookmark(bookmarkId: String): Bookmark?

    /**
     * 通过助记符获取收藏（本作用域内跨列表查找）
     */
    fun getBookmarkByMnemonic(mnemonic: Char): Bookmark?

    /**
     * 获取本作用域所有收藏
     */
    fun getAllBookmarks(): List<Bookmark>

    /**
     * 获取某个文件某一行的收藏（gutter 查询，避免全量扫描）
     */
    fun getLineBookmarks(filePath: String, lineNumber: Int): List<Bookmark>

    /**
     * 检查助记符在本作用域是否可用
     */
    fun isMnemonicAvailable(mnemonic: Char): Boolean

    /**
     * 获取本作用域所有已使用的助记符
     */
    fun getUsedMnemonics(): Set<Char>

    /**
     * 更新收藏描述
     */
    fun updateBookmarkDescription(bookmarkId: String, description: String?): Boolean

    /**
     * 更新收藏的助记符与描述
     */
    fun updateBookmarkDetails(bookmarkId: String, mnemonic: Char?, description: String?): Boolean

    /**
     * 删除指定路径的收藏（返回删除数量）
     */
    fun removeBookmarksByPath(path: String, includeChildren: Boolean): Int

    /**
     * 获取本作用域收藏总数
     */
    fun getTotalCount(): Int

    /**
     * 获取所有列表元信息（始终包含默认列表）
     */
    fun getLists(): List<ListInfo>

    /**
     * 判断列表是否存在
     */
    fun hasList(name: String): Boolean

    /**
     * 新建列表
     */
    fun createList(name: String): Boolean

    /**
     * 重命名列表
     */
    fun renameList(oldName: String, newName: String): Boolean

    /**
     * 删除列表（其收藏并入默认列表）
     */
    fun deleteList(name: String): Boolean

    /**
     * 将收藏移动到指定列表（同作用域内移动）
     */
    fun moveToList(bookmarkId: String, listName: String): Boolean

    /**
     * 设置列表排序方式（按列表记忆）
     */
    fun setSortType(listName: String, sortType: SortType): Boolean

    /**
     * 获取列表排序方式
     */
    fun getSortType(listName: String): SortType

    /**
     * 获取列表下的所有收藏（按该列表排序方式排序）
     */
    fun getBookmarksByList(listName: String): List<Bookmark>
}

/**
 * 按作用域解析对应的管理器实例
 */
object FavoritesScopeResolver {

    /**
     * 获取作用域对应的管理器
     *
     * @param project 当前项目（PROJECT 作用域需要；GLOBAL 作用域忽略）
     * @param scope 目标作用域
     */
    @JvmStatic
    fun managerFor(project: Project, scope: FavoriteScope): ScopedBookmarkManager = when (scope) {
        FavoriteScope.PROJECT -> BookmarkManager.getInstance(project)
        FavoriteScope.GLOBAL -> GlobalBookmarkManager.getInstance()
    }
}
