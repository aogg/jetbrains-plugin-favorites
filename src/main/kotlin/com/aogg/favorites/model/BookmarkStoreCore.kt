package com.aogg.favorites.model

import com.aogg.favorites.helper.BookmarkPersistence
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * 单作用域收藏存储内核（共享内核，1.2.0 从 BookmarkManager 抽取）
 *
 * 负责一个作用域内的收藏增删改查、收藏列表（List）管理、索引维护与持久化读写：
 * - 项目作用域：[BookmarkManager]（PROJECT 服务）委托本内核，持久化走 favorites.xml；
 * - 全局作用域：[GlobalBookmarkManager]（APP 服务）委托本内核，持久化走 favorites.global.xml；
 * - 两者共用同一份逻辑，避免 683 行核心代码复制漂移，行为完全一致。
 *
 * 列表模型约束：
 * - 助记符在本作用域内全局唯一（跨列表），跳转快捷键按助记符跨列表查找
 * - 默认列表 [Bookmark.DEFAULT_LIST_NAME] 不可删除/重命名
 * - 删除列表时其收藏全部并入默认列表
 *
 * @param persistenceProvider 持久化提供器：懒加载首次访问时才解析真实存储，
 *                            便于测试环境先注入假存储再触发加载
 */
class BookmarkStoreCore(private val persistenceProvider: () -> BookmarkPersistence) {

    @Volatile
    private var loaded = false
    private val loadLock = Any()

    // 所有收藏（按ID索引）
    private val bookmarks: MutableMap<String, Bookmark> = ConcurrentHashMap()

    // 匿名收藏列表
    private val anonymousBookmarks: MutableList<Bookmark> = Collections.synchronizedList(mutableListOf())

    // 助记符收藏映射（助记符 -> 收藏ID，跨列表全局唯一）
    private val mnemonicMap: MutableMap<Char, String> = ConcurrentHashMap()

    // 文件收藏列表（文件路径 -> 收藏ID列表）
    private val fileBookmarks: MutableMap<String, MutableList<String>> = ConcurrentHashMap()

    // 文件夹收藏列表（文件夹路径 -> 收藏ID列表）
    private val folderBookmarks: MutableMap<String, MutableList<String>> = ConcurrentHashMap()

    // 行收藏索引（文件路径 -> 行号 -> 收藏ID列表），用于提升 gutter 查询性能
    private val lineBookmarks: MutableMap<String, MutableMap<Int, MutableList<String>>> = ConcurrentHashMap()

    // 收藏列表元信息（始终包含默认列表；sortType 按列表记忆并持久化）
    private val lists: MutableList<ListInfo> = Collections.synchronizedList(mutableListOf(ListInfo(Bookmark.DEFAULT_LIST_NAME)))

    /**
     * 确保持久化数据已加载（只加载一次，避免频繁I/O与全量重建索引）
     */
    fun ensureLoaded() {
        if (loaded) return
        synchronized(loadLock) {
            if (loaded) return
            loadFromStorage()
            loaded = true
        }
    }

    // ==================== 收藏基础操作 ====================

    /**
     * 添加收藏
     *
     * @param bookmark 收藏项
     * @return 是否添加成功（如果助记符已存在则返回false）
     */
    fun addBookmark(bookmark: Bookmark): Boolean {
        ensureLoaded()
        // 检查助记符冲突（跨列表全局唯一）
        if (bookmark.mnemonic != null) {
            if (mnemonicMap.containsKey(bookmark.mnemonic)) {
                return false
            }
            mnemonicMap[bookmark.mnemonic] = bookmark.id
        }

        // 添加到主列表
        bookmarks[bookmark.id] = bookmark

        // 根据类型添加到对应列表
        indexBookmark(bookmark)

        // 保存到持久化存储
        saveToStorage()

        return true
    }

    /**
     * 删除收藏
     *
     * @param bookmarkId 收藏ID
     * @return 是否删除成功
     */
    fun removeBookmark(bookmarkId: String): Boolean {
        ensureLoaded()
        val bookmark = bookmarks.remove(bookmarkId) ?: return false

        // 从助记符映射中移除
        bookmark.mnemonic?.let { mnemonicMap.remove(it) }

        unindexBookmark(bookmark)

        // 保存到持久化存储
        saveToStorage()

        return true
    }

    /**
     * 获取收藏
     *
     * @param bookmarkId 收藏ID
     * @return 收藏项，如果不存在返回null
     */
    fun getBookmark(bookmarkId: String): Bookmark? {
        ensureLoaded()
        return bookmarks[bookmarkId]
    }

