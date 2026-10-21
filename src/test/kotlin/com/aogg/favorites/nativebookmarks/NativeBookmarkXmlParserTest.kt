package com.aogg.favorites.nativebookmarks

import org.jdom.Element
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 原生书签 XML 容错解析器单元测试（纯 org.jdom.Element 构造样例，不依赖 IDE 运行时）
 *
 * 覆盖：
 * - 旧格式（<Bookmark url= line= mnemonic= 属性式，PhpStorm ≤2021.2 书签）
 * - 新格式（<BookmarkState> + option attributes/type + GroupState 分组，2021.3+）
 * - 容错：混入无关元素/坏行号/缺 url 时静默跳过
 * - 路径归一：$PROJECT_DIR$ 宏、项目内绝对路径、项目外路径、相对路径
 * - 助记符解析：裸字符 / LETTER_X / DIGIT_X / DEFAULT→null
 */
class NativeBookmarkXmlParserTest {

    private val basePath = "/work/demo"

    // ==================== 旧格式（属性式） ====================

    @Test
    fun `parse legacy attribute-style bookmark`() {
        val component = Element("component").setAttribute("name", "BookmarksManager")
        val bookmarksOption = Element("option").setAttribute("name", "bookmarks")
        bookmarksOption.addContent(
            Element("Bookmark")
                .setAttribute("url", "file://\$PROJECT_DIR\$/src/a.php")
                .setAttribute("line", "10")
                .setAttribute("mnemonic", "A")
                .setAttribute("description", "入口方法")
        )
        bookmarksOption.addContent(
            Element("Bookmark")
                .setAttribute("url", "file://\$PROJECT_DIR\$/assets")
                .setAttribute("isDirectory", "true")
        )
        component.addContent(bookmarksOption)

        val items = NativeBookmarkXmlParser.parseBookmarksXml(component, basePath)

        assertEquals(2, items.size, "应解析出 2 条原生书签")
        val lineItem = items[0]
        assertEquals("src/a.php", lineItem.relativePath)
        assertEquals(10, lineItem.line)
        assertEquals('A', lineItem.mnemonic)
        assertEquals("入口方法", lineItem.description)
        assertEquals(false, lineItem.isDirectory)

        val dirItem = items[1]
        assertEquals("assets", dirItem.relativePath)
        assertEquals(-1, dirItem.line)
        assertNull(dirItem.mnemonic)
        assertTrue(dirItem.isDirectory, "isDirectory=true 应识别为目录")
    }

    // ==================== 新格式（BookmarkState 结构式） ====================

    @Test
    fun `parse new BookmarkState with group and type`() {
        val state = managerStateWithOneNewBookmark(
            groupName = "核心代码",
            url = "file://\$PROJECT_DIR\$/src/Main.php",
            line = "3",
            type = "LETTER_B",
            description = "主入口"
        )

        val items = NativeBookmarkXmlParser.parseBookmarksXml(state, basePath)

        assertEquals(1, items.size)
        val item = items[0]
        assertEquals("src/Main.php", item.relativePath)
        assertEquals(3, item.line)
        assertEquals('B', item.mnemonic, "type=LETTER_B 应解析出助记符 B")
        assertEquals("核心代码", item.listName, "组名应取最近 GroupState 的 name option")
        assertEquals("主入口", item.description)
        assertTrue(item.isImportable())
    }

    @Test
    fun `parse new BookmarkState digit type and default group`() {
        val state = managerStateWithOneNewBookmark(
            groupName = "demo",
            url = "file://\$PROJECT_DIR\$/src/util.php",
            line = "7",
            type = "DIGIT_0",
            description = null
        )

        val items = NativeBookmarkXmlParser.parseBookmarksXml(state, basePath)
        assertEquals(1, items.size)
        assertEquals('0', items[0].mnemonic)
        // 新版默认组名是项目名，导入时由规划器进"原生书签"兜底列表；解析器原样返回组名
        assertEquals("demo", items[0].listName)
    }

    // ==================== 容错 ====================

    @Test
    fun `skip garbage entries silently`() {
        val component = Element("component").setAttribute("name", "BookmarksManager")

        // 无 url 的书签 → 跳过
        component.addContent(
            Element("option").setAttribute("name", "bookmarks").addContent(
                Element("Bookmark").setAttribute("line", "5")
            )
        )
        // 坏行号 → 按 -1（文件级）处理
        component.addContent(
            Element("option").setAttribute("name", "bookmarks").addContent(
                Element("Bookmark")
                    .setAttribute("url", "file://\$PROJECT_DIR\$/ok.php")
                    .setAttribute("line", "abc")
            )
        )
        // 完全无关的元素 → 忽略
        component.addContent(Element("option").setAttribute("name", "other").addContent(Element("Whatever")))

        val items = NativeBookmarkXmlParser.parseBookmarksXml(component, basePath)

        assertEquals(1, items.size, "只应解析出 1 条有效书签")
        assertEquals("ok.php", items[0].relativePath)
        assertEquals(-1, items[0].line, "坏行号应按 -1 处理")
    }

    // ==================== 路径归一 ====================

