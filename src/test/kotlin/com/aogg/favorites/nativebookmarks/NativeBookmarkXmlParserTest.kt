package com.aogg.favorites.nativebookmarks

import com.aogg.favorites.helper.BookmarkPathMacros
import org.jdom.Element
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
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
 * - 路径宏（1.7.3）：$APPLICATION_CONFIG_DIR$ 等平台宏在导入期展开、历史坏数据（项目根+宏混拼/反斜杠）
 *   不再产出嵌套盘符或项目相对路径、Windows 盘符归约大小写不敏感；平台宏可展开时额外断言「导入路径不残留宏」
 * - 助记符解析：裸字符 / LETTER_X / DIGIT_X / DEFAULT→null
 */
class NativeBookmarkXmlParserTest {

    private val basePath = "/work/demo"

    /** 数据库控制台收藏 uuid（取自用户真实历史坏数据） */
    private val consoleUuid = "34598bcc-3cf7-4eb6-a40c-af84cc1b20f6"

    /** Windows 盘符前缀（断言展开结果已是绝对路径） */
    private val drivePrefix = Regex("^[A-Za-z]:/")

    /** 嵌套盘符（完整展开后出现第二个盘符段=无效路径，如 D:/proj/C:/Users/...） */
    private val nestedDrive = Regex("^[A-Za-z]:/.*[A-Za-z]:/")

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

    // ==================== 路径宏（1.7.3 导入期展开） ====================

    @Test
    fun `resolve application config dir macro should keep usable path`() {
        // 报障场景：导入的原生书签是数据库控制台 console.sql，url 带未展开的 $APPLICATION_CONFIG_DIR$
        val raw = "file://\$APPLICATION_CONFIG_DIR\$/consoles/$consoleUuid/console.sql"

        val resolved = NativeBookmarkXmlParser.resolveRelativePath(raw, basePath)

        assertNotNull(resolved, "平台宏收藏不应被丢弃")
        val path = requireNotNull(resolved)
        // 回归：不得再被当成项目内相对路径（旧 bug 会得到「项目根 + 未展开宏」这种必然不存在的路径）
        assertFalse(path.startsWith("$basePath/"), "不得拼成项目相对路径：$path")
        assertFalse(path.startsWith("file:"), "不得保留 file:// 协议前缀：$path")
        assertFalse(path.contains('\\'), "结果应为正斜杠风格：$path")
        assertTrue(path.endsWith("consoles/$consoleUuid/console.sql"), "应保留控制台 sql 尾部路径：$path")
        if (platformMacroExpandable(raw, basePath)) {
            // 平台可用（IDE 运行时/带平台路径的测试 JVM）：导入路径必须已展开成绝对路径，不得残留宏
            assertFalse(path.contains('$'), "平台宏可展开时导入路径不得残留未展开宏：$path")
            assertTrue(
                path.startsWith("/") || drivePrefix.containsMatchIn(path),
                "宏展开后应为绝对路径：$path"
            )
        } else {
            // 平台不可用：宏整段原样保留（不得半残），交给 IDE 运行时再展开
            assertEquals(
                "\$APPLICATION_CONFIG_DIR\$/consoles/$consoleUuid/console.sql",
                path,
                "未展开时应整段保留宏：$path"
            )
        }
    }

    @Test
    fun `resolve historical console sql url should not produce nested drive`() {
        // 历史坏数据整串：项目根 + 未展开宏 + 盘符混拼（用户真实数据形态）
        val raw = "file://D:/code/www/gs/zuodian/\$APPLICATION_CONFIG_DIR\$/consoles/$consoleUuid/console.sql"

        val resolved = NativeBookmarkXmlParser.resolveRelativePath(raw, "D:/code/www/gs/zuodian")

        assertNotNull(resolved, "历史坏数据也应能解析出候选")
        val path = requireNotNull(resolved)
        assertEquals(
            path,
            NativeBookmarkXmlParser.resolveRelativePath(raw, "D:/code/www/gs/zuodian"),
            "同一输入解析结果应可复现"
        )
        assertFalse(path.startsWith("file:"), "不得保留 file:// 协议前缀：$path")
        assertFalse(path.contains('\\'), "结果应为正斜杠风格：$path")
        assertTrue(path.endsWith("consoles/$consoleUuid/console.sql"), "应保留控制台 sql 尾部路径：$path")
        // 回归：完整展开会得到 D:/proj/C:/Users/... 这类嵌套盘符的无效路径，必须被截断候选取代
        assertFalse(nestedDrive.containsMatchIn(path), "不得出现嵌套盘符的失效路径：$path")
        if (platformMacroExpandable(raw, "D:/code/www/gs/zuodian")) {
            // 平台宏可展开时，嵌套盘符候选被丢弃，实际选中「从宏位置截断展开」的候选 → 不得残留宏
            assertFalse(path.contains('$'), "平台宏可展开时导入路径不得残留未展开宏：$path")
        }
    }