    /**
     * 通过助记符获取收藏（跨列表查找）
     *
     * @param mnemonic 助记符
     * @return 收藏项，如果不存在返回null
     */
    fun getBookmarkByMnemonic(mnemonic: Char): Bookmark? {
        ensureLoaded()
        val bookmarkId = mnemonicMap[mnemonic] ?: return null
        return bookmarks[bookmarkId]
    }

    /**
     * 获取所有收藏
     *
     * @return 所有收藏的列表
     */
    fun getAllBookmarks(): List<Bookmark> {
        ensureLoaded()
        return bookmarks.values.toList()
    }

    /**
     * 获取所有匿名收藏
     *
     * @return 匿名收藏列表
     */
    fun getAnonymousBookmarks(): List<Bookmark> {
        ensureLoaded()
        return anonymousBookmarks.toList()
    }

    /**
     * 获取所有助记符收藏
     *
     * @return 助记符收藏列表
     */
    fun getMnemonicBookmarks(): List<Bookmark> {
        ensureLoaded()
        return bookmarks.values.filter { it.isMnemonic() }
    }

    /**
     * 获取文件的所有收藏
     *
     * @param filePath 文件路径
     * @return 该文件的收藏列表
     */
    fun getBookmarksByFile(filePath: String): List<Bookmark> {
        ensureLoaded()
        val bookmarkIds = fileBookmarks[filePath] ?: return emptyList()
        return bookmarkIds.mapNotNull { bookmarks[it] }
    }

    /**
     * 获取文件夹的所有收藏
     *
     * @param folderPath 文件夹路径
     * @return 该文件夹的收藏列表
     */
    fun getBookmarksByFolder(folderPath: String): List<Bookmark> {
        ensureLoaded()
        val bookmarkIds = folderBookmarks[folderPath] ?: return emptyList()
        return bookmarkIds.mapNotNull { bookmarks[it] }
    }

    /**
     * 获取某个文件某一行的收藏（用于 gutter 查询，避免全量扫描）
     */
    fun getLineBookmarks(filePath: String, lineNumber: Int): List<Bookmark> {
        ensureLoaded()
        val ids = lineBookmarks[filePath]?.get(lineNumber) ?: return emptyList()
        return ids.mapNotNull { bookmarks[it] }
    }

    /**
     * 检查助记符是否可用
     *
     * @param mnemonic 助记符
     * @return 是否可用
     */
    fun isMnemonicAvailable(mnemonic: Char): Boolean {
        ensureLoaded()
        return !mnemonicMap.containsKey(mnemonic)
    }

    /**
     * 获取所有已使用的助记符
     *
     * @return 已使用的助记符集合
     */
    fun getUsedMnemonics(): Set<Char> {
        ensureLoaded()
        return mnemonicMap.keys.toSet()
    }

    /**
     * 获取所有可用的助记符
     *
     * @return 可用的助记符列表（0-9, A-Z）
     */
    fun getAvailableMnemonics(): List<Char> {
        ensureLoaded()
        val allMnemonics = (('0'..'9') + ('A'..'Z')).toSet()
        val usedMnemonics = getUsedMnemonics()
        return (allMnemonics - usedMnemonics).sorted()
    }

    /**
     * 更新收藏描述
     *
     * @param bookmarkId 收藏ID
     * @param description 新描述
     * @return 是否更新成功
     */
    fun updateBookmarkDescription(bookmarkId: String, description: String?): Boolean {
        ensureLoaded()
        val bookmark = bookmarks[bookmarkId] ?: return false
        val updatedBookmark = bookmark.copy(description = description)
        bookmarks[bookmarkId] = updatedBookmark

        // 更新列表中的引用
        if (bookmark.isAnonymous()) {
            val index = anonymousBookmarks.indexOfFirst { it.id == bookmark.id }
            if (index >= 0) {
                anonymousBookmarks[index] = updatedBookmark
            }
        }

        // 保存到持久化存储
        saveToStorage()

        return true
    }

    /**
     * 更新收藏的助记符与描述（用于工具窗口"设置助记符"）
     *
     * @param bookmarkId 收藏ID
     * @param mnemonic 新助记符（null 表示移除助记符）
     * @param description 新描述
     * @return 是否更新成功（目标助记符已被其它收藏占用时返回false）
     */
    fun updateBookmarkDetails(bookmarkId: String, mnemonic: Char?, description: String?): Boolean {
        ensureLoaded()
        val bookmark = bookmarks[bookmarkId] ?: return false
        if (mnemonic != null && !Bookmark.isValidMnemonic(mnemonic)) return false
        if (mnemonic != null && mnemonic != bookmark.mnemonic && mnemonicMap.containsKey(mnemonic)) return false

        val updated = bookmark.copy(mnemonic = mnemonic, description = description)
        replaceBookmarkInternal(bookmark, updated)
        saveToStorage()
        return true
    }

