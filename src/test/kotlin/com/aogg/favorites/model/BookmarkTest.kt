package com.aogg.favorites.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Bookmark 数据模型单元测试
 */
class BookmarkTest {

    @Test
    fun `anonymous bookmark flags should be correct`() {
        val bookmark = Bookmark(
            id = "1",
            type = BookmarkType.LINE,
            filePath = "src/Test.php",
            lineNumber = 10,
            mnemonic = null,
            description = null
        )

        assertTrue(bookmark.isAnonymous(), "匿名收藏标记应为 true")
        assertFalse(bookmark.isMnemonic(), "助记符收藏标记应为 false")
        assertTrue(bookmark.isLineBookmark(), "应为行收藏")
        assertFalse(bookmark.isFileBookmark(), "不应为文件收藏")
        assertFalse(bookmark.isFolderBookmark(), "不应为文件夹收藏")
    }

    @Test
    fun `mnemonic bookmark flags should be correct`() {
        val bookmark = Bookmark(
            id = "2",
            type = BookmarkType.FILE,
            filePath = "src/Test.php",
            mnemonic = 'A',
            description = "测试文件"
        )

        assertFalse(bookmark.isAnonymous(), "匿名收藏标记应为 false")
        assertTrue(bookmark.isMnemonic(), "助记符收藏标记应为 true")
        assertFalse(bookmark.isLineBookmark(), "不应为行收藏")
        assertTrue(bookmark.isFileBookmark(), "应为文件收藏")
        assertFalse(bookmark.isFolderBookmark(), "不应为文件夹收藏")
    }

    @Test
    fun `display name should prefer description`() {
        val bookmark = Bookmark(
            id = "3",
            type = BookmarkType.LINE,
            filePath = "src/Test.php",
            lineNumber = 20,
            description = "自定义描述"
        )

        assertEquals("自定义描述", bookmark.getDisplayName(), "描述不为空时应优先显示描述")
    }

    @Test
    fun `display name should fallback to mnemonic`() {
        val bookmark = Bookmark(
            id = "4",
            type = BookmarkType.LINE,
            filePath = "src/Test.php",
            lineNumber = 30,
            mnemonic = '1'
        )

        assertEquals("收藏 [1]", bookmark.getDisplayName(), "无描述但有助记符时应显示助记符")
    }

    @Test
    fun `display name should fallback to line number for line bookmark`() {
        val bookmark = Bookmark(
            id = "5",
            type = BookmarkType.LINE,
            filePath = "src/Test.php",
            lineNumber = 40
        )

        assertEquals("行 40", bookmark.getDisplayName(), "行收藏应显示行号")
    }

    @Test
    fun `display name should fallback to file name for file bookmark`() {
        val bookmark = Bookmark(
            id = "6",
            type = BookmarkType.FILE,
            filePath = "src/path/TestFile.php"
        )

        assertEquals("TestFile.php", bookmark.getDisplayName(), "文件收藏应显示文件名")
    }

    @Test
    fun `display name should fallback to folder name for folder bookmark`() {
        val bookmark = Bookmark(
            id = "7",
            type = BookmarkType.FOLDER,
            filePath = "src/path/sub/FolderName"
        )

        assertEquals("FolderName", bookmark.getDisplayName(), "文件夹收藏应显示文件夹名")
    }
}

