package com.aogg.favorites.action

import com.aogg.favorites.model.BookmarkType
import com.aogg.favorites.model.FavoriteScope
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito

/**
 * AddActionSupport「目标位置」路径换算纯逻辑单元测试（1.2.0/1.4.x 共享支撑）
 *
 * 说明：
 * - Project/VirtualFile 用 Mockito 打桩（接口 getter，无平台行为），只测纯路径/类型换算
 * - resolveTargetGroup（分组被删回退收藏）依赖 FavoritesSettings 应用级平台服务与项目服务定位器，
 *   纯 JVM 环境无法构造有效的管理器解析，跳过不硬测（见报告）
 */
class AddActionSupportLocationTest {

    private fun project(basePath: String?): Project =
        Mockito.mock(Project::class.java).apply {
            Mockito.`when`(this.basePath).thenReturn(basePath)
        }

    // ==================== relativePathOf ====================

    @Test
    fun `relative path should strip project base with boundary alignment`() {
        val proj = project("/home/u/proj")
        assertEquals("src/A.php", AddActionSupport.relativePathOf(proj, "/home/u/proj/src/A.php"))
        assertEquals("", AddActionSupport.relativePathOf(proj, "/home/u/proj"), "项目根本身换算为空相对路径")
        assertNull(AddActionSupport.relativePathOf(proj, "/home/u/proj-evil/A.php"), "前缀相似但目录边界不对齐不算项目内")
        assertNull(AddActionSupport.relativePathOf(proj, "/home/u/other/A.php"), "项目外返回 null")
    }

    @Test
    fun `relative path should normalize backslashes and handle missing base`() {
        val proj = project("/home/u/proj")
        assertEquals(
            "src/A.php",
            AddActionSupport.relativePathOf(proj, "\\home\\u\\proj\\src\\A.php"),
            "反斜杠统一为正斜杠后换算"
        )
        assertNull(AddActionSupport.relativePathOf(project(null), "/anywhere/A.php"), "无项目根无法换算")
    }

    // ==================== storagePathFor ====================

    @Test
    fun `storage path should follow scope semantics`() {
        val location = AddActionSupport.PendingLocation(
            type = BookmarkType.FILE,
            absolutePath = "/home/u/proj/src/A.php",
            relativePath = "src/A.php",
            lineNumber = -1
        )
        assertEquals("src/A.php", AddActionSupport.storagePathFor(FavoriteScope.PROJECT, location), "项目作用域存相对路径")
        assertEquals(
            "/home/u/proj/src/A.php",
            AddActionSupport.storagePathFor(FavoriteScope.GLOBAL, location),
            "全局作用域存绝对路径"
        )
        val outside = location.copy(relativePath = null)
        assertNull(AddActionSupport.storagePathFor(FavoriteScope.PROJECT, outside), "项目作用域+项目外无法收藏")
        assertEquals(
            "/opt/other.php",
            AddActionSupport.storagePathFor(FavoriteScope.GLOBAL, outside.copy(absolutePath = "/opt/other.php")),
            "全局作用域对项目外位置仍可收藏"
        )
    }

    // ==================== locationOf ====================

    @Test
    fun `location of should detect folder and file types with normalized path`() {
        val folderFile = Mockito.mock(VirtualFile::class.java)
        Mockito.`when`(folderFile.path).thenReturn("C:\\x\\dir")
        Mockito.`when`(folderFile.isDirectory).thenReturn(true)
        val folder = AddActionSupport.locationOf(project("/p"), folderFile, -1)
        assertEquals(BookmarkType.FOLDER, folder.type, "目录判为文件夹收藏")
        assertEquals("C:/x/dir", folder.absolutePath, "绝对路径统一正斜杠")
        assertEquals(-1, folder.lineNumber)

        val normalFile = Mockito.mock(VirtualFile::class.java)
        Mockito.`when`(normalFile.path).thenReturn("/home/u/proj/src/A.php")
        Mockito.`when`(normalFile.isDirectory).thenReturn(false)
        val file = AddActionSupport.locationOf(project("/home/u/proj"), normalFile, 5)
        assertEquals(BookmarkType.FILE, file.type)
        assertEquals("src/A.php", file.relativePath, "项目内文件带相对路径")
        assertEquals(5, file.lineNumber, "行收藏透传 0 基行号")
    }

    // ==================== isAbsolute 兜底工具 ====================

    @Test
    fun `is absolute should delegate to file semantics`() {
        assertTrue(AddActionSupport.isAbsolute("/var/www/a.php"))
        assertTrue(!AddActionSupport.isAbsolute("src/A.php"))
    }
}