    @Test
    fun `resolve relative path variants`() {
        // $PROJECT_DIR$ 宏
        assertEquals(
            "src/a.php",
            NativeBookmarkXmlParser.resolveRelativePath("file://\$PROJECT_DIR\$/src/a.php", basePath)
        )
        // 项目内绝对路径
        assertEquals(
            "src/b.php",
            NativeBookmarkXmlParser.resolveRelativePath("file:///work/demo/src/b.php", basePath)
        )
        // 项目外绝对路径原样保留（仍可跳转）
        assertEquals(
            "/elsewhere/c.php",
            NativeBookmarkXmlParser.resolveRelativePath("file:///elsewhere/c.php", basePath)
        )
        // 已是相对路径
        assertEquals("lib/d.php", NativeBookmarkXmlParser.resolveRelativePath("lib/d.php", basePath))
        // 反斜杠统一
        assertEquals("src/e.php", NativeBookmarkXmlParser.resolveRelativePath("src\\e.php", basePath))
        // 无效输入
        assertNull(NativeBookmarkXmlParser.resolveRelativePath(null, basePath))
        assertNull(NativeBookmarkXmlParser.resolveRelativePath("  ", basePath))
        // $PROJECT_DIR$ 宏但无 basePath → 无法解析
        assertNull(NativeBookmarkXmlParser.resolveRelativePath("file://\$PROJECT_DIR\$/f.php", null))
    }

    // ==================== 助记符解析 ====================

    @Test
    fun `parse mnemonic from raw char and type value`() {
        assertEquals('A', NativeBookmarkXmlParser.parseMnemonic("A"))
        assertEquals('A', NativeBookmarkXmlParser.parseMnemonic("a"))
        assertEquals('5', NativeBookmarkXmlParser.parseMnemonic("5"))
        assertNull(NativeBookmarkXmlParser.parseMnemonic("AB"), "多字符不是助记符")
        assertNull(NativeBookmarkXmlParser.parseMnemonic("!"))
        assertNull(NativeBookmarkXmlParser.parseMnemonic(null))

        assertEquals('C', NativeBookmarkXmlParser.parseMnemonicFromType("LETTER_C"))
        assertEquals('9', NativeBookmarkXmlParser.parseMnemonicFromType("DIGIT_9"))
        assertEquals('D', NativeBookmarkXmlParser.parseMnemonicFromType("D"))
        assertNull(NativeBookmarkXmlParser.parseMnemonicFromType("DEFAULT"), "DEFAULT 不应产生助记符")
        assertNull(NativeBookmarkXmlParser.parseMnemonicFromType(null))
        assertNull(NativeBookmarkXmlParser.parseMnemonicFromType("WHAT_EVER"))
    }

    // ==================== 旧版收藏列表（尽力而为） ====================

    @Test
    fun `parse legacy favorites best-effort`() {
        val component = Element("component").setAttribute("name", "FavoritesProjectState")
        val listElement = Element("list").setAttribute("name", "php收藏")
        listElement.addContent(
            Element("favorite")
                .setAttribute("url", "file://\$PROJECT_DIR\$/src/legacy.php")
                .setAttribute("line", "2")
        )
        component.addContent(listElement)

        val items = NativeBookmarkXmlParser.parseLegacyFavoritesXml(component, basePath)

        assertEquals(1, items.size, "旧收藏列表应尽力解析")
        assertEquals("src/legacy.php", items[0].relativePath)
        assertEquals(2, items[0].line)
        assertEquals("php收藏", items[0].listName)
    }

    // ==================== 样例构造 ====================

    /**
     * 构造新版 BookmarksManager 状态：
     * GroupState > option[name=bookmarks] > BookmarkState > option[attributes]{url,line} + option[type] + option[description]
     */
    private fun managerStateWithOneNewBookmark(
        groupName: String,
        url: String,
        line: String,
        type: String,
        description: String?
    ): Element {
        val attributesOption = Element("option").setAttribute("name", "attributes")
        attributesOption.addContent(
            Element("option").setAttribute("name", "url").setAttribute("value", url)
        )
        attributesOption.addContent(
            Element("option").setAttribute("name", "line").setAttribute("value", line)
        )

        val bookmarkState = Element("BookmarkState")
        bookmarkState.addContent(attributesOption)
        bookmarkState.addContent(
            Element("option").setAttribute("name", "type").setAttribute("value", type)
        )
        if (description != null) {
            bookmarkState.addContent(
                Element("option").setAttribute("name", "description").setAttribute("value", description)
            )
        }

        val bookmarksOption = Element("option").setAttribute("name", "bookmarks")
        bookmarksOption.addContent(bookmarkState)

        val groupState = Element("GroupState")
        groupState.addContent(
            Element("option").setAttribute("name", "name").setAttribute("value", groupName)
        )
        groupState.addContent(
            Element("option").setAttribute("name", "isDefault").setAttribute("value", "false")
        )
        groupState.addContent(bookmarksOption)

        val groupsOption = Element("option").setAttribute("name", "groups")
        groupsOption.addContent(groupState)

        val component = Element("component").setAttribute("name", "BookmarksManager")
        component.addContent(groupsOption)
        return component
    }
}
