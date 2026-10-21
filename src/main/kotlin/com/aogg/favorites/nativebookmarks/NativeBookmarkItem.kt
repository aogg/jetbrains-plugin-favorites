package com.aogg.favorites.nativebookmarks

import com.aogg.favorites.model.Bookmark
import java.util.UUID

/**
 * 原生书签的中间模型（与平台解耦）
 *
 * 通道一（运行时 API）与通道二（XML 容错解析）统一输出本模型，
 * 映射/去重/助记符降级等纯逻辑只依赖本模型，便于单元测试。
 *
 * @param relativePath 项目相对路径（'/' 分隔；无法确定时为空串，将被跳过）
 * @param line 行号（0 基；非行书签为 -1）
 * @param mnemonic 原生助记符（仅 0-9/A-Z，匿名书签为 null）
 * @param listName 原生列表（组）名；null 表示无列表信息（导入时进"原生书签"列表）
 * @param description 原生描述（尽力而为，可为 null）
 * @param isDirectory 是否目录（可定位到 VirtualFile 时才准确；定位不到按文件处理）
 */
data class NativeBookmarkItem(
    val relativePath: String,
    val line: Int = -1,
    val mnemonic: Char? = null,
    val listName: String? = null,
    val description: String? = null,
    val isDirectory: Boolean = false
) {

    /**
     * 去重键：行书签用 (路径+行号)，文件/文件夹收藏用路径
     */
    fun dedupeKey(): String = if (line >= 0) {
        "L|$relativePath|$line"
    } else {
        "P|$relativePath"
    }

    /**
     * 是否具备导入条件（路径可解析）
     */
    fun isImportable(): Boolean = relativePath.isNotBlank()
}

/**
 * 导入规划结果（纯数据，便于测试与增量合并）
 *
 * @param bookmarks 待新增的收藏（已去重、已做助记符降级）
 * @param listsToCreate 需要新建的列表名
 * @param skippedDuplicates 因与现有收藏重复而跳过的原生书签数量
 */
data class ImportPlan(
    val bookmarks: List<Bookmark>,
    val listsToCreate: List<String>,
    val skippedDuplicates: Int
)

/**
 * 导入规划器（纯逻辑，不依赖 IDE 运行时）
 *
 * 规则：
 * - 行号 >= 0 → LINE，否则文件/文件夹（[NativeBookmarkItem.isDirectory] 为 true 时 FOLDER，否则 FILE）
 * - 目标列表：原生列表名存在则同名创建，否则进 [FALLBACK_LIST_NAME]（"原生书签"）列表
 * - 去重：与现有收藏按 (相对路径+行号) 或路径比对，重复跳过；同批次内也去重（可重复执行/增量合并）
 * - 助记符：原生有则带过来，已被占用（或同批次已分配）则降级为匿名收藏
 */
object NativeBookmarkImportPlanner {

    /**
     * 无原生列表信息时使用的兜底列表名
     */
    const val FALLBACK_LIST_NAME = "原生书签"

    /**
     * 生成导入计划
     *
     * @param items 原生书签中间模型
     * @param existingKeys 现有收藏的去重键集合（见 [NativeBookmarkItem.dedupeKey] 同源生成）
     * @param usedMnemonics 现有已占用的助记符集合
     * @param existingListNames 已存在的列表名集合
     */
    fun plan(
        items: List<NativeBookmarkItem>,
        existingKeys: Set<String>,
        usedMnemonics: Set<Char>,
        existingListNames: Set<String>
    ): ImportPlan {
        val keys = existingKeys.toMutableSet()
        val usedChars = usedMnemonics.toMutableSet()
        val listsToCreate = LinkedHashSet<String>()
        val newBookmarks = ArrayList<Bookmark>()
        var skipped = 0

        for (item in items) {
            if (!item.isImportable()) {
                skipped++
                continue
            }
            val key = item.dedupeKey()
            if (!keys.add(key)) {
                skipped++
                continue
            }

            // 目标列表：原生有列表名则同名创建，否则进兜底列表
            val listName = item.listName?.trim()?.takeIf { it.isNotEmpty() } ?: FALLBACK_LIST_NAME
            if (listName !in existingListNames) {
                listsToCreate.add(listName)
            }

            // 助记符：原生有则带过来，已被占用（或同批次已分配）则降级为匿名收藏
            val rawMnemonic = item.mnemonic
            val mnemonic = if (rawMnemonic != null && Bookmark.isValidMnemonic(rawMnemonic) && rawMnemonic !in usedChars) {
                usedChars.add(rawMnemonic)
                rawMnemonic
            } else {
                null
            }
            val type = when {
                item.line >= 0 -> com.aogg.favorites.model.BookmarkType.LINE
                item.isDirectory -> com.aogg.favorites.model.BookmarkType.FOLDER
                else -> com.aogg.favorites.model.BookmarkType.FILE
            }

            newBookmarks += Bookmark(
                id = UUID.randomUUID().toString(),
                type = type,
                filePath = item.relativePath,
                lineNumber = if (item.line >= 0) item.line else -1,
                mnemonic = mnemonic,
                description = item.description?.trim()?.takeIf { it.isNotEmpty() },
                listName = listName
            )
        }

        return ImportPlan(newBookmarks, listsToCreate.toList(), skipped)
    }

    /**
     * 由既有收藏生成去重键集合（导入服务与测试共用）
     */
    fun existingKeysOf(bookmarks: List<Bookmark>): Set<String> =
        bookmarks.map { bookmark ->
            if (bookmark.isLineBookmark()) {
                "L|${bookmark.filePath}|${bookmark.lineNumber}"
            } else {
                "P|${bookmark.filePath}"
            }
        }.toSet()
}
