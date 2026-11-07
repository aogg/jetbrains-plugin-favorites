package com.aogg.favorites.nativebookmarks

import com.aogg.favorites.helper.BookmarkPathMacros
import org.jdom.Element
import java.util.Locale

/**
 * 原生书签 XML 容错解析器（通道二，平台解耦）
 *
 * 设计原则（见 docs/v2-design.md 2.5）：**不假设精确结构**，遍历文件中所有名为
 * Bookmark / BookmarkState 的元素尽力收集 url/file/line/mnemonic/type/group/description，
 * 兼容两种已知的原生持久化形态：
 *
 * 1. 旧书签系统（PhpStorm ≤2021.2，元素属性式）：
 *    <Bookmark url="file://$PROJECT_DIR$/src/a.php" line="10" description="..." mnemonic="A" />
 *
 * 2. 新书签系统（2021.3+，结构式；组件名 BookmarksManager）：
 *    <GroupState>
 *      <option name="name" value="列表名"/>
 *      <option name="bookmarks">
 *        <BookmarkState>
 *          <option name="attributes">
 *            <option name="url" value="file://$PROJECT_DIR$/src/a.php"/>
 *            <option name="line" value="10"/>
 *          </option>
 *          <option name="type" value="LETTER_A"/>
 *          <option name="description" value="..."/>
 *        </BookmarkState>
 *      </option>
 *    </GroupState>
 *
 * 输出统一为 [NativeBookmarkItem]，解析失败的部分静默跳过，不影响其它条目。
 */
object NativeBookmarkXmlParser {

    private const val NEW_BOOKMARK_TAG = "BookmarkState"
    private const val OLD_BOOKMARK_TAG = "Bookmark"
    private const val GROUP_TAG = "GroupState"
    private const val PROJECT_DIR_MACRO = "\$PROJECT_DIR$"

    /** 宏占位符（判定路径里是否还残留未展开宏，如 $MODULE_DIR$） */
    private val MACRO_TOKEN_PATTERN = Regex("\\$[A-Za-z_][A-Za-z0-9_]*\\$?")

    /** Windows 盘符前缀（绝对路径判定用） */
    private val DRIVE_PREFIX_PATTERN = Regex("^[A-Za-z]:/")

    /** 嵌套盘符（完全展开后出现第二个盘符段=无效路径，如 D:/proj/C:/Users/...） */
    private val NESTED_DRIVE_PATTERN = Regex("^[A-Za-z]:/.*[A-Za-z]:/")

    /**
     * 解析原生书签 XML（bookmarks.xml / workspace.xml 中 BookmarksManager 组件的根 Element）
     */
    fun parseBookmarksXml(root: Element, projectBasePath: String?): List<NativeBookmarkItem> {
        val result = ArrayList<NativeBookmarkItem>()

        // 先收集"组名"映射：GroupState/BookmarkState 分组归属（按元素对象身份匹配最近的 GroupState 祖先）
        for (bookmarkElement in allDescendants(root)) {
            when (bookmarkElement.name) {
                OLD_BOOKMARK_TAG -> parseLegacyBookmarkElement(bookmarkElement, projectBasePath)?.let { result += it }
                NEW_BOOKMARK_TAG -> parseNewBookmarkElement(bookmarkElement, projectBasePath)?.let { result += it }
            }
        }
        return result
    }