    /**
     * 删除指定路径的收藏
     *
     * @param path 路径（项目作用域为相对路径，全局作用域为绝对路径）
     * @param includeChildren 是否包含子路径（用于文件夹删除/移出项目）
     * @return 删除数量
     */
    fun removeBookmarksByPath(path: String, includeChildren: Boolean): Int {
        ensureLoaded()
        val toRemove = bookmarks.values.filter { bm ->
            bm.filePath == path || (includeChildren && bm.filePath.startsWith("$path/"))
        }.map { it.id }

        if (toRemove.isEmpty()) return 0

        toRemove.forEach { removeBookmarkInternal(it) }
        saveToStorage()
        return toRemove.size
    }

    /**
     * 更新收藏路径（用于文件/文件夹重命名、移动）
     *
     * @param oldPath 旧路径
     * @param newPath 新路径
     * @param includeChildren 是否包含子路径（用于文件夹）
     * @return 更新数量
     */
    fun updateBookmarksPath(oldPath: String, newPath: String, includeChildren: Boolean): Int {
        ensureLoaded()
        val candidates = bookmarks.values.filter { bm ->
            bm.filePath == oldPath || (includeChildren && bm.filePath.startsWith("$oldPath/"))
        }
        if (candidates.isEmpty()) return 0

        for (bm in candidates) {
            val updatedPath = if (bm.filePath == oldPath) {
                newPath
            } else {
                // includeChildren 分支：保持子路径后缀不变
                newPath + bm.filePath.removePrefix(oldPath)
            }
            val updated = bm.copy(filePath = updatedPath)
            replaceBookmarkInternal(bm, updated)
        }

        saveToStorage()
        return candidates.size
    }

    /**
     * 清理某文件的无效行收藏（行号超出范围）
     *
     * @param filePath 文件路径
     * @param lineCount 文件总行数
     * @return 删除数量
     */
    fun cleanupInvalidLineBookmarks(filePath: String, lineCount: Int): Int {
        ensureLoaded()
        if (lineCount <= 0) return 0
        val toRemove = bookmarks.values.filter { bm ->
            bm.isLineBookmark() && bm.filePath == filePath && (bm.lineNumber < 0 || bm.lineNumber >= lineCount)
        }.map { it.id }
        if (toRemove.isEmpty()) return 0
        toRemove.forEach { removeBookmarkInternal(it) }
        saveToStorage()
        return toRemove.size
    }

    /**
     * 清空所有收藏（同时重置列表元信息为仅默认列表）
     */
    fun clearAll() {
        bookmarks.clear()
        anonymousBookmarks.clear()
        mnemonicMap.clear()
        fileBookmarks.clear()
        folderBookmarks.clear()
        lineBookmarks.clear()
        synchronized(lists) {
            lists.clear()
            lists.add(ListInfo(Bookmark.DEFAULT_LIST_NAME))
        }
    }

    /**
     * 获取收藏总数
     */
    fun getTotalCount(): Int = bookmarks.size

    /**
     * 获取匿名收藏数量
     */
    fun getAnonymousCount(): Int = anonymousBookmarks.size

    /**
     * 获取助记符收藏数量
     */
    fun getMnemonicCount(): Int = mnemonicMap.size

    // ==================== 收藏列表（List）管理 ====================

    /**
     * 获取所有列表元信息（始终包含默认列表，默认列表排在首位）
     */
    fun getLists(): List<ListInfo> {
        ensureLoaded()
        synchronized(lists) {
            ensureDefaultListLocked()
            return lists.toList()
        }
    }

    /**
     * 判断列表是否存在
     */
    fun hasList(name: String): Boolean {
        ensureLoaded()
        synchronized(lists) {
            return lists.any { it.name == name }
        }
    }

    /**
     * 新建列表
     *
     * @param name 列表名称（去空格后不能为空，且不能与现有列表重名）
     * @return 是否创建成功
     */
    fun createList(name: String): Boolean {
        ensureLoaded()
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        synchronized(lists) {
            if (lists.any { it.name == trimmed }) return false
            lists.add(ListInfo(trimmed))
        }
        saveToStorage()
        return true
    }

