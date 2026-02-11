package com.aogg.favorites.model

import com.aogg.favorites.helper.BookmarkStorageHelper
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * 收藏管理器（单例）
 * 负责收藏的增删改查、持久化存储和助记符冲突检测
 */
@Service(Service.Level.PROJECT)
class BookmarkManager(private val project: Project) {

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

    private val storageHelper = BookmarkStorageHelper.getInstance(project)

    @Volatile
    private var loaded = false
    private val loadLock = Any()

    // 所有收藏列表（按ID索引）
    private val bookmarks: MutableMap<String, Bookmark> = ConcurrentHashMap()

    // 匿名收藏列表
    private val anonymousBookmarks: MutableList<Bookmark> = Collections.synchronizedList(mutableListOf())

    // 助记符收藏映射（助记符 -> 收藏ID）
    private val mnemonicMap: MutableMap<Char, String> = ConcurrentHashMap()

    // 文件收藏列表（文件路径 -> 收藏ID列表）
    private val fileBookmarks: MutableMap<String, MutableList<String>> = ConcurrentHashMap()

    // 文件夹收藏列表（文件夹路径 -> 收藏ID列表）
    private val folderBookmarks: MutableMap<String, MutableList<String>> = ConcurrentHashMap()

    // 行收藏索引（文件路径 -> 行号 -> 收藏ID列表），用于提升 gutter 查询性能
    private val lineBookmarks: MutableMap<String, MutableMap<Int, MutableList<String>>> = ConcurrentHashMap()

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(loadLock) {
            if (loaded) return
            loadFromStorage()
            loaded = true
        }
    }

    /**
     * 添加收藏
     *
     * @param bookmark 收藏项
     * @return 是否添加成功（如果助记符已存在则返回false）
     */
    fun addBookmark(bookmark: Bookmark): Boolean {
        ensureLoaded()
        // 检查助记符冲突
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
     * 通过助记符获取收藏
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
            val index = anonymousBookmarks.indexOf(bookmark)
            if (index >= 0) {
                anonymousBookmarks[index] = updatedBookmark
            }
        }

        // 保存到持久化存储
        saveToStorage()

        return true
    }

    /**
     * 删除指定路径的收藏
     *
     * @param path 相对路径
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
     * @param oldPath 旧相对路径
     * @param newPath 新相对路径
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
     * @param filePath 相对文件路径
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
     * 清空所有收藏
     */
    fun clearAll() {
        bookmarks.clear()
        anonymousBookmarks.clear()
        mnemonicMap.clear()
        fileBookmarks.clear()
        folderBookmarks.clear()
        lineBookmarks.clear()
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

    /**
     * 从持久化存储加载收藏
     */
    private fun loadFromStorage() {
        val (anonymousList, mnemonicMap) = storageHelper.loadBookmarks()

        // 清空现有数据
        clearAll()

        // 加载匿名收藏
        anonymousList.forEach { bookmark ->
            bookmarks[bookmark.id] = bookmark
            indexBookmark(bookmark)
        }

        // 加载助记符收藏
        mnemonicMap.forEach { (mnemonic, bookmark) ->
            bookmarks[bookmark.id] = bookmark
            this.mnemonicMap[mnemonic] = bookmark.id
            indexBookmark(bookmark)
        }
    }

    /**
     * 保存收藏到持久化存储
     */
    private fun saveToStorage() {
        val anonymousList = anonymousBookmarks.toList()
        val mnemonicMap = mnemonicMap.mapValues { bookmarks[it.value]!! }
            .filterKeys { it in '0'..'9' || it in 'A'..'Z' }
        storageHelper.saveBookmarks(anonymousList, mnemonicMap)
    }

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

        // 更新索引
        unindexBookmark(oldBookmark)
        indexBookmark(newBookmark)
    }

    private fun removeBookmarkInternal(bookmarkId: String): Boolean {
        val bookmark = bookmarks.remove(bookmarkId) ?: return false
        bookmark.mnemonic?.let { mnemonicMap.remove(it) }
        unindexBookmark(bookmark)
        return true
    }
}