    /**
     * 尽力而为解析旧版收藏列表 XML（PhpStorm ≤2021.2 的 .idea/favorites.xml）
     * 结构未知，只识别带 url 属性的元素；解析失败由调用方静默处理。
     */
    fun parseLegacyFavoritesXml(root: Element, projectBasePath: String?): List<NativeBookmarkItem> {
        val result = ArrayList<NativeBookmarkItem>()
        for (element in allDescendants(root)) {
            val url = element.getAttributeValue("url") ?: continue
            val relativePath = resolveRelativePath(url, projectBasePath) ?: continue
            val listName = ancestorAttributeValue(element, "list")
                ?: ancestorAttributeValue(element, "listName")
                ?: nearestAncestorName(element)
            result += NativeBookmarkItem(
                relativePath = relativePath,
                line = element.getAttributeValue("line")?.toIntOrNull() ?: -1,
                mnemonic = parseMnemonic(element.getAttributeValue("mnemonic")),
                listName = listName?.trim()?.takeIf { it.isNotEmpty() },
                description = element.getAttributeValue("description"),
                isDirectory = element.getAttributeValue("isDirectory")?.toBooleanStrictOrNull() ?: false
            )
        }
        return result
    }

    // ==================== 元素级解析 ====================

    /**
     * 旧格式：<Bookmark url=... line=... mnemonic=... group=... description=.../>
     */
    private fun parseLegacyBookmarkElement(element: Element, projectBasePath: String?): NativeBookmarkItem? {
        val url = element.getAttributeValue("url") ?: element.getAttributeValue("file") ?: return null
        val relativePath = resolveRelativePath(url, projectBasePath) ?: return null
        return NativeBookmarkItem(
            relativePath = relativePath,
            line = element.getAttributeValue("line")?.toIntOrNull() ?: -1,
            mnemonic = parseMnemonic(element.getAttributeValue("mnemonic")),
            listName = element.getAttributeValue("group")?.trim()?.takeIf { it.isNotEmpty() },
            description = element.getAttributeValue("description"),
            isDirectory = element.getAttributeValue("isDirectory")?.toBooleanStrictOrNull() ?: false
        )
    }

    /**
     * 新格式：<BookmarkState> + option 子元素（组名取最近 GroupState 祖先的 name option）
     */
    private fun parseNewBookmarkElement(element: Element, projectBasePath: String?): NativeBookmarkItem? {
        val attributes = LinkedHashMap<String, String>()

        // attributes XMap：option[name="attributes"] 下的 option(name=value) 子项
        element.children.firstOrNull { it.name == "option" && it.getAttributeValue("name") == "attributes" }
            ?.children
            ?.filter { it.name == "option" }
            ?.forEach { option ->
                val key = option.getAttributeValue("name")
                val value = option.getAttributeValue("value")
                if (key != null && value != null) attributes[key] = value
            }

        // 兜底：部分版本直接把 url/line 写成 BookmarkState 的直接 option 子元素
        element.children.filter { it.name == "option" }.forEach { option ->
            val key = option.getAttributeValue("name") ?: return@forEach
            val value = option.getAttributeValue("value") ?: return@forEach
            if (key in setOf("url", "file", "line") && key !in attributes) attributes[key] = value
        }

        val url = attributes["url"] ?: attributes["file"] ?: return null
        val relativePath = resolveRelativePath(url, projectBasePath) ?: return null

        return NativeBookmarkItem(
            relativePath = relativePath,
            line = attributes["line"]?.toIntOrNull() ?: -1,
            mnemonic = parseMnemonic(attributes["mnemonic"]) ?: parseMnemonicFromType(
                childOptionValue(element, "type")
            ),
            listName = nearestGroupName(element),
            description = childOptionValue(element, "description"),
            isDirectory = attributes["isDirectory"]?.toBooleanStrictOrNull() ?: false
        )
    }

    // ==================== 工具方法 ====================

