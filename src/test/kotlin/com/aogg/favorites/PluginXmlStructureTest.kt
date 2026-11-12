package com.aogg.favorites

import org.jdom.Document
import org.jdom.Element
import org.jdom.input.SAXBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 插件主描述符 `META-INF/plugin.xml` 的结构回归守卫（资源级单测，不启动 IDE）
 *
 * 为什么测这个：用户反馈「编辑器正文右键 / 行号区右键里，收藏菜单重复出现」。
 * 根因是「收藏 >」二级菜单组（Favorites.Group）同时挂到了编辑器正文右键（EditorPopupMenu）与
 * 行号区右键（EditorGutterPopupMenu），而这两个位置的原生 F11 / Ctrl+F11 早已被插件接管成
 * 一级菜单项「添加收藏」「添加助记收藏」——用户看到同一功能出现两次。
 * 1.8.0 的修复是从 plugin.xml 删掉这两条 `<add-to-group>`，只保留项目视图 / 编辑器标签页 / 导航栏三处。
 * 本测试直接解析打包进插件的主描述符，防止以后有人「顺手把收藏菜单加回编辑器右键」把重复问题带回。
 *
 * 覆盖：
 * - 主资源 `META-INF/plugin.xml` 在测试运行时可见、可被 JDOM 解析，且确实是本插件的描述符
 *   （根元素 idea-plugin + id=com.aogg.favorites，防止误取平台 jar 里的同名资源）
 * - Favorites.Group 组存在且唯一，且 class 仍是 FavoritesActionGroup、XML 里只写 base 文案「收藏」
 *   （「（Favorites）」后缀由 FavoritesActionTexts 运行时拼接，不写死在 XML）
 * - Favorites.Group **不再**挂 EditorPopupMenu（编辑器正文右键）——重复菜单回归守卫，核心断言
 * - Favorites.Group **不再**挂 EditorGutterPopupMenu（行号区右键）——同上
 * - Favorites.Group 仍挂 ProjectViewPopupMenu / EditorTabPopupMenu / NavbarPopupMenu，且只有这三处
 * - `<version>` 不低于 1.8（本次菜单去重从 1.8.0 起，防版本号/描述符被一起回退到 1.7.x）
 * - `codeInsight.lineMarkerProvider` 注册的 `language` 属性为空串（1.8.1 守卫）：
 *   空 language 即注册到平台 `Language.ANY`（其 ID 就是空串），`LineMarkersPass.getMarkerProviders`
 *   经 `allForLanguageOrAny` 对每个语言（含 PHP）都拼入 ANY 注册项——写死某个语言会让行号区图标
 *   在其它语言的编辑器里全部消失
 */
class PluginXmlStructureTest {

    /** 插件主描述符路径（打包后在插件根，测试运行时由 build/resources/main 提供） */
    private val pluginXmlPath = "META-INF/plugin.xml"

    /** 收藏二级菜单组 id（plugin.xml 里唯一的挂载点） */
    private val favoritesGroupId = "Favorites.Group"

    /** 编辑器正文右键动作组 id：1.8.0 起禁止收藏组挂这里（原生 F11 已接管为一级菜单项） */
    private val editorPopupMenuId = "EditorPopupMenu"

    /** 行号区右键动作组 id：同上禁止挂载（原生 Ctrl+F11 已接管为一级菜单项） */
    private val editorGutterPopupMenuId = "EditorGutterPopupMenu"

    /** 仍应挂载的三处入口（顺序与 plugin.xml 声明顺序一致：均 anchor="last" 置底） */
    private val expectedPopupMenus = listOf(
        "ProjectViewPopupMenu", // 项目视图右键（文件/文件夹）
        "EditorTabPopupMenu", // 编辑器标签页右键
        "NavbarPopupMenu" // 导航栏（文件路径导航条）右键
    )

    /** 行号区图标 provider 实现类（plugin.xml 里 codeInsight.lineMarkerProvider 的 implementationClass） */
    private val lineMarkerProviderClass = "com.aogg.favorites.helper.BookmarkGutterHelper"

    /**
     * 读取并解析插件主 plugin.xml
     *
     * @return JDOM 文档对象（每次调用重新解析，文件很小，避免用例间共享状态）
     */
    private fun pluginXml(): Document {
        val stream = requireNotNull(javaClass.classLoader.getResourceAsStream(pluginXmlPath)) {
            "测试运行时找不到主资源 $pluginXmlPath：确认 src/main/resources 已进测试 classpath"
        }
        return stream.use { SAXBuilder().build(it) }
    }

    /**
     * 取 actions 下的 Favorites.Group 组元素（缺失或多副本直接断言失败）
     *
     * @return 收藏菜单组元素
     */
    private fun favoritesGroup(): Element {
        val groups = pluginXml().rootElement
            .getChildren("actions")
            .flatMap { actions -> actions.getChildren("group") }
            .filter { element -> element.getAttributeValue("id") == favoritesGroupId }
        assertEquals(1, groups.size, "$favoritesGroupId 组应唯一存在，实际找到 ${groups.size} 个")
        return groups.single()
    }

    /**
     * 组上所有 `<add-to-group group-id="...">` 的 group-id（即该组的挂载点列表，保序）
     *
     * @param group 菜单组元素
     * @return 挂载点 group-id 列表（缺失 group-id 记为调用方可见的空串，便于断言定位）
     */
    private fun mountedGroupIds(group: Element): List<String> =
        group.getChildren("add-to-group").map { it.getAttributeValue("group-id") ?: "" }