    @Test
    fun `resolve backslash console sql url should normalize separators`() {
        // 历史坏数据：宏名后缺尾随 $ 且分隔符是反斜杠
        val raw = "file://\$APPLICATION_CONFIG_DIR\\consoles/$consoleUuid\\console.sql"

        val resolved = NativeBookmarkXmlParser.resolveRelativePath(raw, basePath)

        assertNotNull(resolved, "反斜杠历史数据也应能解析")
        val path = requireNotNull(resolved)
        assertFalse(path.contains('\\'), "反斜杠应归一为正斜杠：$path")
        assertFalse(path.startsWith("file:"), "不得保留 file:// 协议前缀：$path")
        assertTrue(path.endsWith("consoles/$consoleUuid/console.sql"), "应保留控制台 sql 尾部路径：$path")
        assertFalse(path.startsWith("$basePath/"), "不得拼成项目相对路径：$path")
        if (platformMacroExpandable(raw, basePath)) {
            assertFalse(path.contains('$'), "平台宏可展开时导入路径不得残留未展开宏：$path")
        }
    }

    @Test
    fun `import console sql bookmark should expand macro at import time`() {
        // 导入期即展开：新格式 BookmarkState 的 console.sql 收藏，导入结果必须是可用路径
        val state = managerStateWithOneNewBookmark(
            groupName = "数据库",
            url = "file://\$APPLICATION_CONFIG_DIR\$/consoles/$consoleUuid/console.sql",
            line = "1",
            type = "LETTER_C",
            description = "控制台 sql"
        )

        val items = NativeBookmarkXmlParser.parseBookmarksXml(state, basePath)

        assertEquals(1, items.size, "带平台宏的控制台收藏不应被丢弃")
        val item = items[0]
        assertFalse(item.relativePath.startsWith("$basePath/"), "不得拼成项目相对路径：${item.relativePath}")
        assertTrue(
            item.relativePath.endsWith("consoles/$consoleUuid/console.sql"),
            "应保留控制台 sql 尾部路径：${item.relativePath}"
        )
        assertEquals("数据库", item.listName)
        assertEquals('C', item.mnemonic)
        assertEquals(1, item.line)
    }

    @Test
    fun `resolve windows project dir url should reduce case insensitively`() {
        // Windows 盘符路径的项目内归约：盘符/路径大小写不敏感
        assertEquals(
            "src/x.php",
            NativeBookmarkXmlParser.resolveRelativePath("file://D:/proj/src/x.php", "D:/proj")
        )
        assertEquals(
            "src/x.php",
            NativeBookmarkXmlParser.resolveRelativePath("file://d:/PROJ/src/x.php", "D:/proj")
        )
    }

    /**
     * 探测当前 JVM 里平台路径宏（`$APPLICATION_CONFIG_DIR$` 等）是否可展开
     *
     * 依赖 IDE 平台初始化：真实 IDE 运行时可用，裸 JVM 单测可能不可用。
     * 只用于决定回归断言强度（可展开时要求导入路径已展开），不对平台具体路径做断言。
     *
     * @param rawPath 原始收藏 url（可带 file:// 前缀与宏）
     * @param projectBasePath 项目根（可空）
     * @return true=候选里存在已完全展开（不含宏）的路径
     */
    private fun platformMacroExpandable(rawPath: String, projectBasePath: String?): Boolean =
        BookmarkPathMacros.candidates(rawPath, projectBasePath).any { !it.contains('$') }

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