    /**
     * url → 项目相对路径：
     * - file://$PROJECT_DIR$/src/a.php → src/a.php
     * - file://$APPLICATION_CONFIG_DIR$/consoles/x/console.sql → 配置目录绝对路径（项目外原样保留）
     * - file:///abs/path/project/src/a.php → src/a.php（可归约时）
     * - 已是相对路径则原样返回（'\' 统一为 '/'）
     * - 无法归约的项目外绝对路径原样保留（仍可跳转），不做丢弃
     */
    fun resolveRelativePath(url: String?, projectBasePath: String?): String? {
        if (url.isNullOrBlank()) return null
        var path = url.trim()
        // 去掉协议前缀（file:// 与 file:）
        for (prefix in arrayOf("file://", "file:")) {
            if (path.startsWith(prefix)) {
                path = path.substring(prefix.length)
                break
            }
        }
        path = path.replace('\\', '/').trim()

        // ////////////////  校验参数：$PROJECT_DIR$ 宏必须有项目根才能展开  start  //////
        // 保持既有语义：无项目根时该宏无法解析，整条书签跳过导入
        if (path.contains(PROJECT_DIR_MACRO) && projectBasePath.isNullOrBlank()) return null
        // ////////////////  校验参数  end  ////////////////////////////////////////////

        // ////////////////  核心逻辑一：路径宏展开（1.7.3）  start  //////////////////////
        // 旧实现只展开 $PROJECT_DIR$，$APPLICATION_CONFIG_DIR$ 等宏被当成项目内相对路径原样保留，
        // 导入后必然解析不到（双击无反应）；现改为候选优先级：完整展开 → 宏截断展开 → 原样，
        // 取第一个「宏已全部展开且不含嵌套盘符」的候选作为导入路径
        val expanded = selectExpandedCandidate(BookmarkPathMacros.candidates(path, projectBasePath))
            ?: return null
        // ////////////////  核心逻辑一  end  ///////////////////////////////////////////

        // ////////////////  核心逻辑二：绝对路径归约为项目相对路径  start  ////////////////
        return reduceToProjectPath(expanded, projectBasePath)
        // ////////////////  核心逻辑二  end  ///////////////////////////////////////////
    }

    /**
     * 从候选路径中选出首个可用项：宏已全部展开、不含嵌套盘符（嵌套盘符=无效路径）、不含重复分隔符
     * （`项目根//绝对路径` 这类拼接产物）；都不可用时退回首个候选（保留原样行为，交给解析期再展开）
     *
     * @param candidates [BookmarkPathMacros.candidates] 产出的候选列表
     * @return 选中的路径；候选为空返回 null
     */
    private fun selectExpandedCandidate(candidates: List<String>): String? =
        candidates.firstOrNull {
            !MACRO_TOKEN_PATTERN.containsMatchIn(it) &&
                !NESTED_DRIVE_PATTERN.containsMatchIn(it) &&
                // 重复分隔符只在 UNC 前缀（//server/share）合法，其余位置视为无效拼接
                !it.substring(1).contains("//")
        } ?: candidates.firstOrNull()

    /**
     * 绝对路径归约为项目相对路径：项目内路径归约，项目外绝对路径原样保留，相对路径原样返回
     *
     * @param path 已展开宏的路径（正斜杠风格）
     * @param projectBasePath 项目根路径（可空）
     * @return 项目相对路径 / 项目外绝对路径 / 原相对路径
     */
    private fun reduceToProjectPath(path: String, projectBasePath: String?): String {
        // ////////////////  校验参数：基准路径 + 绝对性判定  start  //////////////////////
        val base = projectBasePath?.trim()?.replace('\\', '/')?.trimEnd('/')?.takeIf { it.isNotEmpty() }
        // 绝对性判定：Unix 前导斜杠 或 Windows 盘符前缀；相对路径原样返回
        val isAbsolute = path.startsWith("/") || DRIVE_PREFIX_PATTERN.containsMatchIn(path)
        if (!isAbsolute) return path
        if (base == null) return if (path.startsWith("/")) path.trimStart('/') else path
        // ////////////////  校验参数  end  ////////////////////////////////////////////

        // ////////////////  核心逻辑：按前缀归约（Windows 盘符路径大小写不敏感）  start  //
        val ignoreCase = DRIVE_PREFIX_PATTERN.containsMatchIn(path) && DRIVE_PREFIX_PATTERN.containsMatchIn(base)
        val comparedPath = if (ignoreCase) path.lowercase(Locale.ROOT) else path
        val comparedBase = if (ignoreCase) base.lowercase(Locale.ROOT) else base
        return when {
            comparedPath == comparedBase -> ""
            comparedPath.startsWith("$comparedBase/") -> path.substring(base.length + 1)
            else -> path // 项目外路径原样保留
        }
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////
    }

