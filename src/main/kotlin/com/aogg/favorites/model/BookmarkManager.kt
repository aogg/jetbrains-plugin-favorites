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
            // 初始化时加载持久化数据
            manager.loadFromStorage()
            return manager
        }
    }

    private val storageHelper = BookmarkStorageHelper.getInstance(project)

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

    /**
     * 添加收藏
     *
     * @param bookmark 收藏项
     * @return 是否添加成功（如果助记符已存在则返回false）
     */
    fun addBookmark(bookmark: Bookmark): Boolean {
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
        when {
            bookmark.isAnonymous() -> anonymousBookmarks.add(bookmark)
            bookmark.isFileBookmark() -> {
                fileBookmarks.getOrPut(bookmark.filePath) { mutableListOf() }.add(bookmark.id)
            }
            bookmark.isFolderBookmark() -> {
                folderBookmarks.getOrPut(bookmark.filePath) { mutableListOf() }.add(bookmark.id)
            }
        }

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
        val bookmark = bookmarks.remove(bookmarkId) ?: return false

        // 从助记符映射中移除
        bookmark.mnemonic?.let { mnemonicMap.remove(it) }

        // 从匿名收藏列表中移除
        if (bookmark.isAnonymous()) {
            anonymousBookmarks.remove(bookmark)
        }

        // 从文件/文件夹收藏列表中移除
        if (bookmark.isFileBookmark()) {
            fileBookmarks[bookmark.filePath]?.remove(bookmarkId)
        } else if (bookmark.isFolderBookmark()) {
            folderBookmarks[bookmark.filePath]?.remove(bookmarkId)
        }

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
        return bookmarks[bookmarkId]
    }

    /**
     * 通过助记符获取收藏
     *
     * @param mnemonic 助记符
     * @return 收藏项，如果不存在返回null
     */
    fun getBookmarkByMnemonic(mnemonic: Char): Bookmark? {
        val bookmarkId = mnemonicMap[mnemonic] ?: return null
        return bookmarks[bookmarkId]
    }

    /**
     * 获取所有收藏
     *
     * @return 所有收藏的列表
     */
    fun getAllBookmarks(): List<Bookmark> {
        return bookmarks.values.toList()
    }

    /**
     * 获取所有匿名收藏
     *
     * @return 匿名收藏列表
     */
    fun getAnonymousBookmarks(): List<Bookmark> {
        return anonymousBookmarks.toList()
    }

    /**
     * 获取所有助记符收藏
     *
     * @return 助记符收藏列表
     */
    fun getMnemonicBookmarks(): List<Bookmark> {
        return bookmarks.values.filter { it.isMnemonic() }
    }

    /**
     * 获取文件的所有收藏
     *
     * @param filePath 文件路径
     * @return 该文件的收藏列表
     */
    fun getBookmarksByFile(filePath: String): List<Bookmark> {
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
        val bookmarkIds = folderBookmarks[folderPath] ?: return emptyList()
        return bookmarkIds.mapNotNull { bookmarks[it] }
    }

    /**
     * 检查助记符是否可用
     *
     * @param mnemonic 助记符
     * @return 是否可用
     */
    fun isMnemonicAvailable(mnemonic: Char): Boolean {
        return !mnemonicMap.containsKey(mnemonic)
    }

    /**
     * 获取所有已使用的助记符
     *
     * @return 已使用的助记符集合
     */
    fun getUsedMnemonics(): Set<Char> {
        return mnemonicMap.keys.toSet()
    }

    /**
     * 获取所有可用的助记符
     *
     * @return 可用的助记符列表（0-9, A-Z）
     */
    fun getAvailableMnemonics(): List<Char> {
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
     * 清空所有收藏
     */
    fun clearAll() {
        bookmarks.clear()
        anonymousBookmarks.clear()
        mnemonicMap.clear()
        fileBookmarks.clear()
        folderBookmarks.clear()
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
            anonymousBookmarks.add(bookmark)
            if (bookmark.isFileBookmark()) {
                fileBookmarks.getOrPut(bookmark.filePath) { mutableListOf() }.add(bookmark.id)
            } else if (bookmark.isFolderBookmark()) {
                folderBookmarks.getOrPut(bookmark.filePath) { mutableListOf() }.add(bookmark.id)
            }
        }

        // 加载助记符收藏
        mnemonicMap.forEach { (mnemonic, bookmark) ->
            bookmarks[bookmark.id] = bookmark
            this.mnemonicMap[mnemonic] = bookmark.id
            if (bookmark.isFileBookmark()) {
                fileBookmarks.getOrPut(bookmark.filePath) { mutableListOf() }.add(bookmark.id)
            } else if (bookmark.isFolderBookmark()) {
                folderBookmarks.getOrPut(bookmark.filePath) { mutableListOf() }.add(bookmark.id)
            }
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
}