    /**
     * 重命名列表（默认列表不可重命名；其下所有收藏同步迁移）
     *
     * @return 是否重命名成功
     */
    fun renameList(oldName: String, newName: String): Boolean {
        ensureLoaded()
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return false
        if (oldName == Bookmark.DEFAULT_LIST_NAME) return false
        if (oldName == trimmed) return false
        synchronized(lists) {
            val index = lists.indexOfFirst { it.name == oldName }
            if (index < 0) return false
            if (lists.any { it.name == trimmed }) return false
            lists[index] = lists[index].copy(name = trimmed)
        }
        bookmarks.values.filter { it.listName == oldName }.forEach { bm ->
            replaceBookmarkInternal(bm, bm.copy(listName = trimmed))
        }
        saveToStorage()
        return true
    }

    /**
     * 删除列表（默认列表不可删除；其收藏全部并入默认列表）
     *
     * @return 是否删除成功
     */
    fun deleteList(name: String): Boolean {
        ensureLoaded()
        if (name == Bookmark.DEFAULT_LIST_NAME) return false
        synchronized(lists) {
            val index = lists.indexOfFirst { it.name == name }
            if (index < 0) return false
            lists.removeAt(index)
        }
        bookmarks.values.filter { it.listName == name }.forEach { bm ->
            replaceBookmarkInternal(bm, bm.copy(listName = Bookmark.DEFAULT_LIST_NAME))
        }
        synchronized(lists) {
            ensureDefaultListLocked()
        }
        saveToStorage()
        return true
    }

    /**
     * 将收藏移动到指定列表（同一作用域内移动）
     *
     * @param bookmarkId 收藏ID
     * @param listName 目标列表名（必须已存在）
     * @return 是否移动成功
     */
    fun moveToList(bookmarkId: String, listName: String): Boolean {
        ensureLoaded()
        if (!hasList(listName)) return false
        val bookmark = bookmarks[bookmarkId] ?: return false
        if (bookmark.listName == listName) return true
        replaceBookmarkInternal(bookmark, bookmark.copy(listName = listName))
        saveToStorage()
        return true
    }

    /**
     * 设置列表的排序方式（按列表记忆并持久化）
     *
     * @return 是否发生变化
     */
    fun setSortType(listName: String, sortType: SortType): Boolean {
        ensureLoaded()
        var changed = false
        synchronized(lists) {
            ensureDefaultListLocked()
            val index = lists.indexOfFirst { it.name == listName }
            if (index >= 0 && lists[index].sortType != sortType) {
                lists[index] = lists[index].copy(sortType = sortType)
                changed = true
            }
        }
        if (changed) {
            saveToStorage()
        }
        return changed
    }

    /**
     * 获取指定列表的排序方式（列表不存在时返回默认值 ADDED）
     */
    fun getSortType(listName: String): SortType {
        ensureLoaded()
        synchronized(lists) {
            return lists.firstOrNull { it.name == listName }?.sortType ?: SortType.ADDED
        }
    }

    /**
     * 获取指定列表下的所有收藏（按该列表的排序方式排序）
     */
    fun getBookmarksByList(listName: String): List<Bookmark> {
        ensureLoaded()
        val sortType = getSortType(listName)
        return sortBookmarks(bookmarks.values.filter { it.listName == listName }, sortType)
    }

    /**
     * 按排序方式排序收藏集合
     */
    private fun sortBookmarks(bookmarksToSort: Collection<Bookmark>, sortType: SortType): List<Bookmark> =
        when (sortType) {
            SortType.ADDED -> bookmarksToSort.sortedBy { it.createdTime }
            SortType.DESCRIPTION -> bookmarksToSort.sortedWith(
                compareBy({ (it.description ?: "").lowercase(Locale.ROOT) }, { it.createdTime })
            )
            SortType.LOCATION -> bookmarksToSort.sortedWith(
                compareBy({ it.filePath }, { it.lineNumber })
            )
        }

    // ==================== 持久化 ====================

    /**
     * 从持久化存储加载收藏（旧数据无 listName 时自动归入默认列表）
     */
    private fun loadFromStorage() {
        val helper = persistenceProvider()
        val (loadedBookmarks, loadedLists) = helper.loadAll()

        // 清空现有数据
        clearAll()

        // 恢复列表元信息（缺失时兜底默认列表）
        synchronized(lists) {
            loadedLists.forEach { info ->
                if (info.name.isNotBlank() && lists.none { it.name == info.name }) {
                    lists.add(info)
                }
            }
            ensureDefaultListLocked()
        }

        loadedBookmarks.forEach { bookmark ->
            // 旧数据/空列表名迁移到默认列表
            val listName = bookmark.listName.trim().ifEmpty { Bookmark.DEFAULT_LIST_NAME }
            val fixed = if (listName == bookmark.listName) bookmark else bookmark.copy(listName = listName)

            // 自愈：收藏归属了不存在的列表时自动补建元信息，绝不丢数据
            synchronized(lists) {
                if (lists.none { it.name == listName }) {
                    lists.add(ListInfo(listName))
                }
            }

            bookmarks[fixed.id] = fixed
            val mnemonic = fixed.mnemonic
            if (mnemonic != null && Bookmark.isValidMnemonic(mnemonic) && !mnemonicMap.containsKey(mnemonic)) {
                mnemonicMap[mnemonic] = fixed.id
            }
            indexBookmark(fixed)
        }
    }

