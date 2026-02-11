package com.aogg.favorites.model

import java.io.Serializable

/**
 * 收藏项数据模型
 *
 * @param id 收藏唯一标识符
 * @param type 收藏类型（代码行/文件/文件夹）
 * @param filePath 文件路径（相对于项目根目录）
 * @param lineNumber 行号（仅代码行收藏有效，文件/文件夹收藏为-1）
 * @param mnemonic 助记符（数字0-9或字母A-Z，匿名收藏为null）
 * @param description 描述信息（可选）
 * @param createdTime 创建时间戳
 */
data class Bookmark(
    val id: String,
    val type: BookmarkType,
    val filePath: String,
    val lineNumber: Int = -1,
    val mnemonic: Char? = null,
    val description: String? = null,
    val createdTime: Long = System.currentTimeMillis()
) : Serializable {

    companion object {
        private const val serialVersionUID = 1L
    }

    /**
     * 是否为匿名收藏
     */
    fun isAnonymous(): Boolean = mnemonic == null

    /**
     * 是否为助记符收藏
     */
    fun isMnemonic(): Boolean = mnemonic != null

    /**
     * 是否为代码行收藏
     */
    fun isLineBookmark(): Boolean = type == BookmarkType.LINE

    /**
     * 是否为文件收藏
     */
    fun isFileBookmark(): Boolean = type == BookmarkType.FILE

    /**
     * 是否为文件夹收藏
     */
    fun isFolderBookmark(): Boolean = type == BookmarkType.FOLDER

    /**
     * 获取显示名称
     */
    fun getDisplayName(): String {
        return when {
            description != null && description.isNotBlank() -> description
            mnemonic != null -> "收藏 [$mnemonic]"
            else -> when (type) {
                BookmarkType.LINE -> "行 $lineNumber"
                BookmarkType.FILE -> filePath.substringAfterLast('/')
                BookmarkType.FOLDER -> filePath.substringAfterLast('/')
            }
        }
    }
}