    /**
     * 助记符解析：单字符（"A"/"3"）；大小写归一为大写；仅接受 0-9/A-Z
     */
    fun parseMnemonic(raw: String?): Char? {
        val trimmed = raw?.trim() ?: return null
        if (trimmed.length != 1) return null
        // String 没有 uppercaseChar()（仅 Char 有），取首字符（上方已保证长度为 1）转大写
        val ch = trimmed[0].uppercaseChar()
        return ch.takeIf { it in '0'..'9' || it in 'A'..'Z' }
    }

    /**
     * 新格式的 type 值解析：LETTER_A/DIGIT_3（兼容裸 "A"/"3"）；DEFAULT/未知 → null
     */
    fun parseMnemonicFromType(raw: String?): Char? {
        val value = raw?.trim() ?: return null
        return when {
            value.startsWith("LETTER_") -> parseMnemonic(value.substringAfter("LETTER_"))
            value.startsWith("DIGIT_") -> parseMnemonic(value.substringAfter("DIGIT_"))
            else -> parseMnemonic(value)
        }
    }

    /**
     * 最近 GroupState 祖先的 name option 值（新格式的列表名）
     */
    private fun nearestGroupName(element: Element): String? {
        // org.jdom Content.getParent() 返回 Parent（可能是 Document），祖先遍历只关心 Element
        var parent: Element? = element.parent as? Element
        while (parent != null) {
            if (parent.name == GROUP_TAG) {
                return childOptionValue(parent, "name")
                    ?: parent.getAttributeValue("name")?.trim()?.takeIf { it.isNotEmpty() }
            }
            parent = parent.parent as? Element
        }
        return null
    }

    /**
     * 最近祖先元素上指定属性名的值（旧收藏列表的 list/listName 归属）
     */
    private fun ancestorAttributeValue(element: Element, attributeName: String): String? {
        // org.jdom Content.getParent() 返回 Parent（可能是 Document），祖先遍历只关心 Element
        var parent: Element? = element.parent as? Element
        while (parent != null) {
            val value = parent.getAttributeValue(attributeName)
            if (!value.isNullOrBlank()) return value
            parent = parent.parent as? Element
        }
        return null
    }

    /**
     * 最近祖先元素 name 属性值（旧收藏列表的列表名兜底）
     */
    private fun nearestAncestorName(element: Element): String? {
        // org.jdom Content.getParent() 返回 Parent（可能是 Document），祖先遍历只关心 Element
        var parent: Element? = element.parent as? Element
        while (parent != null) {
            val name = parent.getAttributeValue("name")
            if (!name.isNullOrBlank() && parent.name != "component") return name
            parent = parent.parent as? Element
        }
        return null
    }

    /**
     * 读取元素的直接 option 子元素中 name 对应的 value
     */
    private fun childOptionValue(element: Element, name: String): String? =
        element.children
            .firstOrNull { it.name == "option" && it.getAttributeValue("name") == name }
            ?.getAttributeValue("value")

    /**
     * 遍历包括根在内的所有后代元素（按文档序：父先于子、兄先于弟）
     */
    private fun allDescendants(root: Element): List<Element> {
        val result = ArrayList<Element>()
        val stack = ArrayDeque<Element>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            result += current
            // LIFO 栈需逆序压栈才能保证兄弟按文档序弹出
            // （正序压栈会倒序解析同级 Bookmark，导致导入顺序颠倒，随单测修复）
            for (child in current.children.asReversed()) stack.addLast(child)
        }
        return result
    }
}