    /**
     * 保存收藏到持久化存储
     */
    private fun saveToStorage() {
        val snapshot = bookmarks.values.toList()
        val listsSnapshot = synchronized(lists) { lists.toList() }
        persistenceProvider().saveAll(snapshot, listsSnapshot)
    }

    /**
     * 为收藏建立类型索引（匿名列表 / 文件 / 文件夹 / 行索引）
     */
    private fun indexBookmark(bookmark: Bookmark) {
        if (bookmark.isAnonymous()) {
            anonymousBookmarks.add(bookmark)
        }

        when {
            bookmark.isFileBookmark() -> {
                fileBookmarks.getOrPut(bookmark.filePath) { Collections.synchronizedList(mutableListOf()) }.add(bookmark.id)
            }
            bookmark.isFolderBookmark() -> {
                folderBookmarks.getOrPut(bookmark.filePath) { Collections.synchronizedList(mutableListOf()) }.add(bookmark.id)
            }
            bookmark.isLineBookmark() -> {
                val lineMap = lineBookmarks.getOrPut(bookmark.filePath) { ConcurrentHashMap() }
                lineMap.getOrPut(bookmark.lineNumber) { Collections.synchronizedList(mutableListOf()) }.add(bookmark.id)
            }
        }
    }

    /**
     * 移除收藏的类型索引
     */
    private fun unindexBookmark(bookmark: Bookmark) {
        if (bookmark.isAnonymous()) {
            anonymousBookmarks.removeIf { it.id == bookmark.id }
        }

        if (bookmark.isFileBookmark()) {
            fileBookmarks[bookmark.filePath]?.removeIf { it == bookmark.id }
        } else if (bookmark.isFolderBookmark()) {
            folderBookmarks[bookmark.filePath]?.removeIf { it == bookmark.id }
        } else if (bookmark.isLineBookmark()) {
            lineBookmarks[bookmark.filePath]?.get(bookmark.lineNumber)?.removeIf { it == bookmark.id }
        }
    }

    /**
     * 原地替换收藏（保留 id，更新主表/匿名列表/助记符索引/类型索引）
     */
    private fun replaceBookmarkInternal(oldBookmark: Bookmark, newBookmark: Bookmark) {
        // 主表替换
        bookmarks[newBookmark.id] = newBookmark

        // 更新匿名列表里的对象引用（使用id匹配，避免 data class equals 误判）
        if (oldBookmark.isAnonymous()) {
            val idx = anonymousBookmarks.indexOfFirst { it.id == oldBookmark.id }
            if (idx >= 0) {
                anonymousBookmarks[idx] = newBookmark
            }
        }

        // 同步助记符索引：旧助记符释放（仅当仍指向本收藏）、新助记符指向本收藏。
        // 否则"设置助记符"A→B 后 mnemonicMap 残留 A 的旧映射，导致 A 永远不可用、
        // getBookmarkByMnemonic('A') 返回已改为 B 的收藏（1.1.x 遗留缺陷，随单测修复）。
        oldBookmark.mnemonic?.let { if (mnemonicMap[it] == oldBookmark.id) mnemonicMap.remove(it) }
        newBookmark.mnemonic?.let { mnemonicMap[it] = newBookmark.id }

        // 更新索引
        unindexBookmark(oldBookmark)
        indexBookmark(newBookmark)
    }

    /**
     * 删除收藏（内部批量路径：不逐次落盘，由调用方统一 save）
     */
    private fun removeBookmarkInternal(bookmarkId: String): Boolean {
        val bookmark = bookmarks.remove(bookmarkId) ?: return false
        bookmark.mnemonic?.let { mnemonicMap.remove(it) }
        unindexBookmark(bookmark)
        return true
    }

    /**
     * 确保默认列表存在（调用方需持有 lists 锁）
     */
    private fun ensureDefaultListLocked() {
        if (lists.none { it.name == Bookmark.DEFAULT_LIST_NAME }) {
            lists.add(0, ListInfo(Bookmark.DEFAULT_LIST_NAME))
        }
    }
}