    /** 主资源可读、可解析，且确实是本插件（不是平台 jar 里的同名 META-INF/plugin.xml） */
    @Test
    fun `plugin descriptor should be readable from test classpath`() {
        val root = pluginXml().rootElement
        assertEquals("idea-plugin", root.name, "根元素应为 idea-plugin")
        assertEquals(
            "com.aogg.favorites",
            root.getChild("id")?.textTrim,
            "加载到的应是本插件描述符（防误取 IDE 平台 jar 内的 META-INF/plugin.xml）"
        )
        assertEquals("Favorites", root.getChild("name")?.textTrim, "插件名应为 Favorites")
    }

    /** Favorites.Group 存在且唯一；class 仍为 FavoritesActionGroup；XML 里只写 base 文案「收藏」 */
    @Test
    fun `favorites group should exist exactly once with base text`() {
        val group = favoritesGroup()
        assertEquals(
            "com.aogg.favorites.action.FavoritesActionGroup",
            group.getAttributeValue("class"),
            "$favoritesGroupId 应仍由 FavoritesActionGroup 提供上下文显隐"
        )
        assertEquals(
            "收藏",
            group.getAttributeValue("text"),
            "XML 里只写 base 文案「收藏」；「（Favorites）」后缀由 FavoritesActionTexts 运行时按开关拼接"
        )
    }

    /** 核心回归守卫：收藏组不得再挂编辑器正文右键（原生 F11 已接管为一级菜单项） */
    @Test
    fun `favorites group should not be mounted on editor popup menu`() {
        val mounted = mountedGroupIds(favoritesGroup())
        assertFalse(
            mounted.contains(editorPopupMenuId),
            "$favoritesGroupId 不得挂 $editorPopupMenuId（编辑器正文右键）：该位置原生 F11 已接管为" +
                "一级菜单项「添加收藏」，再挂二级菜单就是用户反馈的重复，实际挂载点=$mounted"
        )
    }

    /** 核心回归守卫：收藏组不得再挂行号区右键（原生 Ctrl+F11 已接管为一级菜单项） */
    @Test
    fun `favorites group should not be mounted on editor gutter popup menu`() {
        val mounted = mountedGroupIds(favoritesGroup())
        assertFalse(
            mounted.contains(editorGutterPopupMenuId),
            "$favoritesGroupId 不得挂 $editorGutterPopupMenuId（行号区右键）：该位置原生 Ctrl+F11 已接管为" +
                "一级菜单项「添加助记收藏」，再挂二级菜单就是用户反馈的重复，实际挂载点=$mounted"
        )
    }

    /** 三处正常入口仍在，且只有这三处（多挂一处也要被测出来，避免又出现重复菜单） */
    @Test
    fun `favorites group should only be mounted on project view tab and navbar popups`() {
        val mounted = mountedGroupIds(favoritesGroup())
        assertEquals(
            expectedPopupMenus,
            mounted,
            "$favoritesGroupId 应只挂 $expectedPopupMenus（项目视图 / 编辑器标签页 / 导航栏），实际=$mounted"
        )
    }

    /**
     * 版本号不低于 1.8
     *
     * 不写死 `version == "1.8.0"` 的原因：以后升 1.8.1 / 1.9.0 / 2.0.0 不该因为版本号变化而改测试；
     * 这条断言的意图是「菜单去重的修复起点是 1.8.0，版本号不能被回退到 1.7.x」——1.7.x 的描述符里
     * 收藏组还挂在 EditorPopupMenu / EditorGutterPopupMenu 上，版本回退往往连带描述符一起回退。
     */
    @Test
    fun `plugin version should be at least 1 8`() {
        val versionElement = pluginXml().rootElement.getChild("version")
        assertNotNull(versionElement, "plugin.xml 缺少 <version>")
        val version = requireNotNull(versionElement).textTrim
        val parts = version.split(".")
        assertTrue(parts.size >= 2, "<version> 至少应为 major.minor，实际「$version」")
        val major = parts[0].toIntOrNull()
        val minor = parts.getOrNull(1)?.toIntOrNull()
        assertNotNull(major, "<version> 的 major 不是数字：「$version」")
        assertNotNull(minor, "<version> 的 minor 不是数字：「$version」")
        assertTrue(
            major!! > 1 || (major == 1 && minor!! >= 8),
            "版本 $version 低于 1.8：菜单去重（移除 EditorPopupMenu / EditorGutterPopupMenu 挂载）从 1.8.0 起"
        )
    }

    /**
     * 行号区图标 provider（codeInsight.lineMarkerProvider）必须以空 `language` 注册到全部语言（1.8.1 守卫）
     *
     * 平台机制核实（2023.3 源码）：`Language.ANY = new Language("")`（ID 即空串），
     * `LineMarkersPass.getMarkerProviders` → `LineMarkerProviders.allForLanguageOrAny(language)`
     * 对任何真实语言都会拼上「ANY（空 key）」注册的 provider——所以 `language=""` 是「所有语言（含 PHP）」
     * 的唯一正确写法；若写成 `language="PHP"` 这类单语言，其它语言的编辑器行号区将永远没有收藏图标。
     */
    @Test
    fun `line marker provider should be registered for all languages with empty language attribute`() {
        val providers = pluginXml().rootElement
            .getChildren("extensions")
            .flatMap { extensions -> extensions.getChildren("codeInsight.lineMarkerProvider") }
            .filter { element -> element.getAttributeValue("implementationClass") == lineMarkerProviderClass }
        assertEquals(
            1,
            providers.size,
            "行号区图标 provider 应恰好注册一次，实际 ${providers.size} 个"
        )
        val language = providers.single().getAttributeValue("language")
        assertTrue(
            language != null && language.isEmpty(),
            "lineMarkerProvider 的 language 属性必须是空串（= 平台 Language.ANY，覆盖全部语言含 PHP），" +
                "实际值「$language」——写成具体语言会让其它语言编辑器的行号区图标全部消失"
        )
    }
}
